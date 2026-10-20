# 08 · Administración del sistema

<!-- generado por scripts/storyboards.ts a partir de src/videos: no se edita a mano -->

- **Composición:** `S08-Administrador` · **vídeo:** `salida/videos/srtm-08-administrador.mp4` · **miniatura:** `salida/miniaturas/srtm-08-administrador.jpg`
- **Audiencia:** Administradores del sistema (rol ADMIN) y quien coordina con el área de sistemas
- **Objetivo:** Dar acceso con roles acotados, mantener los datos maestros, seguir la auditoría y coordinar con el área de sistemas el año tributario, los respaldos y las operaciones críticas.
- **Duración:** 9:44 (17517 fotogramas a 30 fps, 1920 × 1080) · **escenas:** 27
- **Manuales:** Manual del administrador

| # | Escena | Tipo | Inicio | Duración | Fuente |
|---|---|---|---|---|---|
| 1 | Administrar Rentas municipales | Portada | 0:00 | 12,0 s | Manual del administrador › Funciones del administrador |
| 2 | Lo que se hace en la consola y lo que no | Comparación | 0:11 | 22,0 s | Manual del administrador › Administración funcional y administración técnica |
| 3 | La cuenta ADMIN se salta todos los permisos | Aviso | 0:33 | 16,0 s | Manual del administrador › Administración funcional y administración técnica |
| 4 | Se abre desde el portal, en la misma sesión | Captura recorrida | 0:48 | 27,5 s | Manual del administrador › Entrar a la consola |
| 5 | Usuarios | Separador de sección | 1:15 | 6,0 s | Manual del administrador › Usuarios |
| 6 | Crear una cuenta | Captura recorrida | 1:21 | 28,0 s | Manual del administrador › Crear una cuenta |
| 7 | Cambiar contraseña, estado o roles | Captura recorrida | 1:48 | 25,5 s | Manual del administrador › Cambiar la contraseña, el nombre o los roles de una cuenta |
| 8 | Desactivar o eliminar una cuenta | Comparación | 2:13 | 22,0 s | Manual del administrador › Desactivar o eliminar una cuenta |
| 9 | La baja no corta una sesión abierta | Aviso | 2:34 | 23,5 s | Manual del administrador › Desactivar o eliminar una cuenta |
| 10 | Roles y permisos | Separador de sección | 2:57 | 6,5 s | Manual del administrador › Roles y permisos |
| 11 | Una cuenta, sus roles y sus permisos | Diagrama | 3:03 | 29,0 s | Manual del administrador › Cómo funcionan los permisos |
| 12 | Crear un rol | Captura recorrida | 3:31 | 25,5 s | Manual del administrador › Crear un rol |
| 13 | La matriz de permisos de un rol | Captura recorrida | 3:56 | 27,5 s | Manual del administrador › Asignar los permisos de un rol |
| 14 | Cinco perfiles de partida | Tabla | 4:23 | 32,0 s | Manual del administrador › Perfiles sugeridos |
| 15 | Datos maestros | Separador de sección | 4:55 | 6,0 s | Manual del administrador › Datos maestros y parámetros |
| 16 | La cabecera de los documentos | Captura recorrida | 5:00 | 31,0 s | Manual del administrador › Corregir la cabecera de los documentos |
| 17 | Las cifras de los cálculos | Captura recorrida | 5:30 | 27,0 s | Manual del administrador › Parámetros tributarios |
| 18 | No edite un parámetro ya usado | Aviso | 5:57 | 18,0 s | Manual del administrador › Parámetros tributarios |
| 19 | Lo que solo se agrega, se corrige con otro acto | Tabla | 6:14 | 23,0 s | Manual del administrador › Registros que no se corrigen |
| 20 | Quién cambió qué, y cuándo | Captura recorrida | 6:37 | 27,0 s | Manual del administrador › Auditoría |
| 21 | Operación | Separador de sección | 7:03 | 6,0 s | Manual del administrador › Procesos masivos |
| 22 | Lo que conviene saber al vigilarlos | Tarjetas | 7:08 | 27,0 s | Manual del administrador › Procesos masivos |
| 23 | Preparar un año tributario | Tabla | 7:35 | 32,0 s | Manual del administrador › Preparar un año tributario |
| 24 | Un respaldo completo tiene dos partes | Tarjetas | 8:06 | 26,0 s | Manual del administrador › Respaldos y restauración |
| 25 | Síntomas frecuentes y qué hacer | Tabla | 8:32 | 25,0 s | Manual del administrador › Monitoreo e incidencias |
| 26 | Antes de una operación crítica | Tabla | 8:56 | 29,5 s | Manual del administrador › Lista de comprobación de operaciones críticas |
| 27 | Administrar, en pocas palabras | Cierre | 9:25 | 19,0 s | Manual del administrador › Funciones del administrador |

