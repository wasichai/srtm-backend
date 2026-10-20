# 06 · Anuncios y propaganda

<!-- generado por scripts/storyboards.ts a partir de src/videos: no se edita a mano -->

- **Composición:** `S06-Anuncios` · **vídeo:** `salida/videos/srtm-06-anuncios.mp4` · **miniatura:** `salida/miniaturas/srtm-06-anuncios.jpg`
- **Audiencia:** Personal que autoriza anuncios (perfil ANUNCIOS)
- **Objetivo:** Registrar, renovar, cesar y retirar anuncios, leer su estado a una fecha y saber cuánto devenga cada acto.
- **Duración:** 4:59 (8982 fotogramas a 30 fps, 1920 × 1080) · **escenas:** 12
- **Manuales:** Manual de anuncios

| # | Escena | Tipo | Inicio | Duración | Fuente |
|---|---|---|---|---|---|
| 1 | Anuncios y propaganda | Portada | 0:00 | 11,0 s | Manual de anuncios › Para qué sirve este manual y a quién se dirige |
| 2 | Lo que hay que saber antes | Tarjetas | 0:10 | 30,0 s | Manual de anuncios › Lo que hay que saber antes |
| 3 | El estado se deduce, a una fecha | Tabla | 0:40 | 26,0 s | Manual de anuncios › Cómo se decide el estado |
| 4 | El padrón, hoy o a una fecha pasada | Captura recorrida | 1:05 | 32,5 s | Manual de anuncios › Consultar el padrón de anuncios |
| 5 | Registrar un anuncio con su autorización | Captura recorrida | 1:37 | 32,5 s | Manual de anuncios › Registrar un anuncio |
| 6 | Si no hubo respuesta, vuelva a pulsar | Aviso | 2:09 | 22,5 s | Manual de anuncios › Si no hubo respuesta: el reintento seguro |
| 7 | Un anuncio renovado | Captura recorrida | 2:31 | 28,0 s | Manual de anuncios › Partes de la ficha |
| 8 | Un ejercicio por renovación, una sola vez | Tarjetas | 2:58 | 27,0 s | Manual de anuncios › Qué ejercicio renueva y cuánto devenga |
| 9 | Primero el cese, después el retiro | Captura recorrida | 3:25 | 32,5 s | Manual de anuncios › Cesar un anuncio |
| 10 | Las tasas de un año | Captura recorrida | 3:57 | 18,5 s | Manual de anuncios › Consultar las tasas de un año |
| 11 | Cómo se aplica la tasa | Tarjetas | 4:15 | 24,5 s | Manual de anuncios › Cómo se aplica la tasa |
| 12 | Los anuncios, en pocas palabras | Cierre | 4:38 | 21,0 s | Manual de anuncios › El anuncio en el sistema |

## 1. Anuncios y propaganda

- **Id:** `portada` · **tipo:** Portada · **inicio:** 0:00 · **duración:** 11,0 s
- **Objetivo de aprendizaje:** Presentar el vídeo y a quién va dirigido.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** El bloque azul baja; la marca, el título y el subtítulo entran escalonados; luego la audiencia y las fuentes.
- **Fuentes:** Manual de anuncios › Para qué sirve este manual y a quién se dirige (`docs/manuales/usuario/anuncios.adoc#presentacion-manual`)
- **Sigue:** 2. Lo que hay que saber antes
- **Contenido:**

  > Anuncios y propaganda: Anuncios y propaganda
  > Un anuncio no se edita: se le agregan actos, y cada uno cuenta.
  > Para quién: Personal que autoriza anuncios (perfil ANUNCIOS).

## 2. Lo que hay que saber antes

