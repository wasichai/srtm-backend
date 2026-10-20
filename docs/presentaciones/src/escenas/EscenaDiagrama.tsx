// Diagramas de cajas y flechas que se construyen por pasos, y diagramas de secuencia. Redibujan los diagramas de los
// manuales (recursos/diagramas/*.mmd) con los mismos nodos y relaciones, para poder revelarlos de a poco.
import React from 'react'
import { useCurrentFrame, useVideoConfig } from 'remotion'
import type { Arista, Diagrama, Nodo, Secuencia } from '../tipos.ts'
import { COLOR, RADIO, SOMBRA, TIPO, conAlfa } from '../tema/tokens.ts'
import { CODIGO, TEXTO, TITULOS } from '../tema/fuentes.ts'
import { Marco } from '../componentes/Marco.tsx'
import { avance, entrar, fundido } from '../componentes/animacion.ts'
import { Icono } from '../componentes/iconos.tsx'
import { ENTRADA_DIAGRAMA, ENTRADA_SECUENCIA } from '../tiempo.ts'
import { Nota } from './EscenasEstructura.tsx'

type Lado = 'derecha' | 'izquierda' | 'arriba' | 'abajo'
type Punto = { x: number; y: number }

const ancla = (n: Nodo, lado: Lado): Punto => {
  switch (lado) {
    case 'derecha':
      return { x: n.x + n.w, y: n.y + n.h / 2 }
    case 'izquierda':
      return { x: n.x, y: n.y + n.h / 2 }
    case 'arriba':
      return { x: n.x + n.w / 2, y: n.y }
    case 'abajo':
      return { x: n.x + n.w / 2, y: n.y + n.h }
  }
}

const lados = (a: Nodo, b: Nodo): [Lado, Lado] => {
  if (b.x >= a.x + a.w - 1) return ['derecha', 'izquierda']
  if (b.x + b.w <= a.x + 1) return ['izquierda', 'derecha']
  return b.y > a.y ? ['abajo', 'arriba'] : ['arriba', 'abajo']
}

const horizontal = (l: Lado): boolean => l === 'derecha' || l === 'izquierda'

/** El camino ortogonal de una arista y el punto donde va su rótulo. */
export const trazar = (a: Nodo, b: Nodo, arista: Arista): { d: string; rotulo: Punto } => {
  if (a.id === b.id) {
    // un bucle sobre el nodo: una transición que vuelve al mismo estado
    const x1 = a.x + a.w * 0.3
    const x2 = a.x + a.w * 0.7
    const y = a.y
    return { d: `M ${x1} ${y} C ${x1} ${y - 70}, ${x2} ${y - 70}, ${x2} ${y}`, rotulo: { x: a.x + a.w / 2, y: y - 66 } }
  }
  const [ls, ll] = lados(a, b)
  const sale = arista.salida ?? ls
  const llega = arista.llegada ?? ll
  const s = ancla(a, sale)
  const e = ancla(b, llega)
  let d: string
  let rotulo: Punto
  if (horizontal(sale) && horizontal(llega)) {
    const mx = (s.x + e.x) / 2
    d = Math.abs(s.y - e.y) < 2 ? `M ${s.x} ${s.y} L ${e.x} ${e.y}` : `M ${s.x} ${s.y} H ${mx} V ${e.y} H ${e.x}`
    rotulo = { x: mx, y: Math.abs(s.y - e.y) < 2 ? s.y : (s.y + e.y) / 2 }
  } else if (!horizontal(sale) && !horizontal(llega)) {
    const my = (s.y + e.y) / 2
    d = Math.abs(s.x - e.x) < 2 ? `M ${s.x} ${s.y} L ${e.x} ${e.y}` : `M ${s.x} ${s.y} V ${my} H ${e.x} V ${e.y}`
    rotulo = { x: (s.x + e.x) / 2, y: my }
  } else if (horizontal(sale)) {
    d = `M ${s.x} ${s.y} H ${e.x} V ${e.y}`
    rotulo = { x: (s.x + e.x) / 2, y: s.y }
  } else {
    d = `M ${s.x} ${s.y} V ${e.y} H ${e.x}`
    rotulo = { x: s.x, y: (s.y + e.y) / 2 }
  }
  return { d, rotulo: { x: rotulo.x + (arista.rotuloDx ?? 0), y: rotulo.y + (arista.rotuloDy ?? 0) } }
}

