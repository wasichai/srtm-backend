// Los nombres de los manuales y de sus secciones, para citar la fuente de cada escena. Los títulos de las secciones
// salen de los propios .adoc (scripts/recursos.ts escribe src/generado/anclas.json): si un manual cambia el título,
// la cita cambia con él, y si un ancla desaparece, npm run verificar lo dice.
import anclas from './generado/anclas.json' with { type: 'json' }
import type { DocManual, Fuente } from './tipos.ts'

export const NOMBRE_DOC: Record<DocManual, string> = {
  index: 'Biblioteca de documentación',
  'usuario/registrador': 'Manual del registrador',
  'usuario/arbitrios-emision': 'Manual de arbitrios y emisión',
  'usuario/fiscalizador': 'Manual del fiscalizador',
  'usuario/anuncios': 'Manual de anuncios',
  'usuario/consulta': 'Manual de consulta',
  'administrador/manual-administrador': 'Manual del administrador',
  'sistema/manual-sistema': 'Manual técnico del sistema',
  'comun/acceso': 'Manuales de usuario',
  'comun/interfaz': 'Manuales de usuario',
  'comun/busqueda': 'Manuales de usuario',
  'comun/mensajes': 'Manuales de usuario',
  'comun/faq': 'Manuales de usuario',
  'referencia/api': 'Manual técnico del sistema',
  'referencia/configuracion': 'Manual técnico del sistema',
  'referencia/modelo-datos': 'Manual técnico del sistema',
  'referencia/permisos': 'Manual del administrador',
  'referencia/glosario': 'Manual del administrador',
  'demo/README': 'Base de demostración',
}

const TITULOS: Record<string, string> = anclas

export const tituloSeccion = (f: Fuente): string | undefined => TITULOS[`${f.doc}#${f.ancla}`]

/** «Manual del registrador › Inscribir un contribuyente» */
export const citar = (f: Fuente): string => {
  const titulo = tituloSeccion(f)
  return titulo ? `${NOMBRE_DOC[f.doc]} › ${titulo}` : NOMBRE_DOC[f.doc]
}
