// Escribe, con los mismos datos de src/videos, lo que se lee sin abrir el código:
//   storyboards/<archivo>.md   el storyboard de cada vídeo: escena por escena, objetivo, contenido, imagen,
//                              animación, duración, fuente y relación con la escena siguiente
//   storyboards/README.md      el índice: cada vídeo y cada escena con su fuente en los manuales
//   storyboards/manifiesto.json el manifiesto para herramientas
// node scripts/storyboards.ts --comprobar no escribe: falla si lo versionado no coincide (lo usa npm run verificar).
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { RAIZ, indiceAnclas } from './recursos.ts'
import { VIDEOS } from '../src/videos/index.ts'
import type { DocManual, Escena, Fuente, Video } from '../src/tipos.ts'
import { NOMBRE_DOC } from '../src/manuales.ts'
import { FPS, fotogramasEscena, fotogramasVideo, iniciosEscenas, reloj, segundosEscena } from '../src/tiempo.ts'

const DIR = path.join(RAIZ, 'storyboards')
const anclas = indiceAnclas()

const nombreDoc = (doc: DocManual): string => NOMBRE_DOC[doc]

const seccion = (f: Fuente): string => anclas[`${f.doc}#${f.ancla}`] ?? f.ancla
const ruta = (f: Fuente): string => `docs/manuales/${f.doc}.adoc#${f.ancla}`
const cita = (f: Fuente): string => `${nombreDoc(f.doc)} › ${seccion(f)} (\`${ruta(f)}\`)`

const segundos = (fotogramas: number): string => `${(fotogramas / FPS).toFixed(1).replace('.', ',')} s`

const tituloEscena = (e: Escena): string => (e.tipo === 'mensaje' ? e.frase : e.titulo)

const TIPO: Record<Escena['tipo'], string> = {
  portada: 'Portada',
  seccion: 'Separador de sección',
  mensaje: 'Mensaje principal',
  tarjetas: 'Tarjetas',
  pantalla: 'Captura recorrida',
  diagrama: 'Diagrama',
  proceso: 'Proceso',
  comparacion: 'Comparación',
  tabla: 'Tabla',
  calculo: 'Cálculo',
  codigo: 'Comandos',
  aviso: 'Aviso',
  secuencia: 'Secuencia',
  cierre: 'Cierre',
}

const ANIMACION: Record<Escena['tipo'], string> = {
  portada: 'El bloque azul baja; la marca, el título y el subtítulo entran escalonados; luego la audiencia y las fuentes.',
  seccion: 'El rótulo de la parte y el filete crecen; entran el título y la bajada.',
  mensaje: 'La frase sube y se asienta; la parte destacada se subraya; entra el apoyo.',
  tarjetas: 'Las tarjetas entran una a una, en orden de lectura; al final, la nota.',
  pantalla:
    'Vista general de la captura; en cada foco la cámara se acerca a la región, atenúa lo demás y la rodea con el recuadro naranja numerado; el panel de pasos marca el paso en curso.',
  diagrama: 'Los nodos aparecen por pasos y las flechas se dibujan; lo nuevo de cada paso se resalta un momento y su explicación aparece al pie.',
  proceso: 'Los pasos entran en orden, unidos por flechas o por una línea que crece.',
  comparacion: 'Las dos columnas entran y sus puntos aparecen uno a uno.',
  tabla: 'La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.',
  calculo: 'La fórmula aparece primero y luego cada fila de la cuenta, hasta el total.',
  codigo: 'Los bloques de comandos entran uno a uno, con lo que deben responder.',
  aviso: 'El recuadro del aviso entra y sus puntos aparecen uno a uno; a su lado, la captura, si la hay.',
  secuencia: 'Los participantes aparecen; cada mensaje se dibuja en orden, resaltado mientras se explica al pie.',
  cierre: 'Los puntos del resumen entran uno a uno; luego los manuales y el vídeo siguiente.',
}