const estiloNodo = (n: Nodo): React.CSSProperties => {
  switch (n.tipo) {
    case 'dato':
      return { backgroundColor: COLOR.papel, border: `2px solid #8A8A8A`, borderRadius: RADIO.grande }
    case 'persona':
      return { backgroundColor: COLOR.marcaSuave, border: `2px solid ${COLOR.marca}`, borderRadius: RADIO.medio }
    case 'externo':
      return { backgroundColor: COLOR.superficie, border: `2px dashed #8A8A8A`, borderRadius: RADIO.medio }
    case 'fuera':
      return { backgroundColor: COLOR.superficie, border: `2px dashed ${COLOR.borde}`, borderRadius: RADIO.medio }
    case 'estado':
      return { backgroundColor: COLOR.superficie, border: `3px solid ${COLOR.shell}`, borderRadius: 999 }
    case 'clave':
      return { backgroundColor: COLOR.shell, border: `2px solid ${COLOR.shell}`, borderRadius: RADIO.medio, boxShadow: SOMBRA.tarjeta }
    case 'grupo':
      return { backgroundColor: conAlfa(COLOR.marcaSuave, 0.6), border: `2px dashed ${COLOR.filete}`, borderRadius: RADIO.grande }
    default:
      return { backgroundColor: COLOR.superficie, border: `2px solid ${COLOR.marca}`, borderRadius: RADIO.medio, boxShadow: SOMBRA.tarjeta }
  }
}

const CajaNodo: React.FC<{ n: Nodo; visible: number; nuevo: number }> = ({ n, visible, nuevo }) => {
  const grupo = n.tipo === 'grupo'
  const claro = n.tipo === 'clave'
  const tenue = n.tipo === 'fuera'
  const compacto = n.h < 90
  return (
    <div
      style={{
        position: 'absolute',
        left: n.x,
        top: n.y,
        width: n.w,
        height: n.h,
        display: 'flex',
        flexDirection: grupo ? 'column' : 'row',
        alignItems: grupo ? 'flex-start' : 'center',
        justifyContent: grupo ? 'flex-start' : 'center',
        gap: 14,
        padding: grupo ? '14px 20px' : '10px 18px',
        textAlign: grupo ? 'left' : 'center',
        opacity: visible,
        transform: `scale(${0.94 + 0.06 * visible})`,
        outline: nuevo > 0 ? `4px solid ${conAlfa(COLOR.foco, 0.55 * nuevo)}` : undefined,
        outlineOffset: 6,
        ...estiloNodo(n),
      }}
    >
      {n.icono && !grupo ? <Icono nombre={n.icono} tam={compacto ? 28 : 34} color={claro ? '#FFFFFF' : COLOR.marca} /> : null}
      <div>
        <div
          style={{
            fontFamily: n.tipo === 'estado' ? CODIGO : TITULOS,
            fontWeight: 700,
            fontSize: grupo ? 22 : compacto ? 24 : 27,
            letterSpacing: grupo ? '0.08em' : undefined,
            textTransform: grupo ? 'uppercase' : undefined,
            lineHeight: 1.15,
            color: claro ? '#FFFFFF' : grupo ? COLOR.marcaFuerte : tenue ? COLOR.tintaTenue : COLOR.tinta,
          }}
        >
          {n.titulo}
        </div>
        {n.texto ? (
          <div
            style={{
              fontFamily: TEXTO,
              fontSize: compacto ? 20 : 22,
              lineHeight: 1.3,
              marginTop: 5,
              color: claro ? conAlfa('#FFFFFF', 0.88) : COLOR.tintaTenue,
            }}
          >
            {n.texto}
          </div>
        ) : null}
      </div>
    </div>
  )
}

const ALTO_PASOS = 112

