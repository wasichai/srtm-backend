# 10 · Instalación, operación y recuperación

<!-- generado por scripts/storyboards.ts a partir de src/videos: no se edita a mano -->

- **Composición:** `S10-Operacion` · **vídeo:** `salida/videos/srtm-10-operacion.mp4` · **miniatura:** `salida/miniaturas/srtm-10-operacion.jpg`
- **Audiencia:** DevOps y administradores de bases de datos
- **Objetivo:** Levantar un entorno, cargar el modelo y los datos, publicar y actualizar versiones, vigilar el servicio y respaldar y restaurar sin perder datos.
- **Duración:** 9:13 (16602 fotogramas a 30 fps, 1920 × 1080) · **escenas:** 22
- **Manuales:** Manual técnico del sistema · Base de demostración

| # | Escena | Tipo | Inicio | Duración | Fuente |
|---|---|---|---|---|---|
| 1 | Instalación, operación y recuperación | Portada | 0:00 | 11,0 s | Manual técnico del sistema › A quién está dirigido |
| 2 | Base y backend en la máquina local | Comandos | 0:10 | 28,0 s | Manual técnico del sistema › Preparar las variables de entorno |
| 3 | docker compose down -v borra los datos | Aviso | 0:38 | 16,0 s | Manual técnico del sistema › Levantar la base de datos local |
| 4 | Sin el modelo, el portal no tiene objetos | Comandos | 0:53 | 30,0 s | Manual técnico del sistema › Cargar el modelo y los datos mínimos |
| 5 | Siete pasos, en este orden | Proceso | 1:23 | 22,0 s | Manual técnico del sistema › Orden de carga |
| 6 | Cinco modos, uno destructivo | Tabla | 1:44 | 26,0 s | Manual técnico del sistema › apply.py: el modelo |
| 7 | Un cambio del modelo, sin sorpresas | Proceso | 2:09 | 22,0 s | Manual técnico del sistema › Aplicar un cambio del modelo en una base compartida |
| 8 | DNI, nombres y domicilios nunca van al repositorio | Aviso | 2:31 | 21,0 s | Manual técnico del sistema › Datos personales |
| 9 | De un commit al jar publicado | Proceso | 2:51 | 25,0 s | Manual técnico del sistema › Versiones y releases |
| 10 | Respaldo primero, todas las instancias juntas | Proceso | 3:16 | 25,0 s | Manual técnico del sistema › Actualizar el backend |
| 11 | Rutas de salud, sin token | Tabla | 3:40 | 22,0 s | Manual técnico del sistema › Salud |
| 12 | Las líneas que conviene vigilar | Tabla | 4:01 | 36,0 s | Manual técnico del sistema › Registros (logs) |
| 13 | Cinco cosas que respaldar | Tabla | 4:37 | 37,0 s | Manual técnico del sistema › Qué hay que respaldar |
| 14 | Un volcado completo, y su prueba | Comandos | 5:13 | 25,0 s | Manual técnico del sistema › Respaldar la base |
| 15 | Siempre en una base nueva y vacía | Comandos | 5:38 | 25,0 s | Manual técnico del sistema › Restaurar la base en una base nueva |
| 16 | Siete comprobaciones antes de usarla | Proceso | 6:02 | 25,0 s | Manual técnico del sistema › Verificar una restauración |
| 17 | Qué se pierde y cómo se recupera | Tabla | 6:26 | 34,0 s | Manual técnico del sistema › Recuperación ante desastres |
| 18 | La base de demostración, de punta a punta | Diagrama | 7:00 | 22,0 s | Manual técnico del sistema › Ensayo con la base de demostración |
| 19 | Lista de verificación | Tabla | 7:21 | 37,0 s | Manual técnico del sistema › Lista de verificación posterior al despliegue |
| 20 | Síntomas frecuentes | Tabla | 7:58 | 31,0 s | Manual técnico del sistema › Diagnóstico de problemas frecuentes |
| 21 | Lo que todavía falta | Tabla | 8:28 | 26,0 s | Manual técnico del sistema › Pendientes y limitaciones conocidas |
| 22 | La operación, en pocas palabras | Cierre | 8:53 | 20,0 s | Manual técnico del sistema › Operación |

