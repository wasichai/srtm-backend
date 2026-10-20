import type { Video } from '../tipos.ts'

// borrador: la estructura del vídeo, por completar
export const infracciones: Video = {
  id: 'S05-Infracciones',
  archivo: 'srtm-05-infracciones',
  numero: '05',
  titulo: 'Infracciones administrativas',
  corto: 'Infracciones administrativas',
  audiencia: 'Personal de fiscalización (perfil FISCALIZADOR)',
  objetivo: 'Por completar.',
  manuales: ['usuario/fiscalizador'],
  miniatura: { escena: 'portada', segundo: 4 },
  escenas: [
    {
      id: 'portada',
      tipo: 'portada',
      objetivo: 'Presentar el vídeo.',
      fuentes: [{ doc: 'usuario/fiscalizador', ancla: 'presentacion-manual' }],
      antetitulo: 'Infracciones administrativas',
      titulo: 'Infracciones administrativas',
      subtitulo: 'Por completar.',
      audiencia: 'Personal de fiscalización (perfil FISCALIZADOR).',
      segundos: 12,
    },
  ],
}
