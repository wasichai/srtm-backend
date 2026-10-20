// Identidad visual de las presentaciones. Es la de los manuales (docs/manuales/estilos/html/manuales.css y
// estilos/pdf/tema-srtm.yml), que a su vez toma los tokens del tema «Portal tributario» de srtm-ui: el azul de la
// barra de la municipalidad, el filete celeste de la portada y la familia Source de Adobe.

export const COLOR = {
  // la barra del portal y la portada de los manuales
  shell: '#0D5FA8',
  marca: '#3A78AF',
  marcaFuerte: '#2F6596',
  marcaOscura: '#0D3F70',
  marcaSuave: '#EFF6FB',
  filete: '#9CC3E6',
  enlace: '#1569B0',
  tinta: '#2B2B2B',
  tintaTenue: '#5F6368',
  tintaClara: '#8A8F96',
  superficie: '#FFFFFF',
  papel: '#F6F6F6',
  borde: '#D8D8D8',
  bordeTabla: '#BFCAD6',
  cabezaTabla: '#EAF2FA',
  franja: '#F8FAFC',
  peligro: '#A94442',
  exito: '#3C763D',
  aviso: '#8A6D3B',
  // el recuadro con que las capturas de los manuales marcan un control (herramientas/capturas.mjs, «marcar»)
  foco: '#E8740C',
} as const

// los cuatro avisos de los manuales, con los colores de manuales.css
export const AVISO = {
  nota: { rotulo: 'Nota', color: COLOR.enlace, fondo: '#F7FAFD', borde: '#DBE6F1' },
  consejo: { rotulo: 'Consejo', color: COLOR.exito, fondo: '#F6FAF5', borde: '#D9E8D6' },
  importante: { rotulo: 'Importante', color: COLOR.shell, fondo: '#F7FAFD', borde: '#DBE6F1' },
  precaucion: { rotulo: 'Precaución', color: COLOR.aviso, fondo: '#FCF8EF', borde: '#EEE1C4' },
  advertencia: { rotulo: 'Advertencia', color: COLOR.peligro, fondo: '#FCF5F5', borde: '#EFD5D5' },
} as const

export type NivelAviso = keyof typeof AVISO

// tamaños para un lienzo de 1920 × 1080 visto como vídeo: nada de texto por debajo de 22 px
export const TIPO = {
  portada: 96,
  seccion: 88,
  titulo: 56,
  frase: 60,
  subtitulo: 34,
  cuerpo: 30,
  lista: 30,
  apoyo: 26,
  rotulo: 22,
  pie: 22,
} as const

export const MARGEN = { x: 120, arriba: 96, abajo: 112 } as const

export const LIENZO = { ancho: 1920, alto: 1080, fps: 30 } as const

export const RADIO = { chico: 6, medio: 10, grande: 16 } as const

export const SOMBRA = {
  tarjeta: '0 1px 2px rgba(13, 63, 112, 0.06), 0 8px 24px rgba(13, 63, 112, 0.08)',
  captura: '0 2px 6px rgba(13, 63, 112, 0.10), 0 24px 60px rgba(13, 63, 112, 0.16)',
} as const

/** `#rrggbb` → `rgba()` */
export const conAlfa = (hex: string, alfa: number): string => {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r}, ${g}, ${b}, ${alfa})`
}
