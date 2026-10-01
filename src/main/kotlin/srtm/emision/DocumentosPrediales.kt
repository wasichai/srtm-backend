package srtm.emision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import srtm.impuesto.LiquidacionService
import srtm.impuesto.ParametroTributario
import srtm.rentas.CONTRIBUYENTE
import srtm.rentas.Contribuyente
import srtm.rentas.DECLARACION
import srtm.rentas.Declaracion
import srtm.rentas.NIVEL_CONSTRUCCION
import srtm.rentas.NivelConstruccion
import srtm.rentas.OBRA_COMPLEMENTARIA
import srtm.rentas.ObraComplementaria
import srtm.rentas.PREDIO
import srtm.rentas.Predio
import srtm.rentas.Registros
import srtm.rentas.vigente
import wasichai.core.common.NotFoundException
import wasichai.core.common.WasichaiException
import java.time.LocalDate
import java.util.UUID

// a pdf and the name it is served or stored under
class Documento(
    val nombre: String,
    val bytes: ByteArray
)

// one of a predio's titulares, for the portal to choose from (409 of the pu)
data class Titular(
    val id: String,
    val nombre: String,
    val documento: String
)

// a predio with several titulares that year, and no contribuyente chosen: problem+json 409 with `titulares`
class VariosTitulares(
    val titulares: List<Titular>
) : WasichaiException(HttpStatus.CONFLICT, "El predio tiene ${titulares.size} titulares: indique el contribuyente")

// the year's liquidación lacks a parameter: problem+json 422 with `faltan`, the ones missing
class FaltanParametros(
    anio: Int,
    val faltan: List<String>
) : WasichaiException(HttpStatus.UNPROCESSABLE_CONTENT, "Faltan parámetros tributarios de $anio: ${faltan.joinToString(", ")}")

// the predial documents: the PU (a predio and one titular) and the HR (a contribuyente). the only way in for the
// endpoints and the masiva (wasichai/srtm-backend#41). reads go through Registros as the caller, so core applies their
// permissions; the pdf is rendered off the request's thread
@Service
class DocumentosPrediales(
    private val registros: Registros,
    private val renderer: PdfRenderer,
    private val liquidaciones: LiquidacionService,
    @param:Value("\${srtm.municipalidad.nombre}") private val municipalidad: String
) {
    // the PU of a predio for one titular: its vigente declaraciones of `anio`, a section per secuencia de uso.
    // NotFoundException without one (or when `contribuyenteId` declares none); VariosTitulares when the predio has
    // more than one titular and none is given
    suspend fun pu(
        predioId: UUID,
        contribuyenteId: UUID?,
        anio: Int
    ): Documento {
        val predio = registros.get(PREDIO, Predio::class.java, predioId)
        val vigentes =
            registros
                .all(DECLARACION, Declaracion::class.java, filters = mapOf("predio" to predioId.toString(), "anio" to anio.toString()))
                .filter(::vigente)
        if (vigentes.isEmpty()) throw NotFoundException("El predio ${predio.codigo.orEmpty()} no tiene declaración jurada vigente en $anio")
        val titulares = vigentes.mapNotNull { it.contribuyente }.distinct()
        val titular =
            when {
                contribuyenteId != null ->
                    contribuyenteId.toString().takeIf { it in titulares }
                        ?: throw NotFoundException("El contribuyente no declara el predio ${predio.codigo.orEmpty()} en $anio")
                titulares.size == 1 -> titulares.single()
                else -> throw VariosTitulares(titularesDe(titulares))
            }
        val contribuyente = registros.get(CONTRIBUYENTE, Contribuyente::class.java, UUID.fromString(titular))
        val usos =
            vigentes.filter { it.contribuyente == titular }.map { d ->
                val id = mapOf("declaracion" to d.id!!)
                UsoDeclarado(
                    declaracion = d,
                    niveles = registros.all(NIVEL_CONSTRUCCION, NivelConstruccion::class.java, filters = id, sort = "created_at"),
                    obras = registros.all(OBRA_COMPLEMENTARIA, ObraComplementaria::class.java, filters = id, sort = "created_at")
                )
            }
        val hoja = hojaPu(municipalidad, anio, predio, contribuyente, usos, LocalDate.now())
        val pdf = withContext(Dispatchers.Default) { renderer.render("pu", mapOf("pu" to hoja)) }
        return Documento("PU-${predio.codigo ?: predioId}-$anio.pdf", pdf)
    }

    // the HR of a contribuyente for `anio`: its vigente declaraciones and the impuesto predial with its cuotas, from the
    // same liquidación GET /liquidacion answers (LiquidacionService.determinar). FaltanParametros when a parameter of
    // the year is missing; NotFoundException when the contribuyente does not exist or has no vigente declaración.
    // `parametros` are the year's already read (a lote of the masiva reads them once); null reads them
    suspend fun hr(
        contribuyenteId: UUID,
        anio: Int,
        parametros: List<ParametroTributario>? = null
    ): Documento {
        val determinacion = liquidaciones.determinar(contribuyenteId, anio, parametros)
        val contribuyente = determinacion.contribuyente
        val liquidacion = determinacion.liquidacion
        if (liquidacion.faltan.isNotEmpty()) throw FaltanParametros(anio, liquidacion.faltan)
        if (determinacion.declaraciones.isEmpty()) {
            throw NotFoundException("El contribuyente ${contribuyente.codigo.orEmpty()} no tiene declaración jurada vigente en $anio")
        }
        val predios = registros.byIds(PREDIO, Predio::class.java, determinacion.declaraciones.mapNotNull { it.predio })
        val hoja = hojaHr(municipalidad, anio, contribuyente, predios, determinacion.declaraciones, liquidacion, LocalDate.now())
        val pdf = withContext(Dispatchers.Default) { renderer.render("hr", mapOf("hr" to hoja)) }
        return Documento("HR-${contribuyente.codigo ?: contribuyenteId}-$anio.pdf", pdf)
    }

    private suspend fun titularesDe(ids: List<String>): List<Titular> {
        val contribuyentes = registros.byIds(CONTRIBUYENTE, Contribuyente::class.java, ids)
        return ids.map { id ->
            val c = contribuyentes[id]
            Titular(id = id, nombre = c?.let(::nombreDe).orEmpty(), documento = c?.let(::documentoDe).orEmpty())
        }
    }
}
