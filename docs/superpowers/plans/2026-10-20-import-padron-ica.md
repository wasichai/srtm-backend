# Importador del padrón de Ica: plan

> **Para agentes:** usar superpowers:executing-plans (o subagent-driven-development) tarea por tarea, con TDD.

**Objetivo:** `model/import_padron_ica.py` carga `PADRON_CONSTRUCCION.xlsx` (hojas `padron` y `construcciones`) en el
Core de srtm: vías y unidades urbanas, contribuyentes, predios, declaraciones prediales y niveles de construcción, y
deja un reporte CSV con lo que hay que revisar (#125, épica #126).

**Arquitectura:** un script stdlib que reutiliza `read_xlsx`, `clean_*`, `post_all`, `LoadError` y `load_enums` de
`import_predios.py` y `Client` de `core_client.py`. Las tablas de códigos de Ica van en `model/data/ica/codigos.json`
(todas con `"confirmado": false`). Tres capas: mapeos puros → `build_dataset` (puro, valida cada registro contra
`model.json`) → `load` idempotente contra Core.

**Stack:** Python 3.11+, solo stdlib; tests con `unittest`, un xlsx ficticio y `fake_core.py`.

**Spec:** issue #125 y la sección «S3» del plan de la épica (`analiza-el-archivo-padron-construccion-x-shimmying-fern.md`).

**Depende de #124** (rama `feat/campos-padron-externo`): los campos `predio.codigo_via`, `codigo_unidad_urbana`,
`codigo_hu_armonizacion`, `codigo_via_armonizacion`; `declaracion_predial.arancel`, `valor_terreno`,
`valor_construccion`, `valor_otras_instalaciones`; `nivel_construccion.clasificacion`, `valor_unitario`, `incremento`,
`porcentaje_depreciacion`, `valor_total`; `via.codigo`, `unidad_urbana.codigo`; y `mes_construccion` opcional. Esta
rama se apila sobre esa.

## Restricciones globales

- El xlsx trae datos personales: nunca se versiona ni se copian sus filas a tests, issues o PR. Los tests arman un xlsx
  ficticio.
- Solo stdlib. Leer el xlsx real con `python3 -I`.
- Docstrings y comentarios del código en inglés, como el resto de `model/`; README, plan, commits y PR en español.
- Nada se carga contra el Core de dev desde esta rama: solo `--dry-run` sobre el xlsx real.

## Decisiones (salen del análisis del xlsx; todas por confirmar con la municipalidad)

- **Llaves:** contribuyente por `codigo` (= `codigo_contribuyente`), predio por `codigo`, DJ por (predio, contribuyente,
  año, secuencia `001`), vía y unidad urbana por (`codigo`, tipo, nombre, ubigeo): 106 códigos de vía comparten nombre,
  así que el nombre solo no basta. Un nivel no tiene llave natural: se compara por (DJ, tipo_nivel, numero_piso,
  anio_construccion, area_construida) contando repeticiones, porque un nivel puede tener varias secciones.
- **Estado de conservación, material y clasificación:** se infieren de la depreciación. Con la tabla del RNT
  (`depreciacion-rnt-2016.csv` de catastro-backend) y la antigüedad `2025 − año`, clasificación 1–4 = tablas 01–04,
  material 1/2/3 = concreto/ladrillo/adobe y estado 1/2/3/4 = muy bueno/bueno/regular/malo explican 3 118 de 3 762
  filas. La clasificación 0 (2 filas) queda vacía y va al reporte.
- **Condición de propiedad:** se aplica la regla de `Condominio.kt`: con más de un titular, CONDOMINO; con uno solo, su
  condición si es PROPIETARIO UNICO, SOCIEDAD CONYUGAL o POSEEDOR y, si no, PROPIETARIO UNICO. Los códigos 2, 6
  (sucesiones) y 8 (OTROS) no traen condición: queda la de la regla. Un 5/9 (condómino) solo en el extracto pasa a
  PROPIETARIO UNICO con 100 % y va al reporte.
- **Porcentaje de condominio:** 100/n a dos decimales; los céntimos que faltan van de a uno a los primeros titulares
  (orden de la hoja), para que la suma sea exactamente 100 (`condominioCon` rechaza una suma mayor que 100).
- **Persona:** RUC que empieza con 20 → JURIDICA con `razon_social`; nombre que empieza con SUC/SUCESION o alguna DJ con
  condición 2 o 6 → SUCESION con `tipo_contribuyente` SUCESION INDIVISA; el resto, NATURAL.
- **Documento:** 1 DNI (rellenado a 8 dígitos), 3 RUC, 0 SIN DOCUMENTO, 7 PASAPORTE; 2 y 5 → OTROS (sin identificar).
  Un tipo sin número, o ningún tipo, es SIN DOCUMENTO. `numero_documento` es único en el modelo: un número que ya usa
  otro código (6 casos) se carga sin número en el segundo y va al reporte.
- **Usos:** los 15 códigos locales pasan a un código de `usos_predio.csv`; la DJ guarda su clase, sub clase y uso.
  Solo 20 → 070101 y 4 → 010101 son seguros; 22 (OTROS) no tiene mapeo.
- **Nivel:** 0 → SOTANO piso 1; n → PISO n. `area_comun` sale de `area_construida_comun`. `valor_unitario_depreciado`
  no se carga.

## Archivos

- Crear `model/data/ica/codigos.json`, `model/data/ica/municipalidad.json` (RUC `null`: `read_municipalidad` descarta
  los vacíos y `ruc` es opcional).
- Crear `model/import_padron_ica.py` y `model/test_import_padron_ica.py`.
- Modificar `README.md`: sección «Cargar la Municipalidad de Ica».

## Tareas

### Tarea 1: tablas de códigos y mapeos puros

- Interfaces: `load_codigos(path) -> dict`; `codigo(value) -> str|None` (texto sin `.0`); `tipo_documento(tipo, numero)
  -> (enum, numero, motivo|None)`; `tipo_persona(tipo_doc, numero, nombre, condiciones) -> str`; `porcentajes(n) ->
  list[Decimal]`; `valores_condominio(autoavaluo, porcentaje) -> (valor_condominio, valor_afecto)`;
  `condicion(codigo_ica, titulares, codigos) -> str`; `fecha(value) -> str|None`; `uso(codigo_ica, codigos, usos) ->
  dict|None`; `nivel(value) -> (tipo_nivel, numero_piso)`.
- Tests: DNI `1234567` → `01234567`; RUC `20…` → JURIDICA, `10…` → NATURAL; sucesión por nombre y por condición;
  `porcentajes(6)` = 16.67 ×4 y 16.66 ×2 y `sum(porcentajes(115)) == 100`; `fecha("1900-01-01 00:00:00") is None`,
  `fecha("2004-01-01 00:00:00") == "2004-01-01"`, serial de Excel `45292` → `2024-01-01`; condición 5 con un titular →
  PROPIETARIO UNICO, 4 con dos → CONDOMINO; uso 20 → TERRENO/DESOCUPADO/SIN CONSTRUIR; nivel 0 → (SOTANO, 1).
- Test de datos: cada opción de `codigos.json` existe en su enum de `model.json` y cada código de uso en
  `usos_predio.csv`.

### Tarea 2: `build_dataset(padron, construcciones, anio, enums, model, codigos) -> Dataset`

- `Dataset`: `vias`, `unidades`, `contribuyentes`, `predios`, `declaraciones`, `niveles`, `reporte` (filas
  `{categoria, hoja, fila, codigo_predio, codigo_contribuyente, detalle}`, sin nombres ni documentos), `errores`
  (problemas de esquema: un campo que el modelo no tiene → exit 2).
- Cada registro se valida contra `model.json` (`check(objeto, attrs)`: campo desconocido, obligatorio vacío, opción
  fuera del enum). Un registro inválido se rechaza al reporte con lo que depende de él (DJ → niveles).
- Reporte: `rechazada`, `condominio_100_n`, `condominio_valores_distintos`, `titulares_en_conflicto`,
  `condomino_solo`, `codigo_sin_mapeo` (agregado por columna y valor), `documento_rellenado`, `documento_repetido`,
  `documento_sin_numero`, `fecha_relleno`, `direccion_compuesta`, `valor_predio_no_cuadra`, `niveles_vs_padron`,
  `formula_nivel` (tolerancia S/ 1,00).
- Tests con filas ficticias: un condominio de 3 con valores distintos, un PROPIETARIO UNICO + SUCESION, una fila con
  letra J, un nivel sin DJ, un código de uso sin mapeo, un DNI de 7 dígitos.

### Tarea 3: `load(client, data, anio, workers) -> (created, skipped)` y CLI

- Orden: via, unidad_urbana, contribuyente, predio, declaracion_predial, nivel_construccion; cada paso lee lo que Core
  tiene y crea solo lo que falta. Un documento que Core ya tiene con otro código se envía sin número.
- CLI: `--excel --anio(2026) --model --codigos --core --email --password --dry-run --limit N (primeros N predios)
  --workers --report (model/reports/padron_ica.csv)`. Salida 0 ok, 1 Core rechazó algo, 2 el modelo no tiene un campo.
- Tests e2e contra `FakeCore`: dry-run no llama a nada y cuenta; la carga crea todo con sus relaciones; la segunda
  corrida crea 0; `--limit 1` toma el condominio entero.

### Tarea 4: README, dry-run real y PR

- README: crear la organización, `apply.py`, `import_catalogos.py --distrito 110101 --municipalidad-json
  data/ica/municipalidad.json`, `import_parametros.py`, `import_padron_ica.py --dry-run` y luego la carga.
- `python3 -I import_padron_ica.py --excel …/PADRON_CONSTRUCCION.xlsx --dry-run`: ~1 458 contribuyentes, 7 521
  predios, 7 829 DJ y ~3 750 niveles; las cifras del reporte van al PR, sin datos personales.

## Foco de revisión

- Un predio con 115 titulares: los porcentajes suman 100 y ninguno queda en 0.
- La segunda corrida no duplica niveles con secciones repetidas (mismo piso, misma área).
- Un contribuyente rechazado no deja DJ huérfanas ni hace caer la carga.
- Códigos que llegan como número (`20.0`) o con espacios se leen igual que el texto.
- Un modelo sin los campos de #124 frena antes de llamar a Core (exit 2).