## 1. Administrar Rentas municipales

- **Id:** `portada` · **tipo:** Portada · **inicio:** 0:00 · **duración:** 12,0 s
- **Objetivo de aprendizaje:** Presentar el vídeo y a quién va dirigido.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** El bloque azul baja; la marca, el título y el subtítulo entran escalonados; luego la audiencia y las fuentes.
- **Fuentes:** Manual del administrador › Funciones del administrador (`docs/manuales/administrador/manual-administrador.adoc#funciones`)
- **Sigue:** 2. Lo que se hace en la consola y lo que no
- **Contenido:**

  > Administración del sistema: Administrar Rentas municipales
  > Cuentas, roles y permisos, datos maestros, auditoría y operación.
  > Para quién: Administradores del sistema (rol ADMIN) y quien coordina con el área de sistemas.

## 2. Lo que se hace en la consola y lo que no

- **Id:** `funcional-tecnica` · **tipo:** Comparación · **inicio:** 0:11 · **duración:** 22,0 s
- **Objetivo de aprendizaje:** Distinguir lo que se hace en la consola de lo que exige acceso al servidor o a la base de datos.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Las dos columnas entran y sus puntos aparecen uno a uno.
- **Fuentes:** Manual del administrador › Administración funcional y administración técnica (`docs/manuales/administrador/manual-administrador.adoc#funcional-tecnica`)
- **Sigue:** 3. La cuenta ADMIN se salta todos los permisos
- **Contenido:**

  > Administración funcional:
  >   - El administrador, con una cuenta ADMIN.
  >   - En la consola de administración del portal.
  >   - Usuarios, roles, permisos, auditoría y registros.
  > Administración técnica:
  >   - El área de sistemas, con acceso al servidor.
  >   - En la línea de comandos y la base de datos.
  >   - Configuración, modelo, importaciones y respaldos.

## 3. La cuenta ADMIN se salta todos los permisos

- **Id:** `cuenta-admin` · **tipo:** Aviso · **inicio:** 0:33 · **duración:** 16,0 s
- **Objetivo de aprendizaje:** Usar la cuenta ADMIN solo para administrar.
- **Imagen:** recuadro de aviso
- **Animación:** El recuadro del aviso entra y sus puntos aparecen uno a uno; a su lado, la captura, si la hay.
- **Fuentes:** Manual del administrador › Administración funcional y administración técnica (`docs/manuales/administrador/manual-administrador.adoc#funcional-tecnica`)
- **Sigue:** 4. Se abre desde el portal, en la misma sesión
- **Contenido:**

  > La cuenta ADMIN se salta todos los permisos: Puede leer, crear, modificar y borrar cualquier registro y cambiar el modelo de datos. Úsela solo para administrar.
  >   - Para el trabajo diario, cada persona, también el administrador, tiene una cuenta con un rol acotado.

## 4. Se abre desde el portal, en la misma sesión