## 1. Instalación, operación y recuperación

- **Id:** `portada` · **tipo:** Portada · **inicio:** 0:00 · **duración:** 11,0 s
- **Objetivo de aprendizaje:** Presentar el vídeo y su audiencia.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** El bloque azul baja; la marca, el título y el subtítulo entran escalonados; luego la audiencia y las fuentes.
- **Fuentes:** Manual técnico del sistema › A quién está dirigido (`docs/manuales/sistema/manual-sistema.adoc#intro-audiencia`)
- **Sigue:** 2. Base y backend en la máquina local
- **Contenido:**

  > Manual técnico del sistema: Instalación, operación y recuperación
  > Del primer arranque al respaldo y la restauración.
  > Para quién: DevOps y administradores de bases de datos.

## 2. Base y backend en la máquina local

- **Id:** `entorno-local` · **tipo:** Comandos · **inicio:** 0:10 · **duración:** 28,0 s
- **Objetivo de aprendizaje:** Levantar la base y el backend en desarrollo y comprobar que responden.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los bloques de comandos entran uno a uno, con lo que deben responder.
- **Fuentes:** Manual técnico del sistema › Preparar las variables de entorno (`docs/manuales/sistema/manual-sistema.adoc#proc-variables-desarrollo`); Manual técnico del sistema › Levantar la base de datos local (`docs/manuales/sistema/manual-sistema.adoc#proc-base-local`); Manual técnico del sistema › Arrancar el backend (`docs/manuales/sistema/manual-sistema.adoc#proc-arrancar-backend`)
- **Sigue:** 3. docker compose down -v borra los datos
- **Contenido:**

  > # variables de entorno: develop/.env no se versiona
  > $ cp develop/example.env develop/.env
  > $ set -a; source develop/.env; set +a
  > # PostgreSQL 18 + PostGIS 3.6 en 127.0.0.1:5433
  > $ docker compose up -d
  > # el backend en http://localhost:8090
  > $ ./gradlew bootRun
  > $ curl http://localhost:8090/actuator/health
  > → {"groups":["liveness","readiness"],"status":"UP"}

## 3. docker compose down -v borra los datos

- **Id:** `down-v` · **tipo:** Aviso · **inicio:** 0:38 · **duración:** 16,0 s
- **Objetivo de aprendizaje:** No borrar por error el volumen de la base local.
- **Imagen:** recuadro de aviso
- **Animación:** El recuadro del aviso entra y sus puntos aparecen uno a uno; a su lado, la captura, si la hay.
- **Fuentes:** Manual técnico del sistema › Levantar la base de datos local (`docs/manuales/sistema/manual-sistema.adoc#proc-base-local`)
- **Sigue:** 4. Sin el modelo, el portal no tiene objetos
- **Contenido:**

  > docker compose down -v borra los datos: El volumen postgres-data se va con el modelo, el padrón, las cuotas y la auditoría. Úselo solo para empezar de cero, y nunca sin un respaldo.
  >   - docker compose down, sin -v, conserva el volumen.

## 4. Sin el modelo, el portal no tiene objetos

- **Id:** `modelo-inicial` · **tipo:** Comandos · **inicio:** 0:53 · **duración:** 30,0 s
- **Objetivo de aprendizaje:** Cargar el modelo y los datos mínimos para que el portal funcione.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los bloques de comandos entran uno a uno, con lo que deben responder.
- **Fuentes:** Manual técnico del sistema › Cargar el modelo y los datos mínimos (`docs/manuales/sistema/manual-sistema.adoc#proc-modelo-inicial`)
- **Sigue:** 5. Siete pasos, en este orden
- **Contenido:**

  > # validar y aplicar model.json
  > $ python3 apply.py --validate-only
  > $ python3 apply.py
  > → done: N created, M updated, K skipped
  > # parámetros del predial y catálogos
  > $ python3 import_parametros.py
  > $ python3 import_catalogos.py --distrito 120302 …
  > → parametro_tributario: 13 created, …
  > # una segunda pasada no tiene nada que hacer
  > $ python3 apply.py --diff
  > → diff: nothing pending

