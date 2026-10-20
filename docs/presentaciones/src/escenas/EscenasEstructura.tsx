// Las escenas que ordenan información: tarjetas, procesos, comparaciones, tablas, cálculos y comandos.
import React from 'react'
import { useCurrentFrame } from 'remotion'
import type { Calculo, Codigo, Comparacion, Proceso, Tabla, Tarjetas, Tono } from '../tipos.ts'
import { COLOR, RADIO, SOMBRA, TIPO, conAlfa } from '../tema/tokens.ts'
import { CODIGO, TEXTO, TITULOS } from '../tema/fuentes.ts'
import { Marco } from '../componentes/Marco.tsx'
import { avance, entrar } from '../componentes/animacion.ts'
import { Icono } from '../componentes/iconos.tsx'

export const colorTono = (t: Tono | undefined): string =>
  t === 'exito' ? COLOR.exito : t === 'aviso' ? COLOR.aviso : t === 'peligro' ? COLOR.peligro : t === 'neutro' ? COLOR.tintaTenue : COLOR.marca

const fondoTono = (t: Tono | undefined): string =>
  t === 'exito' ? '#F6FAF5' : t === 'aviso' ? '#FCF8EF' : t === 'peligro' ? '#FCF5F5' : t === 'neutro' ? COLOR.papel : COLOR.marcaSuave

/** Reparte la aparición de n elementos en una fracción del tiempo de la escena. */
const escalon = (n: number, duracion: number, fraccion = 0.35, maximo = 12): number =>
  n <= 1 ? 0 : Math.min(maximo, (duracion * fraccion) / (n - 1))

const Rotulo: React.FC<{ texto: string; color?: string }> = ({ texto, color = COLOR.marcaFuerte }) => (
  <div style={{ fontFamily: TITULOS, fontWeight: 700, fontSize: 20, letterSpacing: '0.1em', textTransform: 'uppercase', color }}>{texto}</div>
)

export const EscenaTarjetas: React.FC<{ escena: Tarjetas; duracion: number }> = ({ escena, duracion }) => {
  const frame = useCurrentFrame()
  const paso = escalon(escena.tarjetas.length, duracion)
  const compacta = escena.columnas >= 4 || escena.tarjetas.length > 6
  return (
    <Marco antetitulo={escena.antetitulo} titulo={escena.titulo} entrada={escena.entrada} fuente={escena.fuentes[0]} centrar>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: `repeat(${escena.columnas}, 1fr)`,
          gap: compacta ? 22 : 30,
          alignContent: 'start',
        }}
      >
        {escena.tarjetas.map((t, i) => {
          const color = colorTono(t.tono)
          return (
            <div
              key={t.titulo}
              style={{
                backgroundColor: COLOR.superficie,
                border: `1px solid ${COLOR.borde}`,
                borderTop: `5px solid ${color}`,
                borderRadius: RADIO.medio,
                boxShadow: SOMBRA.tarjeta,
                padding: compacta ? '22px 24px' : '28px 30px',
                display: 'flex',
                flexDirection: 'column',
                gap: 10,
                ...entrar(frame, 16 + i * paso, 16, 18),
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
                {t.icono ? (
                  <div
                    style={{
                      flex: '0 0 auto',
                      width: compacta ? 50 : 58,
                      height: compacta ? 50 : 58,
                      borderRadius: 999,
                      backgroundColor: fondoTono(t.tono),
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                    }}
                  >
                    <Icono nombre={t.icono} tam={compacta ? 26 : 30} color={color} />
                  </div>
                ) : null}
                <div>
                  {t.rotulo ? <Rotulo texto={t.rotulo} color={color} /> : null}
                  <div style={{ fontFamily: TITULOS, fontWeight: 700, fontSize: compacta ? 27 : 31, lineHeight: 1.18, color: COLOR.tinta }}>
                    {t.titulo}
                  </div>
                </div>
              </div>
              {t.texto ? (
                <div style={{ fontFamily: TEXTO, fontSize: compacta ? 23 : 26, lineHeight: 1.42, color: COLOR.tintaTenue }}>{t.texto}</div>
              ) : null}
            </div>
          )
        })}
      </div>
      {escena.nota ? <Nota texto={escena.nota} inicio={16 + escena.tarjetas.length * paso + 10} /> : null}
    </Marco>
  )
}

