// Pruebas de consistencia de las presentaciones, sin renderizar nada:
//   - los índices de src/generado y los storyboards están al día con los manuales, las capturas y los datos;
//   - cada escena cita al menos una fuente, y cada fuente es un ancla que existe en los manuales;
//   - cada captura existe, y cada región o encuadre cae dentro de su imagen;
//   - cada arista de un diagrama une nodos que existen y cada nodo cabe en su lienzo;
//   - cada texto se queda en pantalla el tiempo que hace falta para leerlo;
//   - los ids son únicos y válidos, y las duraciones son razonables.
// Sale con 1 si algo falla. node scripts/verificar.ts
import { existsSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { MANUALES, RAIZ, indiceAnclas, indiceCapturas } from './recursos.ts'
import { VIDEOS } from '../src/videos/index.ts'
import type { Escena, Region, Video } from '../src/tipos.ts'
import { FPS, TRANSICION, fotogramasEscena, fotogramasVideo, reloj, segundosEscena } from '../src/tiempo.ts'

const fallas: string[] = []
const avisos: string[] = []
const falla = (donde: string, que: string): void => {
  fallas.push(`${donde}: ${que}`)
}

// velocidad de lectura en pantalla, en palabras por segundo: unos 17 caracteres por segundo, lo que se pide a un
// subtítulo, para un texto corrido; algo más para una tabla o unas tarjetas, que se recorren
const LECTURA = 3
const RECORRIDO = 3.6

const palabras = (...textos: (string | undefined)[]): number =>
  textos
    .filter((t): t is string => Boolean(t))
    .join(' ')
    .split(/\s+/)
    .filter((p) => /[\p{L}\p{N}]/u.test(p)).length

/** Los segundos mínimos para leer lo que muestra una escena (las pantallas y los pasos se miden aparte). */
const lecturaMinima = (e: Escena): number => {
  switch (e.tipo) {
    case 'portada':
      return 2 + palabras(e.antetitulo, e.titulo, e.subtitulo, e.audiencia) / LECTURA
    case 'seccion':
      return 1.5 + palabras(e.titulo, e.bajada) / LECTURA
    case 'mensaje':
      return 2 + palabras(e.antetitulo, e.frase, e.apoyo) / LECTURA
    case 'tarjetas':
      return 2.5 + palabras(e.titulo, e.entrada, e.nota, ...e.tarjetas.flatMap((t) => [t.rotulo, t.titulo, t.texto])) / RECORRIDO
    case 'proceso':
      return 2.5 + palabras(e.titulo, e.entrada, e.nota, ...e.pasos.flatMap((p) => [p.titulo, p.texto, p.quien])) / RECORRIDO
    case 'comparacion':
      return 2.5 + palabras(e.titulo, e.entrada, e.nota, ...e.columnas.flatMap((c) => [c.titulo, ...c.puntos])) / RECORRIDO
    case 'tabla':
      return 2.5 + palabras(e.titulo, e.entrada, e.nota, ...e.encabezados, ...e.filas.flat()) / RECORRIDO
    case 'calculo':
      return 3 + palabras(e.titulo, e.entrada, e.formula, e.nota, ...e.filas.flatMap((f) => [f.concepto, f.detalle, f.valor])) / LECTURA
    case 'codigo':
      return 3 + palabras(e.titulo, e.nota, ...e.bloques.flatMap((b) => [b.comentario, ...b.lineas, ...(b.salida ?? [])])) / LECTURA
    case 'aviso':
      return 2 + palabras(e.titulo, e.texto, ...(e.puntos ?? [])) / LECTURA
    case 'cierre':
      return 2.5 + palabras(e.titulo, ...e.puntos, ...e.manuales, e.siguiente) / RECORRIDO
    case 'diagrama':
      return e.pasos ? 0 : 3 + palabras(e.titulo, e.entrada, e.nota, ...e.nodos.flatMap((n) => [n.titulo, n.texto])) / RECORRIDO
    case 'pantalla':
    case 'secuencia':
      return 0
  }
}

const verificarRegion = (donde: string, r: Region | undefined, img: { ancho: number; alto: number } | undefined): void => {
  if (!r || !img) return
  if (r.w <= 0 || r.h <= 0) falla(donde, `región vacía ${JSON.stringify(r)}`)
  if (r.x < 0 || r.y < 0 || r.x + r.w > img.ancho + 1 || r.y + r.h > img.alto + 1) {
    falla(donde, `región ${JSON.stringify(r)} fuera de la captura (${img.ancho} × ${img.alto})`)
  }
}

const verificarVideo = (v: Video, anclas: Record<string, string>, capturas: Record<string, { ancho: number; alto: number }>): void => {
  if (!/^[a-zA-Z0-9-]+$/.test(v.id)) falla(v.id, 'el id de una composición solo lleva letras, números y guiones')
  const ids = new Set<string>()
  for (const e of v.escenas) {
    const donde = `${v.id} › ${e.id}`
    if (ids.has(e.id)) falla(donde, 'id de escena repetido')
    ids.add(e.id)
    if (!e.objetivo.trim()) falla(donde, 'sin objetivo de aprendizaje')
    if (!e.fuentes.length) falla(donde, 'sin fuente')
    for (const f of e.fuentes) {
      if (!existsSync(path.join(MANUALES, `${f.doc}.adoc`))) falla(donde, `no existe docs/manuales/${f.doc}.adoc`)
      else if (!anclas[`${f.doc}#${f.ancla}`]) falla(donde, `${f.doc}.adoc no tiene el ancla [[${f.ancla}]]`)
    }
    if (e.tipo === 'pantalla') {
      for (const f of e.focos.flatMap((x) => (x.fuente ? [x.fuente] : []))) {
        if (!anclas[`${f.doc}#${f.ancla}`]) falla(donde, `${f.doc}.adoc no tiene el ancla [[${f.ancla}]]`)
      }
    }
    if ('titulo' in e && e.tipo !== 'portada' && e.titulo.length > 62) avisos.push(`${donde}: título largo (${e.titulo.length} caracteres)`)

    const segundos = segundosEscena(e)
    if (fotogramasEscena(e) <= TRANSICION * 2) falla(donde, `dura ${segundos} s: menos que dos fundidos`)
    const minimo = lecturaMinima(e)
    if (segundos + 0.05 < minimo) falla(donde, `dura ${segundos.toFixed(1)} s y su texto pide ${minimo.toFixed(1)} s`)

    if (e.tipo === 'pantalla') {
      const img = capturas[e.captura]
      if (!img) falla(donde, `no existe la captura recursos/capturas/${e.captura}`)
      verificarRegion(`${donde} (encuadre)`, e.encuadre, img)
      let actual = img
      e.focos.forEach((f, i) => {
        const dondeFoco = `${donde} › foco ${i + 1}`
        if (f.captura) {
          actual = capturas[f.captura]
          if (!actual) falla(dondeFoco, `no existe la captura recursos/capturas/${f.captura}`)
        }
        verificarRegion(dondeFoco, f.region, actual)
        verificarRegion(`${dondeFoco} (encuadre)`, f.encuadre, actual)
        const necesita = 1.5 + palabras(f.titulo, f.texto) / LECTURA
        if (f.segundos + 0.05 < necesita) falla(dondeFoco, `dura ${f.segundos} s y su texto pide ${necesita.toFixed(1)} s`)
      })
    }
    if (e.tipo === 'aviso' && e.captura) {
      const img = capturas[e.captura.archivo]
      if (!img) falla(donde, `no existe la captura recursos/capturas/${e.captura.archivo}`)
      verificarRegion(donde, e.captura.region, img)
    }
    if (e.tipo === 'diagrama') {
      const nodos = new Map(e.nodos.map((n) => [n.id, n]))
      if (nodos.size !== e.nodos.length) falla(donde, 'id de nodo repetido')
      for (const n of e.nodos) {
        if (n.x < 0 || n.y < 0 || n.x + n.w > e.lienzo.ancho || n.y + n.h > e.lienzo.alto) falla(donde, `el nodo ${n.id} se sale del lienzo`)
        if ((n.paso ?? 0) > (e.pasos?.length ?? 0)) falla(donde, `el nodo ${n.id} aparece en un paso que no existe`)
      }
      for (const a of e.aristas) {
        if (!nodos.has(a.de) || !nodos.has(a.a)) falla(donde, `la arista ${a.de} → ${a.a} nombra un nodo que no existe`)
        if ((a.paso ?? 0) > (e.pasos?.length ?? 0)) falla(donde, `la arista ${a.de} → ${a.a} aparece en un paso que no existe`)
      }
      e.pasos?.forEach((p, i) => {
        const necesita = 1.5 + palabras(p.texto) / LECTURA
        if (p.segundos + 0.05 < necesita) falla(`${donde} › paso ${i + 1}`, `dura ${p.segundos} s y su texto pide ${necesita.toFixed(1)} s`)
      })
    }
    if (e.tipo === 'secuencia') {
      const ps = new Set(e.participantes.map((p) => p.id))
      e.mensajes.forEach((m, i) => {
        if (!ps.has(m.de) || !ps.has(m.a)) falla(`${donde} › mensaje ${i + 1}`, 'nombra un participante que no existe')
        const necesita = 1.5 + palabras(m.texto, m.detalle) / LECTURA
        if (e.segundosPorMensaje + 0.05 < necesita) falla(`${donde} › mensaje ${i + 1}`, `dura ${e.segundosPorMensaje} s y su texto pide ${necesita.toFixed(1)} s`)
      })
    }
  }
  const mini = v.escenas.find((e) => e.id === v.miniatura.escena)
  if (!mini) falla(v.id, `la miniatura nombra la escena ${v.miniatura.escena}, que no existe`)
  else if (v.miniatura.segundo >= segundosEscena(mini)) falla(v.id, 'el segundo de la miniatura pasa el final de su escena')
  const total = fotogramasVideo(v) / FPS
  if (total < 60 || total > 600) avisos.push(`${v.id}: dura ${reloj(fotogramasVideo(v))}`)
}

const main = (): void => {
  try {
    execFileSync(process.execPath, [path.join(RAIZ, 'scripts', 'recursos.ts'), '--comprobar'], { stdio: 'inherit' })
  } catch {
    fallas.push('src/generado no está al día: npm run recursos')
  }
  try {
    execFileSync(process.execPath, [path.join(RAIZ, 'scripts', 'storyboards.ts'), '--comprobar'], { stdio: 'inherit' })
  } catch {
    fallas.push('storyboards/ no está al día: npm run storyboards')
  }
  const anclas = indiceAnclas()
  const capturas = indiceCapturas()
  const unicos = (clave: (v: Video) => string, que: string): void => {
    const vistos = new Set<string>()
    for (const v of VIDEOS) {
      if (vistos.has(clave(v))) falla(v.id, `${que} repetido`)
      vistos.add(clave(v))
    }
  }
  unicos((v) => v.id, 'id')
  unicos((v) => v.archivo, 'archivo')
  unicos((v) => v.numero, 'número')
  for (const v of VIDEOS) verificarVideo(v, anclas, capturas)

  // capturas que nadie usa: peso muerto en el repositorio
  const usadas = new Set(
    VIDEOS.flatMap((v) =>
      v.escenas.flatMap((e) =>
        e.tipo === 'pantalla' ? [e.captura, ...e.focos.flatMap((f) => (f.captura ? [f.captura] : []))] : e.tipo === 'aviso' && e.captura ? [e.captura.archivo] : [],
      ),
    ),
  )
  for (const c of Object.keys(capturas)) if (!usadas.has(c)) avisos.push(`recursos/capturas/${c}: ninguna escena la usa (npm run capturas la quita)`)

  for (const a of avisos) console.warn(`aviso: ${a}`)
  for (const f of fallas) console.error(`FALLA ${f}`)
  const escenas = VIDEOS.reduce((s, v) => s + v.escenas.length, 0)
  const total = VIDEOS.reduce((s, v) => s + fotogramasVideo(v), 0)
  console.log(`verificar: ${VIDEOS.length} vídeos, ${escenas} escenas, ${reloj(total)} en total; ${fallas.length} fallas, ${avisos.length} avisos`)
  if (fallas.length) process.exit(1)
}

main()