- **Id:** `consola` · **tipo:** Captura recorrida · **inicio:** 0:48 · **duración:** 27,5 s
- **Objetivo de aprendizaje:** Entrar a la consola y reconocer su menú, y saber qué secciones no conviene tocar.
- **Imagen:** captura real `administrador/portal-menu-sesion.png`, captura real `administrador/consola-inicio.png`
- **Animación:** Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.
- **Fuentes:** Manual del administrador › Entrar a la consola (`docs/manuales/administrador/manual-administrador.adoc#entrar-consola`); Manual del administrador › Secciones de la plataforma que Rentas municipales no usa (`docs/manuales/administrador/manual-administrador.adoc#secciones-plataforma`)
- **Sigue:** 5. Usuarios
- **Contenido:**

  > 1. La opción Administración: Solo la ven las cuentas ADMIN, en el riel y en el menú de sesión.
  > 2. El menú de la consola: Datos, GIS, App Builder, Automatización y Administración: Usuarios, Roles, Permisos y Auditoría.
  > 3. Objetos y Relaciones: Son el modelo de datos: lo actualiza el área de sistemas. No los cambie a mano.

## 5. Usuarios

- **Id:** `seccion-usuarios` · **tipo:** Separador de sección · **inicio:** 1:15 · **duración:** 6,0 s
- **Objetivo de aprendizaje:** Situar la parte del vídeo sobre las cuentas.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** El rótulo de la parte y el filete crecen; entran el título y la bajada.
- **Fuentes:** Manual del administrador › Usuarios (`docs/manuales/administrador/manual-administrador.adoc#usuarios`)
- **Sigue:** 6. Crear una cuenta
- **Contenido:**

  > Parte 1: Usuarios
  > Dar acceso, cambiar contraseñas y roles, y dar de baja.

## 6. Crear una cuenta

- **Id:** `crear-cuenta` · **tipo:** Captura recorrida · **inicio:** 1:21 · **duración:** 28,0 s
- **Objetivo de aprendizaje:** Crear una cuenta con su rol.
- **Imagen:** captura real `administrador/usuarios.png`, captura real `administrador/usuario-nuevo.png`
- **Animación:** Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.
- **Fuentes:** Manual del administrador › Crear una cuenta (`docs/manuales/administrador/manual-administrador.adoc#proc-crear-usuario`)
- **Sigue:** 7. Cambiar contraseña, estado o roles
- **Contenido:**

  > 1. Correo, nombre y contraseña: El correo será su usuario y es único; la contraseña inicial tiene al menos 8 caracteres.
  > 2. Los roles: Marque el rol o los roles de la persona: con varios, suma los permisos de todos.
  > 3. Crear: La cuenta aparece Activo en la lista. Entregue la contraseña por un medio seguro.

## 7. Cambiar contraseña, estado o roles

- **Id:** `editar-cuenta` · **tipo:** Captura recorrida · **inicio:** 1:48 · **duración:** 25,5 s
- **Objetivo de aprendizaje:** Asignar una contraseña nueva, desactivar una cuenta o cambiar sus roles.
- **Imagen:** captura real `administrador/usuario-editar.png`
- **Animación:** Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.
- **Fuentes:** Manual del administrador › Cambiar la contraseña, el nombre o los roles de una cuenta (`docs/manuales/administrador/manual-administrador.adoc#proc-cambiar-clave`)
- **Sigue:** 8. Desactivar o eliminar una cuenta
- **Contenido:**

  > 1. Nueva contraseña: Déjela vacía para no cambiarla. El usuario no puede cambiarla desde el portal: se la asigna usted.
  > 2. Estado: Desmarque Activo para desactivar la cuenta.
  > 3. Roles y Guardar: Los roles nuevos rigen desde el siguiente ingreso: la sesión abierta conserva los de antes.

## 8. Desactivar o eliminar una cuenta

- **Id:** `desactivar-eliminar` · **tipo:** Comparación · **inicio:** 2:13 · **duración:** 22,0 s
- **Objetivo de aprendizaje:** Elegir entre desactivar y eliminar una cuenta.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Las dos columnas entran y sus puntos aparecen uno a uno.
- **Fuentes:** Manual del administrador › Desactivar o eliminar una cuenta (`docs/manuales/administrador/manual-administrador.adoc#desactivar-usuario`)
- **Sigue:** 9. La baja no corta una sesión abierta
- **Contenido:**

  > Desactivar:
  >   - La cuenta queda Inactivo y ya no puede ingresar.
  >   - Conserva sus datos: la auditoría la sigue mostrando como autora.
  >   - Es lo recomendable cuando alguien deja el área.
  > Eliminar:
  >   - El cesto de basura borra la cuenta, después de confirmar.
  >   - «¿Eliminar a …? Esta acción no se puede deshacer.»

