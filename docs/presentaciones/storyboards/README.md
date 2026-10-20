# Storyboards de las presentaciones

<!-- generado por scripts/storyboards.ts a partir de src/videos: no se edita a mano -->

Cada vídeo, cada escena y la sección de los manuales de la que sale. El detalle de cada escena está en su storyboard.

10 vídeos, 193 escenas, 72:56 en total.

| Vídeo | Audiencia | Duración | Escenas | Storyboard |
|---|---|---|---|---|
| 01 · Rentas municipales de un vistazo | Todo el personal de rentas, la gerencia y quien empieza a usar el sistema | 3:54 | 10 | [srtm-01-introduccion.md](srtm-01-introduccion.md) |
| 02 · Primeros pasos en el portal | Todos los usuarios del portal, de cualquier perfil | 7:21 | 21 | [srtm-02-primeros-pasos.md](srtm-02-primeros-pasos.md) |
| 03 · Registro tributario | Personal de ventanilla y del registro tributario (perfil REGISTRADOR) | 8:15 | 26 | [srtm-03-registro-tributario.md](srtm-03-registro-tributario.md) |
| 04 · Arbitrios y emisión | Personal del área de arbitrios y de emisión (perfil ARBITRIOS_EMISION) | 8:27 | 24 | [srtm-04-arbitrios-emision.md](srtm-04-arbitrios-emision.md) |
| 05 · Infracciones administrativas | Personal de fiscalización (perfil FISCALIZADOR) | 9:32 | 23 | [srtm-05-infracciones.md](srtm-05-infracciones.md) |
| 06 · Anuncios y propaganda | Personal que autoriza anuncios (perfil ANUNCIOS) | 4:59 | 12 | [srtm-06-anuncios.md](srtm-06-anuncios.md) |
| 07 · Consulta y brecha de recaudación | Orientación al contribuyente, supervisión, auditoría interna y gerencia (perfil CONSULTA) | 4:10 | 13 | [srtm-07-consulta.md](srtm-07-consulta.md) |
| 08 · Administración del sistema | Administradores del sistema (rol ADMIN) y quien coordina con el área de sistemas | 9:41 | 27 | [srtm-08-administrador.md](srtm-08-administrador.md) |
| 09 · Arquitectura técnica | Ingenieros de software, arquitectos y DevOps | 7:31 | 15 | [srtm-09-arquitectura.md](srtm-09-arquitectura.md) |
| 10 · Instalación, operación y recuperación | DevOps y administradores de bases de datos | 9:06 | 22 | [srtm-10-operacion.md](srtm-10-operacion.md) |

## 01 · Rentas municipales de un vistazo

| # | Escena | Fuentes |
|---|---|---|
| 1 | Rentas municipales de un vistazo | `usuario/registrador.adoc#presentacion-sistema` (Para qué sirve el sistema) |
| 2 | Un solo portal web reúne todo el trabajo de rentas de la municipalidad. | `usuario/registrador.adoc#presentacion-sistema` (Para qué sirve el sistema) |
| 3 | El registro tributario es la base de todo lo demás | `usuario/registrador.adoc#presentacion-sistema` (Para qué sirve el sistema) |
| 4 | Todas las pantallas comparten el mismo marco | `comun/interfaz.adoc#partes-pantalla` (Partes de la pantalla) |
| 5 | Los trámites, agrupados por aquello sobre lo que se hacen | `comun/interfaz.adoc#riel-modulos` (El riel de módulos) |
| 6 | Un perfil por cada parte del trabajo | `administrador/manual-administrador.adoc#perfiles-sugeridos` (Perfiles sugeridos)<br>`administrador/manual-administrador.adoc#funciones` (Funciones del administrador) |
| 7 | Un año de rentas, paso a paso | `usuario/arbitrios-emision.adoc#flujo-anual` (El año de arbitrios, paso a paso)<br>`administrador/manual-administrador.adoc#anio-tributario` (Preparar un año tributario) |
| 8 | Seis reglas que valen en todos los módulos | `comun/mensajes.adoc#actos` (Actos con observación)<br>`usuario/arbitrios-emision.adoc#inmutable` (Una cuota no se edita ni se borra)<br>`comun/faq.adoc#faq-comun` (Preguntas generales)<br>`comun/mensajes.adoc#permisos-usuario` (Lo que su rol le permite) |
| 9 | Lo que hace y lo que queda fuera | `usuario/fiscalizador.adoc#despues` (Después de la resolución: cobranza y coactiva)<br>`usuario/registrador.adoc#reniec` (Los nombres se escriben a mano)<br>`usuario/consulta.adoc#brecha-situacion` (La brecha en esta instalación)<br>`comun/busqueda.adoc#listas` (Listas, filtros y páginas) |
| 10 | Rentas municipales, en pocas palabras | `index.adoc#_manuales_de_usuario` (Manuales de usuario) |

