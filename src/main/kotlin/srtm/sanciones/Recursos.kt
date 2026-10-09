package srtm.sanciones

import srtm.impuesto.ParametroTributario
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import java.time.LocalDate

// efecto_multa: a dimension apart from the sentido, never deduced from it
const val SE_MANTIENE = "SE_MANTIENE"
const val SE_DEJA_SIN_EFECTO = "SE_DEJA_SIN_EFECTO"
const val SE_REDUCE = "SE_REDUCE"

// the options of the enums the acts receive (model/model.json): one that is not among them is a 400 that names its field
object Opciones {
    val TIPOS_RECURSO = listOf("DESCARGO", "RECONSIDERACION", "APELACION", "NULIDAD")
    val SENTIDOS = listOf("FUNDADO", "FUNDADO_EN_PARTE", "INFUNDADO", "IMPROCEDENTE")
    val EFECTOS = listOf(SE_MANTIENE, SE_DEJA_SIN_EFECTO, SE_REDUCE)
    val MODALIDADES = listOf("PERSONAL", "CEDULON", "PUBLICACION", "CORREO")
    val RESULTADOS = listOf("NOTIFICADO", "NO_UBICADO", "RECHAZADO")
}

// what a descargo copies of its plazo: the day it could be filed by, whether it was, and the plazo read
data class PlazoDelDescargo(
    val presentadoHasta: LocalDate,
    val enPlazo: Boolean,
    val plazoTexto: String,
    val plazo: String?
)

// rentas' RegistrarDescargo: the plazo is the PLAZO DESCARGO_PAPELETA in force on the infracción's day (as rentas: the
// infracción's ejercicio, not the presentación's), counted in business days from the day after the infracción. a late
// descargo is recorded all the same (en_plazo false): what follows from it is to declare it improcedente
object Descargos {
    fun registrar(
        fechaInfraccion: LocalDate,
        fecha: LocalDate,
        parametros: List<ParametroTributario>
    ): Resultado<PlazoDelDescargo> {
        val calendario = Calendario.de(parametros)
        val plazo = Plazos.plazo(parametros, Llaves.DESCARGO_PAPELETA, fechaInfraccion)
        // without the plazo the end is unknown: the feriados of the first day it would count are asked for already
        val p = plazo.valor ?: return Resultado.faltando(plazo.faltan + Plazos.faltan(calendario, fechaInfraccion, fechaInfraccion))
        val hasta = Plazos.hasta(fechaInfraccion, p.dias, calendario)
        val faltan = Plazos.faltan(calendario, fechaInfraccion, hasta)
        if (faltan.isNotEmpty()) return Resultado.faltando(faltan)
        return Resultado.de(PlazoDelDescargo(hasta, fecha <= hasta, p.texto, p.parametro))
    }
}

// rentas' ResolverConResolucionDeGerencia, its guards. the uniques (clave_ris, clave_descargo) are the guarantee; the
// 409s here only answer before the insert does
object Resoluciones {
    val TIPOS = listOf(RESOLUCION_ADMINISTRATIVA, RESOLUCION_RECURSO)

    // the plazo each tipo grants, printed on its paper and counted from its notificación (#410): both are «plazo para
    // impugnar», RG_RECURSO (art. 218.2 of Ley 27444). a missing RG_RECURSO never falls back on another plazo
    fun plazoQueConcede(tipo: String): String =
        when (tipo) {
            RESOLUCION_ADMINISTRATIVA, RESOLUCION_RECURSO -> Llaves.RG_RECURSO
            else -> throw tipoDesconocido(tipo)
        }

    // the plazo a resolución of `tipo` dated `fecha` copies (plazo_texto), or "PLAZO RG_RECURSO 2026"
    fun plazo(
        tipo: String,
        fecha: LocalDate,
        parametros: List<ParametroTributario>
    ): Resultado<Plazo> = Plazos.plazo(parametros, plazoQueConcede(tipo), fecha)