- **Id:** `conceptos` · **tipo:** Tarjetas · **inicio:** 0:10 · **duración:** 30,0 s
- **Objetivo de aprendizaje:** Conocer los datos que definen un anuncio en el sistema.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Las tarjetas entran una a una, en orden de lectura; al final, la nota.
- **Fuentes:** Manual de anuncios › Lo que hay que saber antes (`docs/manuales/usuario/anuncios.adoc#anuncio-conceptos`)
- **Sigue:** 3. El estado se deduce, a una fecha
- **Contenido:**

  > Titular: Contribuyente inscrito que obtiene la autorización y responde por la tasa.
  > Predio: Donde está el anuncio. Opcional: uno en la vía pública no tiene predio.
  > Clase: Letrero, Panel, Toldo, Banderola, Pantalla digital o Globo aerostático. Fija la tasa.
  > Tipo: Aviso simple, luminoso, iluminado o electrónico.
  > Número: AN-AAAA-NNNNNN: el año de la autorización y un correlativo, que asigna el sistema.
  > Movimientos: Autorización, renovaciones, cese y retiro: se agregan, ninguno se edita.
  > Nota: Los datos del anuncio son los de la autorización: lo que pasa después es un movimiento nuevo.

## 3. El estado se deduce, a una fecha

- **Id:** `estados` · **tipo:** Tabla · **inicio:** 0:40 · **duración:** 26,0 s
- **Objetivo de aprendizaje:** Saber cómo deduce el sistema el estado de un anuncio y qué admite cada estado.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual de anuncios › Cómo se decide el estado (`docs/manuales/usuario/anuncios.adoc#estados`)
- **Sigue:** 4. El padrón, hoy o a una fecha pasada
- **Contenido:**

  > Estado | Cuándo | Qué admite
  > Retirado | Tiene un retiro fechado hasta ese día. | Ningún acto: está cerrado.
  > Cesado | Tiene un cese fechado hasta ese día. | Solo el retiro.
  > Vencido | Su vigencia terminó antes de ese día. | Renovar o cesar.
  > Vigente | En cualquier otro caso. | Renovar o cesar.
  > Nota: El último día de la vigencia el anuncio todavía está vigente; vence al día siguiente.

## 4. El padrón, hoy o a una fecha pasada

- **Id:** `padron` · **tipo:** Captura recorrida · **inicio:** 1:05 · **duración:** 32,5 s
- **Objetivo de aprendizaje:** Consultar el padrón de anuncios con su estado, hoy o a una fecha pasada.
- **Imagen:** captura real `usuario/anuncios/padron.png`, captura real `usuario/anuncios/padron-estado-al.png`
- **Animación:** Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.
- **Fuentes:** Manual de anuncios › Consultar el padrón de anuncios (`docs/manuales/usuario/anuncios.adoc#proc-padron-pasos`); Manual de anuncios › El padrón a una fecha pasada (`docs/manuales/usuario/anuncios.adoc#padron-a-fecha`)
- **Sigue:** 5. Registrar un anuncio con su autorización
- **Contenido:**

  > 1. Filtros: Titular, clase, estado y texto; se aplican juntos al pulsar Buscar.
  > 2. Estado al: La fecha a la que se calcula el estado: hoy, por omisión.
  > 3. Vigente hasta y Estado: «—» en un anuncio cesado o retirado: ya no rige.
  > 4. A una fecha pasada: Al 16/04/2026, AN-2026-000002 regía hasta el 31/12/2026 y AN-2026-000005 estaba cesado.

## 5. Registrar un anuncio con su autorización

- **Id:** `nuevo` · **tipo:** Captura recorrida · **inicio:** 1:37 · **duración:** 32,5 s
- **Objetivo de aprendizaje:** Registrar un anuncio con su autorización y ver cuánto devenga.
- **Imagen:** captura real `usuario/anuncios/nuevo.png`
- **Animación:** Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.
- **Fuentes:** Manual de anuncios › Registrar un anuncio (`docs/manuales/usuario/anuncios.adoc#proc-nuevo-pasos`)
- **Sigue:** 6. Si no hubo respuesta, vuelva a pulsar
- **Contenido:**

  > 1. Titular y predio: El titular, por su DNI, RUC o nombre; el predio es opcional.
  > 2. Clase y tipo: La clase fija la tasa que se devenga.
  > 3. Autorizado el y Vigente hasta: Hoy, por omisión, y el último día que rige; en blanco, el anuncio no vence.
  > 4. Registrar anuncio: El aviso da el número y lo que devenga: S/ 90.20 del ejercicio 2026.

