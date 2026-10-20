// Un recorte fijo de una captura (o la captura entera), escalado para caber en una caja, sin cámara.
import React from 'react'
import { Img, staticFile } from 'remotion'
import type { Captura, Region } from '../tipos.ts'
import { COLOR, RADIO, SOMBRA } from '../tema/tokens.ts'
import { tamanoCaptura } from './Visor.tsx'

export const Recorte: React.FC<{
  archivo: Captura
  region?: Region
  anchoMax: number
  altoMax: number
  /** Nunca más grande que esto (las capturas son 2×). */
  escalaMax?: number
}> = ({ archivo, region, anchoMax, altoMax, escalaMax = 1.9 }) => {
  const img = tamanoCaptura(archivo)
  const r = region ?? { x: 0, y: 0, w: img.ancho, h: img.alto }
  const s = Math.min(escalaMax, anchoMax / r.w, altoMax / r.h)
  return (
    <div
      style={{
        position: 'relative',
        width: r.w * s,
        height: r.h * s,
        overflow: 'hidden',
        borderRadius: RADIO.medio,
        border: `1px solid ${COLOR.borde}`,
        boxShadow: SOMBRA.captura,
        backgroundColor: COLOR.superficie,
      }}
    >
      <Img
        src={staticFile(`capturas/${archivo}`)}
        style={{ position: 'absolute', left: -r.x * s, top: -r.y * s, width: img.ancho * s, height: img.alto * s }}
      />
    </div>
  )
}