const contenido = (e: Escena): string[] => {
  switch (e.tipo) {
    case 'portada':
      return [`${e.antetitulo}: ${e.titulo}`, e.subtitulo, `Para quién: ${e.audiencia}`]
    case 'seccion':
      return [`Parte ${e.numero}: ${e.titulo}`, e.bajada]
    case 'mensaje':
      return [e.frase, ...(e.apoyo ? [e.apoyo] : [])]
    case 'tarjetas':
      return [...(e.entrada ? [e.entrada] : []), ...e.tarjetas.map((t) => `${t.rotulo ? `${t.rotulo} · ` : ''}${t.titulo}${t.texto ? `: ${t.texto}` : ''}`), ...(e.nota ? [`Nota: ${e.nota}`] : [])]
    case 'pantalla':
      return e.focos.map((f, i) => `${i + 1}. ${f.titulo}${f.texto ? `: ${f.texto}` : ''}`)
    case 'diagrama':
      return [
        `Nodos: ${e.nodos.filter((n) => n.tipo !== 'grupo').map((n) => n.titulo).join(' · ')}`,
        ...(e.pasos ?? []).map((p, i) => `Paso ${i + 1}: ${p.texto}`),
        ...(e.nota ? [`Nota: ${e.nota}`] : []),
      ]
    case 'proceso':
      return [...e.pasos.map((p, i) => `${i + 1}. ${p.titulo}${p.texto ? `: ${p.texto}` : ''}${p.quien ? ` (${p.quien})` : ''}`), ...(e.nota ? [`Nota: ${e.nota}`] : [])]
    case 'comparacion':
      return e.columnas.flatMap((c) => [`${c.titulo}:`, ...c.puntos.map((p) => `  - ${p}`)])
    case 'tabla':
      return [e.encabezados.join(' | '), ...e.filas.map((f) => f.join(' | ')), ...(e.nota ? [`Nota: ${e.nota}`] : [])]
    case 'calculo':
      return [...(e.formula ? [e.formula] : []), ...e.filas.map((f) => `${f.concepto}${f.detalle ? ` (${f.detalle})` : ''}: ${f.valor}`), ...(e.nota ? [`Nota: ${e.nota}`] : [])]
    case 'codigo':
      return e.bloques.flatMap((b) => [...(b.comentario ? [`# ${b.comentario}`] : []), ...b.lineas.map((l) => `$ ${l}`), ...(b.salida ?? []).map((s) => `→ ${s}`)])
    case 'aviso':
      return [`${e.titulo}: ${e.texto}`, ...(e.puntos ?? []).map((p) => `  - ${p}`)]
    case 'secuencia':
      return e.mensajes.map((m, i) => `${i + 1}. ${m.de} → ${m.a}: ${m.texto}${m.detalle ? ` (${m.detalle})` : ''}`)
    case 'cierre':
      return [...e.puntos.map((p) => `✓ ${p}`), `Para saber más: ${e.manuales.join(' · ')}`, ...(e.siguiente ? [`Siguiente: ${e.siguiente}`] : [])]
  }
}

const imagen = (e: Escena): string => {
  switch (e.tipo) {
    case 'pantalla': {
      const otras = e.focos.flatMap((f) => (f.captura ? [f.captura] : []))
      return [e.captura, ...otras].map((c) => `captura real \`${c}\``).join(', ')
    }
    case 'aviso':
      return e.captura ? `captura real \`${e.captura.archivo}\`` : 'recuadro de aviso'
    case 'diagrama':
      return `diagrama de ${e.nodos.length} nodos y ${e.aristas.length} flechas, dibujado en la escena`
    case 'secuencia':
      return `diagrama de secuencia: ${e.participantes.map((p) => p.titulo).join(', ')}`
    default:
      return 'composición tipográfica, sin captura'
  }
}

const capturasDe = (e: Escena): string[] =>
  e.tipo === 'pantalla' ? [e.captura, ...e.focos.flatMap((f) => (f.captura ? [f.captura] : []))] : e.tipo === 'aviso' && e.captura ? [e.captura.archivo] : []

const celda = (s: string): string => s.replace(/\|/g, '\\|').replace(/\n/g, ' ')

const storyboard = (v: Video): string => {
  const inicios = iniciosEscenas(v)
  const l: string[] = []
  l.push(`# ${v.numero} · ${v.titulo}`, '')
  l.push('<!-- generado por scripts/storyboards.ts a partir de src/videos: no se edita a mano -->', '')
  l.push(`- **Composición:** \`${v.id}\` · **vídeo:** \`salida/videos/${v.archivo}.mp4\` · **miniatura:** \`salida/miniaturas/${v.archivo}.jpg\``)
  l.push(`- **Audiencia:** ${v.audiencia}`)
  l.push(`- **Objetivo:** ${v.objetivo}`)
  l.push(`- **Duración:** ${reloj(fotogramasVideo(v))} (${fotogramasVideo(v)} fotogramas a ${FPS} fps, 1920 × 1080) · **escenas:** ${v.escenas.length}`)
  l.push(`- **Manuales:** ${[...new Set(v.manuales.map(nombreDoc))].join(' · ')}`, '')
  l.push('| # | Escena | Tipo | Inicio | Duración | Fuente |', '|---|---|---|---|---|---|')
  v.escenas.forEach((e, i) => {
    l.push(`| ${i + 1} | ${celda(tituloEscena(e))} | ${TIPO[e.tipo]} | ${reloj(inicios[i])} | ${segundos(fotogramasEscena(e))} | ${celda(`${nombreDoc(e.fuentes[0].doc)} › ${seccion(e.fuentes[0])}`)} |`)
  })
  l.push('')
  v.escenas.forEach((e, i) => {
    const siguiente = v.escenas[i + 1]
    l.push(`## ${i + 1}. ${tituloEscena(e)}`, '')
    l.push(`- **Id:** \`${e.id}\` · **tipo:** ${TIPO[e.tipo]} · **inicio:** ${reloj(inicios[i])} · **duración:** ${segundos(fotogramasEscena(e))}`)
    l.push(`- **Objetivo de aprendizaje:** ${e.objetivo}`)
    l.push(`- **Imagen:** ${imagen(e)}`)
    l.push(`- **Animación:** ${ANIMACION[e.tipo]}`)
    l.push(`- **Fuentes:** ${e.fuentes.map(cita).join('; ')}`)
    l.push(`- **Sigue:** ${siguiente ? `${i + 2}. ${tituloEscena(siguiente)}` : 'fin del vídeo'}`)
    l.push('- **Contenido:**', '')
    for (const c of contenido(e)) l.push(`  > ${c}`)
    l.push('')
  })
  return `${l.join('\n').trimEnd()}\n`
}