## 5. Siete pasos, en este orden

- **Id:** `orden-carga` · **tipo:** Proceso · **inicio:** 1:23 · **duración:** 22,0 s
- **Objetivo de aprendizaje:** Cargar una base nueva en el orden en que cada paso depende del anterior.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los pasos entran en orden, unidos por flechas o por una línea que crece.
- **Fuentes:** Manual técnico del sistema › Orden de carga (`docs/manuales/sistema/manual-sistema.adoc#carga-orden`); Manual técnico del sistema › Importadores (`docs/manuales/sistema/manual-sistema.adoc#carga-importadores`)
- **Sigue:** 6. Cinco modos, uno destructivo
- **Contenido:**

  > 1. Core arriba: migraciones Flyway
  > 2. model/apply.py: objetos, campos, relaciones
  > 3. import_catalogos.py: ubigeo, categorías, obras, usos
  > 4. import_parametros.py: UIT, tramos, tasas
  > 5. import_cuis.py · import_arbitrios.py: CUIS y ordenanza de arbitrios
  > 6. import_predios.py: padrón (Excel; Ica: import_padron_ica.py)
  > 7. import_catastro.py: lotes (GeoJSON)
  > Nota: Todos los importadores son idempotentes y aceptan --dry-run: úselo primero con cada uno.

## 6. Cinco modos, uno destructivo

- **Id:** `apply-modos` · **tipo:** Tabla · **inicio:** 1:44 · **duración:** 26,0 s
- **Objetivo de aprendizaje:** Elegir el modo de apply.py según lo que se quiere ver o cambiar.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual técnico del sistema › apply.py: el modelo (`docs/manuales/sistema/manual-sistema.adoc#carga-apply`)
- **Sigue:** 7. Un cambio del modelo, sin sorpresas
- **Contenido:**

  > Modo | Qué hace | ¿Escribe?
  > --validate-only | Solo valida model.json, sin llamar a Core. | No
  > --dry-run | Imprime lo que declara model.json, sin consultar a Core. | No
  > --diff | Lee Core e imprime cada escritura como would … | No
  > (sin opción) | Aplica: done: N created, M updated, K skipped. | Sí
  > --drop | Apaga appendOnly y borra el modelo entero, con sus datos. | Sí, destructivo
  > Nota: Antes de --drop: --drop --diff para ver lo que borraría, un respaldo y confirmar que la base no es compartida.

## 7. Un cambio del modelo, sin sorpresas

- **Id:** `base-compartida` · **tipo:** Proceso · **inicio:** 2:09 · **duración:** 22,0 s
- **Objetivo de aprendizaje:** Llevar un cambio del modelo a una base compartida sin sorpresas.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los pasos entran en orden, unidos por flechas o por una línea que crece.
- **Fuentes:** Manual técnico del sistema › Aplicar un cambio del modelo en una base compartida (`docs/manuales/sistema/manual-sistema.adoc#proc-apply-compartida`)
- **Sigue:** 8. DNI, nombres y domicilios nunca van al repositorio
- **Contenido:**

  > 1. Validar: Con --validate-only.
  > 2. Ver lo pendiente: Con --diff, sin escribir.
  > 3. Aprobar: Mostrar la salida y esperar la conformidad.
  > 4. Aplicar: apply.py, sin opción.
  > 5. Confirmar: Otro --diff: nothing pending.
  > Nota: Requisitos: una cuenta con MANAGE_METADATA y un respaldo reciente.

## 8. DNI, nombres y domicilios nunca van al repositorio

- **Id:** `datos-personales` · **tipo:** Aviso · **inicio:** 2:31 · **duración:** 21,0 s
- **Objetivo de aprendizaje:** Proteger los archivos que traen datos personales.
- **Imagen:** recuadro de aviso
- **Animación:** El recuadro del aviso entra y sus puntos aparecen uno a uno; a su lado, la captura, si la hay.
- **Fuentes:** Manual técnico del sistema › Datos personales (`docs/manuales/sistema/manual-sistema.adoc#carga-datos-personales`); Manual técnico del sistema › Datos personales (`docs/manuales/sistema/manual-sistema.adoc#seg-datos-personales`)
- **Sigue:** 9. De un commit al jar publicado
- **Contenido:**

  > DNI, nombres y domicilios nunca van al repositorio: El Excel del padrón, los reportes de los importadores, los archivos de emisión y los respaldos traen datos personales.
  >   - *.xlsx, *.xls, model/reports/ y data/ están en .gitignore.
  >   - Guárdelos en almacenamiento privado, con acceso restringido.
  >   - Borre el Excel de las máquinas de trabajo al terminar la carga.