## 02 · Primeros pasos en el portal

| # | Escena | Fuentes |
|---|---|---|
| 1 | Primeros pasos en el portal | `comun/acceso.adoc#acceso` (Acceso al sistema) |
| 2 | Ingresar y orientarse | `comun/interfaz.adoc#interfaz` (La pantalla de trabajo) |
| 3 | Ingresar con su cuenta | `comun/acceso.adoc#proc-ingresar` (Ingresar al portal)<br>`comun/acceso.adoc#requisitos-acceso` (Qué necesita) |
| 4 | Lo que tiene a mano en todas las pantallas | `comun/interfaz.adoc#partes-pantalla` (Partes de la pantalla) |
| 5 | Cada módulo abre su panel de trámites | `comun/interfaz.adoc#riel-modulos` (El riel de módulos) |
| 6 | Cada página dice qué es y qué hacer | `comun/interfaz.adoc#banda-titulo` (Títulos, pasos e instrucciones) |
| 7 | Buscar y abrir fichas | `comun/busqueda.adoc#busquedas` (Búsquedas, listas y fichas) |
| 8 | Contribuyentes y predios, en una sola búsqueda | `comun/busqueda.adoc#proc-buscar-general` (Buscar un contribuyente o un predio desde la barra) |
| 9 | Si no encuentra a quien sabe que existe | `comun/faq.adoc#faq-comun` (Preguntas generales)<br>`comun/busqueda.adoc#proc-buscar-general` (Buscar un contribuyente o un predio desde la barra) |
| 10 | Varias fichas abiertas a la vez | `comun/busqueda.adoc#fichas-abiertas` (Fichas abiertas) |
| 11 | Todas las listas funcionan igual | `comun/busqueda.adoc#listas` (Listas, filtros y páginas) |
| 12 | Formularios y mensajes | `comun/mensajes.adoc#formularios` (Formularios, mensajes y permisos) |
| 13 | El formulario se revisa antes de grabar | `comun/mensajes.adoc#campos` (Campos obligatorios y formatos) |
| 14 | Qué significa cada mensaje | `comun/mensajes.adoc#campos` (Campos obligatorios y formatos) |
| 15 | ¿Salir sin guardar? | `comun/mensajes.adoc#salir-sin-guardar` (Salir sin guardar) |
| 16 | Lo que no se deshace lleva su motivo | `comun/mensajes.adoc#actos` (Actos con observación) |
| 17 | Un botón gris dice qué permiso falta | `comun/mensajes.adoc#permisos-usuario` (Lo que su rol le permite) |
| 18 | Dos maneras de decir «no» | `comun/mensajes.adoc#permisos-usuario` (Lo que su rol le permite) |
| 19 | Cerrar la sesión y elegir el tema | `comun/acceso.adoc#proc-cerrar-sesion` (Cerrar la sesión)<br>`comun/interfaz.adoc#temas` (Temas de color) |
| 20 | La sesión dura ocho horas | `comun/acceso.adoc#sesion` (Duración de la sesión) |
| 21 | Para empezar a trabajar | `comun/faq.adoc#faq-comun` (Preguntas generales) |

## 03 · Registro tributario