const indice = (): string => {
  const l: string[] = []
  l.push('# Storyboards de las presentaciones', '')
  l.push('<!-- generado por scripts/storyboards.ts a partir de src/videos: no se edita a mano -->', '')
  l.push('Cada vídeo, cada escena y la sección de los manuales de la que sale. El detalle de cada escena está en su storyboard.', '')
  const total = VIDEOS.reduce((s, v) => s + fotogramasVideo(v), 0)
  l.push(`${VIDEOS.length} vídeos, ${VIDEOS.reduce((s, v) => s + v.escenas.length, 0)} escenas, ${reloj(total)} en total.`, '')
  l.push('| Vídeo | Audiencia | Duración | Escenas | Storyboard |', '|---|---|---|---|---|')
  for (const v of VIDEOS) l.push(`| ${v.numero} · ${v.titulo} | ${celda(v.audiencia)} | ${reloj(fotogramasVideo(v))} | ${v.escenas.length} | [${v.archivo}.md](${v.archivo}.md) |`)
  l.push('')
  for (const v of VIDEOS) {
    l.push(`## ${v.numero} · ${v.titulo}`, '', '| # | Escena | Fuentes |', '|---|---|---|')
    v.escenas.forEach((e, i) => l.push(`| ${i + 1} | ${celda(tituloEscena(e))} | ${celda(e.fuentes.map((f) => `\`${f.doc}.adoc#${f.ancla}\` (${seccion(f)})`).join('<br>'))} |`))
    l.push('')
  }
  return `${l.join('\n').trimEnd()}\n`
}

const manifiesto = (): string => {
  const datos = {
    formato: { ancho: 1920, alto: 1080, fps: FPS, codec: 'h264', audio: null },
    videos: VIDEOS.map((v) => {
      const inicios = iniciosEscenas(v)
      return {
        composicion: v.id,
        numero: v.numero,
        titulo: v.titulo,
        audiencia: v.audiencia,
        objetivo: v.objetivo,
        fotogramas: fotogramasVideo(v),
        duracion: reloj(fotogramasVideo(v)),
        salida: { video: `salida/videos/${v.archivo}.mp4`, miniatura: `salida/miniaturas/${v.archivo}.jpg` },
        storyboard: `storyboards/${v.archivo}.md`,
        escenas: v.escenas.map((e, i) => ({
          id: e.id,
          tipo: e.tipo,
          titulo: tituloEscena(e),
          objetivo: e.objetivo,
          inicio: inicios[i],
          fotogramas: fotogramasEscena(e),
          segundos: segundosEscena(e),
          fuentes: e.fuentes.map((f) => ({ archivo: `docs/manuales/${f.doc}.adoc`, ancla: f.ancla, seccion: seccion(f) })),
          capturas: capturasDe(e).map((c) => `recursos/capturas/${c}`),
        })),
      }
    }),
  }
  return `${JSON.stringify(datos, null, 2)}\n`
}

export const archivosStoryboards = (): Record<string, string> => {
  const archivos: Record<string, string> = { 'README.md': indice(), 'manifiesto.json': manifiesto() }
  for (const v of VIDEOS) archivos[`${v.archivo}.md`] = storyboard(v)
  return archivos
}

const main = (): void => {
  const archivos = archivosStoryboards()
  if (process.argv.includes('--comprobar')) {
    const viejos = Object.entries(archivos).filter(([n, c]) => !existsSync(path.join(DIR, n)) || readFileSync(path.join(DIR, n), 'utf8') !== c)
    if (viejos.length) {
      console.error(`storyboards: desactualizados (${viejos.map(([n]) => n).join(', ')}): npm run storyboards`)
      process.exit(1)
    }
    return
  }
  mkdirSync(DIR, { recursive: true })
  for (const [n, c] of Object.entries(archivos)) writeFileSync(path.join(DIR, n), c)
  console.log(`storyboards: ${Object.keys(archivos).length} archivos en storyboards/`)
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
