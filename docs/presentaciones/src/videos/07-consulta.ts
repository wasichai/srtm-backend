import type { Video } from '../tipos.ts'

// borrador: la estructura del vídeo, por completar
export const consulta: Video = {
  id: 'S07-Consulta',
  archivo: 'srtm-07-consulta',
  numero: '07',
  titulo: 'Consulta y brecha de recaudación',
  corto: 'Consulta y brecha',
  audiencia: 'Orientación al contribuyente, supervisión, auditoría y gerencia (perfil CONSULTA)',
  objetivo: 'Por completar.',
  manuales: ['usuario/consulta'],
  miniatura: { escena: 'portada', segundo: 4 },
  escenas: [
    {
      id: 'portada',
      tipo: 'portada',
      objetivo: 'Presentar el vídeo.',
      fuentes: [{ doc: 'usuario/consulta', ancla: 'presentacion-manual' }],
      antetitulo: 'Consulta y brecha',
      titulo: 'Consulta y brecha de recaudación',
      subtitulo: 'Por completar.',
      audiencia: 'Orientación al contribuyente, supervisión, auditoría y gerencia (perfil CONSULTA).',
      segundos: 12,
    },
  ],
}