| # | Escena | Fuentes |
|---|---|---|
| 1 | Registro tributario | `usuario/registrador.adoc#presentacion-manual` (Para qué sirve este manual y a quién se dirige) |
| 2 | Tres registros describen un inmueble | `usuario/registrador.adoc#predio-que-es` (Predio, lote de catastro y declaración) |
| 3 | Contribuyentes | `usuario/registrador.adoc#contribuyentes` (Contribuyentes) |
| 4 | Qué número acepta cada documento | `usuario/registrador.adoc#documento-reglas` (Qué número acepta cada tipo de documento) |
| 5 | Busque siempre antes de inscribir | `usuario/registrador.adoc#proc-buscar-contribuyente` (Buscar un contribuyente) |
| 6 | Paso 1: los datos del contribuyente | `usuario/registrador.adoc#proc-inscribir-contribuyente` (Inscribir un contribuyente)<br>`usuario/registrador.adoc#reniec` (Los nombres se escriben a mano) |
| 7 | Primero, el domicilio fiscal | `usuario/registrador.adoc#proc-registrar-domicilio` (Registrar los domicilios) |
| 8 | Lo que falla se marca en rojo | `usuario/registrador.adoc#proc-inscribir-contribuyente` (Inscribir un contribuyente)<br>`usuario/registrador.adoc#errores` (Errores frecuentes) |
| 9 | Todo lo del contribuyente, en una ficha | `usuario/registrador.adoc#ficha-contribuyente` (La ficha del contribuyente) |
| 10 | Predios | `usuario/registrador.adoc#predios` (Predios) |
| 11 | En el padrón y en el catastro fiscal | `usuario/registrador.adoc#buscar-predios` (El diálogo Buscar predios) |
| 12 | Desde un lote del catastro | `usuario/registrador.adoc#proc-predio-desde-lote` (Registrar un predio a partir de un lote del catastro)<br>`usuario/registrador.adoc#proc-registrar-predio` (Registrar un predio) |
| 13 | Declaraciones juradas | `usuario/registrador.adoc#declaraciones` (Declaraciones juradas) |
| 14 | La declaración jurada en seis ideas | `usuario/registrador.adoc#dj-conceptos` (Lo que hay que saber antes) |
| 15 | Dos pasos y queda presentada | `usuario/registrador.adoc#proc-nueva-declaracion` (Presentar una declaración jurada) |
| 16 | El predio ya tiene titular en el año | `usuario/registrador.adoc#proc-nueva-declaracion` (Presentar una declaración jurada)<br>`usuario/registrador.adoc#proc-cambio-titular` (Registrar un cambio de dueño) |
| 17 | Uso, áreas y valores | `usuario/registrador.adoc#caracteristicas` (Características: uso, áreas y valores) |
| 18 | Del autoavalúo al valor afecto | `usuario/registrador.adoc#dj-conceptos` (Lo que hay que saber antes)<br>`usuario/registrador.adoc#condominos` (Datos de los condóminos) |
| 19 | Varios titulares de un mismo predio | `usuario/registrador.adoc#condominos` (Datos de los condóminos)<br>`usuario/registrador.adoc#proc-agregar-condomino` (Agregar un condómino) |
| 20 | La anulación es el descargo | `usuario/registrador.adoc#proc-anular-declaracion` (Anular una declaración (descargo)) |
| 21 | Cuando un predio se vende | `usuario/registrador.adoc#proc-cambio-titular` (Registrar un cambio de dueño) |
| 22 | Documentos | `usuario/registrador.adoc#documentos` (Documentos: PU, HR y HLA) |
| 23 | PU, HR y HLA desde las fichas | `usuario/registrador.adoc#documentos-que-son` (Qué documento entregar)<br>`usuario/registrador.adoc#proc-ver-documento` (Ver, imprimir o descargar un documento) |
| 24 | QUISPE ROJAS ROSA ELENA, 2026 | `usuario/registrador.adoc#liquidacion` (Cómo se calcula el impuesto predial) |
| 25 | La HR, sección por sección | `usuario/registrador.adoc#hr` (La HR) |
| 26 | El registro tributario, en resumen | `usuario/registrador.adoc#faq-registro` (Preguntas del registro tributario) |

## 04 · Arbitrios y emisión

