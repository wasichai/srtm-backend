# Plan: el entorno de pruebas (`develop/pruebas/`) en el Mac

**Objetivo:** levantar en el Mac el mismo stack que corre como `srtm-ica` en el VPS: la base PostGIS, los dos Core y
las dos UI. Después, repetir sobre ese stack la prueba de punta a punta con el padrón **ficticio**, para comprobar que
la guía de #145 (`develop/pruebas/README.md` y `docs/develop/alta-organizacion.md`) alcanza para hacerlo sin ayuda.

**Fuera del alcance:**
- **Padrón real:** no entra al Mac. Lleva datos personales y solo está autorizado en el VPS.
- **Bases de dev:** no se tocan.

## Estado de partida (2026-10-20)

| Punto | Estado | Consecuencia |
|---|---|---|
| Runtime de Docker local | **No hay.** `DOCKER_HOST=unix:///tmp/docker.sock` apunta al túnel del VPS. | Hay que instalar uno (decisión 1). |
| Arquitectura | arm64 (Apple Silicon), 10 CPU y 16 GB | `postgis/postgis:18-3.6` solo se publica para **amd64** (decisión 2). |
| Disco | 35 GB libres (92 % usado) | Alcanza para unos 4 GB de imágenes y el disco de la VM. Es justo. |
| Puertos 8190, 8191, 5190 y 5191 | Los ocupa el túnel ssh al `srtm-ica` del VPS (pid 10871). | Se cambian con `PRUEBAS_*_PORT`, o se cierra el túnel. |
| Puerto 5448 | Libre | |
| Checkouts | catastro-backend, srtm-ui y catastro-ui están en `~/IdeaProjects` | `preparar.sh` necesita worktrees limpios de `origin/main`. |

## Decisiones (del usuario)

1. **Runtime:** se recomienda **Colima** (`brew install colima`, con `--vm-type vz --vz-rosetta --cpu 4 --memory 6
   --disk 30`): es libre, no tiene licencia y tiene Rosetta para amd64. Las alternativas son OrbStack (más liviano,
   gratis solo para uso personal) y Docker Desktop (licencia en empresas de más de 250 personas).
2. **PostGIS en arm64:**
   - **a)** Emular amd64 con Rosetta, sin cambiar el repo. Basta `platform: linux/amd64` en el `pg` local o
     `DOCKER_DEFAULT_PLATFORM`. PostgreSQL bajo Rosetta anda bien para este volumen.
   - **b)** Un PR chico que agregue `ARG PG_IMAGE=postgis/postgis:18-3.6` en `pg/Dockerfile`, para usar en el Mac
     `imresamu/postgis:18-3.6`, que es multiarquitectura.
   - **Recomendación:** a) primero. Si falla o es lento, b).
3. **Puertos:** se recomienda **otros puertos** y el túnel al VPS sigue abierto. El proyecto se llama `srtm-local`, con
   los puertos 5449, 8290, 8291, 5290 y 5291.

## Tareas

### T1. Runtime (con la decisión 1)
- **Instalar y arrancar:** `brew install colima` y `colima start --vm-type vz --vz-rosetta --cpu 4 --memory 6 --disk 30`.
- **Usarlo:** `DOCKER_HOST` sigue apuntando al VPS en el perfil, así que cada comando local lleva `docker --context
  colima` o `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`. Sin esto, los comandos van al VPS.
- **Verificación:** `docker --context colima info` devuelve `Architecture: aarch64`.

### T2. Contextos de build
- **Worktrees de main:** `git worktree add` desde `origin/main` en los cuatro repos, bajo
  `~/IdeaProjects/pruebas-local/`.
- **Armar los contextos:** `develop/pruebas/preparar.sh <srtm-backend> <catastro-backend> <srtm-ui> <catastro-ui>`.
- **Verificación:** existen `core-srtm/app.jar`, `core-catastro/app.jar`, `ui-srtm/dist/index.html` y
  `ui-catastro/dist/index.html`, y `nginx.conf` tiene el nombre del Core que le toca.

### T3. Claves
- **Archivos:** `pg.env`, `srtm.env` y `catastro.env` se copian de las plantillas, con claves nuevas de `openssl rand`.
  Quedan con `chmod 600` y en gitignore. Las cuentas de catastro se llenan en T6.
- **Verificación:** `git status` no muestra ninguno.

### T4. Arranque
- **Comando:**

  ```bash
  PRUEBAS_PROYECTO=srtm-local PRUEBAS_PG_PORT=5449 PRUEBAS_SRTM_PORT=8290 PRUEBAS_CATASTRO_PORT=8291 \
  PRUEBAS_SRTM_UI_PORT=5290 PRUEBAS_CATASTRO_UI_PORT=5291 \
  docker --context colima compose -f develop/pruebas/compose.yml up -d --build
  ```

- **Con la decisión 2a:** se agrega un `compose.local.yml` fuera del repo (scratchpad) con `services.pg.platform:
  linux/amd64`, y se pasa como segundo `-f`.
- **Verificación:** los 5 servicios `healthy` o `running`; `curl localhost:8290/actuator/health` y el mismo en :8291
  devuelven `UP`; las UI responden en :5290 y :5291.

