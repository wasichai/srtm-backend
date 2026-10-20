// Una presentación: sus escenas en orden, unidas por un fundido corto a blanco. Cada escena es un
// TransitionSeries.Sequence, así su duración se ve y se ajusta en la línea de tiempo de Remotion Studio.
import React from 'react'
import { AbsoluteFill } from 'remotion'
import { TransitionSeries, linearTiming } from '@remotion/transitions'
import type { Video } from './tipos.ts'
import { COLOR } from './tema/tokens.ts'
import { TITULOS, cargarFuentes } from './tema/fuentes.ts'
import { DibujarEscena } from './escenas/index.tsx'
import { ProveedorEscena } from './componentes/contexto.tsx'
import { porBlanco } from './componentes/transicion.tsx'
import { TRANSICION, fotogramasEscena, fotogramasVideo, iniciosEscenas } from './tiempo.ts'

cargarFuentes()

export const Presentacion: React.FC<{ video: Video }> = ({ video }) => {
  const total = fotogramasVideo(video)
  const inicios = iniciosEscenas(video)
  const secciones = video.escenas.flatMap((e, i) => (e.tipo === 'seccion' ? [inicios[i]] : []))
  return (
    <AbsoluteFill style={{ backgroundColor: COLOR.superficie, fontFamily: TITULOS }}>
      <TransitionSeries>
        {video.escenas.flatMap((escena, i) => {
          const duracion = fotogramasEscena(escena)
          const secuencia = (
            <TransitionSeries.Sequence key={escena.id} durationInFrames={duracion} name={`${i + 1}. ${escena.id}`}>
              <ProveedorEscena valor={{ video, inicio: inicios[i], total, secciones }}>
                <DibujarEscena escena={escena} duracion={duracion} />
              </ProveedorEscena>
            </TransitionSeries.Sequence>
          )
          if (i === 0) return [secuencia]
          return [
            <TransitionSeries.Transition key={`t-${escena.id}`} presentation={porBlanco()} timing={linearTiming({ durationInFrames: TRANSICION })} />,
            secuencia,
          ]
        })}
      </TransitionSeries>
    </AbsoluteFill>
  )
}
