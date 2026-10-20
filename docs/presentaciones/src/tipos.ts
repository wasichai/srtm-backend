// El contenido de las presentaciones: cada vídeo es una lista de escenas tipadas (src/videos/*.ts) y cada escena
// nombra de qué manual y de qué sección sale. Los componentes de src/escenas solo dibujan; lo que se dice está aquí.
// scripts/storyboards.ts escribe con estos mismos datos los storyboards y el manifiesto, y scripts/verificar.ts
// comprueba las fuentes, las capturas, las regiones y los tiempos de lectura.
import type { NivelAviso } from './tema/tokens.ts'
import type { NombreIcono } from './componentes/iconos.tsx'

/** Un archivo .adoc de docs/manuales, sin la extensión. */
export type DocManual =
  | 'index'
  | 'usuario/registrador'
  | 'usuario/arbitrios-emision'
  | 'usuario/fiscalizador'
  | 'usuario/anuncios'
  | 'usuario/consulta'
  | 'administrador/manual-administrador'
  | 'sistema/manual-sistema'
  | 'comun/acceso'
  | 'comun/interfaz'
  | 'comun/busqueda'
  | 'comun/mensajes'
  | 'comun/faq'
  | 'referencia/api'
  | 'referencia/configuracion'
  | 'referencia/modelo-datos'
  | 'referencia/permisos'
  | 'referencia/glosario'
  | 'demo/README'

/** Dónde está, en los manuales, lo que dice una escena: un archivo y el ancla `[[…]]` de su sección. */
export type Fuente = { readonly doc: DocManual; readonly ancla: string }

/** Un rectángulo de una captura, en píxeles CSS de la página capturada (la vista de 1440 × 1000). */
export type Region = { readonly x: number; readonly y: number; readonly w: number; readonly h: number }

/** Ruta de una captura bajo recursos/capturas, la misma que en docs/manuales/recursos/capturas. */
export type Captura = string

export type Tono = 'marca' | 'exito' | 'aviso' | 'peligro' | 'neutro'

type Base = {
  /** Identificador estable de la escena dentro del vídeo. */
  readonly id: string
  /** Objetivo de aprendizaje: qué debe entender quien mira. */
  readonly objetivo: string
  /** La primera es la que se cita al pie. */
  readonly fuentes: readonly Fuente[]
  /** Texto previsto para una narración futura. No se sintetiza: las presentaciones no tienen voz. */
  readonly guion?: string
}

export type Portada = Base & {
  readonly tipo: 'portada'
  readonly antetitulo: string
  readonly titulo: string
  readonly subtitulo: string
  readonly audiencia: string
  readonly segundos: number
}

export type Seccion = Base & {
  readonly tipo: 'seccion'
  readonly numero: string
  readonly titulo: string
  readonly bajada: string
  readonly segundos: number
}

export type Mensaje = Base & {
  readonly tipo: 'mensaje'
  readonly antetitulo?: string
  readonly frase: string
  /** Parte de la frase que va en el color de la marca. */
  readonly resaltar?: string
  readonly apoyo?: string
  readonly segundos: number
}

export type Tarjeta = {
  readonly icono?: NombreIcono
  readonly rotulo?: string
  readonly titulo: string
  readonly texto?: string
  readonly tono?: Tono
}

export type Tarjetas = Base & {
  readonly tipo: 'tarjetas'
  readonly antetitulo: string
  readonly titulo: string
  readonly entrada?: string
  readonly tarjetas: readonly Tarjeta[]
  readonly columnas: 2 | 3 | 4 | 5
  readonly nota?: string
  readonly segundos: number
}

export type Foco = {
  readonly titulo: string
  readonly texto?: string
  readonly segundos: number
  /** Lo que se señala. Sin región, el foco muestra la captura entera (o su encuadre). */
  readonly region?: Region
  /** Acercar la cámara a la región (por omisión, sí). */
  readonly acercar?: boolean
  /** Lo que la cámara encuadra, si no es la región. */
  readonly encuadre?: Region
  /** Cambia la imagen del visor desde este foco. */
  readonly captura?: Captura
  /** La sección que cita el pie mientras dura este foco, si no es la primera fuente de la escena. */
  readonly fuente?: Fuente
}

export type Pantalla = Base & {
  readonly tipo: 'pantalla'
  readonly antetitulo: string
  readonly titulo: string
  readonly captura: Captura
  /** El marco del visor: la pantalla entera del portal, un recorte o diálogo, o una página de un documento. */
  readonly marco?: 'portal' | 'recorte' | 'documento'
  /** Lo que se ve al empezar, si no es la captura entera. */
  readonly encuadre?: Region
  readonly focos: readonly Foco[]
  /** Segundos de la vista general antes del primer foco. */
  readonly entrada?: number
}

export type Nodo = {
  readonly id: string
  readonly x: number
  readonly y: number
  readonly w: number
  readonly h: number
  readonly titulo: string
  readonly texto?: string
  readonly tipo?: 'proceso' | 'dato' | 'persona' | 'externo' | 'estado' | 'grupo' | 'fuera' | 'clave'
  readonly icono?: NombreIcono
  /** Paso en que aparece (0 por omisión). */
  readonly paso?: number
}

