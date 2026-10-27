package srtm.reportes

import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import srtm.brecha.RegistroCorrida
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Instant

// what an export says of itself in its Resumen: the report, its corrida, the filters and the order it was asked with
data class Exportable(
    val reporte: Reporte,
    val corrida: RegistroCorrida,
    val filtros: Filtros,
    val orden: Orden,
    val dir: Direccion,
    val generado: Instant
)

// how a column's values are written in the xlsx
enum class TipoColumna(
    val formato: String?
) {
    TEXTO(null),
    DINERO("#,##0.00"),
    AREA("#,##0.00"),
    ENTERO("0"),
    BOOLEANO(null)
}

// a column of an export: clave its csv header (the json's field name, stable), titulo its xlsx header, definicion what
// Notas says of it. total: its sum goes in the Resumen
class Columna<T>(
    val clave: String,
    val titulo: String,
    val definicion: String,
    val tipo: TipoColumna,
    val total: Boolean = false,
    val valor: (T) -> Any?
)

// the export of a report (#248), the same rows /reportes/{clave} pages: csv for Excel in es-PE (utf-8 with a BOM, `;`
// as the separator, numbers with a point, a null empty) and xlsx (fastexcel, written as it goes) with the sheets
// Resumen (report, corrida, filters, order, rows and totals), Detalle (a row each) and Notas (source and definitions)
object Exportacion {
    private const val BOM = "\uFEFF"
    private const val SEPARADOR = ';'
    private const val FIN = "\r\n"

    const val FUENTE =
        "srtm-backend: las filas (brecha_predio) de la corrida de la brecha de recaudación (brecha_corrida) indicada. " +
            "El contribuyente sale del padrón (las DJ vigentes del año de cada predio); la decisión, de la bandeja de " +
            "decisiones de catastro-backend (decision_brecha), leída al exportar."

    val PREDIOS: List<Columna<FilaReportePredio>> =
        listOf(
            Columna(
                "codigoPredio",
                "Código del predio",
                "Código del predio en el padrón de rentas (vacío en un omiso).",
                TipoColumna.TEXTO
            ) { it.codigoPredio },
            Columna(
                "codigoCatastro",
                "Código de catastro",
                "Código municipal de la ficha catastral (o su CPU, o sector-manzana-lote).",
                TipoColumna.TEXTO
            ) { it.codigoCatastro },
            Columna(
                "codigoContribuyente",
                "Código del contribuyente",
                "El titular principal: el de mayor % de condominio en las DJ vigentes del año.",
                TipoColumna.TEXTO
            ) { it.codigoContribuyente },
            Columna("nombreContribuyente", "Contribuyente", "Nombre o razón social del titular principal.", TipoColumna.TEXTO) { it.nombreContribuyente },
            Columna("sector", "Sector", "Sector catastral.", TipoColumna.TEXTO) { it.sector },
            Columna("manzana", "Manzana", "Manzana catastral.", TipoColumna.TEXTO) { it.manzana },
            Columna("lote", "Lote", "Lote.", TipoColumna.TEXTO) { it.lote },
            Columna("uso", "Uso", "Código de uso que da el catastro.", TipoColumna.TEXTO) { it.uso },
            Columna("usoDescripcion", "Descripción del uso", "El uso de ese código en el catálogo de usos de predio.", TipoColumna.TEXTO) { it.usoDescripcion },
            Columna(
                "categoria",
                "Categoría",
                "OMISO, SIN_CATASTRO, SIN_VALOR, SUBVALUADO, SOBREVALUADO, COINCIDE (±5 %) o SIN_LEVANTAR.",
                TipoColumna.TEXTO
            ) { it.categoria },
            Columna(
                "confianza",
                "Confianza",
                "CONFIABLE (vínculo confirmado), DUDOSO o SIN_LEVANTAR.",
                TipoColumna.TEXTO
            ) { it.confianza },
            Columna("autoavaluoDeclarado", "Autoavalúo declarado", "Autoavalúo del predio según sus DJ (S/).", TipoColumna.DINERO) { it.autoavaluoDeclarado },
            Columna("autoavaluoCatastro", "Autoavalúo catastro", "Autoavalúo que da el catastro (S/).", TipoColumna.DINERO) { it.autoavaluoCatastro },
            Columna("deltaAutoavaluo", "Δ autoavalúo", "Autoavalúo catastro menos declarado (S/).", TipoColumna.DINERO, total = true) { it.deltaAutoavaluo },
            Columna("impuestoDeclarado", "Impuesto declarado", "Impuesto predial anual atribuido al predio (S/).", TipoColumna.DINERO) { it.impuestoDeclarado },
            Columna("impuestoCatastro", "Impuesto catastro", "Impuesto con el autoavalúo del catastro (S/).", TipoColumna.DINERO) { it.impuestoCatastro },
            Columna(
                "deltaImpuesto",
                "Δ impuesto",
                "Impuesto catastro menos declarado (S/); en un omiso, estimado.",
                TipoColumna.DINERO,
                total = true
            ) { it.deltaImpuesto },
            Columna("deltaArbitrios", "Δ arbitrios", "Arbitrios del año con los rasgos del catastro menos los declarados (S/).", TipoColumna.DINERO) {
                it.deltaArbitrios
            },
            Columna("estimado", "Estimado", "El Δ impuesto es una estimación (un omiso liquidado solo).", TipoColumna.BOOLEANO) { it.estimado },
            Columna("areaTerrenoDeclarada", "Área de terreno declarada (m²)", "Área de terreno de las DJ.", TipoColumna.AREA) { it.areaTerrenoDeclarada },
            Columna("areaTerrenoCatastro", "Área de terreno catastro (m²)", "Área de terreno de la ficha.", TipoColumna.AREA) { it.areaTerrenoCatastro },
            Columna("areaConstruidaDeclarada", "Área construida declarada (m²)", "Área construida de las DJ.", TipoColumna.AREA) { it.areaConstruidaDeclarada },
            Columna("areaConstruidaCatastro", "Área construida catastro (m²)", "Área construida de la ficha.", TipoColumna.AREA) { it.areaConstruidaCatastro },
            Columna(
                "nivelesComparados",
                "Pisos comparados",
                "Pisos presentes en las DJ y en la ficha (vacío si no se compararon).",
                TipoColumna.ENTERO
            ) { it.nivelesComparados },
            Columna(
                "nivelesCatastroMayor",
                "Pisos mayores en catastro",
                "Pisos comparados cuya área en la ficha es mayor que la declarada.",
                TipoColumna.ENTERO
            ) {
                it.nivelesCatastroMayor
            },
            Columna("deltaAreaNiveles", "Δ m² de pisos", "Suma de lo que cada piso comparado tiene de más en la ficha (m²).", TipoColumna.AREA) {
                it.deltaAreaNiveles
            },
            Columna(
                "decision",
                "Decisión de catastro",
                "Estado de decision_brecha en catastro: PENDIENTE, A_INSPECCIONAR, INSPECCIONADO, A_FISCALIZAR o DESCARTADO.",
                TipoColumna.TEXTO
            ) { it.decision }
        )