| # | Escena | Fuentes |
|---|---|---|
| 1 | Arbitrios y emisión | `usuario/arbitrios-emision.adoc#presentacion-manual` (Para qué sirve este manual y a quién se dirige) |
| 2 | Cómo se calcula | `usuario/arbitrios-emision.adoc#arbitrios` (Los arbitrios) |
| 3 | Una cuota por predio, por servicio y por mes. | `usuario/arbitrios-emision.adoc#arbitrios-que-son` (Qué son los arbitrios) |
| 4 | El predio 01-01-0002, un mes de 2026 | `usuario/arbitrios-emision.adoc#calculo` (Cómo se calcula una cuota) |
| 5 | Cada mes, el titular del día 1 | `usuario/arbitrios-emision.adoc#titular-mes` (De quién es cada cuota: el titular de cada mes) |
| 6 | Un PU por predio o un PU por uso | `usuario/arbitrios-emision.adoc#pu-por-uso` (Un PU por predio o un PU por uso) |
| 7 | Determinar | `usuario/arbitrios-emision.adoc#determinar` (Determinar los arbitrios de un predio o de un contribuyente) |
| 8 | El año de arbitrios, paso a paso | `usuario/arbitrios-emision.adoc#flujo-anual` (El año de arbitrios, paso a paso) |
| 9 | Revise la ordenanza antes de determinar | `usuario/arbitrios-emision.adoc#proc-tasas` (Revisar la ordenanza y las tasas de un año)<br>`usuario/arbitrios-emision.adoc#tasas-tablas` (Cómo leer las tablas) |
| 10 | Las cuotas de un predio, mes a mes | `usuario/arbitrios-emision.adoc#pestana-arbitrios` (La pestaña Arbitrios de las fichas) |
| 11 | Cada determinación lleva su observación | `usuario/arbitrios-emision.adoc#proc-determinar-predio-pasos` (Determinar los arbitrios de un predio)<br>`usuario/arbitrios-emision.adoc#proc-determinar-contribuyente` (Determinar los arbitrios de un contribuyente) |
| 12 | Un predio se determina entero o no se determina | `usuario/arbitrios-emision.adoc#no-se-puede` (Cuando no se puede determinar) |
| 13 | Una cuota no se edita: se anula | `usuario/arbitrios-emision.adoc#anular-cuota-como` (La anulación de una cuota en esta versión)<br>`usuario/arbitrios-emision.adoc#inmutable` (Una cuota no se edita ni se borra) |
| 14 | Determinación masiva y HLA | `usuario/arbitrios-emision.adoc#determinacion-masiva` (Determinación masiva) |
| 15 | Todo el padrón de un año, en segundo plano | `usuario/arbitrios-emision.adoc#proc-determinacion-masiva-pasos` (Lanzar la determinación masiva)<br>`usuario/arbitrios-emision.adoc#estados-determinacion` (Los estados y las columnas de la lista)<br>`usuario/arbitrios-emision.adoc#predios-sin-determinar` (Predios sin determinar) |
| 16 | Revise la ordenanza primero | `usuario/arbitrios-emision.adoc#proc-determinacion-masiva` (Lanzar la determinación masiva de un año) |
| 17 | La hoja de liquidación de arbitrios | `usuario/arbitrios-emision.adoc#proc-ver-hla` (Ver, imprimir o descargar la HLA de un contribuyente)<br>`usuario/arbitrios-emision.adoc#hla-contenido` (Qué contiene la HLA) |
| 18 | Emisión masiva | `usuario/arbitrios-emision.adoc#emision-masiva` (Emisión masiva) |
| 19 | Los documentos del año, de cada contribuyente | `usuario/arbitrios-emision.adoc#emision-que-produce` (Qué produce) |
| 20 | De Pendiente a Terminada | `usuario/arbitrios-emision.adoc#estados-emision` (Los estados de una emisión) |
| 21 | Elija año, formato y documentos | `usuario/arbitrios-emision.adoc#proc-emision-masiva-pasos` (Lanzar la emisión masiva de un año)<br>`usuario/arbitrios-emision.adoc#proc-descargar-emision` (Descargar el archivo) |
| 22 | Errores, archivos depurados y bajas | `usuario/arbitrios-emision.adoc#emision-errores` (Contribuyentes con error)<br>`usuario/arbitrios-emision.adoc#archivo-depurado` (Archivo depurado)<br>`usuario/arbitrios-emision.adoc#proc-eliminar-emision` (Eliminar una emisión) |
| 23 | El archivo tiene datos de todo el padrón | `usuario/arbitrios-emision.adoc#proc-descargar-emision` (Descargar el archivo) |
| 24 | Arbitrios y emisión, en pocas palabras | `usuario/arbitrios-emision.adoc#faq-arbitrios` (Preguntas de arbitrios y emisión) |

## 05 · Infracciones administrativas

