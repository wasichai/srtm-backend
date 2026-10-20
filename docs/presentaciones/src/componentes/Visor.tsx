// El visor de capturas: muestra una captura real del portal y la recorre foco por foco. Cada foco puede acercar la
// cámara a una región, atenuar lo demás y rodearla con el mismo recuadro naranja con que los manuales marcan un
// control. Las regiones están en píxeles CSS de la captura: el recuadro cae donde está el control en la imagen.
import React from 'react'
import { Img, staticFile, useCurrentFrame } from 'remotion'
import capturas from '../generado/capturas.json' with { type: 'json' }
import type { Captura, Foco, Region } from '../tipos.ts'
import { COLOR, RADIO, SOMBRA, conAlfa } from '../tema/tokens.ts'
import { TITULOS } from '../tema/fuentes.ts'
import { CAMARA, avance, fundido } from './animacion.ts'

const TAMANOS: Record<string, { ancho: number; alto: number }> = capturas

export const tamanoCaptura = (archivo: Captura): { ancho: number; alto: number } => {
  const t = TAMANOS[archivo]
  if (!t) throw new Error(`captura desconocida: ${archivo} (npm run capturas, npm run recursos)`)
  return t
}

/** La escala más alta a la que se muestra una captura: se tomaron a 2×, así que hasta 2 se ven nítidas. */
const ESCALA_MAXIMA = 1.9
/** Margen alrededor de una región cuando la cámara se acerca a ella, en píxeles CSS de la captura. */
const AIRE = 56
const MOVIMIENTO = 28
const FUNDIDO_IMAGEN = 14

type Camara = { s: number; tx: number; ty: number }

const camaraPara = (caja: Region, img: { ancho: number; alto: number }, vw: number, vh: number, acercar: boolean): Camara => {
  const ajuste = Math.min(vw / img.ancho, vh / img.alto)
  const s = acercar
    ? Math.max(ajuste, Math.min(ESCALA_MAXIMA, (vw * 0.94) / caja.w, (vh * 0.94) / caja.h))
    : Math.min(ESCALA_MAXIMA, (vw * 0.96) / caja.w, (vh * 0.96) / caja.h)
  const cx = caja.x + caja.w / 2
  const cy = caja.y + caja.h / 2
  const eje = (v: number, centro: number, total: number): number => {
    const t = v / 2 - centro * s
    if (total * s <= v) return (v - total * s) / 2
    return Math.min(0, Math.max(v - total * s, t))
  }
  return { s, tx: eje(vw, cx, img.ancho), ty: eje(vh, cy, img.alto) }
}

const mezclar = (a: Camara, b: Camara, p: number): Camara => ({
  s: Math.exp(Math.log(a.s) + (Math.log(b.s) - Math.log(a.s)) * p),
  tx: a.tx + (b.tx - a.tx) * p,
  ty: a.ty + (b.ty - a.ty) * p,
})

const conAire = (r: Region, img: { ancho: number; alto: number }): Region => {
  const x = Math.max(0, r.x - AIRE)
  const y = Math.max(0, r.y - AIRE)
  return { x, y, w: Math.min(img.ancho, r.x + r.w + AIRE) - x, h: Math.min(img.alto, r.y + r.h + AIRE) - y }
}

/** Los fotogramas (de la escena) en que empieza cada foco. */
export const iniciosFocos = (focos: readonly Foco[], entrada: number, fps: number): number[] => {
  const inicios: number[] = []
  let t = Math.round(entrada * fps)
  for (const f of focos) {
    inicios.push(t)
    t += Math.round(f.segundos * fps)
  }
  return inicios
}

type Plano = { archivo: Captura; camara: Camara; region?: Region; cambia: boolean }

