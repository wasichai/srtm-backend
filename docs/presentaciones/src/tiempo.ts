// Duraciones en fotogramas. Todo lo que mide el tiempo de una presentación sale de aquí: la composición, la
// verificación y los storyboards usan las mismas cuentas.
import type { Escena, Video } from './tipos.ts'

export const FPS = 30

/** Fundido entre dos escenas: se superponen este número de fotogramas. */
export const TRANSICION = 18

/** Lo que dura la vista general de una pantalla antes de su primer foco, si la escena no dice otra cosa. */
export const ENTRADA_PANTALLA = 3

/** Lo que queda una pantalla tras su último foco. */
export const SALIDA_PANTALLA = 1.5

/** Lo que tarda una escena de diagrama en dibujarse antes del primer paso. */
export const ENTRADA_DIAGRAMA = 2.5

/** Lo que tarda el título de una secuencia antes del primer mensaje. */
export const ENTRADA_SECUENCIA = 2.5

export const segundosEscena = (e: Escena): number => {
  switch (e.tipo) {
    case 'pantalla':
      return (e.entrada ?? ENTRADA_PANTALLA) + e.focos.reduce((s, f) => s + f.segundos, 0) + SALIDA_PANTALLA
    case 'diagrama':
      return e.pasos ? ENTRADA_DIAGRAMA + e.pasos.reduce((s, p) => s + p.segundos, 0) : (e.segundos ?? 10)
    case 'secuencia':
      return ENTRADA_SECUENCIA + e.mensajes.length * e.segundosPorMensaje + 2
    default:
      return e.segundos
  }
}

export const fotogramasEscena = (e: Escena): number => Math.round(segundosEscena(e) * FPS)

/** Duración total: las escenas se superponen en cada fundido. */
export const fotogramasVideo = (v: Video): number =>
  v.escenas.reduce((s, e) => s + fotogramasEscena(e), 0) - TRANSICION * (v.escenas.length - 1)

/** El fotograma (del vídeo) en que empieza cada escena. */
export const iniciosEscenas = (v: Video): number[] => {
  const inicios: number[] = []
  let t = 0
  for (const e of v.escenas) {
    inicios.push(t)
    t += fotogramasEscena(e) - TRANSICION
  }
  return inicios
}

export const reloj = (fotogramas: number): string => {
  const s = Math.round(fotogramas / FPS)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}