### T5. Organizaciones
- **Cómo:** siguiendo `alta-organizacion.md`, §1 a §5, con `WASICHAI_CORE=http://localhost:8290` y `:8291`.
- **Qué se crea:** la organización `ica-prueba` en los dos Core, con región COSTA, catálogos y parámetros.
- **Registro:** cada script va con `--dry-run` y después de verdad. Es una base desechable, así que no espera OK, pero
  cada salida se anota.

### T6. Catastro y cuenta de servicio
- **Cuenta:** `crear_cuenta_srtm.py --clave icaprueba` va a un archivo con `chmod 600`. Con ella se llenan las
  `SRTM_CATASTRO_ORGANIZACIONES_ICAPRUEBA_*` de `srtm.env`, y se corre `up -d srtm-core`.
- **Verificación:** el log dice `srtm.catastro: catastro de …, <id de ica-prueba>`.

### T7. Datos ficticios y brecha
- **Generar:** `python3 -I develop/pruebas/generar_ficticio.py model <scratchpad>/ficticio 600`.
- **Cargar:** el padrón con `import_padron_ica.py` y el catastro con `import_fichas.py` e `import_aranceles.py`.
- **Correr la brecha 2026.**
- **Criterio de éxito:** los conteos por categoría coinciden con `perturbaciones.csv`, igual que en el VPS:

  | Categoría | Predios |
  |---|---|
  | COINCIDE | 536, con Δ 0,00 |
  | SUBVALUADO | 26 |
  | SOBREVALUADO | 12 |
  | OMISO | 8 |
  | SIN_CATASTRO | 17 |
  | SIN_VALOR | 9 |

### T8. UI
- **Navegación:** en srtm-ui (:5290) se entra con el administrador de `ica-prueba` y se recorre el panel de la brecha,
  una DJ con su valuación de origen (#137) y el escudo (#141). Se usa Playwright con capturas.
- **Verificación:** no hay errores en la consola, y los totales del panel cuadran con T7.

### T9. Cierre
- **Fallas de la guía:** cada paso que falló o que no estaba escrito va a un PR que corrija `develop/pruebas/README.md`
  o `alta-organizacion.md`. Ahí también va una sección «En el Mac (arm64)» con lo de T1 y la decisión 2.
- **Bajar el stack:** `docker --context colima compose -p srtm-local -f develop/pruebas/compose.yml down -v`.
  `colima stop` es opcional.

## Riesgos
- **Comando al VPS por error:** la mitigación es usar siempre `--context colima` y un nombre de proyecto distinto
  (`srtm-local`). Así, aunque se equivoque el daemon, no choca con `srtm-ica`.
- **Memoria:** dos JVM de 1,5 GB, más PostgreSQL y nginx, piden unos 5 GB. Una VM de 6 GB alcanza. Si el Mac se pone
  lento, se baja `-Xmx` a 1g en los `.env`.
- **Disco al 92 %:** se revisa `df` después de construir. `docker --context colima system prune` libera las capas.

## Resultado (2026-10-20)

Hecho con Colima 0.10.3 (vz, Rosetta, 4 CPU, 6 GB), y `srtm-local` en los puertos 5449, 8290, 8291, 5290 y 5291.

- **Decisiones:** 1 → Colima; 2 → a), Rosetta; 3 → puertos nuevos.
- **T1:** además de Colima hizo falta `docker-buildx`. El builder clásico ignora `platform` y falla con `no matching
  manifest for linux/arm64/v8`, aunque la imagen amd64 ya esté bajada.
- **T4:** los 5 servicios arriba, y los dos Core en `UP`.
- **T5:**
  - srtm: 122 escrituras de modelo, 2 323 catálogos y 13 parámetros, igual que en dev;
  - padrón ficticio: 2 950 registros, sin filas rechazadas;
  - catastro: modelo, rol, región COSTA y cuenta.
- **T6:** la primera corrida falló con 401. Fue un error de copia: se copió `<el clientSecret>` de la línea de
  ejemplo, en vez del secreto. Se corrigió y quedó documentado en el README.
- **T7:** 12 aranceles y 591 fichas con 1 001 pisos. La brecha dio **exactamente** lo sembrado:

  | Categoría | Predios |
  |---|---|
  | COINCIDE | 536, con Δ 0,00 |
  | SUBVALUADO | 26 |
  | SOBREVALUADO | 12 |
  | OMISO | 8 |
  | SIN_CATASTRO | 17 |
  | SIN_VALOR | 9 |

  La versión del catastro es `valuacion-v2`, y el Δ impuesto da S/ 6 616,58.
- **T8:**
  - el panel de la brecha cuadra con la API;
  - el encabezado dice «MUNICIPALIDAD PROVINCIAL DE ICA» (#141);
  - la DJ muestra las columnas del padrón de origen (#137);
  - la consola no tiene errores ni advertencias.
  - **Pendiente:** el escudo. El archivo no estaba en disco, así que no se subió.
  - **Observación, por revisar:** en la ficha del predio, «Sector» y «Manzana catastral» salen «—», aunque la
    ubicación sí tiene la manzana. Parece que el importador no llena esas referencias. La brecha por sector no se
    afecta.
- **T9:** sección «En el Mac (arm64)» y «De punta a punta» en `develop/pruebas/README.md`.