## 9. De un commit al jar publicado

- **Id:** `releases` · **tipo:** Proceso · **inicio:** 2:51 · **duración:** 25,0 s
- **Objetivo de aprendizaje:** Entender cómo se publica una versión y qué se publica.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los pasos entran en orden, unidos por flechas o por una línea que crece.
- **Fuentes:** Manual técnico del sistema › Versiones y releases (`docs/manuales/sistema/manual-sistema.adoc#desp-versiones`); Manual técnico del sistema › Qué se publica (`docs/manuales/sistema/manual-sistema.adoc#desp-artefactos`)
- **Sigue:** 10. Respaldo primero, todas las instancias juntas
- **Contenido:**

  > 1. Commits convencionales: feat: y fix: se mezclan a main.
  > 2. PR de release: release-please sube version= y escribe CHANGELOG.md.
  > 3. Etiqueta: Al mezclarlo: vX.Y.Z y el GitHub Release.
  > 4. El jar: release.yml adjunta srtm-backend-X.Y.Z.jar y su .sha256.
  > Nota: No se publica imagen de Docker ni manifiestos de Kubernetes: srtm-infrastructure solo tiene un README.md.

## 10. Respaldo primero, todas las instancias juntas

- **Id:** `actualizar` · **tipo:** Proceso · **inicio:** 3:16 · **duración:** 25,0 s
- **Objetivo de aprendizaje:** Pasar a una versión nueva sin perder datos ni dejar trabajos a medias.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los pasos entran en orden, unidos por flechas o por una línea que crece.
- **Fuentes:** Manual técnico del sistema › Actualizar el backend (`docs/manuales/sistema/manual-sistema.adoc#proc-actualizar`)
- **Sigue:** 11. Rutas de salud, sin token
- **Contenido:**

  > 1. Respaldar: La base y el almacén.
  > 2. Aplicar el modelo: Si la versión lo cambia, con apply.py.
  > 3. Detener: Todas las instancias de la versión anterior.
  > 4. Arrancar: Las instancias nuevas.
  > 5. Comprobar: Salud, log de arranque y lista de verificación.
  > Nota: Las migraciones de Core no se revierten: volver atrás exige restaurar el respaldo tomado antes de actualizar.

## 11. Rutas de salud, sin token

- **Id:** `salud` · **tipo:** Tabla · **inicio:** 3:40 · **duración:** 22,0 s
- **Objetivo de aprendizaje:** Saber qué rutas de salud hay y qué comprueban.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual técnico del sistema › Salud (`docs/manuales/sistema/manual-sistema.adoc#op-salud`)
- **Sigue:** 12. Las líneas que conviene vigilar
- **Contenido:**

  > Ruta | Responde
  > GET /actuator/health | {"groups":["liveness","readiness"],"status":"UP"}
  > GET /actuator/health/liveness | 200 si el proceso está vivo.
  > GET /actuator/health/readiness | 200 si está listo para atender.
  > GET /api/health | {"status":"UP","application":"srtm-backend"}
  > Nota: No comprueban el almacén. No verificado: si incluyen la base. Para confirmarlo, GET /api/srtm/resumen con un token.

## 12. Las líneas que conviene vigilar