export const Visor: React.FC<{
  captura: Captura
  marco: 'portal' | 'recorte' | 'documento'
  encuadre?: Region
  focos: readonly Foco[]
  inicios: readonly number[]
  fin: number
  ancho: number
  alto: number
}> = ({ captura, marco, encuadre, focos, inicios, fin, ancho, alto }) => {
  const frame = useCurrentFrame()
  // cuando la imagen no llena el visor (un diálogo, una página), se deja aire alrededor
  const aire = marco === 'portal' ? 0 : 36
  const vw = ancho - aire * 2
  const vh = alto - aire * 2

  // el plano de cada tramo: la vista general y luego uno por foco
  const planos: Plano[] = []
  {
    const img = tamanoCaptura(captura)
    const base = encuadre ?? { x: 0, y: 0, w: img.ancho, h: img.alto }
    planos.push({ archivo: captura, camara: camaraPara(base, img, vw, vh, false), cambia: false })
  }
  focos.forEach((f, i) => {
    const anterior = planos[i]
    const archivo = f.captura ?? anterior.archivo
    const img = tamanoCaptura(archivo)
    const completa = { x: 0, y: 0, w: img.ancho, h: img.alto }
    const base = archivo === captura && encuadre ? encuadre : completa
    const acercar = f.acercar ?? true
    const caja = f.encuadre ?? (f.region && acercar ? conAire(f.region, img) : base)
    planos.push({
      archivo,
      camara: camaraPara(caja, img, vw, vh, Boolean(f.encuadre) || (acercar && Boolean(f.region))),
      region: f.region,
      cambia: archivo !== anterior.archivo,
    })
  })

  // el tramo en curso: 0 es la vista general, k + 1 es el foco k
  let k = 0
  inicios.forEach((t, i) => {
    if (frame >= t) k = i + 1
  })
  const actual = planos[k]
  const inicioTramo = k === 0 ? 0 : inicios[k - 1]
  const finTramo = k < inicios.length ? inicios[k] : fin
  const p = avance(frame, inicioTramo, MOVIMIENTO, CAMARA)
  const camara = k === 0 || actual.cambia ? actual.camara : mezclar(planos[k - 1].camara, actual.camara, p)

  const capa = (archivo: Captura, cam: Camara, opacidad: number, clave: string): React.ReactNode => {
    const img = tamanoCaptura(archivo)
    return (
      <Img
        key={clave}
        src={staticFile(`capturas/${archivo}`)}
        style={{
          position: 'absolute',
          left: aire + cam.tx,
          top: aire + cam.ty,
          width: img.ancho * cam.s,
          height: img.alto * cam.s,
          opacity: opacidad,
          borderRadius: marco === 'portal' ? 0 : RADIO.chico,
          boxShadow: marco === 'portal' ? undefined : SOMBRA.captura,
        }}
      />
    )
  }

  // el recuadro: aparece cuando la cámara llegó y se va al terminar el foco
  const region = actual.region
  let recuadro: React.ReactNode = null
  if (region && k > 0) {
    const o = fundido(frame, inicioTramo + (actual.cambia ? FUNDIDO_IMAGEN : MOVIMIENTO - 6), finTramo, 10)
    const pad = 8
    const x = aire + camara.tx + region.x * camara.s - pad
    const y = aire + camara.ty + region.y * camara.s - pad
    const w = region.w * camara.s + pad * 2
    const h = region.h * camara.s + pad * 2
    const crece = 1 + 0.04 * (1 - avance(frame, inicioTramo + MOVIMIENTO - 6, 14))
    recuadro = (
      <>
        <svg width={ancho} height={alto} style={{ position: 'absolute', left: 0, top: 0, opacity: o * 0.9 }}>
          <path
            fillRule="evenodd"
            fill={conAlfa('#0B2440', 0.38)}
            d={`M0 0H${ancho}V${alto}H0Z M${x} ${y + 10}a10 10 0 0 1 10 -10H${x + w - 10}a10 10 0 0 1 10 10V${y + h - 10}a10 10 0 0 1 -10 10H${x + 10}a10 10 0 0 1 -10 -10Z`}
          />
        </svg>
        <div
          style={{
            position: 'absolute',
            left: x,
            top: y,
            width: w,
            height: h,
            borderRadius: 10,
            border: `4px solid ${COLOR.foco}`,
            boxShadow: `0 0 0 6px ${conAlfa(COLOR.foco, 0.18)}`,
            opacity: o,
            transform: `scale(${crece})`,
          }}
        />
        <div
          style={{
            position: 'absolute',
            left: Math.max(8, x - 20),
            top: Math.max(8, y - 20),
            width: 40,
            height: 40,
            borderRadius: 20,
            backgroundColor: COLOR.foco,
            color: '#FFFFFF',
            fontFamily: TITULOS,
            fontWeight: 700,
            fontSize: 22,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            opacity: o,
            boxShadow: '0 2px 8px rgba(0,0,0,0.25)',
          }}
        >
          {k}
        </div>
      </>
    )
  }

  // al cambiar de imagen, la anterior se funde con la nueva
  const anterior = k > 0 && actual.cambia ? planos[k - 1] : null
  const camaraAnterior = anterior ? anterior.camara : null
  const entra = anterior ? fundido(frame, inicioTramo, undefined, FUNDIDO_IMAGEN) : 1

  return (
    <div
      style={{
        position: 'relative',
        width: ancho,
        height: alto,
        overflow: 'hidden',
        borderRadius: RADIO.grande,
        border: `1px solid ${COLOR.borde}`,
        backgroundColor: marco === 'documento' ? '#E4E8ED' : marco === 'recorte' ? '#EEF2F6' : COLOR.papel,
        boxShadow: SOMBRA.tarjeta,
      }}
    >
      {anterior && camaraAnterior ? capa(anterior.archivo, camaraAnterior, 1 - entra, `a-${k}`) : null}
      {capa(actual.archivo, camara, entra, `c-${actual.archivo}`)}
      {recuadro}
    </div>
  )
}
