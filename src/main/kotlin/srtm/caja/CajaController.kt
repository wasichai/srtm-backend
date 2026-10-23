package srtm.caja

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import srtm.Permisos
import wasichai.core.common.ValidationException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// #163: a contribuyente's year in its organization's caja, under /api like the rest of the portal's api. without
// anio, the current year's (in Lima). an organization without caja: 409
@RestController
@RequestMapping("/api/srtm")
class CajaController(
    private val caja: CajaService,
    private val permisos: Permisos
) {
    // the cuotas de predial and of arbitrios sent now as ordenes de cobro: what changed since the last time, and the
    // ordenes in ERROR tried again (Ruling 27)
    @PostMapping("/contribuyentes/{id}/caja")
    suspend fun enviar(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?
    ): ResumenCaja {
        permisos.exigirCrear(ObjetosCaja.ORDEN_CAJA, "enviar a caja")
        return caja.sincronizar(id, anioDe(anio), MOTIVO_A_PEDIDO, aPedido = true)
    }

    @GetMapping("/contribuyentes/{id}/caja")
    suspend fun estado(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?
    ): EstadoCaja {
        permisos.exigirLeer(ObjetosCaja.ORDEN_CAJA, "consultar la caja")
        return caja.estado(id, anioDe(anio))
    }

    private fun anioDe(anio: Int?): Int {
        val a = anio ?: LocalDate.now(ZoneId.of("America/Lima")).year
        if (a !in ANIOS) throw ValidationException("El año no es válido", "anio", "de ${ANIOS.first} a ${ANIOS.last}")
        return a
    }

    private companion object {
        val ANIOS = 1900..9999
    }
}
