// Prepara lo que las composiciones leen y no se versiona dos veces:
//   public/fuentes    las fuentes Source (woff2) de docs/manuales/estilos/fuentes
//   public/marca      la marca editorial de los manuales (no es el escudo de la municipalidad)
//   public/capturas   las capturas a 2× de recursos/capturas (las toma npm run capturas)
// y escribe los índices que el código importa:
//   src/generado/capturas.json   tamaño en píxeles CSS de cada captura
//   src/generado/anclas.json     título de cada sección [[ancla]] de los manuales, para citar la fuente
// Se corre solo antes de npm run dev y de npm run render. node scripts/recursos.ts --comprobar no escribe nada: falla
// si los índices versionados no coinciden con lo que escribiría (npm run verificar lo usa).
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const aqui = path.dirname(fileURLToPath(import.meta.url))
export const RAIZ = path.resolve(aqui, '..')
export const MANUALES = path.resolve(RAIZ, '..', 'manuales')
export const CAPTURAS = path.join(RAIZ, 'recursos', 'capturas')
/** Las capturas de las presentaciones se toman a esta escala (deviceScaleFactor de Playwright). */
export const ESCALA = 2

const GENERADO = path.join(RAIZ, 'src', 'generado')

/** Ancho y alto de un PNG, de su cabecera IHDR. */
export const tamanoPng = (archivo: string): { ancho: number; alto: number } => {
  const b = readFileSync(archivo)
  if (b.toString('ascii', 12, 16) !== 'IHDR') throw new Error(`${archivo} no es un PNG`)
  return { ancho: b.readUInt32BE(16), alto: b.readUInt32BE(20) }
}

const archivosBajo = (dir: string, ext: string): string[] => {
  if (!existsSync(dir)) return []
  return readdirSync(dir, { recursive: true, encoding: 'utf8' })
    .filter((a) => a.endsWith(ext))
    .map((a) => a.split(path.sep).join('/'))
    .sort()
}

export const indiceCapturas = (): Record<string, { ancho: number; alto: number }> => {
  const indice: Record<string, { ancho: number; alto: number }> = {}
  for (const rel of archivosBajo(CAPTURAS, '.png')) {
    const { ancho, alto } = tamanoPng(path.join(CAPTURAS, rel))
    indice[rel] = { ancho: Math.round(ancho / ESCALA), alto: Math.round(alto / ESCALA) }
  }
  return indice
}

// --- títulos de las secciones -----------------------------------------------------------------------------------

const atributos = (): Record<string, string> => {
  const valores: Record<string, string> = {}
  for (const linea of readFileSync(path.join(MANUALES, 'atributos.adoc'), 'utf8').split('\n')) {
    const m = linea.match(/^:([\w-]+):\s*(.*)$/)
    if (m) valores[m[1]] = m[2]
  }
  return valores
}

/** Un título de AsciiDoc en texto plano: sin atributos, macros ni marcas de formato. */
export const textoPlano = (s: string, attrs: Record<string, string>): string =>
  s
    .replace(/\{([\w-]+)\}/g, (_, n: string) => attrs[n] ?? `{${n}}`)
    .replace(/btn:\[([^\]]*)\]/g, '$1')
    .replace(/kbd:\[([^\]]*)\]/g, '$1')
    .replace(/menu:([^[]+)\[([^\]]*)\]/g, (_, a: string, b: string) => [a, ...b.split('>')].map((x) => x.trim()).filter(Boolean).join(' › '))
    .replace(/<<[^>]*>>/g, '')
    .replace(/[*_`]|\+\+?/g, '')
    .trim()

export const indiceAnclas = (): Record<string, string> => {
  const attrs = atributos()
  const indice: Record<string, string> = {}
  for (const rel of archivosBajo(MANUALES, '.adoc')) {
    if (rel === 'README.adoc' || rel.startsWith('salida/') || rel.startsWith('herramientas/')) continue
    const doc = rel.replace(/\.adoc$/, '')
    const lineas = readFileSync(path.join(MANUALES, rel), 'utf8').split('\n')
    lineas.forEach((linea, i) => {
      // un encabezado sin ancla propia tiene el id que le da Asciidoctor: _manuales_de_usuario
      const solo = linea.match(/^=+\s+(.*)$/)
      if (solo && !/^\[\[/.test(lineas[i - 1] ?? '')) {
        const texto = textoPlano(solo[1], attrs)
        const id = `_${texto.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '_').replace(/^_+|_+$/g, '')}`
        indice[`${doc}#${id}`] = texto
      }
      const m = linea.match(/^\[\[([\w-]+)(?:,[^\]]*)?\]\]\s*$/)
      if (!m) return
      // el título es la primera línea con contenido que sigue al ancla: un encabezado (== …) o el título de un
      // bloque (.Título); las líneas de atributos ([NOTE], [cols=…]) y los comentarios se saltan
      for (let j = i + 1; j < Math.min(lineas.length, i + 6); j++) {
        const l = lineas[j].trim()
        if (!l || l.startsWith('//') || l.startsWith('[')) continue
        const encabezado = l.match(/^=+\s+(.*)$/)
        const titulo = l.match(/^\.([^.\s].*)$/)
        const texto = encabezado?.[1] ?? titulo?.[1]
        if (texto) indice[`${doc}#${m[1]}`] = textoPlano(texto, attrs)
        break
      }
    })
  }
  return indice
}

const json = (v: unknown): string => `${JSON.stringify(v, null, 2)}\n`

export const indices = (): Record<string, string> => ({
  'capturas.json': json(indiceCapturas()),
  'anclas.json': json(indiceAnclas()),
})

const main = (): void => {
  const comprobar = process.argv.includes('--comprobar')
  const escritos = indices()
  if (comprobar) {
    const viejos = Object.entries(escritos).filter(
      ([nombre, contenido]) =>
        !existsSync(path.join(GENERADO, nombre)) || readFileSync(path.join(GENERADO, nombre), 'utf8') !== contenido,
    )
    if (viejos.length) {
      console.error(`recursos: src/generado desactualizado (${viejos.map(([n]) => n).join(', ')}): npm run recursos`)
      process.exit(1)
    }
    return
  }
  mkdirSync(GENERADO, { recursive: true })
  for (const [nombre, contenido] of Object.entries(escritos)) writeFileSync(path.join(GENERADO, nombre), contenido)

  // se copia encima, sin vaciar public/ antes: un render en curso sigue encontrando sus archivos; lo que ya no está en
  // recursos/capturas se borra después
  const publico = path.join(RAIZ, 'public')
  cpSync(path.join(MANUALES, 'estilos', 'fuentes', 'woff2'), path.join(publico, 'fuentes'), { recursive: true })
  cpSync(path.join(MANUALES, 'recursos', 'marca'), path.join(publico, 'marca'), { recursive: true })
  if (existsSync(CAPTURAS)) cpSync(CAPTURAS, path.join(publico, 'capturas'), { recursive: true })
  const vigentes = new Set(archivosBajo(CAPTURAS, '.png'))
  for (const rel of archivosBajo(path.join(publico, 'capturas'), '.png')) {
    if (!vigentes.has(rel)) rmSync(path.join(publico, 'capturas', rel))
  }
  const n = Object.keys(JSON.parse(escritos['capturas.json'])).length
  console.log(`recursos: public/ listo (${n} capturas); src/generado al día`)
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
