// La familia Source de Adobe (licencia OFL), la misma de los manuales, cargada desde public/fuentes (la copia
// scripts/recursos.mjs de docs/manuales/estilos/fuentes/woff2): el render no depende de las fuentes del sistema ni
// de Internet.
import { loadFont } from '@remotion/fonts'
import { staticFile } from 'remotion'

export const TITULOS = '"Source Sans 3", sans-serif'
export const TEXTO = '"Source Serif 4", serif'
export const CODIGO = '"Source Code Pro", monospace'

const caras = [
  { family: 'Source Sans 3', archivo: 'SourceSans3-Regular', weight: '400', style: 'normal' },
  { family: 'Source Sans 3', archivo: 'SourceSans3-It', weight: '400', style: 'italic' },
  { family: 'Source Sans 3', archivo: 'SourceSans3-Semibold', weight: '600', style: 'normal' },
  { family: 'Source Sans 3', archivo: 'SourceSans3-Bold', weight: '700', style: 'normal' },
  { family: 'Source Serif 4', archivo: 'SourceSerif4SmText-Regular', weight: '400', style: 'normal' },
  { family: 'Source Serif 4', archivo: 'SourceSerif4SmText-It', weight: '400', style: 'italic' },
  { family: 'Source Serif 4', archivo: 'SourceSerif4SmText-Semibold', weight: '600', style: 'normal' },
  { family: 'Source Code Pro', archivo: 'SourceCodePro-Regular', weight: '400', style: 'normal' },
  { family: 'Source Code Pro', archivo: 'SourceCodePro-Semibold', weight: '600', style: 'normal' },
] as const

let cargadas = false

export const cargarFuentes = (): void => {
  if (cargadas) return
  cargadas = true
  for (const c of caras) {
    loadFont({
      family: c.family,
      url: staticFile(`fuentes/${c.archivo}.woff2`),
      weight: c.weight,
      style: c.style,
      format: 'woff2',
    })
  }
}