- **Id:** `logs` · **tipo:** Tabla · **inicio:** 4:01 · **duración:** 36,0 s
- **Objetivo de aprendizaje:** Reconocer en el log las líneas que piden una acción.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual técnico del sistema › Registros (logs) (`docs/manuales/sistema/manual-sistema.adoc#op-logs`)
- **Sigue:** 13. Cinco cosas que respaldar
- **Contenido:**

  > Mensaje (comienzo) | Nivel | Qué hacer
  > ESCRITURA FUERA DE SRTM RECHAZADA | WARN | Alguien escribió infracciones o anuncios fuera de la API. Revise el usuario.
  > audit_log is append-only through triggers … | WARN | Esperado en desarrollo; en producción, separe los roles de la base.
  > … no cabe en el pool de conexiones | WARN | Suba el pool o baje los trabajadores.
  > … sin quien las corriera pasan a FALLIDA | WARN | Trabajos abandonados: vuelva a lanzarlos.
  > no se pudo ensamblar la emisión masiva | ERROR | Revise el almacén: espacio, permisos, bucket.
  > la corrida de brecha <id> falló | ERROR | El motivo está en el mensaje de la corrida.
  > Nota: El backend escribe en la salida estándar. Cada respuesta lleva X-Correlation-Id, que también queda en audit_log.

## 13. Cinco cosas que respaldar

- **Id:** `que-respaldar` · **tipo:** Tabla · **inicio:** 4:37 · **duración:** 37,0 s
- **Objetivo de aprendizaje:** Saber qué partes forman un respaldo completo.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual técnico del sistema › Qué hay que respaldar (`docs/manuales/sistema/manual-sistema.adoc#resp-que`)
- **Sigue:** 14. Un volcado completo, y su prueba
- **Contenido:**

  > Qué | Por qué | Cómo
  > La base completa | Esquemas wasichai (metadata, usuarios, auditoría) y app_data, y las extensiones. | pg_dump de la base entera
  > El almacén de emisiones | La base guarda solo la clave del archivo: sin él, la descarga da 404. | Copia del directorio o del bucket
  > El almacén de archivos | El escudo de cada municipalidad (campos FILE e IMAGE). | Copia del directorio o del bucket
  > La configuración | El archivo de entorno o los secretos, y el escudo de respaldo. | Cifrada, con los secretos
  > Los datos de entrada | Excel del padrón, GeoJSON, CSV y JSON de ordenanzas. | Almacenamiento privado
  > Nota: Nunca un solo esquema: app_data sin wasichai no sirve, y wasichai sin app_data restaura un modelo sin registros.

## 14. Un volcado completo, y su prueba

- **Id:** `respaldar` · **tipo:** Comandos · **inicio:** 5:13 · **duración:** 25,0 s
- **Objetivo de aprendizaje:** Tomar un volcado completo y comprobar que se puede leer.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los bloques de comandos entran uno a uno, con lo que deben responder.
- **Fuentes:** Manual técnico del sistema › Respaldar la base (`docs/manuales/sistema/manual-sistema.adoc#proc-respaldo-base`); Manual técnico del sistema › Respaldar el almacén de emisiones (`docs/manuales/sistema/manual-sistema.adoc#proc-respaldo-almacen`)
- **Sigue:** 15. Siempre en una base nueva y vacía
- **Contenido:**

  > # formato personalizado: comprimido y restaurable por partes
  > $ docker compose exec -T postgres pg_dump -U srtm -d srtm -Fc > srtm-$(date +%Y%m%d-%H%M).dump
  > # debe dar más que cero; la lista incluye flyway_history_core y audit_log
  > $ pg_restore --list srtm-AAAAMMDD-HHMM.dump | grep -c 'TABLE DATA'

## 15. Siempre en una base nueva y vacía

- **Id:** `restaurar` · **tipo:** Comandos · **inicio:** 5:38 · **duración:** 25,0 s
- **Objetivo de aprendizaje:** Restaurar en una base nueva sin tocar la original.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los bloques de comandos entran uno a uno, con lo que deben responder.
- **Fuentes:** Manual técnico del sistema › Restaurar la base en una base nueva (`docs/manuales/sistema/manual-sistema.adoc#proc-restaurar`); Manual técnico del sistema › Restaurar (`docs/manuales/sistema/manual-sistema.adoc#resp-restaurar`)
- **Sigue:** 16. Siete comprobaciones antes de usarla
- **Contenido:**

  > # cada comando, detrás de docker compose exec -T postgres
  > $ createdb -U srtm srtm_restaurada
  > $ pg_restore -U srtm -d srtm_restaurada --no-owner --exit-on-error < srtm-AAAAMMDD-HHMM.dump
  > # el backend, contra la base restaurada
  > $ WASICHAI_DB_NAME=srtm_restaurada ./gradlew bootRun

