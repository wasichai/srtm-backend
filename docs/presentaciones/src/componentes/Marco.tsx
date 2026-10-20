// El marco común de las escenas: el fondo, la franja de la portada de los manuales, la cabecera (antetítulo y
// título), el pie con la fuente y la barra de avance del vídeo.
import React from 'react'
import { AbsoluteFill, useCurrentFrame } from 'remotion'
import { COLOR, MARGEN, TIPO, conAlfa } from '../tema/tokens.ts'
import { TEXTO, TITULOS } from '../tema/fuentes.ts'
import { citar } from '../manuales.ts'
import type { Fuente } from '../tipos.ts'
import { useEscena } from './contexto.tsx'
import { deslizar, entrar, fundido } from './animacion.ts'

/** La marca editorial de los manuales (recursos/marca/monograma.svg): un edificio público genérico. */
export const Monograma: React.FC<{ tam: number; invertido?: boolean }> = ({ tam, invertido = false }) => {
  const fondo = invertido ? '#FFFFFF' : COLOR.shell
  const figura = invertido ? COLOR.shell : '#FFFFFF'
  return (
    <svg width={tam} height={tam} viewBox="0 0 64 64" aria-hidden>
      <rect width="64" height="64" rx="10" fill={fondo} />
      <path d="M32 11 L52 21 L12 21 Z" fill={figura} />
      <rect x="12" y="23" width="40" height="3" fill={figura} />
      <rect x="15" y="28" width="5" height="17" fill={figura} />
      <rect x="24" y="28" width="5" height="17" fill={figura} />
      <rect x="35" y="28" width="5" height="17" fill={figura} />
      <rect x="44" y="28" width="5" height="17" fill={figura} />
      <rect x="11" y="47" width="42" height="3" fill={figura} />
      <rect x="8" y="52" width="48" height="3" fill={COLOR.filete} />
    </svg>
  )
}

/** La barra de avance del vídeo entero, con una marca en el comienzo de cada sección. */
export const BarraAvance: React.FC = () => {
  const frame = useCurrentFrame()
  const { inicio, total, secciones } = useEscena()
  const p = Math.min(1, (inicio + frame) / Math.max(1, total - 1))
  return (
    <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: 6, backgroundColor: conAlfa(COLOR.filete, 0.35) }}>
      <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: `${p * 100}%`, backgroundColor: COLOR.shell }} />
      {secciones.map((s) => (
        <div
          key={s}
          style={{
            position: 'absolute',
            top: 0,
            bottom: 0,
            left: `${(s / total) * 100}%`,
            width: 3,
            backgroundColor: COLOR.superficie,
          }}
        />
      ))}
    </div>
  )
}

export const Pie: React.FC<{ fuente?: Fuente; claro?: boolean }> = ({ fuente, claro = false }) => {
  const frame = useCurrentFrame()
  const { video } = useEscena()
  const color = claro ? conAlfa('#FFFFFF', 0.82) : COLOR.tintaTenue
  return (
    <div
      style={{
        position: 'absolute',
        left: MARGEN.x,
        right: MARGEN.x,
        bottom: 26,
        height: 34,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 40,
        fontFamily: TITULOS,
        fontSize: TIPO.pie,
        color,
        opacity: fundido(frame, 4, undefined, 18),
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 14, whiteSpace: 'nowrap' }}>
        <Monograma tam={30} invertido={claro} />
        <span style={{ fontWeight: 600 }}>Rentas municipales</span>
        <span style={{ opacity: 0.6 }}>·</span>
        <span>{video.corto}</span>
      </div>
      {fuente ? (
        <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', textAlign: 'right' }}>
          <span style={{ opacity: 0.75 }}>Fuente: </span>
          {citar(fuente)}
        </div>
      ) : null}
    </div>
  )
}

export const Antetitulo: React.FC<{ texto: string; inicio?: number; color?: string }> = ({
  texto,
  inicio = 0,
  color = COLOR.marcaFuerte,
}) => {
  const frame = useCurrentFrame()
  return (
    <div
      style={{
        fontFamily: TITULOS,
        fontWeight: 700,
        fontSize: TIPO.rotulo,
        letterSpacing: '0.12em',
        textTransform: 'uppercase',
        color,
        display: 'flex',
        alignItems: 'center',
        gap: 14,
        ...deslizar(frame, inicio, 16, 16),
      }}
    >
      <span style={{ width: 28, height: 4, backgroundColor: COLOR.filete, display: 'inline-block' }} />
      {texto}
    </div>
  )
}

export const Titulo: React.FC<{ texto: string; inicio?: number; tam?: number }> = ({
  texto,
  inicio = 3,
  tam = TIPO.titulo,
}) => {
  const frame = useCurrentFrame()
  return (
    <div
      style={{
        fontFamily: TITULOS,
        fontWeight: 700,
        fontSize: tam,
        lineHeight: 1.08,
        letterSpacing: '-0.01em',
        color: COLOR.tinta,
        marginTop: 14,
        maxWidth: 1600,
        ...entrar(frame, inicio, 20, 20),
      }}
    >
      {texto}
    </div>
  )
}

export const Entrada: React.FC<{ texto: string; inicio?: number; ancho?: number }> = ({
  texto,
  inicio = 10,
  ancho = 1500,
}) => {
  const frame = useCurrentFrame()
  return (
    <div
      style={{
        fontFamily: TEXTO,
        fontSize: TIPO.cuerpo,
        lineHeight: 1.42,
        color: COLOR.tintaTenue,
        maxWidth: ancho,
        marginTop: 18,
        ...entrar(frame, inicio, 14, 18),
      }}
    >
      {texto}
    </div>
  )
}

/**
 * Fondo, franja, cabecera, contenido y pie. `children` ocupa el área bajo la cabecera, de MARGEN.x a MARGEN.x y hasta
 * el pie.
 */
export const Marco: React.FC<{
  antetitulo?: string
  titulo?: string
  entrada?: string
  fuente?: Fuente
  fondo?: string
  /** Centra el contenido en el alto disponible (cuando no lo llena). */
  centrar?: boolean
  children: React.ReactNode
}> = ({ antetitulo, titulo, entrada, fuente, fondo = COLOR.superficie, centrar = false, children }) => {
  return (
    <AbsoluteFill style={{ backgroundColor: fondo, color: COLOR.tinta }}>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 0, height: 10, backgroundColor: COLOR.shell }} />
      <div style={{ position: 'absolute', left: 0, right: 0, top: 10, height: 3, backgroundColor: COLOR.filete }} />
      <div
        style={{
          position: 'absolute',
          left: MARGEN.x,
          right: MARGEN.x,
          top: 64,
          bottom: MARGEN.abajo,
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        {antetitulo || titulo ? (
          <div style={{ marginBottom: 34 }}>
            {antetitulo ? <Antetitulo texto={antetitulo} /> : null}
            {titulo ? <Titulo texto={titulo} /> : null}
            {entrada ? <Entrada texto={entrada} /> : null}
          </div>
        ) : null}
        <div
          style={{
            flex: 1,
            minHeight: 0,
            position: 'relative',
            display: 'flex',
            flexDirection: 'column',
            justifyContent: centrar ? 'center' : 'flex-start',
            paddingBottom: centrar ? 30 : 0,
          }}
        >
          {children}
        </div>
      </div>
      <Pie fuente={fuente} />
      <BarraAvance />
    </AbsoluteFill>
  )
}