## 6. Si no hubo respuesta, vuelva a pulsar

- **Id:** `reintento` · **tipo:** Aviso · **inicio:** 2:09 · **duración:** 22,5 s
- **Objetivo de aprendizaje:** Saber qué hacer si se corta la comunicación al registrar un anuncio.
- **Imagen:** recuadro de aviso
- **Animación:** El recuadro del aviso entra y sus puntos aparecen uno a uno; a su lado, la captura, si la hay.
- **Fuentes:** Manual de anuncios › Si no hubo respuesta: el reintento seguro (`docs/manuales/usuario/anuncios.adoc#reintento`)
- **Sigue:** 7. Un anuncio renovado
- **Contenido:**

  > Si no hubo respuesta, vuelva a pulsar: Ante «No hubo respuesta del servidor», pulse Registrar anuncio otra vez, sin salir de la página.
  >   - Si el primer intento no llegó, el anuncio se registra ahora.
  >   - Si llegó, el sistema lo reconoce: no registra otro ni devenga otra vez.
  >   - Si recarga la página, cuenta como un anuncio nuevo: busque antes en el padrón.

## 7. Un anuncio renovado

- **Id:** `ficha` · **tipo:** Captura recorrida · **inicio:** 2:31 · **duración:** 28,0 s
- **Objetivo de aprendizaje:** Leer la ficha de un anuncio: vigencia, devengado, actos y movimientos.
- **Imagen:** captura real `usuario/anuncios/ficha.png`
- **Animación:** Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.
- **Fuentes:** Manual de anuncios › Partes de la ficha (`docs/manuales/usuario/anuncios.adoc#ficha-partes`); Manual de anuncios › Movimientos (`docs/manuales/usuario/anuncios.adoc#movimientos`)
- **Sigue:** 8. Un ejercicio por renovación, una sola vez
- **Contenido:**

  > 1. Vigente hasta y devengado: La vigencia que rige hoy y la suma de las tasas devengadas hasta hoy.
  > 2. Actos: Renovar, Cesar y Retirar; si uno no procede, debajo dice por qué.
  > 3. Movimientos: La autorización de 2026 y la renovación para 2027, cada una con su tasa y su referencia de cargo.

## 8. Un ejercicio por renovación, una sola vez

- **Id:** `renovar` · **tipo:** Tarjetas · **inicio:** 2:58 · **duración:** 27,0 s
- **Objetivo de aprendizaje:** Saber qué ejercicio devenga una renovación y cuándo se rechaza.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Las tarjetas entran una a una, en orden de lectura; al final, la nota.
- **Fuentes:** Manual de anuncios › Qué ejercicio renueva y cuánto devenga (`docs/manuales/usuario/anuncios.adoc#renovacion-reglas`); Manual de anuncios › Renovar un anuncio (`docs/manuales/usuario/anuncios.adoc#proc-renovar-pasos`)
- **Sigue:** 9. Primero el cese, después el retiro
- **Contenido:**

  > Un ejercicio: El del año de Nueva vigencia hasta; la prórroga no abarca más de uno.
  > Una sola vez: La autorización ya devengó el ejercicio de su año: no se devenga dos veces.
  > Ejemplo · AN-2026-000001: Rige hasta el 31/12/2026: renovado hasta el 31/12/2027, devenga 2027.
  > Ejemplo · AN-2026-000003: Venció el 31/07/2026 y ya devengó 2026: podrá renovarse desde el 01/01/2027.
  > Nota: Escriba siempre Nueva vigencia hasta: en blanco, el anuncio queda sin plazo.

## 9. Primero el cese, después el retiro