| # | Escena | Fuentes |
|---|---|---|
| 1 | Del acta a la resolución exigible | `usuario/fiscalizador.adoc#presentacion-manual` (Para qué sirve este manual y a quién se dirige) |
| 2 | El ciclo de un acta | `usuario/fiscalizador.adoc#ciclo-acta` (El ciclo de un acta en el sistema)<br>`usuario/fiscalizador.adoc#procedimiento-piezas` (Las piezas del expediente) |
| 3 | Fase del procedimiento y estado de la deuda | `usuario/fiscalizador.adoc#fases` (Fase del procedimiento y estado de la deuda) |
| 4 | Tres plazos que el sistema cuenta solo | `usuario/fiscalizador.adoc#plazos` (Cómo cuenta los días el sistema) |
| 5 | CUIS y notificaciones | `usuario/fiscalizador.adoc#cuis` (El cuadro único de infracciones y sanciones (CUIS)) |
| 6 | El CUIS se consulta a una fecha | `usuario/fiscalizador.adoc#proc-consultar-cuis-pasos` (Consultar el CUIS) |
| 7 | La multa es un porcentaje de la UIT | `usuario/fiscalizador.adoc#cuis-multa` (Cómo se calcula la multa de un código) |
| 8 | Cada código tiene versiones con vigencia | `usuario/fiscalizador.adoc#cuis-versiones` (Versiones y vigencia) |
| 9 | Registrar una versión nueva de un código | `usuario/fiscalizador.adoc#proc-version-cuis-pasos` (Registrar una versión nueva de un código del CUIS) |
| 10 | Notificaciones previas y su subsanación | `usuario/fiscalizador.adoc#proc-consultar-notificaciones` (Consultar las notificaciones previas)<br>`usuario/fiscalizador.adoc#proc-subsanar-pasos` (Registrar la subsanación de una notificación previa) |
| 11 | Actas y expedientes | `usuario/fiscalizador.adoc#actas` (Actas de constatación) |
| 12 | Registrar un acta: la infracción | `usuario/fiscalizador.adoc#proc-registrar-acta-pasos` (Registrar un acta de constatación) |
| 13 | Registrar un acta: el administrado | `usuario/fiscalizador.adoc#proc-registrar-acta-pasos` (Registrar un acta de constatación)<br>`usuario/fiscalizador.adoc#acta-que-es` (Lo que hay que saber antes) |
| 14 | La multa se calcula una vez y queda congelada | `usuario/fiscalizador.adoc#multa-congelada` (La multa: se calcula una vez y queda congelada) |
| 15 | La ficha del expediente | `usuario/fiscalizador.adoc#ficha-expediente` (La ficha del expediente)<br>`usuario/fiscalizador.adoc#acciones` (Qué acciones quedan)<br>`usuario/fiscalizador.adoc#actos-expediente` (Actos del expediente) |
| 16 | Registrar un descargo | `usuario/fiscalizador.adoc#proc-descargo-pasos` (Registrar un descargo)<br>`usuario/fiscalizador.adoc#proc-descargo` (Registrar un descargo o un recurso) |
| 17 | Dos tipos de resolución | `usuario/fiscalizador.adoc#resoluciones-tipos` (Los dos tipos de resolución)<br>`usuario/fiscalizador.adoc#pdf-resolucion` (Ver e imprimir la resolución) |
| 18 | El efecto sobre la multa | `usuario/fiscalizador.adoc#fallo` (El sentido del fallo y su efecto sobre la multa) |
| 19 | Registrar la diligencia de notificación | `usuario/fiscalizador.adoc#proc-notificar-pasos` (Registrar la notificación de una resolución) |
| 20 | Desde cuándo es exigible: un ejemplo | `usuario/fiscalizador.adoc#exigible` (Desde cuándo es exigible) |
| 21 | Anular un acta no tiene vuelta atrás | `usuario/fiscalizador.adoc#proc-anular-pasos` (Anular un acta) |
| 22 | Lo que el sistema no hace | `usuario/fiscalizador.adoc#no-implementado` (Lo que el sistema no hace)<br>`usuario/fiscalizador.adoc#despues` (Después de la resolución: cobranza y coactiva) |
| 23 | El procedimiento, en pocas palabras | `usuario/fiscalizador.adoc#procedimiento` (El procedimiento sancionador en el sistema) |

## 06 · Anuncios y propaganda

| # | Escena | Fuentes |
|---|---|---|
| 1 | Anuncios y propaganda | `usuario/anuncios.adoc#presentacion-manual` (Para qué sirve este manual y a quién se dirige) |
| 2 | Lo que hay que saber antes | `usuario/anuncios.adoc#anuncio-conceptos` (Lo que hay que saber antes) |
| 3 | El estado se deduce, a una fecha | `usuario/anuncios.adoc#estados` (Cómo se decide el estado) |
| 4 | El padrón, hoy o a una fecha pasada | `usuario/anuncios.adoc#proc-padron-pasos` (Consultar el padrón de anuncios)<br>`usuario/anuncios.adoc#padron-a-fecha` (El padrón a una fecha pasada) |
| 5 | Registrar un anuncio con su autorización | `usuario/anuncios.adoc#proc-nuevo-pasos` (Registrar un anuncio) |
| 6 | Si no hubo respuesta, vuelva a pulsar | `usuario/anuncios.adoc#reintento` (Si no hubo respuesta: el reintento seguro) |
| 7 | Un anuncio renovado | `usuario/anuncios.adoc#ficha-partes` (Partes de la ficha)<br>`usuario/anuncios.adoc#movimientos` (Movimientos) |
| 8 | Un ejercicio por renovación, una sola vez | `usuario/anuncios.adoc#renovacion-reglas` (Qué ejercicio renueva y cuánto devenga)<br>`usuario/anuncios.adoc#proc-renovar-pasos` (Renovar un anuncio) |
| 9 | Primero el cese, después el retiro | `usuario/anuncios.adoc#proc-cesar-pasos` (Cesar un anuncio)<br>`usuario/anuncios.adoc#proc-retirar` (Retirar un anuncio) |
| 10 | Las tasas de un año | `usuario/anuncios.adoc#tasas-consultar` (Consultar las tasas de un año) |
| 11 | Cómo se aplica la tasa | `usuario/anuncios.adoc#tasa-como-aplica` (Cómo se aplica la tasa)<br>`usuario/anuncios.adoc#devengado` (Lo devengado no es una deuda) |
| 12 | Los anuncios, en pocas palabras | `usuario/anuncios.adoc#anuncio-sistema` (El anuncio en el sistema) |

