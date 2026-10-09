package srtm

import java.time.LocalDate
import java.time.format.DateTimeFormatter

// a day as a message or a detalle shows it to whoever reads it: dd/MM/yyyy, like the papers. the json fields stay iso
private val LEGIBLE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

fun LocalDate.legible(): String = format(LEGIBLE)