export type Arista = {
  readonly de: string
  readonly a: string
  readonly rotulo?: string
  readonly discontinua?: boolean
  readonly paso?: number
  /** Por dónde sale y entra la flecha; si no, se elige por la posición de los nodos. */
  readonly salida?: 'derecha' | 'izquierda' | 'arriba' | 'abajo'
  readonly llegada?: 'derecha' | 'izquierda' | 'arriba' | 'abajo'
  /** Desplaza el rótulo desde el punto medio. */
  readonly rotuloDx?: number
  readonly rotuloDy?: number
}

export type PasoDiagrama = { readonly texto: string; readonly segundos: number }

export type Diagrama = Base & {
  readonly tipo: 'diagrama'
  readonly antetitulo: string
  readonly titulo: string
  readonly entrada?: string
  /** El lienzo del diagrama, en píxeles; se centra bajo el título. */
  readonly lienzo: { readonly ancho: number; readonly alto: number }
  readonly nodos: readonly Nodo[]
  readonly aristas: readonly Arista[]
  /** Cada paso revela sus nodos y aristas y muestra su texto al pie. Sin pasos, todo aparece de una vez. */
  readonly pasos?: readonly PasoDiagrama[]
  readonly nota?: string
  readonly segundos?: number
}

export type PasoProceso = {
  readonly titulo: string
  readonly texto?: string
  readonly quien?: string
  readonly icono?: NombreIcono
  readonly tono?: Tono
}

export type Proceso = Base & {
  readonly tipo: 'proceso'
  readonly antetitulo: string
  readonly titulo: string
  readonly entrada?: string
  readonly pasos: readonly PasoProceso[]
  readonly nota?: string
  readonly segundos: number
}

export type Columna = {
  readonly titulo: string
  readonly tono: Tono
  readonly icono?: NombreIcono
  readonly puntos: readonly string[]
}

export type Comparacion = Base & {
  readonly tipo: 'comparacion'
  readonly antetitulo: string
  readonly titulo: string
  readonly entrada?: string
  readonly columnas: readonly [Columna, Columna]
  readonly nota?: string
  readonly segundos: number
}

export type Tabla = Base & {
  readonly tipo: 'tabla'
  readonly antetitulo: string
  readonly titulo: string
  readonly entrada?: string
  readonly encabezados: readonly string[]
  readonly filas: readonly (readonly string[])[]
  /** Proporción del ancho de cada columna. */
  readonly anchos?: readonly number[]
  /** Columnas que se escriben en letra de código. */
  readonly codigo?: readonly number[]
  /** Filas que se destacan. */
  readonly resaltar?: readonly number[]
  readonly nota?: string
  readonly segundos: number
}

export type FilaCalculo = {
  readonly concepto: string
  readonly detalle?: string
  readonly valor: string
  readonly tipo?: 'normal' | 'subtotal' | 'total'
}

export type Calculo = Base & {
  readonly tipo: 'calculo'
  readonly antetitulo: string
  readonly titulo: string
  readonly entrada?: string
  /** Una fórmula que se muestra sobre las filas. */
  readonly formula?: string
  readonly filas: readonly FilaCalculo[]
  readonly nota?: string
  readonly segundos: number
}

export type BloqueCodigo = {
  readonly comentario?: string
  readonly lineas: readonly string[]
  /** Lo que el comando debe responder. */
  readonly salida?: readonly string[]
}

export type Codigo = Base & {
  readonly tipo: 'codigo'
  readonly antetitulo: string
  readonly titulo: string
  readonly directorio?: string
  readonly bloques: readonly BloqueCodigo[]
  readonly nota?: string
  readonly segundos: number
}

export type Aviso = Base & {
  readonly tipo: 'aviso'
  readonly nivel: NivelAviso
  readonly antetitulo: string
  readonly titulo: string
  readonly texto: string
  readonly puntos?: readonly string[]
  /** Una captura que acompaña el aviso, entera o recortada. */
  readonly captura?: { readonly archivo: Captura; readonly region?: Region }
  readonly segundos: number
}

export type Participante = { readonly id: string; readonly titulo: string; readonly texto?: string }

export type MensajeSecuencia = {
  readonly de: string
  readonly a: string
  readonly texto: string
  readonly detalle?: string
}

export type Secuencia = Base & {
  readonly tipo: 'secuencia'
  readonly antetitulo: string
  readonly titulo: string
  readonly participantes: readonly Participante[]
  readonly mensajes: readonly MensajeSecuencia[]
  /** Segundos por mensaje. */
  readonly segundosPorMensaje: number
}

export type Cierre = Base & {
  readonly tipo: 'cierre'
  readonly titulo: string
  readonly puntos: readonly string[]
  readonly manuales: readonly string[]
  readonly siguiente?: string
  readonly segundos: number
}

export type Escena =
  | Portada
  | Seccion
  | Mensaje
  | Tarjetas
  | Pantalla
  | Diagrama
  | Proceso
  | Comparacion
  | Tabla
  | Calculo
  | Codigo
  | Aviso
  | Secuencia
  | Cierre

export type Video = {
  /** Id de la composición en Remotion. */
  readonly id: string
  /** Nombre del MP4 y de la miniatura, sin extensión. */
  readonly archivo: string
  readonly numero: string
  readonly titulo: string
  /** Lo que dice el pie de cada escena. */
  readonly corto: string
  readonly audiencia: string
  readonly objetivo: string
  readonly manuales: readonly DocManual[]
  readonly escenas: readonly Escena[]
  /** La escena (y el segundo dentro de ella) que hace de miniatura. */
  readonly miniatura: { readonly escena: string; readonly segundo: number }
}