    val CONTRIBUYENTES: List<Columna<FilaReporteContribuyente>> =
        listOf(
            Columna("codigoContribuyente", "Código del contribuyente", "Código del contribuyente en el padrón.", TipoColumna.TEXTO) { it.codigoContribuyente },
            Columna("nombreContribuyente", "Contribuyente", "Nombre o razón social.", TipoColumna.TEXTO) { it.nombreContribuyente },
            Columna("predios", "Predios", "Sus predios comparados (con un Δ) de la corrida.", TipoColumna.ENTERO) { it.predios },
            Columna(
                "deltaAutoavaluo",
                "Δ autoavalúo",
                "Suma de su parte del Δ autoavalúo de cada predio (por su % de condominio) (S/).",
                TipoColumna.DINERO,
                total = true
            ) { it.deltaAutoavaluo },
            Columna(
                "deltaImpuesto",
                "Δ impuesto",
                "Suma de su parte del Δ impuesto de cada predio (por su % de condominio) (S/).",
                TipoColumna.DINERO,
                total = true
            ) { it.deltaImpuesto }
        )

    fun nombre(
        reporte: Reporte,
        anio: Int,
        formato: String
    ) = "brecha-${reporte.clave}-$anio.$formato"

    fun <T> csv(
        columnas: List<Columna<T>>,
        filas: List<T>
    ): ByteArray {
        val texto = StringBuilder(BOM)
        texto.append(columnas.joinToString(SEPARADOR.toString()) { campo(it.clave) }).append(FIN)
        for (f in filas) texto.append(columnas.joinToString(SEPARADOR.toString()) { campo(textoDe(it.valor(f))) }).append(FIN)
        return texto.toString().toByteArray(Charsets.UTF_8)
    }

    private fun textoDe(valor: Any?): String =
        when (valor) {
            null -> ""
            is BigDecimal -> valor.toPlainString()
            else -> valor.toString()
        }

    // quoted when it carries the separator, a quote or a line break, its quotes doubled
    private fun campo(texto: String): String =
        if (texto.any { it == SEPARADOR || it == '"' || it == '\n' || it == '\r' }) "\"" + texto.replace("\"", "\"\"") + "\"" else texto

