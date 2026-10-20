// Las escenas de texto: portada, separador de sección, mensaje principal, aviso y cierre.
import React from 'react'
import { AbsoluteFill, useCurrentFrame } from 'remotion'
import type { Aviso, Cierre, Mensaje, Portada, Seccion } from '../tipos.ts'
import { AVISO, COLOR, MARGEN, RADIO, SOMBRA, TIPO, conAlfa } from '../tema/tokens.ts'
import { TEXTO, TITULOS } from '../tema/fuentes.ts'
import { Antetitulo, BarraAvance, Marco, Monograma, Pie } from '../componentes/Marco.tsx'
import { avance, entrar, fundido } from '../componentes/animacion.ts'
import { Icono, type NombreIcono } from '../componentes/iconos.tsx'
import { Recorte } from '../componentes/Recorte.tsx'
import { useEscena } from '../componentes/contexto.tsx'
import { NOMBRE_DOC } from '../manuales.ts'
import edicion from '../generado/edicion.json' with { type: 'json' }

const ALTO_BANDA = 600

/** La portada de los manuales (recursos/marca/portada.svg) llevada a 16:9: el bloque azul con su cuadrícula tenue. */
export const EscenaPortada: React.FC<{ escena: Portada }> = ({ escena }) => {
  const frame = useCurrentFrame()
  const { video } = useEscena()
  const sube = avance(frame, 0, 26)
  return (
    <AbsoluteFill style={{ backgroundColor: COLOR.superficie }}>
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: 0,
          height: ALTO_BANDA,
          backgroundColor: COLOR.shell,
          transform: `translateY(${(sube - 1) * 40}px)`,
          backgroundImage: `linear-gradient(${conAlfa('#FFFFFF', 0.07)} 1px, transparent 1px), linear-gradient(90deg, ${conAlfa('#FFFFFF', 0.07)} 1px, transparent 1px)`,
          backgroundSize: '120px 100px',
          backgroundPosition: '-1px -1px',
        }}
      />
      <div style={{ position: 'absolute', left: 0, right: 0, top: ALTO_BANDA, height: 12, backgroundColor: COLOR.filete }} />
      <div style={{ position: 'absolute', left: MARGEN.x, top: 104, right: MARGEN.x }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 26, ...entrar(frame, 6, 16, 18) }}>
          <Monograma tam={84} invertido />
          <div style={{ fontFamily: TITULOS, color: '#FFFFFF' }}>
            <div style={{ fontSize: 34, fontWeight: 700 }}>Rentas municipales</div>
            <div style={{ fontSize: 24, opacity: 0.85 }}>Municipalidad Distrital de Perené</div>
          </div>
        </div>
        <div style={{ marginTop: 76 }}>
          <Antetitulo texto={`${video.numero} · ${escena.antetitulo}`} inicio={14} color={COLOR.filete} />
        </div>
        <div
          style={{
            fontFamily: TITULOS,
            fontWeight: 700,
            fontSize: escena.titulo.length > 34 ? 80 : TIPO.portada,
            lineHeight: 1.04,
            letterSpacing: '-0.015em',
            color: '#FFFFFF',
            marginTop: 20,
            maxWidth: 1600,
            ...entrar(frame, 18, 26, 22),
          }}
        >
          {escena.titulo}
        </div>
        <div
          style={{
            fontFamily: TEXTO,
            fontSize: 36,
            lineHeight: 1.35,
            color: conAlfa('#FFFFFF', 0.9),
            marginTop: 22,
            maxWidth: 1400,
            ...entrar(frame, 28, 16, 20),
          }}
        >
          {escena.subtitulo}
        </div>
      </div>
      <div
        style={{
          position: 'absolute',
          left: MARGEN.x,
          right: MARGEN.x,
          top: ALTO_BANDA + 84,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          gap: 80,
        }}
      >
        <div style={{ ...entrar(frame, 40, 14, 18), maxWidth: 1000 }}>
          <div style={{ fontFamily: TITULOS, fontWeight: 700, fontSize: TIPO.rotulo, letterSpacing: '0.12em', textTransform: 'uppercase', color: COLOR.marcaFuerte }}>
            Para quién
          </div>
          <div style={{ fontFamily: TEXTO, fontSize: 32, lineHeight: 1.4, color: COLOR.tinta, marginTop: 10 }}>{escena.audiencia}</div>
        </div>
        <div style={{ ...entrar(frame, 48, 14, 18), textAlign: 'right' }}>
          <div style={{ fontFamily: TITULOS, fontWeight: 700, fontSize: TIPO.rotulo, letterSpacing: '0.12em', textTransform: 'uppercase', color: COLOR.marcaFuerte }}>
            Basado en
          </div>
          <div style={{ fontFamily: TEXTO, fontSize: 28, lineHeight: 1.45, color: COLOR.tintaTenue, marginTop: 10 }}>
            {[...new Set(video.manuales.map((m) => NOMBRE_DOC[m]))].map((m) => (
              <div key={m}>{m}</div>
            ))}
            <div style={{ fontFamily: TITULOS, fontSize: 22, color: COLOR.tintaClara, marginTop: 8 }}>
              Edición {edicion.edicion} · {edicion.fecha} · Rentas municipales {edicion.version}
            </div>
          </div>
        </div>
      </div>
      <BarraAvance />
    </AbsoluteFill>
  )
}