## 9. La baja no corta una sesión abierta

- **Id:** `sesion-abierta` · **tipo:** Aviso · **inicio:** 2:34 · **duración:** 23,5 s
- **Objetivo de aprendizaje:** Saber que una baja no corta la sesión ya abierta y qué hacer si es urgente.
- **Imagen:** recuadro de aviso
- **Animación:** El recuadro del aviso entra y sus puntos aparecen uno a uno; a su lado, la captura, si la hay.
- **Fuentes:** Manual del administrador › Desactivar o eliminar una cuenta (`docs/manuales/administrador/manual-administrador.adoc#desactivar-usuario`)
- **Sigue:** 10. Roles y permisos
- **Contenido:**

  > La baja no corta una sesión abierta: Esa sesión vale hasta que vence, ocho horas por defecto: el sistema no revoca las sesiones emitidas.
  >   - Si es urgente, pida al área de sistemas que active la revocación o cambie el secreto de firma, que cierra todas las sesiones.
  >   - Si la cuenta lanzó una emisión o determinación masiva, espere a que termine: sus lotes fallarían.

## 10. Roles y permisos

- **Id:** `seccion-roles` · **tipo:** Separador de sección · **inicio:** 2:57 · **duración:** 6,5 s
- **Objetivo de aprendizaje:** Situar la parte del vídeo sobre roles y permisos.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** El rótulo de la parte y el filete crecen; entran el título y la bajada.
- **Fuentes:** Manual del administrador › Roles y permisos (`docs/manuales/administrador/manual-administrador.adoc#roles-y-permisos`)
- **Sigue:** 11. Una cuenta, sus roles y sus permisos
- **Contenido:**

  > Parte 2: Roles y permisos
  > Quién puede consultar, crear, modificar o borrar cada tipo de registro.

## 11. Una cuenta, sus roles y sus permisos

- **Id:** `modelo-permisos` · **tipo:** Diagrama · **inicio:** 3:03 · **duración:** 29,0 s
- **Objetivo de aprendizaje:** Entender cómo se combinan cuentas, roles y permisos.
- **Imagen:** diagrama de 6 nodos y 4 flechas, dibujado en la escena
- **Animación:** Los nodos aparecen por pasos y las flechas se dibujan; lo nuevo de cada paso se resalta un momento y su explicación aparece al pie.
- **Fuentes:** Manual del administrador › Cómo funcionan los permisos (`docs/manuales/administrador/manual-administrador.adoc#modelo-permisos`); Manual del administrador › Cómo se decide un permiso (`docs/manuales/referencia/permisos.adoc#ref-permisos-modelo`)
- **Sigue:** 12. Crear un rol
- **Contenido:**

  > Nodos: Cuenta · Rol · Permiso · Solo sus registros · Permisos de campo · ADMIN
  > Paso 1: Una cuenta tiene uno o más roles; si tiene varios, suma sus permisos.
  > Paso 2: Un permiso autoriza una acción sobre un objeto: consultar, crear, modificar o borrar.
  > Paso 3: Los permisos de campo solo restringen; «Solo sus registros» limita el rol a lo que creó la persona.
  > Paso 4: ADMIN se salta todas las comprobaciones: su matriz no cambia nada.

## 12. Crear un rol

