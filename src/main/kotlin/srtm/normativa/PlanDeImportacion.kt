package srtm.normativa

import srtm.impuesto.ParametroTributario
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate

// a row the importación writes: a new one (id null) or a POR_VERIFICAR one it corrects, with the estado it leaves
data class Escritura(
    val fila: Int,
    val id: String?,
    val parametro: ParametroTributario,
    val estado: String,
    val transcribio: String,
    val verifico: String?
)

// a row of the csv that is not written, and why
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RechazoDeFila(
    val fila: Int,
    val motivo: String
)

data class Plan(
    val crear: List<Escritura>,
    val actualizar: List<Escritura>,
    val iguales: Int,
    val rechazos: List<RechazoDeFila>
)

// what an importación writes, from the csv and the organization's rows (#187), as model/import_parametros.py does it:
// - a row signed by two different people (transcribio, verifico) enters VIGENTE; one with transcribio alone or no
//   signature POR_VERIFICAR, transcribed by the csv's or by the user; verifico without transcribio does not fit;
// - idempotent by tipo, clave and vigencia_desde over the rows not ANULADO: the same data is skipped; other data on a
//   POR_VERIFICAR row corrects it (and verifies it, with two signatures); on a VIGENTE one it is a rechazo, annul
//   it first;
// - a new VIGENTE row that overlaps another VIGENTE of its tipo and clave (stored, or earlier in the file) is a
//   rechazo, as verifying it would be.
// a rechazo leaves the other rows alone
object PlanDeImportacion {
    fun planear(
        filas: List<FilaCsv>,
        guardados: List<ParametroTributario>,
        usuario: String
    ): Plan {
        val vivos = guardados.filter { EstadoParametro.de(it.estado) != EstadoParametro.ANULADO }
        val porLlave = vivos.associateBy(::llaveDe)
        val vigentes = vivos.filter { it.rige() }.toMutableList()
        val vistas = mutableMapOf<String, Int>()
        val crear = mutableListOf<Escritura>()
        val actualizar = mutableListOf<Escritura>()
        val rechazos = mutableListOf<RechazoDeFila>()
        var iguales = 0

        for (fila in filas) {
            fun motivo(texto: String) {
                rechazos += RechazoDeFila(fila.linea, texto)
            }
            val p =
                try {
                    DatosDeParametro.validar(fila.valores.filterKeys { it in CamposParametro.DATOS })
                } catch (e: ValidationException) {
                    motivo(e.violations.joinToString("; ") { "${it.field} ${it.message}" }.ifEmpty { e.message })
                    continue
                }
            val transcribio = fila.valores[CamposParametro.TRANSCRIBIO]
            val verifico = fila.valores[CamposParametro.VERIFICO]
            if (transcribio == null && verifico != null) {
                motivo("trae verifico sin transcribio: la doble firma necesita quién transcribió")
                continue
            }
            if (transcribio != null && verifico != null && firmante(transcribio) == firmante(verifico)) {
                motivo("transcribio y verifico son la misma persona")
                continue
            }
            val estado = if (verifico != null) EstadoParametro.VIGENTE else EstadoParametro.POR_VERIFICAR
            val llave = llaveDe(p)
            vistas[llave]?.let {
                motivo("repite la llave (tipo, clave, vigencia_desde) de la línea $it")
                continue
            }
            vistas[llave] = fila.linea
            val escritura = Escritura(fila.linea, null, p, estado, transcribio ?: usuario, verifico)
            val guardado = porLlave[llave]
            if (guardado == null) {
                val otro = if (estado == EstadoParametro.VIGENTE) solapado(p, vigentes) else null
                if (otro != null) {
                    motivo(seSolapaCon(otro))
                    continue
                }
                crear += escritura
                if (estado == EstadoParametro.VIGENTE) vigentes += p
                continue
            }
            val cambios = DatosDeParametro.cambios(guardado, p)
            if (guardado.rige()) {
                if (cambios.isEmpty()) {
                    iguales++
                } else {
                    motivo("ya hay un parámetro VIGENTE con esa llave y otros datos (${cambios.joinToString(", ")}): anúlelo primero en Normativa")
                }
                continue
            }
            if (cambios.isEmpty() && estado == EstadoParametro.POR_VERIFICAR && firmante(guardado.transcribio) == firmante(escritura.transcribio)) {
                iguales++
                continue
            }
            val otro = if (estado == EstadoParametro.VIGENTE) solapado(p, vigentes) else null
            if (otro != null) {
                motivo(seSolapaCon(otro))
                continue
            }
            actualizar += escritura.copy(id = guardado.id)
            if (estado == EstadoParametro.VIGENTE) vigentes += p
        }
        return Plan(crear, actualizar, iguales, rechazos)
    }

    private fun solapado(
        p: ParametroTributario,
        vigentes: List<ParametroTributario>
    ): ParametroTributario? = vigentes.firstOrNull { seSolapan(it, p) }

    fun seSolapaCon(otro: ParametroTributario) =
        "se solapa con ${nombreDe(otro)}, VIGENTE${otro.id?.let { " ($it)" }.orEmpty()}: cierre la vigencia de ${nombreDe(otro)} antes " +
            "(o anúlelo), o corrija la vigencia de este"
}

// the 200 of an importación: what it writes (or would, with dry_run), what was already as the csv says, and the rows
// that do not fit, by their line in the file
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ResultadoImportacion(
    val dryRun: Boolean,
    val crear: List<FilaImportada>,
    val actualizar: List<FilaImportada>,
    val iguales: Int,
    val rechazos: List<RechazoDeFila>
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FilaImportada(
    val fila: Int,
    val id: String?,
    val tipo: String?,
    val clave: String?,
    val vigenciaDesde: LocalDate?,
    val vigenciaHasta: LocalDate?,
    val valorNumerico: BigDecimal?,
    val texto: String?,
    val estado: String
) {
    companion object {
        fun de(e: Escritura) =
            FilaImportada(
                e.fila,
                e.id,
                e.parametro.tipo,
                e.parametro.clave,
                e.parametro.vigenciaDesde,
                e.parametro.vigenciaHasta,
                e.parametro.valorNumerico,
                e.parametro.texto,
                e.estado
            )
    }
}
