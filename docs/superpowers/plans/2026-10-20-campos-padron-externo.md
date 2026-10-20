# Campos opcionales para padrones externos (Ica): plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Objetivo:** el modelo predial puede guardar lo que trae el padrón de Ica, sin obligar a nada a los registros que ya
están (Perené) ni a los que registra el portal.

**Arquitectura:** solo `model/model.json` cambia de forma: campos nuevos, todos opcionales, al final de cada objeto
(el orden en que `apply.py` los crea en Core), y `nivel_construccion.mes_construccion` deja de ser obligatorio
(`apply.py` ya relaja un campo que el modelo deja de exigir). Los DTO del portal **no** ganan los campos: los registros
se leen ignorando lo desconocido y se escriben fusionados sobre lo guardado (`Registros.replace`), así que un guardado
del portal no los borra. Si el DTO los tuviera, un guardado de srtm-ui (que no los manda) los dejaría en null.

**Stack:** model.json + apply.py (Python, unittest), Kotlin/Spring sobre wasichai, pruebas de integración contra el
PG18+PostGIS local (localhost:5446, `srtm_test`).

**Spec:** issue wasichai/srtm-backend#124 (épica #126).

## Restricciones globales

- `predio`: `codigo_via`, `codigo_unidad_urbana`, `codigo_hu_armonizacion`, `codigo_via_armonizacion` (TEXT).
- `declaracion_predial`: `arancel`, `valor_terreno`, `valor_construccion`, `valor_otras_instalaciones` (DECIMAL).
- `nivel_construccion`: `clasificacion` (ENUM `clasificacion`, el de la DJ), `valor_unitario`, `incremento`,
  `porcentaje_depreciacion`, `valor_total` (DECIMAL).
- `via`, `unidad_urbana`: `codigo` (TEXT, no único: Perené no lo trae).
- Ninguno `required` ni `unique`. Sin opciones de enum nuevas (regex `^[\p{L}0-9 _.-]{1,64}$`).
- Nada contra el Core ni la base de dev: solo `apply.py --dry-run`; el `--diff` lo corre el usuario.
- No tocar `src/main/kotlin/srtm/brecha/*` ni srtm-ui.

## Revisión: qué asume que existe `mes_construccion`

- `HojaPu.anioMes` ya es null-safe: sin mes imprime solo el año (`2015`), sin año, vacío.
- `Dtos.NivelConstruccion.mesConstruccion` ya es `Int?`; ninguna regla (`Reglas.kt`, `ReglasFueraDelPortal.kt`) lo lee.
- La HR no lee niveles. El cálculo del impuesto parte de `valor_autoavaluo`, no de los niveles.
- srtm-ui: la lista muestra `—` sin mes; el formulario del nivel lo pide (`required: true` en `declaracionSpecs.tsx`),
  así que editar en el portal un nivel importado de Ica pedirá el mes. No se rompe nada.

## Puntos de revisión

1. Un guardado del portal de un predio, una DJ o un nivel de Ica conserva los campos nuevos (el DTO no los tiene).
2. Un nivel sin mes se puede registrar y la PU sale con el año solo.
3. La HR de un contribuyente cuya DJ trae los valores de Ica sale igual.
4. `obra_complementaria.mes_construccion` sigue obligatorio (el issue solo relaja el del nivel).
5. `--diff` contra dev: solo POST de campos nuevos y un PUT `required: false` de `mes_construccion`.

---

### Tarea 1: el modelo

**Archivos:** `model/model.json`, `model/test_model.py`, `README.md` (sección Modelo).

- [ ] Prueba `test_the_padron_externo_fields_are_optional` en `ShippedModelTests`: por objeto, los campos nuevos al
  final en el orden de arriba, con su tipo, sin `required` ni `unique`; `nivel_construccion.clasificacion.enum ==
  "clasificacion"`; `nivel_construccion.mes_construccion` sin `required`; `obra_complementaria.mes_construccion`
  con `required`.
- [ ] `cd model && python3 -m unittest test_model -v`: falla (faltan campos).
- [ ] Agregar los campos a `model.json` con label y descripción en castellano («Heredado del padrón externo (Ica)…»)
  y quitar `required` de `nivel_construccion.mes_construccion`, con descripción.
- [ ] `python3 -m unittest` en verde y `python3 apply.py --dry-run` muestra los campos.
- [ ] README: un párrafo «Padrones externos (Ica)» con los campos y por qué son opcionales.
- [ ] Commit `feat(modelo): campos opcionales para padrones externos (Ica)`.

### Tarea 2: el backend con los campos vacíos o llenos

**Archivos:** `src/test/kotlin/srtm/rentas/PadronExternoApiTest.kt` (nuevo), `src/test/kotlin/srtm/emision/HojaPuTest.kt`,
`PuApiTest.kt`, `HrApiTest.kt`.

- [ ] `PadronExternoApiTest`: `a nivel without its month is accepted` (POST `/api/srtm/declaraciones/{d}/niveles`
  sin `mes_construccion` → 201); `the external padron's fields survive a portal save` (se escriben por
  `/api/objects/{objeto}/records/{id}`, se guarda por el portal predio, declaración y nivel, y siguen ahí).
- [ ] `HojaPuTest`: `a nivel without its month shows its year alone` → `anioMes == "2015"`.
- [ ] `PuApiTest`: `a nivel of an external padron, without its month, prints its year` → 200 con `2015` y sin `/2015`.
- [ ] `HrApiTest`: `the hr reads a declaracion with the external padron's values` → 200 con el código del predio.
- [ ] `./gradlew test` en verde (antes del modelo nuevo, los de nivel sin mes fallan con 400).
- [ ] Commit `test(modelo): la PU, la HR y el portal con los campos del padrón externo`.