- **Id:** `crear-rol` · **tipo:** Captura recorrida · **inicio:** 3:31 · **duración:** 25,5 s
- **Objetivo de aprendizaje:** Crear un rol nuevo.
- **Imagen:** captura real `administrador/roles.png`
- **Animación:** Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.
- **Fuentes:** Manual del administrador › Crear un rol (`docs/manuales/administrador/manual-administrador.adoc#proc-crear-rol`)
- **Sigue:** 13. La matriz de permisos de un rol
- **Contenido:**

  > 1. Nombre técnico y Etiqueta: El nombre en mayúsculas (CAJERO) y la etiqueta que verán los administradores (Caja).
  > 2. Solo sus registros: Márquelo solo si el rol debe ver únicamente lo que crea cada persona.
  > 3. Crear: El rol nace sin permisos: tiene 0 hasta que se guarde su matriz.

## 13. La matriz de permisos de un rol

- **Id:** `matriz` · **tipo:** Captura recorrida · **inicio:** 3:56 · **duración:** 27,5 s
- **Objetivo de aprendizaje:** Asignar los permisos de un rol, por objeto y por campo.
- **Imagen:** captura real `administrador/permisos-matriz.png`, captura real `administrador/permisos-campo.png`
- **Animación:** Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.
- **Fuentes:** Manual del administrador › Asignar los permisos de un rol (`docs/manuales/administrador/manual-administrador.adoc#proc-permisos-rol`); Manual del administrador › Permisos de campo (`docs/manuales/administrador/manual-administrador.adoc#permisos-campo`)
- **Sigue:** 14. Cinco perfiles de partida
- **Contenido:**

  > 1. Elija el rol: Aparece su matriz: una fila por objeto y una columna por acción.
  > 2. Conceda lo mínimo: Todos los objetos concede la acción sobre cualquier objeto: evítela salvo en un rol de solo lectura.
  > 3. Permisos de campo: Desmarque Lectura o Escritura en los campos que el rol no debe ver o cambiar.

## 14. Cinco perfiles de partida

- **Id:** `perfiles` · **tipo:** Tabla · **inicio:** 4:23 · **duración:** 32,0 s
- **Objetivo de aprendizaje:** Conocer los perfiles de partida y la lectura de la brecha que necesita todo rol.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual del administrador › Perfiles sugeridos (`docs/manuales/administrador/manual-administrador.adoc#perfiles-sugeridos`)
- **Sigue:** 15. Datos maestros
- **Contenido:**

  > Nombre técnico | Etiqueta | Para qué
  > REGISTRADOR | Registrador del padrón | Contribuyentes, predios, declaraciones y catastro; ver PU, HR y HLA.
  > ARBITRIOS_EMISION | Arbitrios y emisión | Determinar y anular arbitrios; determinación y emisión masivas.
  > FISCALIZADOR | Fiscalización e infracciones | CUIS, actas, descargos, resoluciones y su notificación.
  > ANUNCIOS | Anuncios y propaganda | Autorizar, renovar, cesar y retirar anuncios.
  > CONSULTA | Consulta | Solo lectura del padrón y de la brecha.
  > Nota: Todos leen la brecha (brecha_corrida y brecha_predio): agregue esas lecturas a cada rol nuevo, o verá el panel con un error de permiso.

## 15. Datos maestros

- **Id:** `seccion-datos` · **tipo:** Separador de sección · **inicio:** 4:55 · **duración:** 6,0 s
- **Objetivo de aprendizaje:** Situar la parte del vídeo sobre los datos maestros.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** El rótulo de la parte y el filete crecen; entran el título y la bajada.
- **Fuentes:** Manual del administrador › Datos maestros y parámetros (`docs/manuales/administrador/manual-administrador.adoc#datos-maestros`)
- **Sigue:** 16. La cabecera de los documentos
- **Contenido:**

  > Parte 3: Datos maestros
  > La cabecera de los documentos, los parámetros y la auditoría.

## 16. La cabecera de los documentos

