// La página de las presentaciones en el sitio de los manuales (GitHub Pages): presentaciones/index.html, con cada
// vídeo, su miniatura, su audiencia y su duración. Se escribe junto a los MP4 y las miniaturas ya renderizados.
//   node scripts/sitio.ts <carpeta>     <carpeta>/videos/*.mp4 y <carpeta>/miniaturas/*.jpg → <carpeta>/index.html
// Usa las fuentes del sitio de los manuales (../html/estilos/fuentes), que está al lado en el mismo sitio. Solo
// necesita Node: no importa nada de node_modules.
import { existsSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { VIDEOS } from '../src/videos/index.ts'
import { fotogramasVideo, reloj } from '../src/tiempo.ts'

const html = (s: string): string => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

const REPO = 'https://github.com/wasichai/srtm-backend/blob/main/docs/presentaciones/storyboards'

const main = (): void => {
  const carpeta = process.argv[2]
  if (!carpeta) {
    console.error('uso: node scripts/sitio.ts <carpeta con videos/ y miniaturas/>')
    process.exit(2)
  }
  const faltan = VIDEOS.filter((v) => !existsSync(path.join(carpeta, 'videos', `${v.archivo}.mp4`)))
  if (faltan.length) {
    console.error(`sitio: faltan los MP4 de ${faltan.map((v) => v.numero).join(', ')}`)
    process.exit(1)
  }
  const total = VIDEOS.reduce((s, v) => s + fotogramasVideo(v), 0)
  const tarjetas = VIDEOS.map(
    (v) => `    <article>
      <video controls preload="none" poster="miniaturas/${v.archivo}.jpg" src="videos/${v.archivo}.mp4"></video>
      <p class="numero">${v.numero} · ${reloj(fotogramasVideo(v))}</p>
      <h2>${html(v.titulo)}</h2>
      <p class="audiencia">${html(v.audiencia)}</p>
      <p class="enlaces"><a href="videos/${v.archivo}.mp4" download>Descargar MP4</a> · <a href="${REPO}/${v.archivo}.md">Storyboard y fuentes</a></p>
    </article>`,
  ).join('\n')
  const pagina = `<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Presentaciones de Rentas municipales</title>
<style>
@font-face { font-family: "Source Sans 3"; src: url("../html/estilos/fuentes/woff2/SourceSans3-Regular.woff2") format("woff2"); font-weight: 400; }
@font-face { font-family: "Source Sans 3"; src: url("../html/estilos/fuentes/woff2/SourceSans3-Bold.woff2") format("woff2"); font-weight: 700; }
@font-face { font-family: "Source Serif 4"; src: url("../html/estilos/fuentes/woff2/SourceSerif4SmText-Regular.woff2") format("woff2"); font-weight: 400; }
:root { --shell: #0d5fa8; --filete: #9cc3e6; --tinta: #2b2b2b; --tenue: #5f6368; --borde: #d8d8d8; --enlace: #1569b0; }
* { box-sizing: border-box; }
body { margin: 0; background: #fff; color: var(--tinta); font-family: "Source Serif 4", Georgia, serif; line-height: 1.5; }
header { background: var(--shell); color: #fff; padding: 3rem 1.5rem 2.5rem; border-bottom: 6px solid var(--filete); }
header div, main { max-width: 72rem; margin: 0 auto; }
h1, h2, .numero, .enlaces { font-family: "Source Sans 3", Helvetica, Arial, sans-serif; }
h1 { margin: 0 0 0.5rem; font-size: 2.4rem; }
header p { margin: 0; opacity: 0.9; }
header a { color: #fff; }
main { padding: 2rem 1.5rem 4rem; display: grid; grid-template-columns: repeat(auto-fill, minmax(20rem, 1fr)); gap: 2rem; }
article { border: 1px solid var(--borde); border-radius: 10px; overflow: hidden; box-shadow: 0 8px 24px rgba(13, 63, 112, 0.08); }
video { display: block; width: 100%; aspect-ratio: 16 / 9; background: #e4e8ed; }
article > p, article > h2 { margin: 0; padding: 0 1.1rem; }
.numero { padding-top: 0.9rem; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; font-size: 0.8rem; color: var(--shell); }
h2 { font-size: 1.3rem; margin: 0.2rem 0 0.3rem; }
.audiencia { color: var(--tenue); font-size: 0.95rem; }
.enlaces { padding-top: 0.6rem; padding-bottom: 1rem; font-size: 0.95rem; }
a { color: var(--enlace); }
</style>
</head>
<body>
<header>
  <div>
    <h1>Presentaciones de Rentas municipales</h1>
    <p>${VIDEOS.length} vídeos, ${reloj(total)} en total, hechos a partir de los manuales. Empiece por el 01 y el 02; luego, el de su perfil. · <a href="../html/index.html">Manuales</a></p>
  </div>
</header>
<main>
${tarjetas}
</main>
</body>
</html>
`
  writeFileSync(path.join(carpeta, 'index.html'), pagina)
  console.log(`sitio: ${path.join(carpeta, 'index.html')} con ${VIDEOS.length} vídeos`)
}

main()
