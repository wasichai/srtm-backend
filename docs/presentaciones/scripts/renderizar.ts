// Renderiza las presentaciones y sus miniaturas. Empaqueta el proyecto una sola vez para todos los vídeos.
//   node scripts/renderizar.ts [vídeo …]              → salida/videos/<archivo>.mp4 y salida/miniaturas/<archivo>.jpg
//   node scripts/renderizar.ts --solo-miniaturas [vídeo …]
// Un vídeo se nombra por su número (03), su id (S03-RegistroTributario) o su archivo; sin nombres, todos.
// Deja en salida/informe-render.json la duración, el tamaño y el tiempo de cada render.
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { renderMedia, renderStill, selectComposition } from '@remotion/renderer'
import { SALIDA, elegirVideos, empaquetar } from './remotion.ts'
import { FPS, iniciosEscenas, reloj } from '../src/tiempo.ts'
import type { Video } from '../src/tipos.ts'

/** Calidad de los MP4: H.264 casi sin pérdida visible, en el espacio de color de la alta definición. */
export const PARAMETROS_VIDEO = {
  codec: 'h264',
  crf: 18,
  pixelFormat: 'yuv420p',
  colorSpace: 'bt709',
  imageFormat: 'jpeg',
  jpegQuality: 92,
  x264Preset: 'medium',
} as const

type Informe = Record<string, { duracion: string; fotogramas: number; bytes: number; segundosRender: number; fecha: string }>

const fotogramaMiniatura = (v: Video): number => {
  const i = v.escenas.findIndex((e) => e.id === v.miniatura.escena)
  return iniciosEscenas(v)[i] + Math.round(v.miniatura.segundo * FPS)
}

const main = async (): Promise<void> => {
  const args = process.argv.slice(2)
  const soloMiniaturas = args.includes('--solo-miniaturas')
  const videos = elegirVideos(args)
  const serveUrl = await empaquetar()
  mkdirSync(path.join(SALIDA, 'videos'), { recursive: true })
  mkdirSync(path.join(SALIDA, 'miniaturas'), { recursive: true })
  const archivoInforme = path.join(SALIDA, 'informe-render.json')
  const informe: Informe = existsSync(archivoInforme) ? JSON.parse(readFileSync(archivoInforme, 'utf8')) : {}
  // la mitad de los núcleos: el resto queda para el codificador
  const concurrencia = Math.max(2, Math.floor(os.availableParallelism() / 2))

  for (const video of videos) {
    const composition = await selectComposition({ serveUrl, id: video.id, inputProps: { video } })
    const miniatura = path.join(SALIDA, 'miniaturas', `${video.archivo}.jpg`)
    await renderStill({ serveUrl, composition, frame: fotogramaMiniatura(video), output: miniatura, imageFormat: 'jpeg', jpegQuality: 90, inputProps: { video } })
    console.log(`miniatura: ${path.relative(process.cwd(), miniatura)}`)
    if (soloMiniaturas) continue

    const destino = path.join(SALIDA, 'videos', `${video.archivo}.mp4`)
    const inicio = Date.now()
    let ultimo = -1
    await renderMedia({
      serveUrl,
      composition,
      inputProps: { video },
      outputLocation: destino,
      ...PARAMETROS_VIDEO,
      muted: true,
      concurrency: concurrencia,
      overwrite: true,
      onProgress: ({ progress }) => {
        const p = Math.floor(progress * 20)
        if (p !== ultimo) {
          ultimo = p
          process.stdout.write(`${video.archivo}: ${Math.round(progress * 100)} %\r`)
        }
      },
    })
    const segundos = Math.round((Date.now() - inicio) / 1000)
    const bytes = statSync(destino).size
    informe[video.archivo] = {
      duracion: reloj(composition.durationInFrames),
      fotogramas: composition.durationInFrames,
      bytes,
      segundosRender: segundos,
      fecha: new Date().toISOString(),
    }
    writeFileSync(archivoInforme, `${JSON.stringify(informe, null, 2)}\n`)
    console.log(`vídeo: ${path.relative(process.cwd(), destino)} · ${reloj(composition.durationInFrames)} · ${(bytes / 1e6).toFixed(1)} MB · ${segundos} s`)
  }
}

await main()