## 07 · Consulta y brecha de recaudación

| # | Escena | Fuentes |
|---|---|---|
| 1 | Consulta y brecha de recaudación | `usuario/consulta.adoc#presentacion-manual` (Para qué sirve este manual y a quién se dirige) |
| 2 | Consultar sin cambiar nada | `usuario/consulta.adoc#padron` (Consultar el padrón) |
| 3 | Lo ve todo, tal como está | `usuario/consulta.adoc#presentacion-manual` (Para qué sirve este manual y a quién se dirige)<br>`usuario/consulta.adoc#sin-escritura` (Lo que su perfil no puede hacer) |
| 4 | Dos maneras de mostrarlo | `usuario/consulta.adoc#sin-escritura` (Lo que su perfil no puede hacer) |
| 5 | La ficha de un contribuyente | `usuario/consulta.adoc#proc-consultar-contribuyente` (Consultar un contribuyente) |
| 6 | Las cuotas, de solo consulta | `usuario/consulta.adoc#arbitrios-ficha` (Los arbitrios de un contribuyente o de un predio) |
| 7 | Todo lo que el sistema emite | `usuario/consulta.adoc#documentos` (Documentos)<br>`usuario/consulta.adoc#proc-descargar-emision` (Descargar una emisión masiva) |
| 8 | La brecha de recaudación | `usuario/consulta.adoc#brecha` (Brecha de recaudación) |
| 9 | Compara, predio por predio, lo declarado con lo que valoriza el catastro. | `usuario/consulta.adoc#brecha-que-es` (Para qué sirve) |
| 10 | Cómo se empareja el catastro con el padrón | `usuario/consulta.adoc#brecha-cruce` (Cómo se compara el padrón con el catastro) |
| 11 | Dónde queda cada predio | `usuario/consulta.adoc#brecha-cruce` (Cómo se compara el padrón con el catastro) |
| 12 | Aún no se ha calculado | `usuario/consulta.adoc#brecha-situacion` (La brecha en esta instalación)<br>`usuario/consulta.adoc#brecha-pagina` (La página Brecha de recaudación) |
| 13 | Consultar sin cambiar nada | `usuario/consulta.adoc#presentacion-manual` (Para qué sirve este manual y a quién se dirige) |

## 08 · Administración del sistema

