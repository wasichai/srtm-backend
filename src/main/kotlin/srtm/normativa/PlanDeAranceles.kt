package srtm.normativa

import com.fasterxml.jackson.annotation.JsonUnwrapped
import srtm.Violaciones
import srtm.leerRegistro
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import java.math.BigDecimal

// the data of an arancel as Normativa takes it (POST and PATCH /normativa/aranceles, each row of an importación):
// CamposArancel.DATOS, a text trimmed and an empty one null; the clave is computed (id_aranc|anio)
object DatosDeArancel {
    // the body's data, every other key a 400 (the clave, the signatures and the estado are Normativa's to write)
    fun de(cuerpo: Map<String, Any?>): Map<String, Any?> {
        val ajenos = cuerpo.keys.filter { it !in CamposArancel.DATOS }
        if (ajenos.isNotEmpty()) {
            throw ValidationException(
                "El arancel solo lleva sus datos",
                ajenos.map { FieldViolation(it, "no es un dato del arancel: ${CamposArancel.DATOS.joinToString(", ")}") }
            )
        }
        return cuerpo.mapValues { (_, valor) -> if (valor is String) valor.trim().ifEmpty { null } else valor }
    }

    // the data as an arancel with its clave: a 400 that names each field that does not fit
    fun validar(datos: Map<String, Any?>): Arancel {
        val a = leerRegistro(Arancel::class.java, datos.filterKeys { it in CamposArancel.DATOS })
        val v = Violaciones()
        v.requerido(CamposArancel.ID_ARANC, a.idAranc)
        if (v.requerido(CamposArancel.ANIO, a.anio) && a.anio!! !in ANIOS) v.mal(CamposArancel.ANIO, "es un año de ${ANIOS.first} a ${ANIOS.last}")
        if (v.requerido(CamposArancel.VALOR_M2, a.valorM2) && a.valorM2!!.signum() < 0) v.mal(CamposArancel.VALOR_M2, "no es negativo")
        v.lanzar("El arancel")
        val idAranc = a.idAranc!!.trim()
        return a.copy(idAranc = idAranc, clave = claveDeArancel(idAranc, a.anio))
    }

    // the data fields in which two aranceles differ: a number by its value, an empty text as null
    fun cambios(
        a: Arancel,
        b: Arancel
    ): List<String> =
        listOfNotNull(
            "id_aranc".takeIf { a.idAranc != b.idAranc },
            "anio".takeIf { a.anio != b.anio },
            "valor_m2".takeIf { !mismoNumero(a.valorM2, b.valorM2) },
            "cod_via".takeIf { a.codVia.orEmpty() != b.codVia.orEmpty() },
            "cuadra".takeIf { a.cuadra.orEmpty() != b.cuadra.orEmpty() },
            "lado".takeIf { a.lado.orEmpty() != b.lado.orEmpty() },
            "norma".takeIf { a.norma.orEmpty() != b.norma.orEmpty() },
            "fuente".takeIf { a.fuente.orEmpty() != b.fuente.orEmpty() }
        )

    // its data and clave as the attributes a write sends: null included (a replace clears what is left out)
    fun atributos(a: Arancel): Map<String, Any?> =
        mapOf(
            CamposArancel.CLAVE to claveDeArancel(a.idAranc, a.anio),
            "id_aranc" to a.idAranc,
            "anio" to a.anio,
            "valor_m2" to a.valorM2,
            "cod_via" to a.codVia,
            "cuadra" to a.cuadra,
            "lado" to a.lado,
            "norma" to a.norma,
            "fuente" to a.fuente
        )

    private fun mismoNumero(
        x: BigDecimal?,
        y: BigDecimal?
    ): Boolean = if (x == null || y == null) x == y else x.compareTo(y) == 0

    private val ANIOS = 1900..2100
}

// the csv of POST /normativa/aranceles/importar: its data and the two signatures. id_aranc, anio and valor_m2 required
object CsvDeAranceles {
    val COLUMNAS = CamposArancel.DATOS + listOf(CamposParametro.TRANSCRIBIO, CamposParametro.VERIFICO)

    fun leer(texto: String): List<FilaCsv> = Csv.leer(texto, COLUMNAS, listOf("id_aranc", "anio", "valor_m2"), "id_aranc,anio,valor_m2")
}

// an arancel the importación writes: a new one (id null) or a POR_VERIFICAR one it corrects, with the estado it leaves
data class EscrituraDeArancel(
    val fila: Int,
    val id: String?,
    val arancel: Arancel,
    val estado: String,
    val transcribio: String,
    val verifico: String?
)

data class PlanDeArancel(
    val crear: List<EscrituraDeArancel>,
    val actualizar: List<EscrituraDeArancel>,
    val iguales: Int,
    val rechazos: List<RechazoDeFila>
)

