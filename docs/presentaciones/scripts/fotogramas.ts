// Fotogramas de control: para revisar un vídeo sin verlo entero. De cada escena saca el fotograma en que todo está a
// la vista (y, en una pantalla, uno por foco; en un diagrama por pasos, uno por paso).
//   node scripts/fotogramas.ts [vídeo …]      → salida/fotogramas/<archivo>/NN-escena-K.jpg
import { mkdirSync, rmSync } from 'node:fs'
import path from 'node:path'
import { renderStill, selectComposition } from '@remotion/renderer'
import { SALIDA, elegirVideos, empaquetar } from './remotion.ts'
import { ENTRADA_DIAGRAMA, ENTRADA_PANTALLA, ENTRADA_SECUENCIA, FPS, TRANSICION, fotogramasEscena, iniciosEscenas } from '../src/tiempo.ts'
import type { Escena } from '../src/tipos.ts'

/** Los momentos (en fotogramas de la escena) que conviene mirar. */
export const momentosDeControl = (e: Escena): number[] => {
  const dur = fotogramasEscena(e)
  // antes de que empiece el fundido a la escena siguiente
  const casiFin = Math.max(0, dur - TRANSICION - 10)
  switch (e.tipo) {
    case 'pantalla': {
      const out: number[] = []
      let t = (e.entrada ?? ENTRADA_PANTALLA) * FPS
      out.push(Math.round(t - 12))
      for (const f of e.focos) {
        out.push(Math.round(t + f.segundos * FPS * 0.7))
        t += f.segundos * FPS
      }
      return out
    }
    case 'diagrama': {
      if (!e.pasos?.length) return [casiFin]
      const out: number[] = []
      let t = ENTRADA_DIAGRAMA * FPS
      for (const p of e.pasos) {
        t += p.segundos * FPS
        out.push(Math.min(casiFin, Math.round(t - 14)))
      }
      return out
    }
    case 'secuencia': {
      const ini = ENTRADA_SECUENCIA * FPS
      const mitad = Math.floor(e.mensajes.length / 2)
      return [Math.round(ini + (mitad + 0.8) * e.segundosPorMensaje * FPS), casiFin]
    }
    default:
      return [casiFin]
  }
}

const main = async (): Promise<void> => {
  const videos = elegirVideos(process.argv.slice(2))
  const serveUrl = await empaquetar()
  for (const video of videos) {
    const dir = path.join(SALIDA, 'fotogramas', video.archivo)
    rmSync(dir, { recursive: true, force: true })
    mkdirSync(dir, { recursive: true })
    const composition = await selectComposition({ serveUrl, id: video.id, inputProps: { video } })
    const inicios = iniciosEscenas(video)
    const archivos: string[] = []
    for (const [i, escena] of video.escenas.entries()) {
      for (const [k, m] of momentosDeControl(escena).entries()) {
        const frame = Math.min(composition.durationInFrames - 1, inicios[i] + m)
        const archivo = path.join(dir, `${String(i + 1).padStart(2, '0')}-${escena.id}-${k + 1}.jpg`)
        await renderStill({ serveUrl, composition, frame, output: archivo, imageFormat: 'jpeg', jpegQuality: 90, inputProps: { video } })
        archivos.push(archivo)
      }
    }
    console.log(`fotogramas: ${video.archivo}: ${archivos.length} en ${path.relative(process.cwd(), dir)}`)
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === new URL(import.meta.url).pathname) await main()