    // a resolución of `tipo` on the acta `acta`, with the facts recorded about it. SE_REDUCE: 422, the reduced amount
    // is the ordinance's (D-02b) and a made-up % is worse than none; a RECURSO resolves a descargo; sentido and efecto
    // go with the descargo and only with it; the descargo is this acta's; something left to resolve; one RIS per
    // acta and one resolución per descargo (409)
    fun validar(
        acta: String,
        tipo: String,
        descargo: DescargoPapeleta?,
        sentido: String?,
        efecto: String?,
        hechos: HechosDelActa
    ) {
        if (tipo !in TIPOS) throw tipoDesconocido(tipo)
        if (efecto == SE_REDUCE) {
            throw NoProcede(
                "No hay regla de reducción: la fija la ordenanza (D-02b)",
                listOf(FieldViolation("efecto", "SE_REDUCE no se admite todavía"))
            )
        }
        Procedimiento.exigirQueQuedeAlgoQue("resolver", hechos)
        if (tipo == RESOLUCION_RECURSO && descargo == null) {
            throw NoProcede("Una resolución RECURSO resuelve un descargo", listOf(FieldViolation("descargo", "es obligatorio en una RECURSO")))
        }
        if ((descargo != null) != (sentido != null && efecto != null)) {
            throw NoProcede(
                if (descargo ==
                    null
                ) {
                    "Sin descargo no hay fallo: sentido y efecto van con el descargo"
                } else {
                    "El descargo se resuelve con su sentido y su efecto"
                },
                listOf(FieldViolation(if (descargo == null) "sentido" else "efecto", "sentido y efecto van con el descargo, y solo con él"))
            )
        }
        if (descargo != null && descargo.papeleta != acta) {
            throw NoProcede("El descargo ${descargo.numeroExpediente} es de otra acta", listOf(FieldViolation("descargo", "es de esta acta")))
        }
        if (tipo == RESOLUCION_ADMINISTRATIVA) {
            hechos.resoluciones.firstOrNull { it.tipo == RESOLUCION_ADMINISTRATIVA }?.let {
                throw ConflictException("El acta ya tiene su RIS, la ${it.numero}")
            }
        }
        if (descargo != null) {
            hechos.resoluciones.firstOrNull { it.descargo == descargo.id }?.let {
                throw ConflictException("El descargo ${descargo.numeroExpediente} ya se resolvió con la ${it.numero}")
            }
        }
    }

    // why no resolución can be dictated now, or null: the ficha's acciones.resolucion. nothing left to resolve (an
    // ANULADA or DEJADA_SIN_EFECTO acta); or its RIS is dictated and every descargo has its resolución, so neither a
    // RIS nor a RGR is left to dictate until a new descargo comes
    fun impedimento(
        h: HechosDelActa,
        descargos: List<DescargoPapeleta>
    ): String? {
        Procedimiento.impedimento("resolver", h)?.let { return it }
        val ris = h.resoluciones.firstOrNull { it.tipo == RESOLUCION_ADMINISTRATIVA } ?: return null
        val resueltos = h.resoluciones.mapNotNull { it.descargo }.toSet()
        if (descargos.any { it.id !in resueltos }) return null
        return "El acta ya tiene su RIS, la ${ris.numero}, y ningún descargo espera su resolución: " +
            "solo queda dictar la de un descargo nuevo"
    }

    private fun tipoDesconocido(tipo: String) =
        NoProcede("El tipo de resolución '$tipo' no es uno de ${TIPOS.joinToString(", ")}", listOf(FieldViolation("tipo", "es ${TIPOS.joinToString(" o ")}")))
}

// what a notificación of a resolución fixes: whether it takes effect and, when it does, from when the resolución can be
// demanded and with which plazo
data class Exigibilidad(
    val surteEfecto: Boolean,
    val exigibleDesde: LocalDate?,
    val plazo: Plazo?
)

// rentas' NotificarResolucionDeGerencia and Exigibilidad: NOTIFICADO and RECHAZADO take effect (art. 104 a), a
// NO_UBICADO does not and is followed by another intento. the plazo is the one the resolución grants, in force on the
// diligencia's day; it runs from the business day after it, and the day after it ends the resolución is exigible
object NotificacionesDeResolucion {
    fun surteEfecto(resultado: String): Boolean = resultado in SURTEN_EFECTO

    fun exigibilidad(
        tipo: String,
        resultado: String,
        fechaDiligencia: LocalDate,
        parametros: List<ParametroTributario>
    ): Resultado<Exigibilidad> {
        if (!surteEfecto(resultado)) return Resultado.de(Exigibilidad(false, null, null))
        val calendario = Calendario.de(parametros)
        val plazo = Resoluciones.plazo(tipo, fechaDiligencia, parametros)
        val p = plazo.valor ?: return Resultado.faltando(plazo.faltan + Plazos.faltan(calendario, fechaDiligencia, fechaDiligencia))
        val exigible = Plazos.exigibleDesde(fechaDiligencia, p.dias, calendario)
        val faltan = Plazos.faltan(calendario, fechaDiligencia, exigible.minusDays(1))
        if (faltan.isNotEmpty()) return Resultado.faltando(faltan)
        return Resultado.de(Exigibilidad(true, exigible, p))
    }
}
