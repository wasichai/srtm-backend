package srtm.impuesto

import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

// the verified parameters the tests liquidate with: model/data/parametros-predial.csv, the copy of normativa's rows
// that import_parametros.py loads. no figure of a test is typed: they come from here
object Parametros {
    val predial: List<ParametroTributario> by lazy { leer(File("model/data/parametros-predial.csv")) }

    fun uit(anio: Int): BigDecimal = predial.single { it.tipo == "UIT" && it.vigenciaDesde!!.year == anio }.valorNumerico!!

    fun valor(
        tipo: String,
        clave: String? = null
    ): BigDecimal = predial.single { it.tipo == tipo && it.clave == clave }.valorNumerico!!

    // the csv's rows as core stores them: an empty cell is null
    fun leer(file: File): List<ParametroTributario> {
        val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val header = celdas(lines.first())
        return lines.drop(1).map { line ->
            val row = header.zip(celdas(line)).toMap().mapValues { it.value.ifEmpty { null } }
            ParametroTributario(
                tipo = row["tipo"],
                clave = row["clave"],
                vigenciaDesde = row["vigencia_desde"]?.let(LocalDate::parse),
                vigenciaHasta = row["vigencia_hasta"]?.let(LocalDate::parse),
                valorNumerico = row["valor_numerico"]?.let(::BigDecimal),
                texto = row["texto"],
                norma = row["norma"],
                fuente = row["fuente"],
                transcribio = row["transcribio"],
                verifico = row["verifico"]
            )
        }
    }

    // a csv line: commas inside quotes belong to the cell
    private fun celdas(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> cells += cell.toString().also { cell.clear() }
                else -> cell.append(c)
            }
        }
        return cells + cell.toString()
    }
}
