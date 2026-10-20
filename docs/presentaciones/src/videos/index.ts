// Las presentaciones, en el orden en que se recomienda verlas.
import type { Video } from '../tipos.ts'
import { introduccion } from './01-introduccion.ts'
import { primerosPasos } from './02-primeros-pasos.ts'
import { registroTributario } from './03-registro-tributario.ts'
import { arbitriosEmision } from './04-arbitrios-emision.ts'
import { infracciones } from './05-infracciones.ts'
import { anuncios } from './06-anuncios.ts'
import { consulta } from './07-consulta.ts'
import { administrador } from './08-administrador.ts'
import { arquitectura } from './09-arquitectura.ts'
import { operacion } from './10-operacion.ts'

export const VIDEOS: readonly Video[] = [
  introduccion,
  primerosPasos,
  registroTributario,
  arbitriosEmision,
  infracciones,
  anuncios,
  consulta,
  administrador,
  arquitectura,
  operacion,
]
