package srtm.rentas

import srtm.pide.Persona

// what a fuente PIDE RENIEC vouches for in the records that name a person (srtm.pide.ConsultasReniec.respaldar)

fun Contribuyente.persona() = Persona(tipoDocumento, numeroDocumento, apellidoPaterno, apellidoMaterno, nombres, fuenteInformacion)

fun Relacionado.persona() = Persona(tipoDocumento, numeroDocumento, apellidoPaterno, apellidoMaterno, nombres, fuenteInformacion)

fun Transferente.persona() = Persona(tipoDocumento, numeroDocumento, apellidoPaterno, apellidoMaterno, nombres, fuenteInformacion)
