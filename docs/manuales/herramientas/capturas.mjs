// Capturas de los manuales con Playwright, contra la demo (demo/README.adoc). Lee capturas.json y escribe cada
// captura en recursos/capturas/<archivo>. Se corre desde herramientas/: node capturas.mjs [perfil|id …]
//
// Cada captura entra con la cuenta de su perfil (demo/perfiles.json; la clave, de SRTM_DEMO_PASSWORD), recorre sus
// pasos y toma la página o un recorte. Las referencias ({ref:nombre}) se resuelven antes por la API de la demo,
// así los pasos no dependen de los ids que tocaron al sembrar.
import { chromium } from 'playwright'
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import os from 'node:os'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const aqui = path.dirname(fileURLToPath(import.meta.url))
const raiz = path.resolve(aqui, '..')
const config = JSON.parse(await readFile(path.join(aqui, 'capturas.json'), 'utf8'))
// las capturas de cada manual, en capturas/<manual>.json: sus referencias se suman a las comunes
config.referencias = {}
config.capturas = []
for (const archivo of (await readdir(path.join(aqui, 'capturas'))).filter((a) => a.endsWith('.json')).sort()) {
  const parte = JSON.parse(await readFile(path.join(aqui, 'capturas', archivo), 'utf8'))
  Object.assign(config.referencias, parte.referencias ?? {})
  config.capturas.push(...(parte.capturas ?? []).map((c) => ({ ...c, manual: archivo.replace(/\.json$/, '') })))
}
const perfiles = JSON.parse(await readFile(path.join(raiz, 'demo', 'perfiles.json'), 'utf8'))

// demo.env (si existe) da los valores por defecto; el entorno manda
const entorno = { ...(await leerEnv(path.join(raiz, 'demo', 'demo.env'))), ...process.env }
const UI = entorno.SRTM_DEMO_UI ?? 'http://localhost:5183'
const CORE = entorno.WASICHAI_CORE ?? 'http://localhost:8098'
const CLAVE_DEMO = entorno.SRTM_DEMO_PASSWORD ?? 'Demo-2026'
// las presentaciones (docs/presentaciones) toman las mismas capturas a otra escala y en su propia carpeta
const ESCALA = Number(entorno.SRTM_CAPTURAS_ESCALA ?? config.escala ?? 1)
const DESTINO = path.resolve(aqui, entorno.SRTM_CAPTURAS_DESTINO ?? path.join(raiz, 'recursos', 'capturas'))
// nunca contra la base de desarrollo: la demo vive en :8098 (demo/demo.env.example)
if (!/:(8098|8099)\b/.test(CORE) && !entorno.SRTM_DEMO_PERMITIR_OTRO_CORE) {
  console.error(`capturas: ${CORE} no es el Core de la demo (:8098). SRTM_DEMO_PERMITIR_OTRO_CORE=1 para forzarlo`)
  process.exit(2)
}

// cada argumento es un manual (el nombre de su capturas/<manual>.json), un id o, si no es ninguno de los dos, un
// perfil: «registrador» es el manual del registrador, no todas las capturas con ese perfil
const filtro = process.argv.slice(2)
const manuales = new Set(config.capturas.map((c) => c.manual))
const ids = new Set(config.capturas.map((c) => c.id))
const elegidas = config.capturas.filter(
  (c) =>
    filtro.length === 0 ||
    filtro.some((f) => (manuales.has(f) ? c.manual === f : ids.has(f) ? c.id === f : c.perfil === f)),
)

async function leerEnv(archivo) {
  if (!existsSync(archivo)) return {}
  const valores = {}
  for (const linea of (await readFile(archivo, 'utf8')).split('\n')) {
    const m = linea.match(/^([A-Z0-9_]+)=(.*)$/)
    if (m) valores[m[1]] = m[2]
  }
  return valores
}

function cuenta(perfil) {
  if (perfil === 'admin') {
    return { email: entorno.WASICHAI_EMAIL ?? 'admin@wasichai.local', clave: entorno.WASICHAI_PASSWORD ?? 'admin' }
  }
  // registrador → REGISTRADOR, arbitrios → ARBITRIOS_EMISION
  const usuario = perfiles.perfiles.find((u) => u.rol.toLowerCase().split('_')[0] === perfil.toLowerCase())
  if (!usuario) throw new Error(`perfil sin usuario en demo/perfiles.json: ${perfil}`)
  return { email: usuario.email, clave: CLAVE_DEMO }
}

// --- referencias: GET a la API de la demo, como admin ---------------------------------------------------------

