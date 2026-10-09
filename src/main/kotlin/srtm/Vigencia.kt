package srtm

import java.time.LocalDate

// the vigencia of the rows that rule for a while (parámetros tributarios, versiones del CUIS…): in force on `dia` from
// desde (required) to hasta (open when empty), both ends counting. one rule for every module
fun vigente(
    desde: LocalDate?,
    hasta: LocalDate?,
    dia: LocalDate
): Boolean = desde != null && desde <= dia && (hasta == null || hasta >= dia)
