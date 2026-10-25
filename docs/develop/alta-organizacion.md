# Alta de una organización: la Municipalidad de Ica

Cómo se da de alta una municipalidad nueva junto a Perené, en srtm-backend y en catastro-backend, hasta poder correr
su brecha de recaudación. Es el procedimiento que se siguió con Ica (épica #126) el 2026-10-20: primero se ensayó en
el [entorno de pruebas](../../develop/pruebas/README.md) y después se hizo en dev. El padrón, sus columnas y el
importador están en el [README, «Cargar la Municipalidad de Ica»](../../README.md#cargar-la-municipalidad-de-ica).

**Regla:** cada paso que escribe va primero con `--dry-run` o `--diff`. La corrida real necesita el OK de quien
administra dev.

## 0. Antes de empezar

- **Código:** srtm-backend en main, con #128 (campos de padrón externo), #130 (un catastro por organización), #131
  (importador de Ica), #137 (valuación de origen) y #141 (municipalidad por organización). catastro-backend en main,
  con #12 (región por organización y tablas de Costa) y #14 (clasificación por construcción y `valuacion-v2`).
- **Bases de dev:** se llega a ellas por túnel.

  ```bash
  ssh -fN -o ExitOnForwardFailure=yes -i ~/.ssh/github_actions_deploy_stg \
    -L 5433:localhost:5433 -L 5434:localhost:5434 github@<vps>
  ```

- **Core:** si no escuchan en 8090 y 8091, se exporta `WASICHAI_CORE=http://localhost:<puerto>` antes de cada bloque.
  Todos los scripts lo leen.
- **`python3 -I`:** todos los comandos de `model/` corren aislados (#139). Hace falta sobre todo en los que leen
  archivos de afuera, como el xlsx del padrón o los csv de catastro.

## 1. catastro: las organizaciones que ya existen, antes del jar nuevo

Desde #12, catastro-backend valora según la región de la organización y no tiene región por omisión. Antes de
cambiar el jar, cada organización que ya existe necesita su región. Si no, srtm recibe 404, 403 o 409. Para Perené,
como `admin@wasichai.local`:

```bash
cd catastro-backend/model
python3 -I apply.py --diff                        # objeto configuracion_catastro y campos de construccion
python3 -I apply_roles.py --dry-run               # SRTM_LECTOR también lee configuracion_catastro
python3 -I configurar_region.py --region SELVA --dry-run
```

Con el OK se corren sin `--diff` ni `--dry-run`, y después se reinicia el Core de catastro con el jar de main.

## 2. srtm: las organizaciones que ya existen

```bash
cd srtm-backend/model
python3 -I apply.py --diff     # en Perené: los 15 campos de #124, mes_construccion opcional y municipalidad.escudo
```

## 3. La organización nueva

En los dos Core, como superadministrador:

```bash
curl -X POST "$CORE/api/organizations" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name": "Municipalidad Provincial de Ica", "slug": "ica", "adminEmail": "<correo>", "adminPassword": "<clave>"}'
```

- **Correo único:** el login es por correo, así que el administrador no puede repetir el de otra organización.
- **Ids distintos:** cada Core le da su propio id. El que cuenta para srtm es el de **srtm**.
- **Dónde guardar la credencial:** en `develop/.env`, que no se versiona.

  ```bash
  ICA_ADMIN_EMAIL=<correo>
  ICA_ADMIN_PASSWORD=<clave>
  ICA_ORG_ID_SRTM=<id en srtm>
  ICA_ORG_ID_CATASTRO=<id en catastro>
  ```

## 4. srtm como administrador de la organización

```bash
cd srtm-backend/model
export WASICHAI_EMAIL=$ICA_ADMIN_EMAIL WASICHAI_PASSWORD=$ICA_ADMIN_PASSWORD
python3 -I apply.py --diff                        # el modelo completo: 122 escrituras
python3 -I import_catalogos.py --distrito 110101 --municipalidad-json data/ica/municipalidad.json \
  --obras-csv data/ica/obras_complementarias_costa.csv --dry-run
python3 -I import_parametros.py --dry-run         # UIT, tramos, mínimo: sin ellos la brecha queda FALLIDA
python3 -I import_padron_ica.py --excel <PADRON_CONSTRUCCION.xlsx> --dry-run
```

Hay dos fallas esperadas antes de aplicar el modelo: `import_catalogos.py` y `import_parametros.py` responden 404
(`object ubigeo does not exist`). Por eso `apply.py` va primero de verdad, y después se repiten los dry-run.

**Escudo:** la imagen se sube al registro `municipalidad` de la organización (#138), en multipart con la parte `file`:
`POST /api/objects/municipalidad/records/<id>/files/escudo`. `GET /api/srtm/municipalidad` lo devuelve (#141).

## 5. catastro como administrador de la organización

```bash
cd catastro-backend/model
export WASICHAI_EMAIL=$ICA_ADMIN_EMAIL WASICHAI_PASSWORD=$ICA_ADMIN_PASSWORD
python3 -I apply.py --diff && python3 -I apply_roles.py --dry-run
python3 -I configurar_region.py --region COSTA --dry-run
python3 -I crear_cuenta_srtm.py --clave ica --dry-run
```

`crear_cuenta_srtm.py` muestra el secreto **una sola vez**. Su salida se redirige a un archivo con `chmod 600`, no a
una terminal compartida.

## 6. srtm: el catastro de la organización

En el `.env` del Core de srtm:

```bash
SRTM_CATASTRO_ORGANIZACIONES_ICA_URL=http://localhost:8091
SRTM_CATASTRO_ORGANIZACIONES_ICA_CLIENT_ID=<clientId>
SRTM_CATASTRO_ORGANIZACIONES_ICA_CLIENT_SECRET=<clientSecret>
```

- **La clave:** si es el slug de srtm y solo tiene letras y números, ya nombra a la organización.
- **Slug con guion o id:** van en `_ORGANIZACION` (el id de srtm), bajo una clave cualquiera.
- **Perené:** sus variables de una sola organización (`SRTM_CATASTRO_URL`, …) siguen valiendo.

Se reinicia el Core, y el log tiene que decir `srtm.catastro: catastro de 00000000-…-0001, ica`.

## 7. Levantamiento y brecha

La brecha necesita el catastro de la organización: fichas, pisos y aranceles cargados con `import_fichas.py` e
`import_aranceles.py`. El de Ica está pendiente (catastro-backend#10). Sin catastro, la corrida falla con «El catastro
no devolvió valuaciones».

En **dev no se carga un catastro sintético**, porque chocaría con el levantamiento real: `codigo_municipal` es único. Para
probar sirve el [entorno de pruebas](../../develop/pruebas/README.md).

```bash
curl -X POST "$SRTM/api/srtm/brecha/corridas" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"anio":2026}'
curl "$SRTM/api/srtm/brecha/corridas/<id>" -H "Authorization: Bearer $TOKEN"     # hasta TERMINADA o FALLIDA
curl "$SRTM/api/srtm/brecha/2026/panel" -H "Authorization: Bearer $TOKEN"
```

Sin ordenanza ni servicios de arbitrios del año, la corrida termina con «Δ arbitrios no calculado», como se espera.

## 8. caja: los pagos (#165)

El buzón de caja-backend entrega cada pago con `POST {url}/pagos`, y srtm lo recibe en `POST /api/srtm/caja/pagos`.
Caja entra como una **cuenta de servicio de srtm** (wasichai ADR-043), y la organización sale de su token. Los pasos
van como administrador de la organización, en el Core de srtm.

**El rol `CAJA`:** crea y lee `pago_caja` y `pago_caja_detalle`, y lee lo que la imputación consulta.

```bash
curl -X POST "$SRTM/api/roles" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name": "CAJA", "label": "Caja (buzón de pagos)"}'
curl -X PUT "$SRTM/api/roles/CAJA/permissions" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"permissions": [
  {"objectName": "pago_caja", "action": "CREATE"}, {"objectName": "pago_caja", "action": "READ"},
  {"objectName": "pago_caja_detalle", "action": "CREATE"}, {"objectName": "pago_caja_detalle", "action": "READ"},
  {"objectName": "orden_caja", "action": "READ"}, {"objectName": "cuota_arbitrio", "action": "READ"},
  {"objectName": "anulacion_cuota_arbitrio", "action": "READ"}, {"objectName": "cuota_predial", "action": "READ"},
  {"objectName": "contribuyente", "action": "READ"}]}'
```

**La cuenta de servicio `caja`:**

```bash
curl -X POST "$SRTM/api/service-accounts" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name": "caja", "roles": ["CAJA"]}'
```

- **El secreto:** la respuesta trae `clientId` y `clientSecret`, y el secreto se muestra **una sola vez**. La salida se
  redirige a un archivo con `chmod 600`, como la de `crear_cuenta_srtm.py`.
- **Si se filtra:** `POST /api/service-accounts/{id}/secret` lo rota. Los tokens ya emitidos viven 15 minutos.

**En caja-backend**, el destino `rentas` usa esa cuenta en vez de un `token` fijo (caja-backend#51):

| Propiedad | Variable | Valor |
|---|---|---|
| `caja.buzon.destinos.rentas.url` | `CAJA_BUZON_DESTINOS_RENTAS_URL` | `https://<srtm>/api/srtm/caja` |
| `caja.buzon.destinos.rentas.token-url` | `CAJA_BUZON_DESTINOS_RENTAS_TOKENURL` | `https://<srtm>/api/auth/token` |
| `caja.buzon.destinos.rentas.client-id` | `CAJA_BUZON_DESTINOS_RENTAS_CLIENTID` | el `clientId` |
| `caja.buzon.destinos.rentas.client-secret` | `CAJA_BUZON_DESTINOS_RENTAS_CLIENTSECRET` | el `clientSecret` |

- **El sentido contrario:** srtm manda las órdenes a caja con `SRTM_CAJA_ORGANIZACIONES_<CLAVE>_URL`, `_CLIENT_ID` y
  `_CLIENT_SECRET`. Esa es la cuenta de servicio de caja-backend.
- **Sin esa entrada:** srtm contesta **503** a los pagos de la organización. Caja los reintenta y no los da por
  entregados.

**Qué contesta srtm:**

| Caso | Respuesta |
|---|---|
| Todas las órdenes se aplican | 200 `{"aplicado":true,"a_cuenta":false,"motivo":null}` |
| Alguna va a cuenta: otro importe, sustituida, ya no vigente, ya pagada o desconocida | 202 `{"aplicado":false,"a_cuenta":true,"motivo":"…"}` |
| `pagoId` repetido | 409, con el mismo cuerpo de la primera vez |
| `PAGO_ANULADO` | 200 `{"aplicado":true,"a_cuenta":false,"motivo":null}`, aunque el pago original haya ido a cuenta: una anulación no es un pago |
| `PAGO_ANULADO` cuyo original srtm no tiene | 503 `{"motivo":"no conozco el pago original"}` |
| Cuerpo ilegible | 400, y caja lo deja `MUERTO` |
| Sin token o con el token vencido | 401. Sin el rol, o con el token de una persona (aunque sea la administradora), 403. Caja reintenta los dos |

**La conciliación y la vigencia (#166, #168):** con la misma cuenta, el mismo rol y la misma `url`, caja pregunta
también por el día y por las órdenes (solo lectura: `pago_caja` y `orden_caja`, que el rol ya tiene).

| Pregunta de caja | Respuesta |
|---|---|
| `GET {url}/pagos/conciliacion?fecha=AAAA-MM-DD` | 200 `{"fecha","recibidos","aplicados","rechazados":0,"recibidos_a_cuenta","importe_aplicado","importe_a_cuenta"}`: conteos enteros, importes en cadena con 2 decimales. Los pagos del día son los registrados con esa fecha de pago, sin los anulados. Sin `fecha` o mal escrita, 400 |
| `POST {url}/ordenes/vigencia` con `{"ordenes":[{"referencia_externa","importe"}]}` (hasta 50) | 200 `{"ordenes":[{"referencia_externa","vigente","importe","motivo"}]}`, en el mismo orden y con el importe de srtm. No es vigente la desconocida, la que ya no se cobra (anulada, sustituida o retirada) ni la ya pagada |
| Organización sin caja configurada | 503, y caja la toma como «no contesta» |

## 9. Corte analítico (#205)

El corte analítico (`docs/informes/brecha`) lee srtm y catastro con cuentas de servicio de solo lectura y saca
una foto de la organización en Parquet, seudonimizada. Los pasos van como administrador de la organización, en
el Core de srtm.

**El rol `ANALITICA`:** solo lee las diez tablas que el corte necesita, nada de escritura.

```bash
curl -X POST "$SRTM/api/roles" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name": "ANALITICA", "label": "Corte analítico (solo lectura)"}'
curl -X PUT "$SRTM/api/roles/ANALITICA/permissions" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"permissions": [
  {"objectName": "predio", "action": "READ"}, {"objectName": "contribuyente", "action": "READ"},
  {"objectName": "declaracion_predial", "action": "READ"}, {"objectName": "nivel_construccion", "action": "READ"},
  {"objectName": "obra_complementaria", "action": "READ"}, {"objectName": "cuota_predial", "action": "READ"},
  {"objectName": "brecha_corrida", "action": "READ"}, {"objectName": "brecha_predio", "action": "READ"},
  {"objectName": "parametro_tributario", "action": "READ"}, {"objectName": "arancel", "action": "READ"}]}'
```

**La cuenta de servicio `analitica`:**

```bash
curl -X POST "$SRTM/api/service-accounts" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name": "analitica", "roles": ["ANALITICA"]}'
```

- **El secreto:** la respuesta trae `clientId` y `clientSecret`, y el secreto se muestra **una sola vez**. La
  salida se redirige a un archivo con `chmod 600`, como la de `crear_cuenta_srtm.py` y la de `caja`.
- **Si se filtra:** `POST /api/service-accounts/{id}/secret` lo rota. Los tokens ya emitidos viven 15 minutos
  (wasichai ADR-043): el cliente del corte vuelve a pedir uno ante un 401 y sigue.

**En catastro:** el corte no abre una cuenta nueva; lee con la cuenta `srtm` que ya existe (`SRTM_LECTOR`,
sección 5), la misma que usa srtm para leer fichas, construcciones, obras, titulares y valuaciones de la
organización. Si catastro necesita distinguir en su auditoría el corte de la lectura normal de la brecha, hace
falta otra cuenta allá (no está hecho).

**El archivo de secretos:** las dos URL, las dos credenciales y la clave de seudonimización van en
`~/.config/srtm-analitica/<org>.env` (fuera del repositorio, modo `0600`), nunca en `parametros.json`:

```bash
SRTM_URL=<url del Core de srtm de la organización>
SRTM_CLIENT_ID=<clientId de analitica>
SRTM_CLIENT_SECRET=<clientSecret de analitica>
CATASTRO_URL=<url del Core de catastro de la organización>
CATASTRO_CLIENT_ID=<clientId de la cuenta srtm en catastro>
CATASTRO_CLIENT_SECRET=<clientSecret de la cuenta srtm en catastro>
CLAVE_SEUDONIMO=<64 caracteres hexadecimales, de python3 -c "import secrets; print(secrets.token_hex(32))">
```

La clave se guarda también en el gestor de claves de la organización, no solo en este archivo: cambiarla corta
la comparación entre cortes anteriores y nuevos. Más detalle en `docs/informes/brecha/README.adoc`.

## Registro de Ica (2026-10-20)

**Dev:**
- **Perené:** catastro quedó con región SELVA, con 4 escrituras de modelo y el rol. En srtm se aplicaron 16 escrituras
  de modelo y después `municipalidad.escudo`.
- **Ica en srtm:**
  - modelo completo;
  - 2 323 registros de catálogos: 1 893 ubigeos, 57 categorías de valor, 96 obras de Costa, 276 usos y la
    municipalidad;
  - 13 parámetros;
  - padrón `PADRON_CONSTRUCCION.xlsx` (un extracto de unas 7 800 de 32 000 filas): **21 315 registros**, que son 588
    vías, 161 unidades urbanas, 1 458 contribuyentes, 7 521 predios, 7 829 DJ y 3 758 niveles, con 4 filas rechazadas.
    La segunda corrida crea 0.
- **Ica en catastro:** modelo, rol, región COSTA y cuenta de servicio. Todavía no tiene levantamiento.

**Entorno de pruebas `srtm-ica` (Docker del VPS):**
- **Organización `ica-prueba`:** padrón y catastro ficticios (600 predios). La brecha dio exactamente lo sembrado:

  | Categoría | Predios |
  |---|---|
  | COINCIDE | 536 (Δ 0,00) |
  | SUBVALUADO | 26 |
  | SOBREVALUADO | 12 |
  | OMISO | 8 |
  | SIN_CATASTRO | 17 |
  | SIN_VALOR | 9 |

- **Organización `ica`:** el padrón real, con autorización expresa, más un catastro **sintético, solo de prueba**,
  derivado del mismo padrón:

  | Categoría | Predios |
  |---|---|
  | COINCIDE | 6 715 |
  | SUBVALUADO | 395 |
  | SOBREVALUADO | 171 |
  | SIN_CATASTRO | 226 |
  | OMISO | 150 |
  | SIN_VALOR | 14 |

- **Motor:** el de Costa reproduce exacto el valor de 3 688 de los 3 755 niveles del padrón.

**Pendiente** (en la épica #126):
- el levantamiento real (catastro-backend#10);
- el padrón completo;
- las tablas de códigos por confirmar con la municipalidad;
- el porcentaje de condominio;
- el RUC.
