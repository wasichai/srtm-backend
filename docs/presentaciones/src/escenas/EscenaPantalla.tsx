// Una pantalla real del portal recorrida foco por foco: a la izquierda la captura, a la derecha los pasos. El paso en
// curso muestra su explicación; los anteriores quedan marcados como hechos.
import React from 'react'
import { useCurrentFrame, useVideoConfig } from 'remotion'
import type { Pantalla } from '../tipos.ts'
import { Marco } from '../componentes/Marco.tsx'
import { Visor, iniciosFocos } from '../componentes/Visor.tsx'
import { COLOR, TIPO } from '../tema/tokens.ts'
import { TEXTO, TITULOS } from '../tema/fuentes.ts'
import { avance, entrar } from '../componentes/animacion.ts'
import { ENTRADA_PANTALLA } from '../tiempo.ts'

export const VISOR = { ancho: 1150, alto: 748 } as const

const Circulo: React.FC<{ n: number; estado: 'hecho' | 'activo' | 'pendiente' }> = ({ n, estado }) => (
  <div
    style={{
      flex: '0 0 auto',
      width: 40,
      height: 40,
      borderRadius: 20,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      fontFamily: TITULOS,
      fontWeight: 700,
      fontSize: 22,
      color: estado === 'pendiente' ? COLOR.tintaClara : '#FFFFFF',
      backgroundColor: estado === 'activo' ? COLOR.foco : estado === 'hecho' ? COLOR.marca : 'transparent',
      border: estado === 'pendiente' ? `2px solid ${COLOR.borde}` : 'none',
    }}
  >
    {estado === 'hecho' ? '✓' : n}
  </div>
)

export const EscenaPantalla: React.FC<{ escena: Pantalla; duracion: number }> = ({ escena, duracion }) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()
  const inicios = iniciosFocos(escena.focos, escena.entrada ?? ENTRADA_PANTALLA, fps)
  let activo = -1
  inicios.forEach((t, i) => {
    if (frame >= t) activo = i
  })

  return (
    <Marco antetitulo={escena.antetitulo} titulo={escena.titulo} fuente={escena.focos[activo]?.fuente ?? escena.fuentes[0]}>
      <div style={{ display: 'flex', gap: 46, height: VISOR.alto }}>
        <div style={{ ...entrar(frame, 6, 24, 22) }}>
          <Visor
            captura={escena.captura}
            marco={escena.marco ?? 'portal'}
            encuadre={escena.encuadre}
            focos={escena.focos}
            inicios={inicios}
            fin={duracion}
            ancho={VISOR.ancho}
            alto={VISOR.alto}
          />
        </div>
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 18, paddingTop: 4 }}>
          {escena.focos.map((f, i) => {
            const estado = i < activo ? 'hecho' : i === activo ? 'activo' : 'pendiente'
            const abre = i === activo ? avance(frame, inicios[i], 18) : 0
            return (
              <div key={i} style={{ display: 'flex', gap: 18, ...entrar(frame, 14 + i * 5, 12, 14) }}>
                <Circulo n={i + 1} estado={estado} />
                <div style={{ flex: 1, paddingTop: 4 }}>
                  <div
                    style={{
                      fontFamily: TITULOS,
                      fontWeight: estado === 'activo' ? 700 : 600,
                      fontSize: 28,
                      lineHeight: 1.18,
                      color: estado === 'activo' ? COLOR.tinta : estado === 'hecho' ? COLOR.tintaTenue : COLOR.tintaClara,
                    }}
                  >
                    {f.titulo}
                  </div>
                  {f.texto && i === activo ? (
                    <div
                      style={{
                        fontFamily: TEXTO,
                        fontSize: TIPO.apoyo,
                        lineHeight: 1.42,
                        color: COLOR.tintaTenue,
                        marginTop: 10,
                        opacity: abre,
                        transform: `translateY(${(1 - abre) * 8}px)`,
                      }}
                    >
                      {f.texto}
                    </div>
                  ) : null}
                </div>
              </div>
            )
          })}
        </div>
      </div>
    </Marco>
  )
}
