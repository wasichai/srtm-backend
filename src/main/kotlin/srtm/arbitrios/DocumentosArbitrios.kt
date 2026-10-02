package srtm.arbitrios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import srtm.emision.CabeceraDocumento
import srtm.emision.Documento
import srtm.emision.PdfRenderer
import srtm.impuesto.ParametroTributario
import srtm.rentas.CONTRIBUYENTE
import srtm.rentas.Contribuyente
import srtm.rentas.Registros
import wasichai.core.common.WasichaiException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

// a contribuyente that owes no arbitrio of the year: its predios are determined, and no cuota is charged to it (a
// condómino whose share is not the largest). no HLA, and it is not an error: the masiva leaves it out
class SinArbitrios(
    mensaje: String
) : WasichaiException(HttpStatus.NOT_FOUND, mensaje)

// the HLA of a contribuyente for `anio`: its cuotas as they were determined, the same figures GET
// /contribuyentes/{id}/arbitrios answers (ArbitriosService.delContribuyente). FaltanArbitrios when the year lacks its
// ratified ordinance, when a predio of its still has cuotas to determine (or cannot be determined), or a month with a
// cuota lacks its due date (ARBITRIO_VENCIMIENTO); SinArbitrios when nothing is charged to it. reads go through
// Registros as the caller; the pdf is rendered off the request's thread
@Service
class DocumentosArbitrios(
    private val arbitrios: ArbitriosService,
    private val registros: Registros,
    private val renderer: PdfRenderer,
    private val cabeceras: CabeceraDocumento
) {
    // `contexto`: the year's, already read (a lote of the masiva reads it once); null reads it
    suspend fun hla(
        contribuyenteId: UUID,
        anio: Int,
        contexto: ContextoArbitrios? = null
    ): Documento {
        val delAnio = contexto ?: arbitrios.contexto(anio)
        val contribuyente = registros.get(CONTRIBUYENTE, Contribuyente::class.java, contribuyenteId)
        val codigo = contribuyente.codigo ?: contribuyenteId.toString()
        val datos = arbitrios.delContribuyente(contribuyenteId, anio, delAnio)
        val faltan = Arbitrios.faltanDelAnio(delAnio).toMutableList()
        for (m in datos.predios) {
            val predio = m.predio.codigo ?: m.predio.id
            if (m.pendientes > 0) faltan += "Predio $predio: ${m.pendientes} cuotas de arbitrios por determinar"
            faltan += m.faltan.filter { it !in faltan }.map { if (predio != null && predio in it) it else "Predio $predio: $it" }
        }
        val conCuota = Periodo.MESES.filter { p -> datos.predios.any { it.totalesPorMes[p - 1].signum() != 0 || it.filas.any { f -> f.meses[p - 1] != null } } }
        if (faltan.isEmpty() && conCuota.isEmpty()) throw SinArbitrios("El contribuyente $codigo no tiene cuotas de arbitrios a su nombre en $anio")
        val vencimientos = conCuota.associateWith { vencimiento(delAnio.parametros, anio, it) }
        faltan += vencimientos.filterValues { it == null }.keys.map { "${Llaves.ARBITRIO_VENCIMIENTO} $it $anio" }
        if (faltan.isNotEmpty()) throw FaltanArbitrios(anio, faltan.distinct())

        val cuotas = arbitrios.cuotasDe(contribuyenteId, anio)
        val zonasYUsos =
            cuotas.groupBy { it.predio }.mapKeys { it.key.orEmpty() }.mapValues { (_, c) ->
                c.mapNotNull { it.zona }.distinct() to c.mapNotNull { it.usoArbitrio }.distinct()
            }
        val hoja =
            hojaHla(
                cabeceras.actual(),
                contribuyente,
                datos,
                delAnio.ordenanza!!,
                vencimientos.mapValues { it.value!! },
                zonasYUsos,
                LocalDateTime.now(LIMA)
            )
        val pdf = withContext(Dispatchers.Default) { renderer.render("hla", mapOf("hla" to hoja)) }
        return Documento("HLA-$codigo-$anio.pdf", pdf)
    }

    // the HLA maker of a lote of the masiva: the year's contexto read once, as the lote's user. a contribuyente charged
    // nothing has none (null); one that cannot have it throws, and the masiva counts it among its errors
    suspend fun hlas(anio: Int): suspend (UUID) -> Documento? {
        val contexto = arbitrios.contexto(anio)
        return { id ->
            try {
                hla(id, anio, contexto)
            } catch (_: SinArbitrios) {
                null
            }
        }
    }

    // what the year lacks for any HLA: its ratified ordinance, servicios, tasas, zonas and usos (as GET
    // /arbitrios/parametros says), and each month's due date
    suspend fun faltanHla(anio: Int): List<String> {
        val p = arbitrios.parametros(anio)
        return p.faltan + Periodo.MESES.filter { vencimiento(p.parametros, anio, it) == null }.map { "${Llaves.ARBITRIO_VENCIMIENTO} $it $anio" }
    }

    private companion object {
        // the documents carry the hour they were emitted at, as the municipality reads its clock
        val LIMA: ZoneId = ZoneId.of("America/Lima")
    }
}

// the due date of a month's cuota: ARBITRIO_VENCIMIENTO <month> in force on its day of atribución, its texto a date.
// null when there is none, or it is not a date
fun vencimiento(
    parametros: List<ParametroTributario>,
    anio: Int,
    periodo: Int
): LocalDate? {
    val dia = Periodo.atribucion(anio, periodo)
    return parametros
        .filter { it.tipo == Llaves.ARBITRIO_VENCIMIENTO && it.clave?.trim() == periodo.toString() && vigente(it.vigenciaDesde, it.vigenciaHasta, dia) }
        .maxByOrNull { it.vigenciaDesde!! }
        ?.texto
        ?.trim()
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
