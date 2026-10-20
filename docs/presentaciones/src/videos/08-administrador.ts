import type { Video } from '../tipos.ts'

// borrador: la estructura del vídeo, por completar
export const administrador: Video = {
  id: 'S08-Administrador',
  archivo: 'srtm-08-administrador',
  numero: '08',
  titulo: 'Administración del sistema',
  corto: 'Administración',
  audiencia: 'Administradores del sistema (rol ADMIN)',
  objetivo: 'Por completar.',
  manuales: ['administrador/manual-administrador'],
  miniatura: { escena: 'portada', segundo: 4 },
  escenas: [
    {
      id: 'portada',
      tipo: 'portada',
      objetivo: 'Presentar el vídeo.',
      fuentes: [{ doc: 'administrador/manual-administrador', ancla: 'presentacion' }],
      antetitulo: 'Administración',
      titulo: 'Administración del sistema',
      subtitulo: 'Por completar.',
      audiencia: 'Administradores del sistema (rol ADMIN).',
      segundos: 12,
    },
  ],
}