// what an importación of aranceles writes (#188), as a parámetros' one (PlanDeImportacion): the signatures (Firmas),
// idempotent by clave over the aranceles not ANULADO (the same data is skipped; other data on a POR_VERIFICAR one
// corrects it, and verifies it with two signatures; on a VIGENTE one it is a rechazo, annul it first), and a clave
// repeated in the file is a rechazo. an arancel is of one year: no overlaps. a rechazo leaves the other rows alone
object PlanDeAranceles {
    fun planear(
        filas: List<FilaCsv>,
        guardados: List<Arancel>,
        usuario: String
    ): PlanDeArancel {
        val porClave = guardados.filter { EstadoParametro.de(it.estado) != EstadoParametro.ANULADO }.associateBy { it.clave.orEmpty() }
        val vistas = mutableMapOf<String, Int>()
        val crear = mutableListOf<EscrituraDeArancel>()
        val actualizar = mutableListOf<EscrituraDeArancel>()
        val rechazos = mutableListOf<RechazoDeFila>()
        var iguales = 0

        for (fila in filas) {
            fun motivo(texto: String) {
                rechazos += RechazoDeFila(fila.linea, texto)
            }
            val a =
                try {
                    DatosDeArancel.validar(fila.valores.filterKeys { it in CamposArancel.DATOS })
                } catch (e: ValidationException) {
                    motivo(e.violations.joinToString("; ") { "${it.field} ${it.message}" }.ifEmpty { e.message })
                    continue
                }
            val (estado, transcribio, verifico) =
                when (val firmas = Firmas.de(fila, usuario)) {
                    is Firmas.Mal -> {
                        motivo(firmas.motivo)
                        continue
                    }
                    is Firmas.Bien -> firmas
                }
            val clave = a.clave.orEmpty()
            vistas[clave]?.let {
                motivo("repite la clave (id_aranc, anio) de la línea $it")
                continue
            }
            vistas[clave] = fila.linea
            val escritura = EscrituraDeArancel(fila.linea, null, a, estado, transcribio, verifico)
            val guardado = porClave[clave]
            if (guardado == null) {
                crear += escritura
                continue
            }
            val cambios = DatosDeArancel.cambios(guardado, a)
            if (guardado.rige()) {
                if (cambios.isEmpty()) {
                    iguales++
                } else {
                    motivo(
                        "VIGENTE distinto: anule primero el arancel ${guardado.nombre()} (${guardado.id}) en Normativa " +
                            "(cambia: ${cambios.joinToString(", ")})"
                    )
                }
                continue
            }
            if (cambios.isEmpty() && estado == EstadoParametro.POR_VERIFICAR && firmante(guardado.transcribio) == firmante(transcribio)) {
                iguales++
                continue
            }
            actualizar += escritura.copy(id = guardado.id)
        }
        return PlanDeArancel(crear, actualizar, iguales, rechazos)
    }

    // the 409 of an importación that failed after `escritos` of its `lotes` were written (`filas` rows): what is in
    // stays, and the same file run again skips it and writes the rest
    fun parcial(
        escritos: Int,
        lotes: Int,
        filas: Int,
        causa: String
    ) = ConflictException(
        "La importación quedó a medias: se escribieron $escritos de $lotes lotes ($filas filas) antes de un error ($causa). " +
            "Vuelva a importar el mismo archivo: es idempotente, lo escrito se salta y se completa lo que falta"
    )
}

// the 200 of an importación of aranceles: what it writes (or would, with dry_run), what was already as the csv says,
// and the rows that do not fit, by their line in the file
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ResultadoImportacionAranceles(
    val dryRun: Boolean,
    val crear: List<FilaDeArancel>,
    val actualizar: List<FilaDeArancel>,
    val iguales: Int,
    val rechazos: List<RechazoDeFila>,
    // the copy to catastro of the rows left VIGENTE (#189): null with dry_run or without catastro
    val sincronizacion: Sincronizacion? = null
)

// the 200 of POST /normativa/aranceles/verificar: the ids verified, the ones refused and the copy to catastro of the
// verified ones (null without catastro)
data class ResultadoVerificacionAranceles(
    val verificados: List<String>,
    val rechazados: List<RechazoDeVerificacion>,
    val sincronizacion: Sincronizacion?
)

// the 200 of POST /normativa/aranceles/{id}/anular: the arancel, flat, and the copy to catastro (null without it)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ArancelAnulado(
    @get:JsonUnwrapped val arancel: Arancel,
    val sincronizacion: Sincronizacion?
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FilaDeArancel(
    val fila: Int,
    val id: String?,
    val idAranc: String?,
    val anio: Int?,
    val valorM2: BigDecimal?,
    val codVia: String?,
    val cuadra: String?,
    val lado: String?,
    val estado: String
) {
    companion object {
        fun de(e: EscrituraDeArancel) =
            FilaDeArancel(e.fila, e.id, e.arancel.idAranc, e.arancel.anio, e.arancel.valorM2, e.arancel.codVia, e.arancel.cuadra, e.arancel.lado, e.estado)
    }
}
