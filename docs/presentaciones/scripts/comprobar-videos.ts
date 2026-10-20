// Comprueba los MP4 ya renderizados: códec, resolución, cuadros por segundo, formato de píxel, duración exacta en
// fotogramas, sin pista de audio, y que exista su miniatura. Luego extrae del propio MP4 un fotograma por escena
// (salida/control/<archivo>/), para revisar el vídeo codificado y no solo los fotogramas sueltos.
// Usa la ffmpeg y la ffprobe que trae Remotion (npx remotion ffmpeg|ffprobe), no las del sistema.
//   node scripts/comprobar-videos.ts [vídeo …]
import { existsSync, mkdirSync, rmSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { SALIDA, elegirVideos } from './remotion.ts'
import { RAIZ } from './recursos.ts'
import { momentosDeControl } from './fotogramas.ts'
import { FPS, fotogramasVideo, iniciosEscenas, reloj } from '../src/tiempo.ts'

type Stream = { codec_type: string; codec_name: string; width?: number; height?: number; r_frame_rate?: string; pix_fmt?: string; nb_frames?: string }

const remotion = (herramienta: 'ffmpeg' | 'ffprobe', args: string[]): string =>
  execFileSync('npx', ['remotion', herramienta, ...args], { cwd: RAIZ, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })

const main = (): void => {
  const videos = elegirVideos(process.argv.slice(2))
  const fallas: string[] = []
  for (const video of videos) {
    const mp4 = path.join(SALIDA, 'videos', `${video.archivo}.mp4`)
    if (!existsSync(mp4)) {
      fallas.push(`${video.archivo}: no está renderizado (npm run render -- ${video.numero})`)
      continue
    }
    const info = JSON.parse(remotion('ffprobe', ['-v', 'error', '-count_frames', '-print_format', 'json', '-show_streams', '-show_format', mp4])) as {
      streams: Stream[]
      format: { duration: string; size: string }
    }
    const v = info.streams.filter((s) => s.codec_type === 'video')
    const a = info.streams.filter((s) => s.codec_type === 'audio')
    const esperados = fotogramasVideo(video)
    const problemas: string[] = []
    if (v.length !== 1) problemas.push(`${v.length} pistas de vídeo`)
    const s = v[0]
    if (s) {
      if (s.codec_name !== 'h264') problemas.push(`códec ${s.codec_name}`)
      if (s.width !== 1920 || s.height !== 1080) problemas.push(`${s.width} × ${s.height}`)
      if (s.r_frame_rate !== `${FPS}/1`) problemas.push(`${s.r_frame_rate} cuadros por segundo`)
      if (s.pix_fmt !== 'yuv420p') problemas.push(`formato de píxel ${s.pix_fmt}`)
      if (Number(s.nb_frames) !== esperados) problemas.push(`${s.nb_frames} fotogramas (se esperaban ${esperados})`)
    }
    if (a.length) problemas.push('tiene audio, y las presentaciones no tienen narración')
    if (!existsSync(path.join(SALIDA, 'miniaturas', `${video.archivo}.jpg`))) problemas.push('sin miniatura')
    if (problemas.length) fallas.push(`${video.archivo}: ${problemas.join('; ')}`)

    // un fotograma por escena, sacado del MP4
    const dir = path.join(SALIDA, 'control', video.archivo)
    rmSync(dir, { recursive: true, force: true })
    mkdirSync(dir, { recursive: true })
    const inicios = iniciosEscenas(video)
    video.escenas.forEach((e, i) => {
      const momentos = momentosDeControl(e)
      const t = (inicios[i] + momentos[momentos.length - 1]) / FPS
      remotion('ffmpeg', ['-y', '-loglevel', 'error', '-ss', t.toFixed(3), '-i', mp4, '-frames:v', '1', '-q:v', '3', path.join(dir, `${String(i + 1).padStart(2, '0')}-${e.id}.jpg`)])
    })
    const mb = (Number(info.format.size) / 1e6).toFixed(1)
    console.log(`${video.archivo}: ${problemas.length ? 'FALLA' : 'bien'} · h264 1920×1080 ${FPS} fps · ${reloj(esperados)} · ${mb} MB · ${video.escenas.length} fotogramas de control`)
  }
  for (const f of fallas) console.error(`FALLA ${f}`)
  if (fallas.length) process.exit(1)
}

main()