| # | Escena | Fuentes |
|---|---|---|
| 1 | Administrar Rentas municipales | `administrador/manual-administrador.adoc#funciones` (Funciones del administrador) |
| 2 | Lo que se hace en la consola y lo que no | `administrador/manual-administrador.adoc#funcional-tecnica` (Administración funcional y administración técnica) |
| 3 | La cuenta ADMIN se salta todos los permisos | `administrador/manual-administrador.adoc#funcional-tecnica` (Administración funcional y administración técnica) |
| 4 | Se abre desde el portal, en la misma sesión | `administrador/manual-administrador.adoc#entrar-consola` (Entrar a la consola)<br>`administrador/manual-administrador.adoc#secciones-plataforma` (Secciones de la plataforma que Rentas municipales no usa) |
| 5 | Usuarios | `administrador/manual-administrador.adoc#usuarios` (Usuarios) |
| 6 | Crear una cuenta | `administrador/manual-administrador.adoc#proc-crear-usuario` (Crear una cuenta) |
| 7 | Cambiar contraseña, estado o roles | `administrador/manual-administrador.adoc#proc-cambiar-clave` (Cambiar la contraseña, el nombre o los roles de una cuenta) |
| 8 | Desactivar o eliminar una cuenta | `administrador/manual-administrador.adoc#desactivar-usuario` (Desactivar o eliminar una cuenta) |
| 9 | La baja no corta una sesión abierta | `administrador/manual-administrador.adoc#desactivar-usuario` (Desactivar o eliminar una cuenta) |
| 10 | Roles y permisos | `administrador/manual-administrador.adoc#roles-y-permisos` (Roles y permisos) |
| 11 | Una cuenta, sus roles y sus permisos | `administrador/manual-administrador.adoc#modelo-permisos` (Cómo funcionan los permisos)<br>`referencia/permisos.adoc#ref-permisos-modelo` (Cómo se decide un permiso) |
| 12 | Crear un rol | `administrador/manual-administrador.adoc#proc-crear-rol` (Crear un rol) |
| 13 | La matriz de permisos de un rol | `administrador/manual-administrador.adoc#proc-permisos-rol` (Asignar los permisos de un rol)<br>`administrador/manual-administrador.adoc#permisos-campo` (Permisos de campo) |
| 14 | Cinco perfiles de partida | `administrador/manual-administrador.adoc#perfiles-sugeridos` (Perfiles sugeridos) |
| 15 | Datos maestros | `administrador/manual-administrador.adoc#datos-maestros` (Datos maestros y parámetros) |
| 16 | La cabecera de los documentos | `administrador/manual-administrador.adoc#proc-municipalidad` (Corregir la cabecera de los documentos)<br>`administrador/manual-administrador.adoc#municipalidad` (Cabecera de los documentos (municipalidad)) |
| 17 | Las cifras de los cálculos | `administrador/manual-administrador.adoc#parametros` (Parámetros tributarios) |
| 18 | No edite un parámetro ya usado | `administrador/manual-administrador.adoc#parametros` (Parámetros tributarios) |
| 19 | Lo que solo se agrega, se corrige con otro acto | `administrador/manual-administrador.adoc#correcciones` (Registros que no se corrigen)<br>`administrador/manual-administrador.adoc#registros-consola` (Ver y editar registros desde la consola) |
| 20 | Quién cambió qué, y cuándo | `administrador/manual-administrador.adoc#auditoria` (Auditoría) |
| 21 | Operación | `administrador/manual-administrador.adoc#procesos-masivos` (Procesos masivos) |
| 22 | Lo que conviene saber al vigilarlos | `administrador/manual-administrador.adoc#procesos-masivos` (Procesos masivos) |
| 23 | Preparar un año tributario | `administrador/manual-administrador.adoc#anio-tributario` (Preparar un año tributario) |
| 24 | Un respaldo completo tiene dos partes | `administrador/manual-administrador.adoc#respaldo` (Respaldos y restauración) |
| 25 | Síntomas frecuentes y qué hacer | `administrador/manual-administrador.adoc#monitoreo` (Monitoreo e incidencias) |
| 26 | Antes de una operación crítica | `administrador/manual-administrador.adoc#checklist` (Lista de comprobación de operaciones críticas) |
| 27 | Administrar, en pocas palabras | `administrador/manual-administrador.adoc#funciones` (Funciones del administrador) |

## 09 · Arquitectura técnica

| # | Escena | Fuentes |
|---|---|---|
| 1 | Arquitectura técnica | `sistema/manual-sistema.adoc#intro-audiencia` (A quién está dirigido) |
| 2 | Un backend, un portal y una base | `sistema/manual-sistema.adoc#arq-contexto` (Contexto) |
| 3 | La pila y sus versiones | `sistema/manual-sistema.adoc#req-versiones` (Versiones)<br>`sistema/manual-sistema.adoc#intro-version` (Versión que se describe) |
| 4 | Los paquetes de srtm y la plataforma | `sistema/manual-sistema.adoc#arq-componentes` (Componentes)<br>`sistema/manual-sistema.adoc#arq-modulos` (Módulos de SRTM y responsabilidades) |
| 5 | Lo que da la plataforma y lo que agrega SRTM | `sistema/manual-sistema.adoc#arq-wasichai` (La plataforma wasichai) |
| 6 | De la petición a la fila y su auditoría | `sistema/manual-sistema.adoc#arq-peticion` (Recorrido de una petición) |
| 7 | El modelo se carga por REST, no con SQL | `sistema/manual-sistema.adoc#arq-metadata` (Modelo de metadata cargado por REST)<br>`referencia/modelo-datos.adoc#ref-modelo-almacenamiento` (Dónde se guarda) |
| 8 | El padrón: contribuyente, predio y DJ | `referencia/modelo-datos.adoc#ref-modelo-diagramas` (Diagramas entidad-relación) |
| 9 | Garantías que no dependen del portal | `sistema/manual-sistema.adoc#arq-decisiones` (Decisiones verificables en el código) |
| 10 | Lotes repartidos entre instancias | `sistema/manual-sistema.adoc#comp-emision-masiva` (Emisión masiva)<br>`sistema/manual-sistema.adoc#async-estados` (Estados y fallos) |
| 11 | Cuántos trabajadores corre una instancia | `sistema/manual-sistema.adoc#comp-trabajadores` (Trabajadores)<br>`sistema/manual-sistema.adoc#desp-servidor` (Desplegar en un servidor) |
| 12 | Disco local o bucket S3 | `sistema/manual-sistema.adoc#comp-almacen` (Almacén de archivos) |
| 13 | Autenticación, permisos y auditoría | `sistema/manual-sistema.adoc#seg-autenticacion` (Autenticación)<br>`sistema/manual-sistema.adoc#seg-seed` (Semilla de desarrollo)<br>`sistema/manual-sistema.adoc#seg-autorizacion` (Autorización)<br>`sistema/manual-sistema.adoc#seg-guardia` (Guardia de escritura)<br>`sistema/manual-sistema.adoc#seg-auditoria` (Auditoría)<br>`sistema/manual-sistema.adoc#seg-pendiente` (Endurecimiento disponible y apagado) |
| 14 | Cuatro suites, todas en el CI | `sistema/manual-sistema.adoc#pruebas` (Pruebas)<br>`sistema/manual-sistema.adoc#pruebas-ci` (Integración continua) |
| 15 | La arquitectura, en pocas palabras | `sistema/manual-sistema.adoc#arquitectura` (Arquitectura) |

