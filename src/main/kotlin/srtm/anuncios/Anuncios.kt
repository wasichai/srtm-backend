package srtm.anuncios

import org.springframework.http.HttpStatus
import srtm.impuesto.ParametroTributario
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.WasichaiException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// where an anuncio stands on a day. not a column: it derives from its movimientos and the day asked (rentas'
// EstadoDelAnuncio). RETIRADO over CESADO over VENCIDO over VIGENTE
const val VIGENTE = "VIGENTE"
const val VENCIDO = "VENCIDO"
const val CESADO = "CESADO"
const val RETIRADO = "RETIRADO"
val ESTADOS = listOf(VIGENTE, VENCIDO, CESADO, RETIRADO)

// model.json's clase_anuncio and tipo_anuncio (AnunciosTest compares them)
val CLASES = listOf("LETRERO", "PANEL", "TOLDO", "BANDEROLA", "PANTALLA_DIGITAL", "GLOBO_AEROSTATICO")
val TIPOS_DE_ANUNCIO = listOf("AVISO_SIMPLE", "AVISO_LUMINOSO", "AVISO_ILUMINADO", "AVISO_ELECTRONICO")

// what keeps an act from accruing: a 422 that names each missing parámetro (TASA_ANUNCIO PANEL 2026)
class FaltanAnuncios(
    val faltan: List<String>
) : WasichaiException(HttpStatus.UNPROCESSABLE_CONTENT, "No hay tasa que devengar: falta ${faltan.joinToString("; ")}")

// an act the anuncio does not admit (cesado, out of order, several ejercicios...): a 422 that says why
class ActoNoAdmitido(
    mensaje: String
) : WasichaiException(HttpStatus.UNPROCESSABLE_CONTENT, mensaje)