export const Nota: React.FC<{ texto: string; inicio: number }> = ({ texto, inicio }) => {
  const frame = useCurrentFrame()
  return (
    <div
      style={{
        marginTop: 40,
        paddingTop: 18,
        borderTop: `1px solid ${COLOR.borde}`,
        fontFamily: TEXTO,
        fontStyle: 'italic',
        fontSize: TIPO.apoyo,
        lineHeight: 1.4,
        color: COLOR.tintaTenue,
        ...entrar(frame, inicio, 8, 16),
      }}
    >
      {texto}
    </div>
  )
}

const Numero: React.FC<{ n: number; tam?: number; color?: string }> = ({ n, tam = 46, color = COLOR.shell }) => (
  <div
    style={{
      flex: '0 0 auto',
      width: tam,
      height: tam,
      borderRadius: tam / 2,
      backgroundColor: color,
      color: '#FFFFFF',
      fontFamily: TITULOS,
      fontWeight: 700,
      fontSize: tam * 0.5,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
    }}
  >
    {n}
  </div>
)

export const EscenaProceso: React.FC<{ escena: Proceso; duracion: number }> = ({ escena, duracion }) => {
  const frame = useCurrentFrame()
  const n = escena.pasos.length
  const paso = escalon(n, duracion, 0.5, 40)
  const horizontal = n <= 5
  return (
    <Marco antetitulo={escena.antetitulo} titulo={escena.titulo} entrada={escena.entrada} fuente={escena.fuentes[0]} centrar>
      {horizontal ? (
        <div style={{ display: 'flex', alignItems: 'stretch', gap: 0, marginTop: 10 }}>
          {escena.pasos.map((p, i) => {
            const color = colorTono(p.tono)
            const inicio = 18 + i * paso
            return (
              <React.Fragment key={p.titulo}>
                {i > 0 ? (
                  <div style={{ flex: '0 0 54px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                    <svg width="40" height="40" viewBox="0 0 40 40" style={{ opacity: avance(frame, inicio - 6, 12) }}>
                      <path d="M8 20 H30 M22 11 L31 20 L22 29" stroke={COLOR.filete} strokeWidth="4" fill="none" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  </div>
                ) : null}
                <div
                  style={{
                    flex: 1,
                    backgroundColor: COLOR.superficie,
                    border: `1px solid ${COLOR.borde}`,
                    borderRadius: RADIO.medio,
                    boxShadow: SOMBRA.tarjeta,
                    padding: '26px 24px',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 14,
                    ...entrar(frame, inicio, 18, 18),
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <Numero n={i + 1} color={color} />
                    {p.icono ? <Icono nombre={p.icono} tam={34} color={color} /> : null}
                  </div>
                  <div style={{ fontFamily: TITULOS, fontWeight: 700, fontSize: 29, lineHeight: 1.18, color: COLOR.tinta }}>{p.titulo}</div>
                  {p.texto ? <div style={{ fontFamily: TEXTO, fontSize: 24, lineHeight: 1.42, color: COLOR.tintaTenue }}>{p.texto}</div> : null}
                  {p.quien ? (
                    <div style={{ marginTop: 'auto', paddingTop: 10, borderTop: `1px solid ${COLOR.borde}` }}>
                      <Rotulo texto={p.quien} color={COLOR.tintaTenue} />
                    </div>
                  ) : null}
                </div>
              </React.Fragment>
            )
          })}
        </div>
      ) : (
        <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', gap: n > 7 ? 10 : 16, marginTop: 4 }}>
          <div
            style={{
              position: 'absolute',
              left: 21,
              top: 20,
              bottom: 20,
              width: 4,
              backgroundColor: COLOR.filete,
              transformOrigin: 'top',
              transform: `scaleY(${avance(frame, 14, Math.max(20, paso * n))})`,
            }}
          />
          {escena.pasos.map((p, i) => {
            const color = colorTono(p.tono)
            return (
              <div key={p.titulo} style={{ display: 'flex', alignItems: 'center', gap: 26, ...entrar(frame, 18 + i * paso, 12, 16) }}>
                <Numero n={i + 1} color={color} />
                <div
                  style={{
                    flex: 1,
                    display: 'flex',
                    alignItems: 'baseline',
                    gap: 24,
                    padding: '14px 24px',
                    backgroundColor: i % 2 ? COLOR.superficie : COLOR.franja,
                    border: `1px solid ${COLOR.borde}`,
                    borderRadius: RADIO.chico,
                  }}
                >
                  <div style={{ flex: '0 0 auto', maxWidth: 560, fontFamily: TITULOS, fontWeight: 700, fontSize: 27, color: COLOR.tinta }}>{p.titulo}</div>
                  {p.texto ? <div style={{ flex: 1, fontFamily: TEXTO, fontSize: 24, lineHeight: 1.35, color: COLOR.tintaTenue }}>{p.texto}</div> : <div style={{ flex: 1 }} />}
                  {p.quien ? <Rotulo texto={p.quien} color={COLOR.tintaTenue} /> : null}
                </div>
              </div>
            )
          })}
        </div>
      )}
      {escena.nota ? <Nota texto={escena.nota} inicio={18 + n * paso + 6} /> : null}
    </Marco>
  )
}

export const EscenaComparacion: React.FC<{ escena: Comparacion; duracion: number }> = ({ escena, duracion }) => {
  const frame = useCurrentFrame()
  const total = escena.columnas[0].puntos.length + escena.columnas[1].puntos.length
  const paso = escalon(total, duracion, 0.45, 14)
  let k = 0
  return (
    <Marco antetitulo={escena.antetitulo} titulo={escena.titulo} entrada={escena.entrada} fuente={escena.fuentes[0]} centrar>
      <div style={{ display: 'flex', gap: 40 }}>
        {escena.columnas.map((c, ci) => {
          const color = colorTono(c.tono)
          return (
            <div
              key={c.titulo}
              style={{
                flex: 1,
                backgroundColor: COLOR.superficie,
                border: `1px solid ${COLOR.borde}`,
                borderRadius: RADIO.medio,
                boxShadow: SOMBRA.tarjeta,
                overflow: 'hidden',
                ...entrar(frame, 10 + ci * 8, 18, 18),
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '22px 30px', backgroundColor: fondoTono(c.tono), borderBottom: `3px solid ${color}` }}>
                {c.icono ? <Icono nombre={c.icono} tam={36} color={color} /> : null}
                <div style={{ fontFamily: TITULOS, fontWeight: 700, fontSize: 32, color: COLOR.tinta }}>{c.titulo}</div>
              </div>
              <div style={{ padding: '24px 30px', display: 'flex', flexDirection: 'column', gap: 18 }}>
                {c.puntos.map((p) => {
                  const inicio = 24 + k++ * paso
                  return (
                    <div key={p} style={{ display: 'flex', gap: 16, ...entrar(frame, inicio, 10, 14) }}>
                      <div style={{ flex: '0 0 auto', marginTop: 3, width: 28, display: 'flex', justifyContent: 'center' }}>
                        {c.tono === 'peligro' || c.tono === 'exito' || c.tono === 'aviso' ? (
                          <Icono nombre={c.tono === 'peligro' ? 'mal' : c.tono === 'exito' ? 'bien' : 'alerta'} tam={28} color={color} grosor={2} />
                        ) : (
                          <span style={{ width: 11, height: 11, borderRadius: 6, backgroundColor: color, marginTop: 12 }} />
                        )}
                      </div>
                      <div style={{ fontFamily: TEXTO, fontSize: 27, lineHeight: 1.4, color: COLOR.tinta }}>{p}</div>
                    </div>
                  )
                })}
              </div>
            </div>
          )
        })}
      </div>
      {escena.nota ? <Nota texto={escena.nota} inicio={24 + total * paso + 6} /> : null}
    </Marco>
  )
}

export const EscenaTabla: React.FC<{ escena: Tabla; duracion: number }> = ({ escena, duracion }) => {
  const frame = useCurrentFrame()
  const paso = escalon(escena.filas.length, duracion, 0.4, 10)
  const anchos = escena.anchos ?? escena.encabezados.map(() => 1)
  const columnas = anchos.map((a) => `${a}fr`).join(' ')
  const densa = escena.filas.length > 7
  const tamTexto = densa ? 23 : 26
  return (
    <Marco antetitulo={escena.antetitulo} titulo={escena.titulo} entrada={escena.entrada} fuente={escena.fuentes[0]} centrar>
      <div style={{ border: `1px solid ${COLOR.bordeTabla}`, borderRadius: RADIO.medio, overflow: 'hidden', ...entrar(frame, 10, 12, 16) }}>
        <div style={{ display: 'grid', gridTemplateColumns: columnas, backgroundColor: COLOR.cabezaTabla, borderBottom: `2px solid ${COLOR.bordeTabla}` }}>
          {escena.encabezados.map((h) => (
            <div key={h} style={{ padding: densa ? '12px 20px' : '16px 22px', fontFamily: TITULOS, fontWeight: 700, fontSize: 23, color: COLOR.marcaOscura }}>
              {h}
            </div>
          ))}
        </div>
        {escena.filas.map((fila, i) => {
          const destacada = escena.resaltar?.includes(i)
          return (
            <div
              key={i}
              style={{
                display: 'grid',
                gridTemplateColumns: columnas,
                backgroundColor: destacada ? COLOR.marcaSuave : i % 2 ? COLOR.franja : COLOR.superficie,
                borderTop: i ? `1px solid ${conAlfa(COLOR.bordeTabla, 0.6)}` : undefined,
                boxShadow: destacada ? `inset 6px 0 0 ${COLOR.shell}` : undefined,
                ...entrar(frame, 20 + i * paso, 8, 12),
              }}
            >
              {fila.map((celda, j) => (
                <div
                  key={j}
                  style={{
                    padding: densa ? '10px 20px' : '15px 22px',
                    fontFamily: escena.codigo?.includes(j) ? CODIGO : j === 0 ? TITULOS : TEXTO,
                    fontWeight: j === 0 && !escena.codigo?.includes(j) ? 600 : 400,
                    fontSize: escena.codigo?.includes(j) ? tamTexto - 2 : tamTexto,
                    lineHeight: 1.36,
                    color: j === 0 ? COLOR.tinta : COLOR.tintaTenue,
                  }}
                >
                  {celda}
                </div>
              ))}
            </div>
          )
        })}
      </div>
      {escena.nota ? <Nota texto={escena.nota} inicio={20 + escena.filas.length * paso + 6} /> : null}
    </Marco>
  )
}

export const EscenaCalculo: React.FC<{ escena: Calculo; duracion: number }> = ({ escena, duracion }) => {
  const frame = useCurrentFrame()
  const paso = escalon(escena.filas.length, duracion, 0.5, 30)
  const inicioFilas = escena.formula ? 34 : 18
  return (
    <Marco antetitulo={escena.antetitulo} titulo={escena.titulo} entrada={escena.entrada} fuente={escena.fuentes[0]} centrar>
      {escena.formula ? (
        <div
          style={{
            alignSelf: 'flex-start',
            fontFamily: CODIGO,
            fontSize: 28,
            color: COLOR.marcaOscura,
            backgroundColor: COLOR.marcaSuave,
            border: `1px solid ${COLOR.filete}`,
            borderRadius: RADIO.chico,
            padding: '16px 26px',
            marginBottom: 28,
            ...entrar(frame, 12, 12, 16),
          }}
        >
          {escena.formula}
        </div>
      ) : null}
      <div style={{ maxWidth: 1500, display: 'flex', flexDirection: 'column' }}>
        {escena.filas.map((f, i) => {
          const total = f.tipo === 'total'
          const sub = f.tipo === 'subtotal'
          return (
            <div
              key={i}
              style={{
                display: 'grid',
                gridTemplateColumns: '1.1fr 1.6fr 0.7fr',
                alignItems: 'baseline',
                gap: 30,
                padding: total ? '20px 24px' : '14px 24px',
                marginTop: total ? 12 : 0,
                borderTop: total ? `3px solid ${COLOR.shell}` : sub ? `1px solid ${COLOR.bordeTabla}` : undefined,
                borderBottom: total ? undefined : `1px solid ${conAlfa(COLOR.borde, 0.7)}`,
                backgroundColor: total ? COLOR.marcaSuave : undefined,
                ...entrar(frame, inicioFilas + i * paso, 10, 14),
              }}
            >
              <div style={{ fontFamily: TITULOS, fontWeight: total || sub ? 700 : 600, fontSize: total ? 31 : 28, color: COLOR.tinta }}>{f.concepto}</div>
              <div style={{ fontFamily: TEXTO, fontSize: 25, lineHeight: 1.35, color: COLOR.tintaTenue }}>{f.detalle ?? ''}</div>
              <div
                style={{
                  fontFamily: TITULOS,
                  fontWeight: total ? 700 : 600,
                  fontSize: total ? 36 : 29,
                  textAlign: 'right',
                  fontVariantNumeric: 'tabular-nums',
                  color: total ? COLOR.shell : COLOR.tinta,
                }}
              >
                {f.valor}
              </div>
            </div>
          )
        })}
      </div>
      {escena.nota ? <Nota texto={escena.nota} inicio={inicioFilas + escena.filas.length * paso + 6} /> : null}
    </Marco>
  )
}

export const EscenaCodigo: React.FC<{ escena: Codigo; duracion: number }> = ({ escena, duracion }) => {
  const frame = useCurrentFrame()
  const paso = escalon(escena.bloques.length, duracion, 0.5, 60)
  return (
    <Marco antetitulo={escena.antetitulo} titulo={escena.titulo} fuente={escena.fuentes[0]} centrar>
      <div
        style={{
          backgroundColor: '#F6F8FA',
          border: `1px solid ${COLOR.borde}`,
          borderRadius: RADIO.medio,
          overflow: 'hidden',
          ...entrar(frame, 8, 14, 16),
        }}
      >
        {escena.directorio ? (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 12,
              padding: '12px 26px',
              borderBottom: `1px solid ${COLOR.borde}`,
              backgroundColor: COLOR.superficie,
              fontFamily: TITULOS,
              fontSize: 22,
              color: COLOR.tintaTenue,
            }}
          >
            <Icono nombre="codigo" tam={24} color={COLOR.tintaTenue} />
            Directorio: <span style={{ fontFamily: CODIGO, color: COLOR.tinta }}>{escena.directorio}</span>
          </div>
        ) : null}
        <div style={{ padding: '18px 26px 22px', display: 'flex', flexDirection: 'column', gap: 16 }}>
          {escena.bloques.map((b, i) => (
            <div key={i} style={{ ...entrar(frame, 18 + i * paso, 8, 14) }}>
              {b.comentario ? (
                <div style={{ fontFamily: CODIGO, fontSize: 24, color: '#6A737D', lineHeight: 1.45 }}># {b.comentario}</div>
              ) : null}
              {b.lineas.map((l) => (
                <div key={l} style={{ fontFamily: CODIGO, fontSize: 26, color: COLOR.tinta, lineHeight: 1.5, whiteSpace: 'pre' }}>
                  <span style={{ color: COLOR.marca, userSelect: 'none' }}>$ </span>
                  {l}
                </div>
              ))}
              {b.salida?.length ? (
                <div style={{ marginTop: 6, paddingLeft: 18, borderLeft: `3px solid ${COLOR.exito}` }}>
                  {b.salida.map((s) => (
                    <div key={s} style={{ fontFamily: CODIGO, fontSize: 23, color: COLOR.exito, lineHeight: 1.45, whiteSpace: 'pre-wrap' }}>
                      {s}
                    </div>
                  ))}
                </div>
              ) : null}
            </div>
          ))}
        </div>
      </div>
      {escena.nota ? <Nota texto={escena.nota} inicio={18 + escena.bloques.length * paso + 6} /> : null}
    </Marco>
  )
}
