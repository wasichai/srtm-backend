import type { Video } from '../tipos.ts'

// borrador: la estructura del vídeo, por completar
export const operacion: Video = {
  id: 'S10-Operacion',
  archivo: 'srtm-10-operacion',
  numero: '10',
  titulo: 'Instalación, operación y recuperación',
  corto: 'Operación y recuperación',
  audiencia: 'DevOps y administradores de bases de datos',
  objetivo: 'Por completar.',
  manuales: ['sistema/manual-sistema', 'demo/README'],
  miniatura: { escena: 'portada', segundo: 4 },
  escenas: [
    {
      id: 'portada',
      tipo: 'portada',
      objetivo: 'Presentar el vídeo.',
      fuentes: [{ doc: 'sistema/manual-sistema', ancla: 'operacion' }],
      antetitulo: 'Operación y recuperación',
      titulo: 'Instalación, operación y recuperación',
      subtitulo: 'Por completar.',
      audiencia: 'DevOps y administradores de bases de datos.',
      segundos: 12,
    },
  ],
}
