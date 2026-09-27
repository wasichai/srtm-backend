package srtm.impuesto

import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import srtm.rentas.ContribuyenteService
import srtm.rentas.DECLARACION
import srtm.rentas.Declaracion
import srtm.rentas.Registros
import srtm.rentas.totalesDeContribuyente
import srtm.rentas.vigente
import java.time.LocalDate
import java.util.UUID

const val PARAMETRO_TRIBUTARIO = "parametro_tributario"

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

@Service
class LiquidacionService(
    private val registros: Registros,
    private val contribuyentes: ContribuyenteService
) {
    // the base is the valor afecto of its vigentes declaraciones of the year: each titular's part (condominio), so a
    // shared predio is not counted twice. an annulled one counts nothing. a contribuyente that does not exist is a 404
    suspend fun liquidar(
        id: UUID,
        anio: Int
    ): Liquidacion {
        contribuyentes.get(id)
        val declaraciones =
            registros
                .all(DECLARACION, Declaracion::class.java, filters = mapOf("contribuyente" to id.toString(), "anio" to anio.toString()))
                .filter(::vigente)
        val parametros = registros.all(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java)
        return ImpuestoPredial.liquidar(anio, totalesDeContribuyente(declaraciones).valorAfecto, parametros)
    }
}