export const EscenaSeccion: React.FC<{ escena: Seccion }> = ({ escena }) => {
  const frame = useCurrentFrame()
  const linea = avance(frame, 8, 30)
  return (
    <AbsoluteFill style={{ backgroundColor: COLOR.marcaSuave }}>
      <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 26, backgroundColor: COLOR.shell }} />
      <div style={{ position: 'absolute', left: 26, top: 0, bottom: 0, width: 6, backgroundColor: COLOR.filete }} />
      <div style={{ position: 'absolute', left: 200, right: 200, top: 300 }}>
        <div
          style={{
            fontFamily: TITULOS,
            fontWeight: 700,
            fontSize: 30,
            letterSpacing: '0.14em',
            color: COLOR.marca,
            ...entrar(frame, 0, 14, 16),
          }}
        >
          PARTE {escena.numero}
        </div>
        <div style={{ height: 5, width: 160 * linea, backgroundColor: COLOR.shell, marginTop: 22, marginBottom: 30 }} />
        <div
          style={{
            fontFamily: TITULOS,
            fontWeight: 700,
            fontSize: TIPO.seccion,
            lineHeight: 1.04,
            letterSpacing: '-0.015em',
            color: COLOR.marcaOscura,
            ...entrar(frame, 6, 24, 22),
          }}
        >
          {escena.titulo}
        </div>
        <div
          style={{
            fontFamily: TEXTO,
            fontSize: 38,
            lineHeight: 1.4,
            color: COLOR.tintaTenue,
            marginTop: 28,
            maxWidth: 1300,
            ...entrar(frame, 18, 16, 20),
          }}
        >
          {escena.bajada}
        </div>
      </div>
      <Pie fuente={escena.fuentes[0]} />
      <BarraAvance />
    </AbsoluteFill>
  )
}

const conResalte = (frase: string, resaltar: string | undefined, p: number): React.ReactNode => {
  if (!resaltar || !frase.includes(resaltar)) return frase
  const [antes, despues] = frase.split(resaltar)
  return (
    <>
      {antes}
      <span
        style={{
          color: COLOR.shell,
          backgroundImage: `linear-gradient(${conAlfa(COLOR.filete, 0.45)}, ${conAlfa(COLOR.filete, 0.45)})`,
          backgroundRepeat: 'no-repeat',
          backgroundPosition: '0 88%',
          backgroundSize: `${p * 100}% 0.22em`,
        }}
      >
        {resaltar}
      </span>
      {despues}
    </>
  )
}

