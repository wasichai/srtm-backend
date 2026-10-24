package srtm.normativa

import wasichai.core.common.ValidationException

// a data row of the csv: its line in the file (comments and blank lines count, as an editor shows it) and its cells
// by column, an empty one null
data class FilaCsv(
    val linea: Int,
    val valores: Map<String, String?>
)

// the csv of POST /normativa/parametros/importar, as model/import_parametros.py reads model/data/parametros-predial.csv:
// the lines that start with '#' are comments, the first other line is the header, and only its known columns are read
// (COLUMNAS; tipo and vigencia_desde are required). a cell between quotes may hold commas, and "" is a quote in it. a
// cell does not span lines. a malformed file is a 400 and nothing of it is read
object CsvDeParametros {
    val COLUMNAS = CamposParametro.DATOS + listOf(CamposParametro.TRANSCRIBIO, CamposParametro.VERIFICO)
    private val REQUERIDAS = listOf("tipo", "vigencia_desde")

    fun leer(texto: String): List<FilaCsv> {
        val lineas =
            texto
                .removePrefix("﻿")
                .lines()
                .mapIndexed { i, linea -> i + 1 to linea.removeSuffix("\r") }
                .filter { (_, linea) -> linea.isNotBlank() && !linea.startsWith("#") }
        val (lineaCabecera, cabecera) = lineas.firstOrNull() ?: throw malo("El CSV no tiene cabecera: tipo,clave,vigencia_desde,...")
        val columnas = celdas(cabecera, lineaCabecera).map { it.trim() }
        val faltan = REQUERIDAS.filter { it !in columnas }
        if (faltan.isNotEmpty()) throw malo("La cabecera del CSV no tiene ${faltan.joinToString(", ")} (línea $lineaCabecera)")
        return lineas.drop(1).map { (numero, linea) ->
            val valores = celdas(linea, numero)
            if (valores.size > columnas.size) throw malo("La línea $numero tiene ${valores.size} celdas y la cabecera ${columnas.size}")
            FilaCsv(
                numero,
                columnas
                    .zip(valores + List(columnas.size - valores.size) { "" })
                    .filter { (columna, _) -> columna in COLUMNAS }
                    .associate { (columna, valor) -> columna to valor.trim().ifEmpty { null } }
            )
        }
    }

    // a line's cells: commas inside quotes belong to the cell, "" inside them is a quote
    private fun celdas(
        linea: String,
        numero: Int
    ): List<String> {
        val celdas = mutableListOf<String>()
        val celda = StringBuilder()
        var entreComillas = false
        var i = 0
        while (i < linea.length) {
            val c = linea[i]
            when {
                c == '"' && entreComillas && linea.getOrNull(i + 1) == '"' -> {
                    celda.append('"')
                    i++
                }
                c == '"' -> entreComillas = !entreComillas
                c == ',' && !entreComillas -> celdas += celda.toString().also { celda.clear() }
                else -> celda.append(c)
            }
            i++
        }
        if (entreComillas) throw malo("La línea $numero abre comillas y no las cierra")
        return celdas + celda.toString()
    }

    private fun malo(mensaje: String) = ValidationException(mensaje, "csv", mensaje)
}