- **Id:** `cabecera` · **tipo:** Captura recorrida · **inicio:** 5:00 · **duración:** 31,0 s
- **Objetivo de aprendizaje:** Corregir la cabecera de los documentos y saber qué no se hace en la consola.
- **Imagen:** captura real `administrador/municipalidad.png`
- **Animación:** Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.
- **Fuentes:** Manual del administrador › Corregir la cabecera de los documentos (`docs/manuales/administrador/manual-administrador.adoc#proc-municipalidad`); Manual del administrador › Cabecera de los documentos (municipalidad) (`docs/manuales/administrador/manual-administrador.adoc#municipalidad`)
- **Sigue:** 17. Las cifras de los cálculos
- **Contenido:**

  > 1. Un solo registro: Nombre, oficina, RUC, gerencia, dirección y escudo encabezan el PU, la HR, la HLA y las resoluciones.
  > 2. Corregir y grabar: Pulse la fila, corrija y grabe. Los documentos nuevos lo muestran en hasta un minuto.
  > 3. El escudo, en el mismo registro: Cada municipalidad tiene el suyo. La consola aún no sube archivos: lo sube el área de sistemas.

## 17. Las cifras de los cálculos

- **Id:** `parametros` · **tipo:** Captura recorrida · **inicio:** 5:30 · **duración:** 27,0 s
- **Objetivo de aprendizaje:** Leer una fila de los parámetros tributarios.
- **Imagen:** captura real `administrador/parametros.png`
- **Animación:** Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.
- **Fuentes:** Manual del administrador › Parámetros tributarios (`docs/manuales/administrador/manual-administrador.adoc#parametros`)
- **Sigue:** 18. No edite un parámetro ya usado
- **Contenido:**

  > 1. Tipo y clave: Qué parámetro es (UIT, TRAMO_PREDIAL, TASA_ARBITRIO…) y, si hay varios, cuál.
  > 2. Vigencia desde y hasta: El período en que rige: cada cálculo usa la fila vigente en su fecha.
  > 3. Valor, norma y doble firma: El valor, su norma, y quién lo transcribió y quién lo verificó: dos personas.

## 18. No edite un parámetro ya usado

- **Id:** `parametro-usado` · **tipo:** Aviso · **inicio:** 5:57 · **duración:** 18,0 s
- **Objetivo de aprendizaje:** No alterar un parámetro con el que ya se calculó.
- **Imagen:** recuadro de aviso
- **Animación:** El recuadro del aviso entra y sus puntos aparecen uno a uno; a su lado, la captura, si la hay.
- **Fuentes:** Manual del administrador › Parámetros tributarios (`docs/manuales/administrador/manual-administrador.adoc#parametros`)
- **Sigue:** 19. Lo que solo se agrega, se corrige con otro acto
- **Contenido:**

  > No edite un parámetro ya usado: Cambia los cálculos que se vuelvan a hacer, pero no los hechos: las cuotas guardan el parámetro con que se calcularon.
  >   - Un valor nuevo es una fila nueva, con su vigencia y la doble firma, que carga el área de sistemas.

## 19. Lo que solo se agrega, se corrige con otro acto

- **Id:** `correcciones` · **tipo:** Tabla · **inicio:** 6:14 · **duración:** 23,0 s
- **Objetivo de aprendizaje:** Saber cómo se corrige lo que solo se agrega.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual del administrador › Registros que no se corrigen (`docs/manuales/administrador/manual-administrador.adoc#correcciones`); Manual del administrador › Ver y editar registros desde la consola (`docs/manuales/administrador/manual-administrador.adoc#registros-consola`)
- **Sigue:** 20. Quién cambió qué, y cuándo
- **Contenido:**

  > Registro equivocado | Cómo se corrige
  > Acta | Anular el acta, con su motivo, y registrar la correcta.
  > Anuncio | Cesar o retirar el anuncio y registrar el correcto.
  > Notificación, descargo o resolución | Sin anulación: anote el error en el acto siguiente.
  > Cuota de arbitrio | Anularla por la API (sistemas) y volver a determinar.

## 20. Quién cambió qué, y cuándo

