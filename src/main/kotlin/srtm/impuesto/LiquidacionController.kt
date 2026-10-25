package srtm.impuesto

import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import srtm.rentas.Contribuyente
import srtm.rentas.ContribuyenteService
import srtm.rentas.DECLARACION
import srtm.rentas.Declaracion
import srtm.rentas.PREDIO
import srtm.rentas.Predio
import srtm.rentas.Registros
import srtm.rentas.totalesDeContribuyente
import srtm.rentas.vigente
import java.time.LocalDate
import java.util.UUID

// the impuesto predial of a contribuyente, under /api like the rest of the portal's api: core's jwt chain protects it
@RestController
@RequestMapping("/api/srtm")
class LiquidacionController(
    private val liquidaciones: LiquidacionService
) {
    // without anio, the current year's
    @GetMapping("/contribuyentes/{id}/liquidacion")
    suspend fun liquidacion(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?
    ) = liquidaciones.liquidar(id, anio ?: LocalDate.now().year)
}

// what the liquidación of a contribuyente in a year was computed from: the contribuyente and its vigentes declaraciones
class Determinacion(
    val contribuyente: Contribuyente,
    val declaraciones: List<Declaracion>,
    val liquidacion: Liquidacion
)

// the only liquidación of a contribuyente: the /liquidacion endpoint and the HR (srtm.emision) both come here, so the
// HR prints the figures the endpoint answers
@Service
class LiquidacionService(
    private val registros: Registros,
    private val contribuyentes: ContribuyenteService,
    private val parametrosTributarios: ParametrosTributarios
) {
    suspend fun liquidar(
        id: UUID,
        anio: Int
    ): Liquidacion = determinar(id, anio).liquidacion

    // the base is the valor afecto of its vigentes declaraciones of the year: each titular's part (condominio), so a
    // shared predio is not counted twice. an annulled one counts nothing. a contribuyente that does not exist is a 404.
    // `parametros` are the year's already read (the masiva reads them once per lote); null reads them
    suspend fun determinar(
        id: UUID,
        anio: Int,
        parametros: List<ParametroTributario>? = null
    ): Determinacion {
        val contribuyente = contribuyentes.get(id)
        val declaraciones =
            registros
                .all(DECLARACION, Declaracion::class.java, filters = mapOf("contribuyente" to id.toString(), "anio" to anio.toString()))
                .filter(::vigente)
        val liquidacion = ImpuestoPredial.liquidar(anio, totalesDeContribuyente(declaraciones).valorAfecto, parametros ?: parametrosTributarios.todos())
        return Determinacion(contribuyente, declaraciones, liquidacion.copy(avisos = avisos(declaraciones)))
    }

    // #236: the predios are read only when some declaración is por revisar, so the masiva's lote pays nothing otherwise
    private suspend fun avisos(declaraciones: List<Declaracion>): List<AvisoLiquidacion> {
        val porRevisar = declaraciones.filter(::porRevisar)
        if (porRevisar.isEmpty()) return emptyList()
        return avisosPorRevisar(porRevisar, registros.byIds(PREDIO, Predio::class.java, porRevisar.mapNotNull { it.predio }))
    }
}

private fun porRevisar(d: Declaracion) = d.autoavaluoPorRevisar == true

// #236: an aviso for each declaración por revisar, in their order; `predios` by id gives each its código
fun avisosPorRevisar(
    declaraciones: List<Declaracion>,
    predios: Map<String, Predio>
): List<AvisoLiquidacion> =
    declaraciones.filter(::porRevisar).map { d ->
        AvisoLiquidacion(
            declaracion = d.id!!,
            predio = d.predio,
            predioCodigo = d.predio?.let { predios[it]?.codigo },
            motivo = d.autoavaluoMotivo?.ifBlank { null } ?: AUTOAVALUO_POR_REVISAR
        )
    }
