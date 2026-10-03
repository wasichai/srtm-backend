package srtm.sanciones

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import srtm.sanciones.Filtros.entero
import srtm.sanciones.Filtros.fecha
import srtm.sanciones.Filtros.id
import srtm.sanciones.Filtros.page
import srtm.sanciones.Filtros.size
import srtm.sanciones.Filtros.soloConoce
import srtm.sanciones.Filtros.texto
import wasichai.core.common.PageResponse
import java.time.LocalDate
import java.util.UUID

// the CUIS, the notificaciones previas and the panel of the portal (SPEC §7, Multas). a query parameter an endpoint
// does not know, or cannot read, is a 422 that names it; an act answers 201. «hoy» is the server's day
@RestController
@RequestMapping("/api/srtm/infracciones")
class InfraccionesController(
    private val cuis: CuisService,
    private val notificaciones: NotificacionesService,
    private val paneles: PanelService
) {
    @GetMapping("/cuis")
    suspend fun cuis(
        @RequestParam params: Map<String, String>
    ): CatalogoCuis {
        soloConoce(params, "vigentes_a", "materia", "q")
        return cuis.catalogo(fecha(params, "vigentes_a") ?: LocalDate.now(), texto(params, "materia"), texto(params, "q"))
    }

    // 201 with the new version, flat, and `cerrada`: the one it closed, or null
    @PostMapping("/cuis")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun nuevaVersion(
        @RequestBody pedido: PedidoVersionCuis
    ): VersionCuisCreada = cuis.nuevaVersion(pedido)

    @GetMapping("/notificaciones")
    suspend fun notificaciones(
        @RequestParam params: Map<String, String>
    ): PageResponse<NotificacionPrevia> {
        soloConoce(params, "numero", "contribuyente", "desde", "hasta", "vencidas_a", "page", "size")
        return notificaciones.pagina(
            texto(params, "numero"),
            id(params, "contribuyente"),
            fecha(params, "desde"),
            fecha(params, "hasta"),
            fecha(params, "vencidas_a") ?: LocalDate.now(),
            page(params),
            size(params)
        )
    }

    @PostMapping("/notificaciones")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun registrar(
        @RequestBody pedido: PedidoNotificacion
    ): NotificacionPrevia = notificaciones.registrar(pedido, LocalDate.now())

    @PostMapping("/notificaciones/{id}/subsanacion")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun subsanar(
        @PathVariable id: UUID,
        @RequestBody pedido: PedidoSubsanacion
    ): SubsanacionNotificacion = notificaciones.subsanar(id, pedido, LocalDate.now())

    @GetMapping("/notificaciones/vencidas")
    suspend fun vencidas(
        @RequestParam params: Map<String, String>
    ): PageResponse<NotificacionPrevia> {
        soloConoce(params, "corte", "page", "size")
        return notificaciones.vencidas(fecha(params, "corte") ?: LocalDate.now(), page(params), size(params))
    }

    @GetMapping("/notificaciones/por-contribuyente")
    suspend fun delContribuyente(
        @RequestParam params: Map<String, String>
    ): PageResponse<NotificacionPrevia> {
        soloConoce(params, "contribuyente", "page", "size")
        val contribuyente = id(params, "contribuyente") ?: throw ParametroInvalido("contribuyente", "es obligatorio: el id del contribuyente")
        return notificaciones.delContribuyente(contribuyente, LocalDate.now(), page(params), size(params))
    }

    // the year's counts (anio, by default the current one) and the previas that end this week, at today
    @GetMapping("/panel")
    suspend fun panel(
        @RequestParam params: Map<String, String>
    ): PanelDeInfracciones {
        soloConoce(params, "anio")
        val hoy = LocalDate.now()
        return paneles.panel(entero(params, "anio", hoy.year, ANIOS), hoy)
    }

    private companion object {
        // a year as the arbitrios read it
        val ANIOS = 1900..9999
    }
}