// whether an act is allowed on a day, and why not
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Accion(
    val permitida: Boolean,
    val motivo: String?
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Acciones(
    val renovacion: Accion,
    val cese: Accion,
    val retiro: Accion
)

// an act before which another must not be dated: "la autorización AN-2026-000001" on 2026-03-02
data class ActoPrevio(
    val nombre: String,
    val fecha: LocalDate
)

// the rules of the anuncios: no database, no clock, the day as an argument (rentas' EstadoDelAnuncio,
// MovimientoDeAnuncio and OrdenDeLosActos). no tasa is written here: it is read from parametro_tributario
object Anuncios {
    // the vigencia_hasta of the last act that accrues dated up to `fecha` (a renewal extends it without editing the
    // anuncio); among acts of the same day, the later in the list. null: without term
    fun vigencia(
        movimientos: List<MovimientoAnuncio>,
        fecha: LocalDate
    ): LocalDate? {
        var vigencia: LocalDate? = null
        var ultimo: LocalDate? = null
        for (m in movimientos) {
            val dia = m.fecha ?: continue
            if (m.tipo !in DEVENGAN || dia > fecha) continue
            if (ultimo == null || dia >= ultimo) {
                ultimo = dia
                vigencia = m.vigenciaHasta
            }
        }
        return vigencia
    }

    // the estado on `fecha`: a movimiento dated after it does not count (a padrón of a past day says what it said then)
    fun estado(
        movimientos: List<MovimientoAnuncio>,
        fecha: LocalDate
    ): String {
        val hasta = movimientos.filter { it.fecha != null && it.fecha <= fecha }
        if (hasta.any { it.tipo == RETIRO }) return RETIRADO
        if (hasta.any { it.tipo == CESE }) return CESADO
        val vigencia = vigencia(movimientos, fecha)
        return if (vigencia != null && vigencia < fecha) VENCIDO else VIGENTE
    }

    // a cesado or retirado anuncio is not renewed: the cese stops the debt to come, and leaves the accrued one
    fun admiteRenovacion(estado: String) = estado == VIGENTE || estado == VENCIDO

    // the ejercicio a renewal accrues (rentas#417): the one it renews, not the act's. the year of the new vigencia;
    // without one, the act's. counted from the day after the current vigencia when it still runs on the act's day,
    // else from the act: a prórroga over several ejercicios is not charged as one (422), it is renewed year by year
    fun ejercicioQueRenueva(
        vigenciaActual: LocalDate?,
        fechaActo: LocalDate,
        nuevaVigencia: LocalDate?
    ): Int {
        if (nuevaVigencia == null) return fechaActo.year
        val desde = if (vigenciaActual != null && vigenciaActual >= fechaActo) vigenciaActual.plusDays(1) else fechaActo
        if (desde.year < nuevaVigencia.year) {
            throw ActoNoAdmitido(
                "La prórroga del $desde al $nuevaVigencia abarca los ejercicios ${desde.year} a ${nuevaVigencia.year}, y una " +
                    "renovación devenga uno solo: se renueva ejercicio a ejercicio"
            )
        }
        return nuevaVigencia.year
    }

    // the TASA_ANUNCIO row of a clase in force on `dia`, the latest when several are. one of 0 or less is no tasa: a clase
    // the ordinance does not charge is left without row, not charged at 0
    fun tasa(
        parametros: List<ParametroTributario>,
        clase: String,
        dia: LocalDate
    ): ParametroTributario? =
        parametros
            .filter { it.tipo == Llaves.TASA_ANUNCIO && it.clave == clase && vigente(it, dia) }
            .filter { it.valorNumerico != null && it.valorNumerico.signum() > 0 && it.id != null }
            .maxByOrNull { it.vigenciaDesde!! }

    // a clase's tasa of an ejercicio, as GET /anuncios/tasas shows it: the row in force on January 1 (the one a renewal
    // of that ejercicio reads), else the first one that starts within the year. null: the year does not charge it
    fun tasaDelEjercicio(
        parametros: List<ParametroTributario>,
        clase: String,
        anio: Int
    ): ParametroTributario? {
        val inicio = LocalDate.of(anio, 1, 1)
        val fin = LocalDate.of(anio, 12, 31)
        return tasa(parametros, clase, inicio)
            ?: parametros
                .filter { it.tipo == Llaves.TASA_ANUNCIO && it.clave == clase && it.id != null }
                .filter { it.valorNumerico != null && it.valorNumerico.signum() > 0 }
                .filter { it.vigenciaDesde != null && it.vigenciaDesde in inicio..fin }
                .minByOrNull { it.vigenciaDesde!! }
    }

    // the act that accrues (AUTORIZACION or RENOVACION) of an ejercicio: its clave, its referencia de cargo and the
    // tasa copied from the row read, which stays as its procedencia
    fun devengo(
        anuncio: String,
        tipo: String,
        fecha: LocalDate,
        anio: Int,
        tasa: ParametroTributario,
        vigenciaHasta: LocalDate?,
        observacion: String
    ): MovimientoAnuncio {
        require(tipo in DEVENGAN) { "$tipo no devenga" }
        return MovimientoAnuncio(
            tipo = tipo,
            fecha = fecha,
            anio = anio,
            referenciaCargo = referenciaDeCargo(anuncio, anio),
            tasa = tasa.valorNumerico,
            vigenciaHasta = vigenciaHasta,
            clave = claveDeMovimiento(anuncio, tipo, anio),
            observacion = observacion,
            anuncio = anuncio,
            parametro = tasa.id
        )
    }

    // the cese or the retiro: a motivo, no vigencia, nothing accrued
    fun baja(
        anuncio: String,
        tipo: String,
        fecha: LocalDate,
        motivo: String,
        observacion: String
    ): MovimientoAnuncio {
        require(tipo == CESE || tipo == RETIRO) { "$tipo no es una baja" }
        return MovimientoAnuncio(
            tipo = tipo,
            fecha = fecha,
            motivo = motivo,
            clave = claveDeMovimiento(anuncio, tipo),
            observacion = observacion,
            anuncio = anuncio
        )
    }

    // the tasas accrued up to `alDia`: what the acts determined, not a debt (nothing here is paid)
    fun devengado(
        movimientos: List<MovimientoAnuncio>,
        alDia: LocalDate
    ): BigDecimal =
        movimientos
            .filter { it.tipo in DEVENGAN && it.fecha != null && it.fecha <= alDia }
            .mapNotNull { it.tasa }
            .fold(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP)

    // no act is dated after today nor before the acts it follows (rentas' OrdenDeLosActos): a 422 that names both dates
    fun exigirOrden(
        acto: String,
        fecha: LocalDate,
        hoy: LocalDate,
        previos: List<ActoPrevio>
    ) {
        if (fecha > hoy) throw ActoNoAdmitido("La fecha de $acto ($fecha) es posterior a hoy ($hoy): un acto no se fecha en el futuro")
        previos.firstOrNull { fecha < it.fecha }?.let {
            throw ActoNoAdmitido("La fecha de $acto ($fecha) es anterior a la de ${it.nombre} (${it.fecha}): los actos van en orden")
        }
    }

    // the three acts left to an anuncio on `alDia`, as its estado admits them (the service decides all the same)
    fun acciones(
        movimientos: List<MovimientoAnuncio>,
        alDia: LocalDate
    ): Acciones {
        val estado = estado(movimientos, alDia)
        return Acciones(
            renovacion =
                when (estado) {
                    CESADO -> Accion(false, "Un anuncio cesado no se renueva: el cese detiene la tasa de los ejercicios siguientes.")
                    RETIRADO -> Accion(false, "Un anuncio retirado no se renueva.")
                    else -> Accion(true, null)
                },
            cese =
                when (estado) {
                    CESADO -> Accion(false, "El anuncio ya está cesado.")
                    RETIRADO -> Accion(false, "El anuncio ya está retirado.")
                    else -> Accion(true, null)
                },
            retiro =
                when (estado) {
                    CESADO -> Accion(true, null)
                    RETIRADO -> Accion(false, "El anuncio ya está retirado.")
                    else -> Accion(false, "Se retira después del cese: el anuncio no está cesado.")
                }
        )
    }

    private fun vigente(
        p: ParametroTributario,
        dia: LocalDate
    ) = p.vigenciaDesde != null && p.vigenciaDesde <= dia && (p.vigenciaHasta == null || p.vigenciaHasta >= dia)
}
