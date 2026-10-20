// Lo que comparten renderizar.ts y fotogramas.ts: el empaquetado del proyecto (una vez por corrida) y la elección de
// los vídeos por argumento.
import path from 'node:path'
import { bundle } from '@remotion/bundler'
import { RAIZ } from './recursos.ts'
import { VIDEOS } from '../src/videos/index.ts'
import type { Video } from '../src/tipos.ts'

export const SALIDA = path.join(RAIZ, 'salida')

export const empaquetar = async (): Promise<string> => {
  let ultimo = -1
  return bundle({
    entryPoint: path.join(RAIZ, 'src', 'index.ts'),
    publicDir: path.join(RAIZ, 'public'),
    rspack: true,
    onProgress: (p) => {
      const decena = Math.floor(p / 25)
      if (decena !== ultimo) {
        ultimo = decena
        process.stdout.write(`empaquetando: ${p} %\r`)
      }
    },
  })
}

/** Los vídeos que nombran los argumentos (id de la composición, número o archivo); sin argumentos, todos. */
export const elegirVideos = (args: readonly string[]): Video[] => {
  const nombres = args.filter((a) => !a.startsWith('--'))
  if (!nombres.length) return [...VIDEOS]
  const elegidos = VIDEOS.filter((v) => nombres.some((n) => n === v.id || n === v.numero || n === v.archivo))
  const sobran = nombres.filter((n) => !VIDEOS.some((v) => n === v.id || n === v.numero || n === v.archivo))
  if (sobran.length) {
    console.error(`no hay presentación ${sobran.join(', ')}; las hay: ${VIDEOS.map((v) => `${v.numero} ${v.id}`).join(', ')}`)
    process.exit(2)
  }
  return elegidos
}