## 16. Siete comprobaciones antes de usarla

- **Id:** `verificar` · **tipo:** Proceso · **inicio:** 6:02 · **duración:** 25,0 s
- **Objetivo de aprendizaje:** Comprobar que la base restaurada está completa antes de usarla.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los pasos entran en orden, unidos por flechas o por una línea que crece.
- **Fuentes:** Manual técnico del sistema › Verificar una restauración (`docs/manuales/sistema/manual-sistema.adoc#proc-verificar-restauracion`)
- **Sigue:** 17. Qué se pierde y cómo se recupera
- **Contenido:**

  > 1. Salud: /actuator/health → UP, sin errores de Flyway.
  > 2. Modelo: 42 objetos; apply.py --diff sin pendientes.
  > 3. Datos: /api/srtm/resumen da los totales del original.
  > 4. Usuarios: Una cuenta de cada perfil abre una pantalla suya.
  > 5. Auditoría: /api/audit?limit=5: las últimas entradas.
  > 6. Archivos: Se descarga la última emisión terminada.
  > 7. Opcional: Conteo de filas por tabla de app_data.

## 17. Qué se pierde y cómo se recupera

- **Id:** `desastres` · **tipo:** Tabla · **inicio:** 6:26 · **duración:** 34,0 s
- **Objetivo de aprendizaje:** Saber qué se pierde y cómo se recupera en cada situación.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual técnico del sistema › Recuperación ante desastres (`docs/manuales/sistema/manual-sistema.adoc#resp-desastres`)
- **Sigue:** 18. La base de demostración, de punta a punta
- **Contenido:**

  > Situación | Qué se pierde | Cómo se recupera
  > Se perdió el servidor | Nada, si la base y el almacén están aparte. | Instalar el jar en otro servidor; los lotes se retoman solos.
  > Se perdió la base | Lo escrito después del último respaldo. | Restaurar el último volcado y rehacer lo posterior.
  > Se perdió el almacén | Los archivos no respaldados. | Restaurar la copia; lo que falte se vuelve a emitir.
  > Falló una actualización | Lo escrito con la versión nueva. | Restaurar el volcado previo y volver al jar anterior.
  > apply.py --drop por error | Los registros de los objetos borrados. | Restaurar el último volcado: no hay otra vía.

## 18. La base de demostración, de punta a punta

- **Id:** `demo` · **tipo:** Diagrama · **inicio:** 7:00 · **duración:** 22,0 s
- **Objetivo de aprendizaje:** Usar la base de demostración para ensayar el respaldo y la restauración sin datos reales.
- **Imagen:** diagrama de 7 nodos y 6 flechas, dibujado en la escena
- **Animación:** Los nodos aparecen por pasos y las flechas se dibujan; lo nuevo de cada paso se resalta un momento y su explicación aparece al pie.
- **Fuentes:** Manual técnico del sistema › Ensayo con la base de demostración (`docs/manuales/sistema/manual-sistema.adoc#resp-demo`); Base de demostración › Restaurar y verificar (`docs/manuales/demo/README.adoc#_restaurar_y_verificar`)
- **Sigue:** 19. Lista de verificación
- **Contenido:**

  > Nodos: make demo-arriba · Core :8098 · make demo-sembrar · make demo-exportar · make demo-restaurar · make demo-verificar · make capturas
  > Nota: Datos sintéticos: un ensayo del respaldo y la restauración que compara conteos, claves foráneas y secuencias.

## 19. Lista de verificación