export const EscenaMensaje: React.FC<{ escena: Mensaje }> = ({ escena }) => {
  const frame = useCurrentFrame()
  return (
    <Marco fuente={escena.fuentes[0]}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', paddingLeft: 60, paddingRight: 160, paddingBottom: 40 }}>
        {escena.antetitulo ? <Antetitulo texto={escena.antetitulo} /> : null}
        <div
          style={{
            fontFamily: TEXTO,
            fontWeight: 600,
            fontSize: escena.frase.length > 90 ? 54 : TIPO.frase,
            lineHeight: 1.22,
            color: COLOR.tinta,
            marginTop: 30,
            maxWidth: 1450,
            ...entrar(frame, 6, 26, 24),
          }}
        >
          {conResalte(escena.frase, escena.resaltar, avance(frame, 30, 24))}
        </div>
        {escena.apoyo ? (
          <div
            style={{
              fontFamily: TITULOS,
              fontSize: TIPO.subtitulo,
              lineHeight: 1.42,
              color: COLOR.tintaTenue,
              marginTop: 40,
              maxWidth: 1350,
              paddingLeft: 26,
              borderLeft: `5px solid ${COLOR.filete}`,
              ...entrar(frame, 34, 16, 20),
            }}
          >
            {escena.apoyo}
          </div>
        ) : null}
      </div>
    </Marco>
  )
}

const ICONO_AVISO: Record<Aviso['nivel'], NombreIcono> = {
  nota: 'alerta',
  consejo: 'bien',
  importante: 'alerta',
  precaucion: 'advertencia',
  advertencia: 'prohibido',
}

