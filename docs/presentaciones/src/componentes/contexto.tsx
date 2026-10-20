// Lo que cada escena sabe del vídeo en que está: para el pie (título corto, fuente) y la barra de avance.
import React, { createContext, useContext } from 'react'
import type { Video } from '../tipos.ts'

export type ContextoEscena = {
  readonly video: Video
  /** Fotograma del vídeo en que empieza la escena. */
  readonly inicio: number
  /** Fotogramas del vídeo entero. */
  readonly total: number
  /** Fotogramas en que empieza cada sección (las escenas de tipo seccion), para marcarlas en la barra. */
  readonly secciones: readonly number[]
}

const Contexto = createContext<ContextoEscena | null>(null)

export const ProveedorEscena: React.FC<{ valor: ContextoEscena; children: React.ReactNode }> = ({
  valor,
  children,
}) => <Contexto.Provider value={valor}>{children}</Contexto.Provider>

export const useEscena = (): ContextoEscena => {
  const valor = useContext(Contexto)
  if (!valor) throw new Error('una escena se dibuja dentro de ProveedorEscena')
  return valor
}