- **Id:** `checklist` · **tipo:** Tabla · **inicio:** 7:21 · **duración:** 37,0 s
- **Objetivo de aprendizaje:** Recorrer las comprobaciones posteriores a un despliegue.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual técnico del sistema › Lista de verificación posterior al despliegue (`docs/manuales/sistema/manual-sistema.adoc#op-checklist`)
- **Sigue:** 20. Síntomas frecuentes
- **Contenido:**

  > Comprobación | Cómo
  > El servicio responde. | GET /actuator/health → UP
  > La semilla de desarrollo está apagada. | El login admin@wasichai.local / admin da 401
  > El secreto JWT no es el de desarrollo. | Archivo de entorno o gestor de secretos
  > El modelo está al día. | apply.py --diff → nothing pending
  > La API lee la base. | GET /api/srtm/resumen con un token
  > El log no tiene ERROR. | Solo los WARN esperados
  > Los trabajadores corren donde deben. | La línea «corre N trabajadores»
  > El almacén es escribible y persistente. | Una emisión de prueba se descarga
  > La cabecera es la de la municipalidad. | Un PU o una HR de prueba
  > PIDE y catastro están como se decidió. | Encendidos o apagados, a propósito
  > El respaldo está programado y probado. | Una restauración de prueba

## 20. Síntomas frecuentes

- **Id:** `diagnostico` · **tipo:** Tabla · **inicio:** 7:58 · **duración:** 31,0 s
- **Objetivo de aprendizaje:** Resolver los problemas más frecuentes por su síntoma.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual técnico del sistema › Diagnóstico de problemas frecuentes (`docs/manuales/sistema/manual-sistema.adoc#diagnostico`)
- **Sigue:** 21. Lo que todavía falta
- **Contenido:**

  > Síntoma | Causa probable | Acción
  > Could not find wasichai:… | Sin token de GitHub Packages. | Configurar gpr.user y gpr.key.
  > Connection refused a :5433 | Base apagada o sin túnel. | docker compose up -d o el túnel SSH.
  > 401 en toda llamada | Token vencido (8 h) u otro secreto. | Iniciar sesión otra vez.
  > 409 «Catastro no configurado…» | Apagado o sin entrada para la organización. | SRTM_CATASTRO_ORGANIZACIONES_*.
  > 422 con faltan | Falta un parámetro del año. | import_parametros.py --csv.
  > 410 en la descarga | La retención depuró el archivo. | Volver a emitir el año.
  > Los trabajos no avanzan | Trabajadores en 0 o pool agotado. | Revisar la línea de arranque.

## 21. Lo que todavía falta

- **Id:** `pendientes` · **tipo:** Tabla · **inicio:** 8:28 · **duración:** 26,0 s
- **Objetivo de aprendizaje:** Conocer las limitaciones conocidas antes de operar en producción.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual técnico del sistema › Pendientes y limitaciones conocidas (`docs/manuales/sistema/manual-sistema.adoc#pendientes`)
- **Sigue:** 22. La operación, en pocas palabras
- **Contenido:**

  > Tema | Estado
  > PIDE RENIEC | Nunca probado contra la PIDE real. Apagado.
  > Despliegue | Sin imagen de Docker, manifiestos ni infraestructura como código.
  > Seguridad | Semilla activa por defecto; un solo rol de base de datos en los entornos actuales.
  > Cobranza | SRTM determina y no cobra: deuda y pagos son de caja-backend.
  > Medición | Falta medir la emisión masiva con el padrón real (#55).
  > Release | v0.9.0 usa wasichai 0.6.0; main no tiene release propio.

## 22. La operación, en pocas palabras

- **Id:** `cierre` · **tipo:** Cierre · **inicio:** 8:53 · **duración:** 20,0 s
- **Objetivo de aprendizaje:** Recordar lo esencial de la operación y saber dónde seguir.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los puntos del resumen entran uno a uno; luego los manuales y el vídeo siguiente.
- **Fuentes:** Manual técnico del sistema › Operación (`docs/manuales/sistema/manual-sistema.adoc#operacion`)
- **Sigue:** fin del vídeo
- **Contenido:**

  > ✓ El modelo se carga con apply.py, siempre con --diff antes.
  > ✓ Respaldo completo: la base entera y el almacén.
  > ✓ Se restaura en una base nueva y se verifica antes de usarla.
  > ✓ Las migraciones no se revierten: respalde antes de actualizar.
  > Para saber más: Manual técnico del sistema · Base de demostración (demo/README.adoc)
