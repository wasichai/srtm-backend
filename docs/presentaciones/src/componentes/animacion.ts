// El lenguaje de movimiento: pocas curvas, siempre las mismas. Todo depende del fotograma de Remotion, nunca del
// reloj: un render da siempre el mismo resultado.
import { Easing, interpolate } from 'remotion'
import type { CSSProperties } from 'react'

/** Desaceleración larga: lo que entra llega rápido y se asienta despacio. */
export const SUAVE = Easing.bezier(0.16, 1, 0.3, 1)

/** Para los movimientos de cámara: arranca y frena sin brusquedad. */
export const CAMARA = Easing.bezier(0.65, 0, 0.35, 1)

/** 0 → 1 entre `inicio` y `inicio + duracion` (en fotogramas). */
export const avance = (frame: number, inicio: number, duracion: number, easing = SUAVE): number =>
  interpolate(frame, [inicio, inicio + Math.max(1, duracion)], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing,
  })

/** Aparece subiendo un poco. */
export const entrar = (frame: number, inicio: number, distancia = 18, duracion = 16): CSSProperties => {
  const p = avance(frame, inicio, duracion)
  return { opacity: p, transform: `translateY(${(1 - p) * distancia}px)` }
}

/** Aparece desde la izquierda. */
export const deslizar = (frame: number, inicio: number, distancia = 24, duracion = 18): CSSProperties => {
  const p = avance(frame, inicio, duracion)
  return { opacity: p, transform: `translateX(${(p - 1) * distancia}px)` }
}

/** Opacidad que sube y, si se da `fin`, baja al terminar. */
export const fundido = (frame: number, inicio: number, fin?: number, duracion = 12): number => {
  const entra = avance(frame, inicio, duracion, Easing.linear)
  if (fin === undefined) return entra
  return Math.min(entra, 1 - avance(frame, fin - duracion, duracion, Easing.linear))
}