## 10 · Instalación, operación y recuperación

| # | Escena | Fuentes |
|---|---|---|
| 1 | Instalación, operación y recuperación | `sistema/manual-sistema.adoc#intro-audiencia` (A quién está dirigido) |
| 2 | Base y backend en la máquina local | `sistema/manual-sistema.adoc#proc-variables-desarrollo` (Preparar las variables de entorno)<br>`sistema/manual-sistema.adoc#proc-base-local` (Levantar la base de datos local)<br>`sistema/manual-sistema.adoc#proc-arrancar-backend` (Arrancar el backend) |
| 3 | docker compose down -v borra los datos | `sistema/manual-sistema.adoc#proc-base-local` (Levantar la base de datos local) |
| 4 | Sin el modelo, el portal no tiene objetos | `sistema/manual-sistema.adoc#proc-modelo-inicial` (Cargar el modelo y los datos mínimos) |
| 5 | Siete pasos, en este orden | `sistema/manual-sistema.adoc#carga-orden` (Orden de carga)<br>`sistema/manual-sistema.adoc#carga-importadores` (Importadores) |
| 6 | Cinco modos, uno destructivo | `sistema/manual-sistema.adoc#carga-apply` (apply.py: el modelo) |
| 7 | Un cambio del modelo, sin sorpresas | `sistema/manual-sistema.adoc#proc-apply-compartida` (Aplicar un cambio del modelo en una base compartida) |
| 8 | DNI, nombres y domicilios nunca van al repositorio | `sistema/manual-sistema.adoc#carga-datos-personales` (Datos personales)<br>`sistema/manual-sistema.adoc#seg-datos-personales` (Datos personales) |
| 9 | De un commit al jar publicado | `sistema/manual-sistema.adoc#desp-versiones` (Versiones y releases)<br>`sistema/manual-sistema.adoc#desp-artefactos` (Qué se publica) |
| 10 | Respaldo primero, todas las instancias juntas | `sistema/manual-sistema.adoc#proc-actualizar` (Actualizar el backend) |
| 11 | Rutas de salud, sin token | `sistema/manual-sistema.adoc#op-salud` (Salud) |
| 12 | Las líneas que conviene vigilar | `sistema/manual-sistema.adoc#op-logs` (Registros (logs)) |
| 13 | Cuatro cosas que respaldar | `sistema/manual-sistema.adoc#resp-que` (Qué hay que respaldar) |
| 14 | Un volcado completo, y su prueba | `sistema/manual-sistema.adoc#proc-respaldo-base` (Respaldar la base)<br>`sistema/manual-sistema.adoc#proc-respaldo-almacen` (Respaldar el almacén de emisiones) |
| 15 | Siempre en una base nueva y vacía | `sistema/manual-sistema.adoc#proc-restaurar` (Restaurar la base en una base nueva)<br>`sistema/manual-sistema.adoc#resp-restaurar` (Restaurar) |
| 16 | Siete comprobaciones antes de usarla | `sistema/manual-sistema.adoc#proc-verificar-restauracion` (Verificar una restauración) |
| 17 | Qué se pierde y cómo se recupera | `sistema/manual-sistema.adoc#resp-desastres` (Recuperación ante desastres) |
| 18 | La base de demostración, de punta a punta | `sistema/manual-sistema.adoc#resp-demo` (Ensayo con la base de demostración)<br>`demo/README.adoc#_restaurar_y_verificar` (Restaurar y verificar) |
| 19 | Lista de verificación | `sistema/manual-sistema.adoc#op-checklist` (Lista de verificación posterior al despliegue) |
| 20 | Síntomas frecuentes | `sistema/manual-sistema.adoc#diagnostico` (Diagnóstico de problemas frecuentes) |
| 21 | Lo que todavía falta | `sistema/manual-sistema.adoc#pendientes` (Pendientes y limitaciones conocidas) |
| 22 | La operación, en pocas palabras | `sistema/manual-sistema.adoc#operacion` (Operación) |