export const EscenaDiagrama: React.FC<{ escena: Diagrama; duracion: number }> = ({ escena, duracion }) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()
  const conPasos = Boolean(escena.pasos?.length)

  // inicio de cada paso: el paso 0 es el dibujo inicial
  const inicios: number[] = [0]
  let t = Math.round(ENTRADA_DIAGRAMA * fps)
  for (const p of escena.pasos ?? []) {
    inicios.push(t)
    t += Math.round(p.segundos * fps)
  }
  let actual = 0
  inicios.forEach((ini, i) => {
    if (frame >= ini) actual = i
  })

  const disponibleAlto = 760 - (escena.entrada ? 70 : 0) - (conPasos ? ALTO_PASOS + 20 : 0) - (escena.nota ? 60 : 0)
  const escala = Math.min(1, 1680 / escena.lienzo.ancho, disponibleAlto / escena.lienzo.alto)
  const porId = new Map(escena.nodos.map((n) => [n.id, n]))

  // sin pasos, todo aparece escalonado en el primer tercio
  const orden = new Map<string, number>()
  escena.nodos.forEach((n, i) => orden.set(n.id, i))
  const aparicionNodo = (n: Nodo): number =>
    conPasos ? inicios[n.paso ?? 0] + (n.paso ? 6 : 10 + (orden.get(n.id) ?? 0) * 3) : 12 + (orden.get(n.id) ?? 0) * Math.min(8, (duracion * 0.25) / escena.nodos.length)
  const aparicionArista = (a: Arista, i: number): number => {
    if (conPasos) {
      const p = a.paso ?? Math.max(porId.get(a.de)?.paso ?? 0, porId.get(a.a)?.paso ?? 0)
      return inicios[p] + (p ? 16 : 24 + i * 3)
    }
    return 12 + escena.nodos.length * Math.min(8, (duracion * 0.25) / escena.nodos.length) + i * 4
  }
  const pasoDe = (n: Nodo): number => n.paso ?? 0

  const grupos = escena.nodos.filter((n) => n.tipo === 'grupo')
  const nodos = escena.nodos.filter((n) => n.tipo !== 'grupo')

  return (
    <Marco antetitulo={escena.antetitulo} titulo={escena.titulo} entrada={escena.entrada} fuente={escena.fuentes[0]}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'flex-start' }}>
        <div style={{ flex: 1, alignSelf: 'stretch', display: 'flex', alignItems: 'center', justifyContent: 'center', paddingBottom: conPasos ? 24 : 0 }}>
        <div style={{ width: escena.lienzo.ancho * escala, height: escena.lienzo.alto * escala, position: 'relative' }}>
          <div
            style={{
              position: 'absolute',
              left: 0,
              top: 0,
              width: escena.lienzo.ancho,
              height: escena.lienzo.alto,
              transform: `scale(${escala})`,
              transformOrigin: '0 0',
            }}
          >
            {grupos.map((n) => (
              <CajaNodo key={n.id} n={n} visible={avance(frame, aparicionNodo(n), 16)} nuevo={0} />
            ))}
            <svg width={escena.lienzo.ancho} height={escena.lienzo.alto} style={{ position: 'absolute', left: 0, top: 0, overflow: 'visible' }}>
              <defs>
                <marker id="punta" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" fill={COLOR.tintaTenue} />
                </marker>
              </defs>
              {escena.aristas.map((a, i) => {
                const de = porId.get(a.de)
                const al = porId.get(a.a)
                if (!de || !al) return null
                const { d } = trazar(de, al, a)
                const ini = aparicionArista(a, i)
                const p = avance(frame, ini, 20)
                return (
                  <path
                    key={`${a.de}-${a.a}-${i}`}
                    d={d}
                    fill="none"
                    stroke={COLOR.tintaTenue}
                    strokeWidth={3}
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    markerEnd={p > 0.95 ? 'url(#punta)' : undefined}
                    pathLength={1}
                    strokeDasharray={a.discontinua ? '0.012 0.012' : 1}
                    strokeDashoffset={a.discontinua ? 0 : 1 - p}
                    opacity={a.discontinua ? p : 1}
                  />
                )
              })}
            </svg>
            {nodos.map((n) => {
              const ini = aparicionNodo(n)
              const nuevo = conPasos && pasoDe(n) > 0 && pasoDe(n) === actual ? fundido(frame, ini, Math.min(ini + 50, inicios[actual + 1] ?? duracion), 12) : 0
              return <CajaNodo key={n.id} n={n} visible={avance(frame, ini, 16)} nuevo={nuevo} />
            })}
            {escena.aristas.map((a, i) => {
              const de = porId.get(a.de)
              const al = porId.get(a.a)
              if (!de || !al || !a.rotulo) return null
              const { rotulo } = trazar(de, al, a)
              return (
                <div
                  key={`r-${i}`}
                  style={{
                    position: 'absolute',
                    left: rotulo.x,
                    top: rotulo.y,
                    transform: 'translate(-50%, -50%)',
                    padding: '4px 12px',
                    borderRadius: RADIO.chico,
                    backgroundColor: COLOR.superficie,
                    border: `1px solid ${COLOR.borde}`,
                    fontFamily: TITULOS,
                    fontSize: 21,
                    color: COLOR.tintaTenue,
                    whiteSpace: 'nowrap',
                    opacity: avance(frame, aparicionArista(a, i) + 12, 12),
                  }}
                >
                  {a.rotulo}
                </div>
              )
            })}
          </div>
        </div>
        </div>
        {conPasos ? (
          <div
            style={{
              marginTop: 'auto',
              alignSelf: 'stretch',
              minHeight: ALTO_PASOS,
              display: 'flex',
              alignItems: 'center',
              gap: 26,
              padding: '18px 30px',
              backgroundColor: COLOR.marcaSuave,
              borderLeft: `6px solid ${COLOR.foco}`,
              borderRadius: RADIO.medio,
              opacity: actual > 0 ? 1 : 0,
            }}
          >
            <div style={{ fontFamily: TITULOS, fontWeight: 700, fontSize: 22, letterSpacing: '0.1em', color: COLOR.marcaFuerte, whiteSpace: 'nowrap' }}>
              {Math.max(1, actual)} / {escena.pasos?.length}
            </div>
            <div
              key={actual}
              style={{ fontFamily: TEXTO, fontSize: TIPO.cuerpo, lineHeight: 1.38, color: COLOR.tinta, ...entrar(frame, inicios[actual], 8, 14) }}
            >
              {escena.pasos?.[Math.max(0, actual - 1)].texto}
            </div>
          </div>
        ) : null}
        {escena.nota && !conPasos ? (
          <div style={{ alignSelf: 'stretch' }}>
            <Nota texto={escena.nota} inicio={Math.round(duracion * 0.4)} />
          </div>
        ) : null}
      </div>
    </Marco>
  )
}