- **Id:** `auditoria` · **tipo:** Captura recorrida · **inicio:** 6:37 · **duración:** 27,0 s
- **Objetivo de aprendizaje:** Consultar quién cambió qué y cuándo.
- **Imagen:** captura real `administrador/auditoria.png`, captura real `administrador/auditoria-cambios.png`
- **Animación:** Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.
- **Fuentes:** Manual del administrador › Auditoría (`docs/manuales/administrador/manual-administrador.adoc#auditoria`)
- **Sigue:** 21. Operación
- **Contenido:**

  > 1. Filtros: Por Objeto, por Operación (Creación, Modificación, Eliminación) y cuántas entradas ver.
  > 2. Cada cambio, una fila: La operación, el objeto, el registro, el usuario y la fecha. Nadie puede editarla ni borrarla.
  > 3. Lo que cambió: La flecha despliega los campos que cambiaron, con su valor anterior y el nuevo.

## 21. Operación

- **Id:** `seccion-operacion` · **tipo:** Separador de sección · **inicio:** 7:03 · **duración:** 6,0 s
- **Objetivo de aprendizaje:** Situar la parte del vídeo sobre la operación.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** El rótulo de la parte y el filete crecen; entran el título y la bajada.
- **Fuentes:** Manual del administrador › Procesos masivos (`docs/manuales/administrador/manual-administrador.adoc#procesos-masivos`)
- **Sigue:** 22. Lo que conviene saber al vigilarlos
- **Contenido:**

  > Parte 4: Operación
  > Procesos masivos, año tributario, respaldos e incidencias.

## 22. Lo que conviene saber al vigilarlos

- **Id:** `procesos-masivos` · **tipo:** Tarjetas · **inicio:** 7:08 · **duración:** 27,0 s
- **Objetivo de aprendizaje:** Vigilar la determinación y la emisión masivas.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Las tarjetas entran una a una, en orden de lectura; al final, la nota.
- **Fuentes:** Manual del administrador › Procesos masivos (`docs/manuales/administrador/manual-administrador.adoc#procesos-masivos`)
- **Sigue:** 23. Preparar un año tributario
- **Contenido:**

  > Corren en segundo plano, en lotes de 100. Los lanza el perfil de arbitrios y emisión; usted los vigila.
  > Una a la vez: Una sola emisión masiva en curso por año.
  > Reintentos: Un lote que falla se reintenta hasta 3 veces.
  > Errores: El contribuyente que no se emite queda listado, con su motivo; el resto sigue.
  > Retención: Se guardan los últimos 5 archivos de cada año; los demás, Archivo depurado.
  > Datos de todo el padrón: Comparta los archivos solo por medios autorizados.

## 23. Preparar un año tributario

- **Id:** `anio-tributario` · **tipo:** Tabla · **inicio:** 7:35 · **duración:** 32,0 s
- **Objetivo de aprendizaje:** Preparar el año tributario con cada área responsable.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual del administrador › Preparar un año tributario (`docs/manuales/administrador/manual-administrador.adoc#anio-tributario`)
- **Sigue:** 24. Un respaldo completo tiene dos partes
- **Contenido:**

  > Tarea | Quién
  > La UIT, con su norma y doble firma | Sistemas, con rentas
  > Tramos, mínimo y tasas al 1 de enero | Sistemas
  > Ordenanza de arbitrios ratificada y sus tasas | Rentas y sistemas
  > El CUIS y los plazos, si cambiaron | Fiscalización y sistemas
  > Feriados del año nuevo, antes del 1 de enero | Sistemas
  > Tasas de anuncios del año | Sistemas
  > Cabecera de los documentos revisada | Administrador
  > Declaraciones juradas al día | Registradores
  > Determinación masiva terminada | Arbitrios y emisión
  > Emisión de prueba, luego la definitiva | Arbitrios y emisión
  > Nota: Sin los feriados del año, sus descargos y notificaciones no se registran.

## 24. Un respaldo completo tiene dos partes