async function token(perfil = 'admin') {
  const { email, clave } = cuenta(perfil)
  const r = await fetch(`${CORE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: clave }),
  })
  if (!r.ok) throw new Error(`login en ${CORE}: ${r.status}`)
  return (await r.json()).token
}

function extraer(valor, ruta) {
  return ruta.split('.').reduce((v, k) => (v == null ? v : v[k]), valor)
}

async function resolverReferencias() {
  const definiciones = config.referencias ?? {}
  if (Object.keys(definiciones).length === 0) return {}
  const bearer = await token()
  const valores = {}
  for (const [nombre, def] of Object.entries(definiciones)) {
    // una referencia puede usar las anteriores: /api/srtm/contribuyentes/{ref:contribuyente}/declaraciones
    const ruta = sustituir(def.get, valores)
    const r = await fetch(`${CORE}${ruta}`, { headers: { Authorization: `Bearer ${bearer}` } })
    if (!r.ok) throw new Error(`referencia ${nombre}: GET ${ruta} → ${r.status}`)
    const v = extraer(await r.json(), def.campo)
    if (v == null) throw new Error(`referencia ${nombre}: ${def.campo} vacío en GET ${ruta}`)
    valores[nombre] = String(v)
  }
  return valores
}

function sustituir(texto, refs) {
  return typeof texto === 'string'
    ? texto.replace(/\{ref:([a-z0-9_-]+)\}/gi, (_, n) => {
        if (!(n in refs)) throw new Error(`referencia sin definir: ${n}`)
        return refs[n]
      })
    : texto
}

// --- pasos ------------------------------------------------------------------------------------------------------

const MARCA = 'srtm-manual-marca'

async function paso(page, p, refs) {
  const [accion, ...args] = Array.isArray(p) ? p : [p]
  const a = args.map((x) => sustituir(x, refs))
  switch (accion) {
    case 'ir':
      // load y no networkidle: una página con mapa sigue pidiendo teselas; preparar espera la red con un tope
      await page.goto(`${UI}${a[0]}`, { waitUntil: 'load' })
      return
    case 'clic':
      await page.locator(a[0]).first().click()
      return
    case 'llenar':
      await page.locator(a[0]).first().fill(a[1])
      return
    case 'escribir':
      await page.locator(a[0]).first().pressSequentially(a[1], { delay: 30 })
      return
    case 'elegir':
      await page.locator(a[0]).first().selectOption({ label: a[1] })
      return
    case 'tecla':
      await page.keyboard.press(a[0])
      return
    case 'esperar':
      await page.locator(a[0]).first().waitFor({ state: 'visible', timeout: a[1] ? Number(a[1]) : 20000 })
      return
    case 'esperarTexto':
      await page.getByText(a[0], { exact: false }).first().waitFor({ state: 'visible', timeout: 20000 })
      return
    case 'pausa':
      await page.waitForTimeout(Number(a[0]))
      return
    case 'desplazar':
      await page.locator(a[0]).first().scrollIntoViewIfNeeded()
      return
    case 'arriba':
      await page.evaluate(() => window.scrollTo(0, 0))
      return
    case 'pasar':
      await page.locator(a[0]).first().hover()
      return
    case 'marcar':
      // un recuadro de color sobre lo que el texto nombra; no cambia la página
      await page.locator(a[0]).first().evaluate((el, clase) => el.classList.add(clase), MARCA)
      return
    default:
      throw new Error(`paso desconocido: ${accion}`)
  }
}

// el tema es una preferencia del usuario guardada en el servidor, que manda sobre localStorage: la misma que deja el
// menú Tema de la cabecera (PUT /api/auth/me/preferences)
async function fijarTema(email, clave) {
  const r = await fetch(`${CORE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: clave }),
  })
  if (!r.ok) throw new Error(`login de ${email}: ${r.status}`)
  const { token: bearer } = await r.json()
  const p = await fetch(`${CORE}/api/auth/me/preferences`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${bearer}` },
    body: JSON.stringify({ theme: config.tema ?? 'portal-tributario' }),
  })
  if (!p.ok) throw new Error(`tema de ${email}: ${p.status}`)
}

async function entrar(contexto, perfil) {
  const page = await contexto.newPage()
  const { email, clave } = cuenta(perfil)
  await fijarTema(email, clave)
  await page.goto(`${UI}/login`, { waitUntil: 'networkidle' })
  await page.locator('#email').fill(email)
  await page.locator('#password').fill(clave)
  await page.getByRole('button', { name: 'Ingresar' }).click()
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 20000 })
  await page.close()
}

async function preparar(page) {
  await page.addStyleTag({
    content: `
      *, *::before, *::after { animation: none !important; transition: none !important; caret-color: transparent !important; }
      .${MARCA} { outline: 3px solid #E8740C !important; outline-offset: 3px !important; border-radius: 3px; }
    `,
  })
  await page.evaluate(() => document.fonts.ready)
  // ningún indicador de carga a la vista
  await page
    .waitForFunction(() => !document.querySelector('[aria-busy="true"], .animate-spin'), null, { timeout: 15000 })
    .catch(() => {})
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {})
}

async function capturarPdf(c, refs) {
  const bearer = await token(c.perfil)
  const ruta = sustituir(c.pdf, refs)
  const r = await fetch(`${CORE}${ruta}`, { headers: { Authorization: `Bearer ${bearer}` } })
  if (!r.ok) throw new Error(`GET ${ruta} → ${r.status}`)
  const temporal = await mkdtemp(path.join(os.tmpdir(), 'srtm-captura-'))
  try {
    const pdf = path.join(temporal, 'documento.pdf')
    await writeFile(pdf, Buffer.from(await r.arrayBuffer()))
    const destino = path.join(DESTINO, c.archivo)
    await mkdir(path.dirname(destino), { recursive: true })
    const pagina = String(c.pagina ?? 1)
    execFileSync('pdftoppm', ['-r', String(Math.round((c.resolucion ?? 110) * ESCALA)), '-f', pagina, '-l', pagina, '-png', '-singlefile', pdf, destino.replace(/\.png$/, '')])
  } finally {
    await rm(temporal, { recursive: true, force: true })
  }
}

async function main() {
  const refs = await resolverReferencias()
  // --lang: los campos de fecha nativos salen en dd/mm/aaaa, como los ve un usuario en español
  const navegador = await chromium.launch({ args: ['--lang=es-PE'] })
  const vista = config.vista ?? { width: 1440, height: 1000 }
  const sesiones = new Map()
  let hechas = 0
  const fallidas = []
  for (const c of elegidas) {
    if (c.pdf) {
      // un documento que emite el sistema (PU, HR, HLA, resolución): se pide a la API con la cuenta del perfil y se
      // dibuja una página con pdftoppm (poppler)
      try {
        await capturarPdf(c, refs)
        hechas += 1
        console.log(`captura: ${c.archivo}`)
      } catch (e) {
        fallidas.push(`${c.id}: ${e.message.split('\n')[0]}`)
        console.error(`FALLA ${c.id}: ${e.message.split('\n')[0]}`)
      }
      continue
    }
    let contexto = sesiones.get(c.perfil)
    if (!contexto) {
      // la vista de siempre: la de una captura (c.vista) se fija en su página, no en el contexto del perfil
      contexto = await navegador.newContext({
        viewport: vista,
        deviceScaleFactor: ESCALA,
        locale: 'es-PE',
        timezoneId: 'America/Lima',
        reducedMotion: 'reduce',
        colorScheme: 'light',
      })
      // también en localStorage, para que la página de ingreso (sin sesión) salga con el mismo tema
      await contexto.addInitScript((tema) => {
        try {
          localStorage.setItem('srtm.theme', tema)
        } catch {}
      }, config.tema ?? 'portal-tributario')
      if (c.perfil !== 'anonimo') await entrar(contexto, c.perfil)
      sesiones.set(c.perfil, contexto)
    }
    const page = await contexto.newPage()
    if (c.vista) await page.setViewportSize(c.vista)
    try {
      for (const p of c.pasos) {
        await paso(page, p, refs)
        if (Array.isArray(p) && p[0] === 'ir') await preparar(page)
      }
      await preparar(page)
      const destino = path.join(DESTINO, c.archivo)
      await mkdir(path.dirname(destino), { recursive: true })
      if (c.area) {
        // un rectángulo fijo de la vista: lo que abarca varias piezas (el riel y su panel, un menú y su botón)
        await page.screenshot({ path: destino, clip: c.area })
      } else if (c.recorte) {
        let el = page.locator(sustituir(c.recorte, refs)).first()
        // subir: el recorte toma el contenedor n niveles arriba (el texto «Sin permiso» y su botón)
        for (let i = 0; i < (c.subir ?? 0); i++) el = el.locator('xpath=..')
        await el.scrollIntoViewIfNeeded()
        const caja = await el.boundingBox()
        // anchoMax: de una franja ancha (una fila de botones alineada a la derecha), solo su extremo derecho
        if (c.anchoMax && caja.width > c.anchoMax) {
          caja.x += caja.width - c.anchoMax
          caja.width = c.anchoMax
        }
        // altoMax: de un bloque largo (una tabla), solo su comienzo
        if (c.altoMax && caja.height > c.altoMax) caja.height = c.altoMax
        const margen = c.margen ?? 12
        const tam = page.viewportSize()
        const x = Math.max(0, caja.x - margen)
        const y = Math.max(0, caja.y - margen)
        await page.screenshot({
          path: destino,
          clip: {
            x,
            y,
            width: Math.min(tam.width - x, caja.width + 2 * margen),
            height: Math.min(tam.height - y, caja.height + 2 * margen),
          },
        })
      } else {
        await page.screenshot({ path: destino, fullPage: Boolean(c.completa) })
      }
      hechas += 1
      console.log(`captura: ${c.archivo}`)
    } catch (e) {
      fallidas.push(`${c.id}: ${e.message.split('\n')[0]}`)
      console.error(`FALLA ${c.id}: ${e.message.split('\n')[0]}`)
      await page.screenshot({ path: path.join(aqui, `falla-${c.id.replace(/\W+/g, '-')}.png`) }).catch(() => {})
    } finally {
      await page.close()
    }
  }
  await navegador.close()
  console.log(`capturas: ${hechas} hechas, ${fallidas.length} fallidas`)
  if (fallidas.length) process.exit(1)
}

await main()