export const EscenaSecuencia: React.FC<{ escena: Secuencia }> = ({ escena }) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()
  const n = escena.participantes.length
  const ancho = 1680
  const col = ancho / n
  const xDe = new Map(escena.participantes.map((p, i) => [p.id, col * i + col / 2]))
  const altoCabeza = 96
  const top = altoCabeza + 26
  const altoFila = Math.min(64, (560 - top) / Math.max(1, escena.mensajes.length) + 10)
  const porMensaje = Math.round(escena.segundosPorMensaje * fps)
  const inicio0 = Math.round(ENTRADA_SECUENCIA * fps)
  let actual = -1
  escena.mensajes.forEach((_, i) => {
    if (frame >= inicio0 + i * porMensaje) actual = i
  })
  const altoLienzo = top + escena.mensajes.length * altoFila + 20

  return (
    <Marco antetitulo={escena.antetitulo} titulo={escena.titulo} fuente={escena.fuentes[0]}>
      <div style={{ position: 'relative', width: ancho, height: altoLienzo }}>
        {escena.participantes.map((p, i) => (
          <div
            key={p.id}
            style={{
              position: 'absolute',
              left: col * i + 10,
              top: 0,
              width: col - 20,
              height: altoCabeza,
              backgroundColor: COLOR.superficie,
              border: `2px solid ${COLOR.marca}`,
              borderRadius: RADIO.medio,
              boxShadow: SOMBRA.tarjeta,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              textAlign: 'center',
              padding: '6px 10px',
              ...entrar(frame, 8 + i * 4, 10, 14),
            }}
          >
            <div style={{ fontFamily: CODIGO, fontWeight: 600, fontSize: 21, color: COLOR.tinta, lineHeight: 1.2 }}>{p.titulo}</div>
            {p.texto ? <div style={{ fontFamily: TEXTO, fontSize: 19, color: COLOR.tintaTenue, marginTop: 4, lineHeight: 1.2 }}>{p.texto}</div> : null}
          </div>
        ))}
        <svg width={ancho} height={altoLienzo} style={{ position: 'absolute', left: 0, top: 0, overflow: 'visible' }}>
          <defs>
            <marker id="punta-sec" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" fill={COLOR.marcaOscura} />
            </marker>
          </defs>
          {escena.participantes.map((p) => (
            <line key={p.id} x1={xDe.get(p.id)} x2={xDe.get(p.id)} y1={altoCabeza} y2={altoLienzo} stroke={COLOR.bordeTabla} strokeWidth={2} strokeDasharray="6 8" />
          ))}
          {escena.mensajes.map((m, i) => {
            const ini = inicio0 + i * porMensaje
            const p = avance(frame, ini, 16)
            const y = top + i * altoFila + altoFila / 2
            const x1 = xDe.get(m.de) ?? 0
            const x2 = xDe.get(m.a) ?? 0
            const activo = i === actual
            const color = activo ? COLOR.foco : COLOR.marcaOscura
            if (m.de === m.a) {
              return (
                <path
                  key={i}
                  d={`M ${x1} ${y - 14} H ${x1 + 60} V ${y + 14} H ${x1 + 6}`}
                  fill="none"
                  stroke={color}
                  strokeWidth={activo ? 3.5 : 2.5}
                  markerEnd="url(#punta-sec)"
                  opacity={p}
                />
              )
            }
            return (
              <line
                key={i}
                x1={x1}
                x2={x1 + (x2 - x1) * p}
                y1={y}
                y2={y}
                stroke={color}
                strokeWidth={activo ? 3.5 : 2.5}
                markerEnd={p > 0.95 ? 'url(#punta-sec)' : undefined}
                opacity={p > 0 ? 1 : 0}
              />
            )
          })}
        </svg>
        {escena.mensajes.map((m, i) => {
          const ini = inicio0 + i * porMensaje
          const y = top + i * altoFila + altoFila / 2
          const x1 = xDe.get(m.de) ?? 0
          const x2 = xDe.get(m.a) ?? 0
          const propio = m.de === m.a
          const izquierda = propio ? x1 + 74 : Math.min(x1, x2) + 14
          const activo = i === actual
          return (
            <div
              key={i}
              style={{
                position: 'absolute',
                left: izquierda,
                top: y - (propio ? 16 : 36),
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                fontFamily: TITULOS,
                fontWeight: activo ? 700 : 600,
                fontSize: 22,
                color: activo ? COLOR.tinta : COLOR.tintaTenue,
                whiteSpace: 'nowrap',
                opacity: avance(frame, ini + 6, 12),
              }}
            >
              <span
                style={{
                  width: 30,
                  height: 30,
                  borderRadius: 15,
                  backgroundColor: activo ? COLOR.foco : COLOR.marca,
                  color: '#FFFFFF',
                  fontSize: 18,
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                {i + 1}
              </span>
              {m.texto}
            </div>
          )
        })}
      </div>
      <div
        style={{
          marginTop: 'auto',
          minHeight: 104,
          display: 'flex',
          alignItems: 'center',
          gap: 26,
          padding: '16px 30px',
          backgroundColor: COLOR.marcaSuave,
          borderLeft: `6px solid ${COLOR.foco}`,
          borderRadius: RADIO.medio,
          opacity: actual >= 0 ? 1 : 0,
        }}
      >
        <div style={{ fontFamily: TITULOS, fontWeight: 700, fontSize: 22, letterSpacing: '0.1em', color: COLOR.marcaFuerte, whiteSpace: 'nowrap' }}>
          {Math.max(1, actual + 1)} / {escena.mensajes.length}
        </div>
        <div key={actual} style={{ fontFamily: TEXTO, fontSize: 28, lineHeight: 1.38, color: COLOR.tinta, ...entrar(frame, inicio0 + Math.max(0, actual) * porMensaje, 8, 14) }}>
          {escena.mensajes[Math.max(0, actual)]?.detalle ?? escena.mensajes[Math.max(0, actual)]?.texto}
        </div>
      </div>
    </Marco>
  )
}
