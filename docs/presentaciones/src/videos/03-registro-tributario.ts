import type { Video } from '../tipos.ts'

// borrador: la estructura del vídeo, por completar
export const registroTributario: Video = {
  id: 'S03-RegistroTributario',
  archivo: 'srtm-03-registro-tributario',
  numero: '03',
  titulo: 'Registro tributario',
  corto: 'Registro tributario',
  audiencia: 'Personal de ventanilla y del registro tributario (perfil REGISTRADOR)',
  objetivo: 'Por completar.',
  manuales: ['usuario/registrador'],
  miniatura: { escena: 'portada', segundo: 4 },
  escenas: [
    {
      id: 'portada',
      tipo: 'portada',
      objetivo: 'Presentar el vídeo.',
      fuentes: [{ doc: 'usuario/registrador', ancla: 'presentacion-manual' }],
      antetitulo: 'Registro tributario',
      titulo: 'Registro tributario',
      subtitulo: 'Por completar.',
      audiencia: 'Personal de ventanilla y del registro tributario (perfil REGISTRADOR).',
      segundos: 12,
    },
  ],
}