- **Id:** `respaldos` · **tipo:** Tarjetas · **inicio:** 8:06 · **duración:** 26,0 s
- **Objetivo de aprendizaje:** Coordinar con sistemas los respaldos y sus precauciones.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Las tarjetas entran una a una, en orden de lectura; al final, la nota.
- **Fuentes:** Manual del administrador › Respaldos y restauración (`docs/manuales/administrador/manual-administrador.adoc#respaldo`)
- **Sigue:** 25. Síntomas frecuentes y qué hacer
- **Contenido:**

  > La base de datos: Modelo, registros, usuarios y auditoría.
  > El almacén de emisiones: Los PDF y ZIP, en el disco o en S3.
  > Cada día, y antes de cada cambio: Respaldo diario, uno extra antes de actualizar, y pruebas de restauración.
  > Restaurar borra lo posterior: Con el sistema detenido, aprobación y un respaldo del estado actual.
  > Nota: Una actualización no se revierte: volver atrás exige el respaldo previo.

## 25. Síntomas frecuentes y qué hacer

- **Id:** `monitoreo` · **tipo:** Tabla · **inicio:** 8:32 · **duración:** 25,0 s
- **Objetivo de aprendizaje:** Saber qué hacer ante las incidencias más comunes.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual del administrador › Monitoreo e incidencias (`docs/manuales/administrador/manual-administrador.adoc#monitoreo`)
- **Sigue:** 26. Antes de una operación crítica
- **Contenido:**

  > Síntoma | Qué hacer
  > Nadie puede ingresar | Con sistemas: /actuator/health debe decir UP y la base estar arriba.
  > Una persona ve «Sin permiso» | Revisar los roles de su cuenta y la matriz del rol.
  > Una emisión quedó Fallida | Ver su mensaje; si fue una caída, relanzarla.
  > Un documento con la cabecera equivocada | Corregir el registro Municipalidad y esperar un minuto.
  > ESCRITURA FUERA DE SRTM RECHAZADA | Alguien intentó escribir actas o anuncios fuera del portal: revisar la auditoría.

## 26. Antes de una operación crítica

- **Id:** `operaciones-criticas` · **tipo:** Tabla · **inicio:** 8:56 · **duración:** 29,5 s
- **Objetivo de aprendizaje:** Comprobar lo necesario antes de cada operación crítica.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual del administrador › Lista de comprobación de operaciones críticas (`docs/manuales/administrador/manual-administrador.adoc#checklist`)
- **Sigue:** 27. Administrar, en pocas palabras
- **Contenido:**

  > Operación | Comprobar antes
  > Actualizar la versión | Respaldo; ensayo en una copia; apply.py --diff revisado; aviso; nada masivo en curso.
  > Importación masiva | Respaldo; --dry-run revisado; el archivo correcto, del año correcto.
  > Determinación masiva | Ordenanza ratificada, parámetros del año y declaraciones al día.
  > Emisión masiva | Determinación terminada; cabecera y emisión de prueba revisadas.
  > Baja de una cuenta | Sin procesos masivos en curso; revocar sesiones si es urgente.
  > Cambiar permisos de un rol | Qué cuentas lo tienen; probar con una cuenta de prueba.
  > Restaurar la base | Sistema detenido; respaldo actual; aprobación; verificar después.

## 27. Administrar, en pocas palabras

- **Id:** `cierre` · **tipo:** Cierre · **inicio:** 9:25 · **duración:** 19,0 s
- **Objetivo de aprendizaje:** Recordar lo esencial y saber dónde seguir.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los puntos del resumen entran uno a uno; luego los manuales y el vídeo siguiente.
- **Fuentes:** Manual del administrador › Funciones del administrador (`docs/manuales/administrador/manual-administrador.adoc#funciones`)
- **Sigue:** fin del vídeo
- **Contenido:**

  > ✓ Lo funcional, en la consola; lo técnico, con el área de sistemas.
  > ✓ Cada persona con su cuenta y un rol acotado: conceda lo mínimo.
  > ✓ Lo que solo se agrega se corrige con otro acto, no editándolo.
  > ✓ Antes de cada operación crítica: respaldo, ensayo y verificación.
  > Para saber más: Manual del administrador · Manual técnico del sistema
  > Siguiente: 09 · Arquitectura técnica