- **Id:** `cesar-retirar` · **tipo:** Captura recorrida · **inicio:** 3:25 · **duración:** 32,5 s
- **Objetivo de aprendizaje:** Cesar un anuncio y, después, registrar su retiro.
- **Imagen:** captura real `usuario/anuncios/cesar.png`, captura real `usuario/anuncios/ficha-cesado.png`, captura real `usuario/anuncios/retirar.png`
- **Animación:** Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.
- **Fuentes:** Manual de anuncios › Cesar un anuncio (`docs/manuales/usuario/anuncios.adoc#proc-cesar-pasos`); Manual de anuncios › Retirar un anuncio (`docs/manuales/usuario/anuncios.adoc#proc-retirar`)
- **Sigue:** 10. Las tasas de un año
- **Contenido:**

  > 1. Cesar: Fecha, motivo y observación. El anuncio deja de estar vigente; el cese no se deshace.
  > 2. Un anuncio cesado: Vigente hasta «—», con la fecha del cese; lo devengado no cambia.
  > 3. Solo queda retirar: Renovar y Cesar quedan grises, con su motivo.
  > 4. Retirar: Deja constancia de que el elemento ya no está y cierra el anuncio.

## 10. Las tasas de un año

- **Id:** `tasas` · **tipo:** Captura recorrida · **inicio:** 3:57 · **duración:** 18,5 s
- **Objetivo de aprendizaje:** Consultar la tasa de cada clase en un año.
- **Imagen:** captura real `usuario/anuncios/tasas.png`
- **Animación:** Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.
- **Fuentes:** Manual de anuncios › Consultar las tasas de un año (`docs/manuales/usuario/anuncios.adoc#tasas-consultar`)
- **Sigue:** 11. Cómo se aplica la tasa
- **Contenido:**

  > 1. Año: El siguiente, el actual y los cuatro anteriores.
  > 2. Una tasa por clase: S/ 90.20; la banderola, S/ 31.00. Sin tasa, una clase no se autoriza ni se renueva.

## 11. Cómo se aplica la tasa

- **Id:** `como-aplica` · **tipo:** Tarjetas · **inicio:** 4:15 · **duración:** 24,5 s
- **Objetivo de aprendizaje:** Saber cómo se aplica la tasa y qué significa lo devengado.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Las tarjetas entran una a una, en orden de lectura; al final, la nota.
- **Fuentes:** Manual de anuncios › Cómo se aplica la tasa (`docs/manuales/usuario/anuncios.adoc#tasa-como-aplica`); Manual de anuncios › Lo devengado no es una deuda (`docs/manuales/usuario/anuncios.adoc#devengado`)
- **Sigue:** 12. Los anuncios, en pocas palabras
- **Contenido:**

  > Por clase y ejercicio: No depende del área, los lados ni la cantidad.
  > Se copia en el movimiento: Si después cambia la tarifa, lo ya devengado no cambia.
  > Cese y retiro no devengan: Solo devengan la autorización y las renovaciones.
  > Lo devengado no es deuda: El sistema no registra pagos: la orden de cobro va a caja.
  > Nota: Perené no tiene ordenanza de tasa por anuncios: se usa el derecho de trámite de su TUPA.

## 12. Los anuncios, en pocas palabras

- **Id:** `cierre` · **tipo:** Cierre · **inicio:** 4:38 · **duración:** 21,0 s
- **Objetivo de aprendizaje:** Recordar lo esencial y dónde seguir.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los puntos del resumen entran uno a uno; luego los manuales y el vídeo siguiente.
- **Fuentes:** Manual de anuncios › El anuncio en el sistema (`docs/manuales/usuario/anuncios.adoc#anuncio-sistema`)
- **Sigue:** fin del vídeo
- **Contenido:**

  > ✓ El anuncio no se edita: se le agregan movimientos.
  > ✓ El estado se deduce a una fecha: Retirado, Cesado, Vencido o Vigente.
  > ✓ Cada ejercicio se devenga una sola vez, con la tasa de su clase.
  > ✓ El cobro va a caja, con la referencia de cargo.
  > Para saber más: Manual de anuncios · Manual del administrador: la tasa TASA_ANUNCIO
  > Siguiente: 07 · Consulta y brecha de recaudación
