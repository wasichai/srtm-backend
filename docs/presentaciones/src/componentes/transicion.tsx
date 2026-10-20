// El paso de una escena a otra: la que sale se desvanece y luego aparece la que entra, sobre el fondo blanco. Dos
// títulos nunca se superponen, como sí pasaría en un fundido cruzado.
import React from 'react'
import { AbsoluteFill } from 'remotion'
import type { TransitionPresentation, TransitionPresentationComponentProps } from '@remotion/transitions'

const Componente: React.FC<TransitionPresentationComponentProps<Record<string, never>>> = ({
  children,
  presentationDirection,
  presentationProgress,
}) => {
  const p = presentationProgress
  const opacidad = presentationDirection === 'exiting' ? Math.max(0, 1 - p * 2) : Math.max(0, p * 2 - 1)
  return <AbsoluteFill style={{ opacity: opacidad }}>{children}</AbsoluteFill>
}

export const porBlanco = (): TransitionPresentation<Record<string, never>> => ({ component: Componente, props: {} })
