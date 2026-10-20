// Vuelve a tomar, a 2×, las capturas que usan las presentaciones, con la misma herramienta y los mismos pasos que
// las de los manuales (docs/manuales/herramientas/capturas.mjs): cada captura de recursos/capturas es la del manual
// con el mismo nombre, con el doble de píxeles para que los acercamientos se lean nítidos. Necesita la base de
// demostración arriba (docs/manuales/demo/README.adoc); no graba nada en ella.
//   node scripts/capturas.ts            todas las que usan las escenas; borra las que ya nadie usa
//   node scripts/capturas.ts --listar   solo dice cuáles son
// La dirección de la demo sale, como en los manuales, de SRTM_DEMO_UI y WASICHAI_CORE (o de demo/demo.env).
import { existsSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { CAPTURAS, ESCALA, MANUALES } from './recursos.ts'
import { VIDEOS } from '../src/videos/index.ts'

const HERRAMIENTAS = path.join(MANUALES, 'herramientas')

const usadas = (): string[] =>
  [
    ...new Set(
      VIDEOS.flatMap((v) =>
        v.escenas.flatMap((e) =>
          e.tipo === 'pantalla' ? [e.captura, ...e.focos.flatMap((f) => (f.captura ? [f.captura] : []))] : e.tipo === 'aviso' && e.captura ? [e.captura.archivo] : [],
        ),
      ),
    ),
  ].sort()

/** archivo → id de la captura en herramientas/capturas/<manual>.json */
const idsPorArchivo = (): Map<string, string> => {
  const mapa = new Map<string, string>()
  const dir = path.join(HERRAMIENTAS, 'capturas')
  for (const a of readdirSync(dir).filter((x) => x.endsWith('.json'))) {
    const { capturas = [] } = JSON.parse(readFileSync(path.join(dir, a), 'utf8')) as { capturas?: { id: string; archivo: string }[] }
    for (const c of capturas) mapa.set(c.archivo, c.id)
  }
  return mapa
}

const main = (): void => {
  const archivos = usadas()
  const ids = idsPorArchivo()
  const sinId = archivos.filter((a) => !ids.has(a))
  if (sinId.length) {
    console.error(`capturas: ninguna definición de herramientas/capturas toma ${sinId.join(', ')}`)
    process.exit(2)
  }
  if (process.argv.includes('--listar')) {
    for (const a of archivos) console.log(`${ids.get(a)}\t${a}`)
    return
  }
  if (!existsSync(path.join(HERRAMIENTAS, 'node_modules', 'playwright'))) {
    execFileSync('npm', ['ci', '--no-audit', '--no-fund'], { cwd: HERRAMIENTAS, stdio: 'inherit' })
  }
  execFileSync(process.execPath, ['capturas.mjs', ...archivos.map((a) => ids.get(a) as string)], {
    cwd: HERRAMIENTAS,
    stdio: 'inherit',
    env: { ...process.env, SRTM_CAPTURAS_ESCALA: String(ESCALA), SRTM_CAPTURAS_DESTINO: CAPTURAS },
  })
  // las que ya ninguna escena usa
  const vigentes = new Set(archivos)
  const sobran = readdirSync(CAPTURAS, { recursive: true, encoding: 'utf8' })
    .filter((a) => a.endsWith('.png'))
    .map((a) => a.split(path.sep).join('/'))
    .filter((a) => !vigentes.has(a))
  for (const a of sobran) rmSync(path.join(CAPTURAS, a))
  console.log(`capturas: ${archivos.length} tomadas a ${ESCALA}×${sobran.length ? `, ${sobran.length} borradas porque nadie las usa` : ''}; ahora npm run recursos`)
}

main()
