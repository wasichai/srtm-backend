import type { Video } from '../tipos.ts'

// borrador: la estructura del vídeo, por completar
export const primerosPasos: Video = {
  id: 'S02-PrimerosPasos',
  archivo: 'srtm-02-primeros-pasos',
  numero: '02',
  titulo: 'Primeros pasos en el portal',
  corto: 'Primeros pasos',
  audiencia: 'Todos los usuarios del portal, de cualquier perfil',
  objetivo: 'Por completar.',
  manuales: ['comun/acceso', 'comun/interfaz', 'comun/busqueda', 'comun/mensajes'],
  miniatura: { escena: 'portada', segundo: 4 },
  escenas: [
    {
      id: 'portada',
      tipo: 'portada',
      objetivo: 'Presentar el vídeo.',
      fuentes: [{ doc: 'comun/acceso', ancla: 'proc-ingresar' }],
      antetitulo: 'Primeros pasos',
      titulo: 'Primeros pasos en el portal',
      subtitulo: 'Por completar.',
      audiencia: 'Todos los usuarios del portal, de cualquier perfil.',
      segundos: 12,
    },
  ],
}
