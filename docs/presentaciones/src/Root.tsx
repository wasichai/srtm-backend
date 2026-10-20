import React from 'react'
import { Composition, Folder } from 'remotion'
import { Presentacion } from './Presentacion.tsx'
import { VIDEOS } from './videos/index.ts'
import { FPS, fotogramasVideo } from './tiempo.ts'
import { LIENZO } from './tema/tokens.ts'

export const Raiz: React.FC = () => (
  <Folder name="Presentaciones">
    {VIDEOS.map((video) => (
      <Composition
        key={video.id}
        id={video.id}
        component={Presentacion}
        defaultProps={{ video }}
        durationInFrames={fotogramasVideo(video)}
        fps={FPS}
        width={LIENZO.ancho}
        height={LIENZO.alto}
      />
    ))}
  </Folder>
)