    fun <T> xlsx(
        e: Exportable,
        columnas: List<Columna<T>>,
        filas: List<T>
    ): ByteArray {
        val salida = ByteArrayOutputStream()
        val libro = Workbook(salida, "srtm", "1.0")
        resumen(libro.newWorksheet("Resumen"), e, columnas, filas)
        detalle(libro.newWorksheet("Detalle"), columnas, filas)
        notas(libro.newWorksheet("Notas"), e, columnas)
        libro.finish()
        return salida.toByteArray()
    }

    private fun <T> resumen(
        hoja: Worksheet,
        e: Exportable,
        columnas: List<Columna<T>>,
        filas: List<T>
    ) {
        var r = 0

        fun fila(
            etiqueta: String,
            valor: Any?,
            formato: String? = null
        ) {
            hoja.value(r, 0, etiqueta)
            hoja.style(r, 0).bold().set()
            escribir(hoja, r, 1, valor)
            if (formato != null) hoja.style(r, 1).format(formato).set()
            r++
        }
        hoja.value(r, 0, e.reporte.titulo)
        hoja
            .style(r, 0)
            .bold()
            .fontSize(14)
            .set()
        r += 2
        fila("Descripción", e.reporte.descripcion)
        fila("Año", e.corrida.anio)
        fila("Corrida", e.corrida.id)
        fila("Estado", e.corrida.estado)
        fila("Terminada", e.corrida.terminado)
        fila("Versión del catastro", e.corrida.versionCatastro)
        fila("Generado", e.generado.toString())
        r++
        val dados = e.filtros.dados()
        if (dados.isEmpty()) fila("Filtros", "ninguno")
        for ((nombre, valor) in dados) fila(nombre, valor)
        fila("Orden", "${e.orden.clave} ${e.dir.name.lowercase()}")
        r++
        fila("Filas", filas.size)
        for (c in columnas.filter { it.total }) {
            fila(c.titulo, Reportes.sumaONull(filas.map { c.valor(it) as BigDecimal? }), c.tipo.formato)
        }
        r++
        fila("Fuente", FUENTE)
        hoja.width(0, 24.0)
        hoja.width(1, 60.0)
    }

    private fun <T> detalle(
        hoja: Worksheet,
        columnas: List<Columna<T>>,
        filas: List<T>
    ) {
        columnas.forEachIndexed { c, columna ->
            hoja.value(0, c, columna.titulo)
            hoja.width(c, maxOf(10.0, columna.titulo.length + 2.0))
        }
        hoja
            .range(0, 0, 0, columnas.size - 1)
            .style()
            .bold()
            .set()
        filas.forEachIndexed { i, f -> columnas.forEachIndexed { c, columna -> escribir(hoja, i + 1, c, columna.valor(f)) } }
        if (filas.isNotEmpty()) {
            columnas.forEachIndexed { c, columna ->
                columna.tipo.formato?.let {
                    hoja
                        .range(1, c, filas.size, c)
                        .style()
                        .format(it)
                        .set()
                }
            }
        }
        hoja.freezePane(0, 1)
    }

    private fun <T> notas(
        hoja: Worksheet,
        e: Exportable,
        columnas: List<Columna<T>>
    ) {
        hoja.value(0, 0, "Notas")
        hoja
            .style(0, 0)
            .bold()
            .fontSize(14)
            .set()
        hoja.value(2, 0, "Fuente")
        hoja.value(2, 1, FUENTE)
        hoja.value(3, 0, "Reporte")
        hoja.value(3, 1, "${e.reporte.titulo}: ${e.reporte.descripcion}")
        hoja.value(4, 0, "Corrida")
        hoja.value(4, 1, "${e.corrida.id} (${e.corrida.anio}), la última TERMINADA del año salvo que se pidiera otra.")
        hoja
            .range(2, 0, 4, 0)
            .style()
            .bold()
            .set()
        val desde = 6
        hoja.value(desde, 0, "Columna")
        hoja.value(desde, 1, "Definición")
        hoja.value(desde, 2, "Campo (csv y API)")
        hoja
            .range(desde, 0, desde, 2)
            .style()
            .bold()
            .set()
        columnas.forEachIndexed { i, c ->
            hoja.value(desde + 1 + i, 0, c.titulo)
            hoja.value(desde + 1 + i, 1, c.definicion)
            hoja.value(desde + 1 + i, 2, c.clave)
        }
        hoja.width(0, 30.0)
        hoja.width(1, 90.0)
        hoja.width(2, 24.0)
    }

    // a null is no cell
    private fun escribir(
        hoja: Worksheet,
        r: Int,
        c: Int,
        valor: Any?
    ) {
        when (valor) {
            null -> {}
            is Number -> hoja.value(r, c, valor)
            is Boolean -> hoja.value(r, c, valor)
            else -> hoja.value(r, c, valor.toString())
        }
    }
}
