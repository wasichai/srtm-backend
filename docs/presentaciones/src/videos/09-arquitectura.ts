import type { Video } from '../tipos.ts'

// borrador: la estructura del vídeo, por completar
export const arquitectura: Video = {
  id: 'S09-Arquitectura',
  archivo: 'srtm-09-arquitectura',
  numero: '09',
  titulo: 'Arquitectura técnica',
  corto: 'Arquitectura técnica',
  audiencia: 'Ingenieros de software, arquitectos y DevOps',
  objetivo: 'Por completar.',
  manuales: ['sistema/manual-sistema'],
  miniatura: { escena: 'portada', segundo: 4 },
  escenas: [
    {
      id: 'portada',
      tipo: 'portada',
      objetivo: 'Presentar el vídeo.',
      fuentes: [{ doc: 'sistema/manual-sistema', ancla: 'arquitectura' }],
      antetitulo: 'Arquitectura técnica',
      titulo: 'Arquitectura técnica',
      subtitulo: 'Por completar.',
      audiencia: 'Ingenieros de software, arquitectos y DevOps.',
      segundos: 12,
    },
  ],
}
