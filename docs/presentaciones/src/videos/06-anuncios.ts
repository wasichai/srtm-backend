import type { Video } from '../tipos.ts'

// borrador: la estructura del vídeo, por completar
export const anuncios: Video = {
  id: 'S06-Anuncios',
  archivo: 'srtm-06-anuncios',
  numero: '06',
  titulo: 'Anuncios y propaganda',
  corto: 'Anuncios y propaganda',
  audiencia: 'Personal que autoriza anuncios (perfil ANUNCIOS)',
  objetivo: 'Por completar.',
  manuales: ['usuario/anuncios'],
  miniatura: { escena: 'portada', segundo: 4 },
  escenas: [
    {
      id: 'portada',
      tipo: 'portada',
      objetivo: 'Presentar el vídeo.',
      fuentes: [{ doc: 'usuario/anuncios', ancla: 'presentacion-manual' }],
      antetitulo: 'Anuncios y propaganda',
      titulo: 'Anuncios y propaganda',
      subtitulo: 'Por completar.',
      audiencia: 'Personal que autoriza anuncios (perfil ANUNCIOS).',
      segundos: 12,
    },
  ],
}