export const CajaAviso: React.FC<{
  nivel: Aviso['nivel']
  titulo?: string
  texto: string
  puntos?: readonly string[]
  inicio?: number
  tam?: 'grande' | 'chica'
}> = ({ nivel, titulo, texto, puntos, inicio = 0, tam = 'grande' }) => {
  const frame = useCurrentFrame()
  const a = AVISO[nivel]
  const grande = tam === 'grande'
  return (
    <div
      style={{
        backgroundColor: a.fondo,
        border: `1px solid ${a.borde}`,
        borderLeft: `${grande ? 10 : 6}px solid ${a.color}`,
        borderRadius: RADIO.medio,
        padding: grande ? '40px 48px' : '22px 28px',
        ...entrar(frame, inicio, 18, 20),
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          fontFamily: TITULOS,
          fontWeight: 700,
          fontSize: grande ? 24 : 20,
          letterSpacing: '0.1em',
          textTransform: 'uppercase',
          color: a.color,
        }}
      >
        <Icono nombre={ICONO_AVISO[nivel]} tam={grande ? 30 : 24} color={a.color} grosor={2.2} />
        {a.rotulo}
      </div>
      {titulo ? (
        <div style={{ fontFamily: TITULOS, fontWeight: 700, fontSize: grande ? 44 : 30, lineHeight: 1.15, color: COLOR.tinta, marginTop: grande ? 18 : 10 }}>
          {titulo}
        </div>
      ) : null}
      <div style={{ fontFamily: TEXTO, fontSize: grande ? 31 : 25, lineHeight: 1.45, color: COLOR.tinta, marginTop: grande ? 16 : 8 }}>{texto}</div>
      {puntos?.length ? (
        <div style={{ marginTop: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {puntos.map((p, i) => (
            <div
              key={p}
              style={{
                display: 'flex',
                gap: 16,
                fontFamily: TEXTO,
                fontSize: grande ? 28 : 24,
                lineHeight: 1.4,
                color: COLOR.tinta,
                ...entrar(frame, inicio + 20 + i * 8, 10, 14),
              }}
            >
              <span style={{ flex: '0 0 auto', width: 10, height: 10, borderRadius: 5, backgroundColor: a.color, marginTop: 15 }} />
              <span>{p}</span>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  )
}

export const EscenaAviso: React.FC<{ escena: Aviso }> = ({ escena }) => {
  const frame = useCurrentFrame()
  return (
    <Marco antetitulo={escena.antetitulo} fuente={escena.fuentes[0]}>
      <div style={{ flex: 1, display: 'flex', gap: 56, alignItems: 'center', paddingBottom: 20 }}>
        <div style={{ flex: escena.captura ? '0 0 920px' : '0 1 1480px' }}>
          <CajaAviso nivel={escena.nivel} titulo={escena.titulo} texto={escena.texto} puntos={escena.puntos} inicio={8} />
        </div>
        {escena.captura ? (
          <div style={{ flex: 1, display: 'flex', justifyContent: 'center', opacity: fundido(frame, 24, undefined, 16) }}>
            <Recorte archivo={escena.captura.archivo} region={escena.captura.region} anchoMax={660} altoMax={640} />
          </div>
        ) : null}
      </div>
    </Marco>
  )
}

export const EscenaCierre: React.FC<{ escena: Cierre }> = ({ escena }) => {
  const frame = useCurrentFrame()
  return (
    <AbsoluteFill style={{ backgroundColor: COLOR.superficie }}>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 0, height: 10, backgroundColor: COLOR.shell }} />
      <div style={{ position: 'absolute', left: 0, right: 0, top: 10, height: 3, backgroundColor: COLOR.filete }} />
      <div style={{ position: 'absolute', left: MARGEN.x, right: MARGEN.x, top: 64, bottom: MARGEN.abajo, display: 'flex', gap: 80, alignItems: 'center' }}>
        <div style={{ flex: 1, paddingBottom: 30 }}>
          <Antetitulo texto="En resumen" />
          <div style={{ fontFamily: TITULOS, fontWeight: 700, fontSize: TIPO.titulo, lineHeight: 1.1, marginTop: 14, ...entrar(frame, 3, 20, 20) }}>
            {escena.titulo}
          </div>
          <div style={{ marginTop: 44, display: 'flex', flexDirection: 'column', gap: 22 }}>
            {escena.puntos.map((p, i) => (
              <div key={p} style={{ display: 'flex', gap: 20, alignItems: 'flex-start', ...entrar(frame, 16 + i * 9, 12, 16) }}>
                <div style={{ marginTop: 2 }}>
                  <Icono nombre="bien" tam={34} color={COLOR.exito} grosor={2} />
                </div>
                <div style={{ fontFamily: TEXTO, fontSize: TIPO.lista, lineHeight: 1.4, color: COLOR.tinta }}>{p}</div>
              </div>
            ))}
          </div>
        </div>
        <div style={{ flex: '0 0 560px', display: 'flex', flexDirection: 'column', gap: 28, paddingTop: 60 }}>
          <div
            style={{
              backgroundColor: COLOR.marcaSuave,
              borderLeft: `6px solid ${COLOR.marca}`,
              borderRadius: RADIO.medio,
              padding: '30px 34px',
              ...entrar(frame, 30, 14, 18),
            }}
          >
            <div style={{ display: 'flex', gap: 12, alignItems: 'center', fontFamily: TITULOS, fontWeight: 700, fontSize: 22, letterSpacing: '0.1em', textTransform: 'uppercase', color: COLOR.marcaFuerte }}>
              <Icono nombre="manual" tam={28} color={COLOR.marcaFuerte} />
              Para saber más
            </div>
            {escena.manuales.map((m) => (
              <div key={m} style={{ fontFamily: TEXTO, fontSize: 27, lineHeight: 1.45, color: COLOR.tinta, marginTop: 12 }}>
                {m}
              </div>
            ))}
            <div style={{ fontFamily: TITULOS, fontSize: 22, color: COLOR.tintaTenue, marginTop: 16 }}>
              En HTML y PDF: docs/manuales (make build)
            </div>
          </div>
          {escena.siguiente ? (
            <div
              style={{
                border: `1px solid ${COLOR.borde}`,
                borderRadius: RADIO.medio,
                padding: '26px 34px',
                boxShadow: SOMBRA.tarjeta,
                ...entrar(frame, 40, 14, 18),
              }}
            >
              <div style={{ fontFamily: TITULOS, fontWeight: 700, fontSize: 22, letterSpacing: '0.1em', textTransform: 'uppercase', color: COLOR.marcaFuerte }}>
                Siguiente vídeo
              </div>
              <div style={{ fontFamily: TITULOS, fontWeight: 600, fontSize: 30, lineHeight: 1.25, color: COLOR.tinta, marginTop: 10 }}>{escena.siguiente}</div>
            </div>
          ) : null}
        </div>
      </div>
      <Pie fuente={escena.fuentes[0]} />
      <BarraAvance />
    </AbsoluteFill>
  )
}
