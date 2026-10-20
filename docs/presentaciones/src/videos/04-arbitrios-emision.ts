import type { Video } from '../tipos.ts'

// borrador: la estructura del vídeo, por completar
export const arbitriosEmision: Video = {
  id: 'S04-ArbitriosEmision',
  archivo: 'srtm-04-arbitrios-emision',
  numero: '04',
  titulo: 'Arbitrios y emisión',
  corto: 'Arbitrios y emisión',
  audiencia: 'Personal del área de arbitrios y de emisión (perfil ARBITRIOS_EMISION)',
  objetivo: 'Por completar.',
  manuales: ['usuario/arbitrios-emision'],
  miniatura: { escena: 'portada', segundo: 4 },
  escenas: [
    {
      id: 'portada',
      tipo: 'portada',
      objetivo: 'Presentar el vídeo.',
      fuentes: [{ doc: 'usuario/arbitrios-emision', ancla: 'presentacion-manual' }],
      antetitulo: 'Arbitrios y emisión',
      titulo: 'Arbitrios y emisión',
      subtitulo: 'Por completar.',
      audiencia: 'Personal del área de arbitrios y de emisión (perfil ARBITRIOS_EMISION).',
      segundos: 12,
    },
  ],
}
