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
import srtm.sanciones.Filtros.fecha
import srtm.sanciones.Filtros.page
import srtm.sanciones.Filtros.size
import srtm.sanciones.Filtros.soloConoce
import srtm.sanciones.Filtros.texto
import wasichai.core.common.PageResponse
import java.time.LocalDate
import java.util.UUID

// the actas of the portal (SPEC §7, Multas): the alta with its multa, the grid and the ficha of the expediente, the
// anulación, and the actas of a contribuyente or a predio. the fase and the estado travel at «hoy», the server's day
@RestController
@RequestMapping("/api/srtm")
class ActasController(
    private val actas: ActasService
) {
    @GetMapping("/infracciones/actas")
    suspend fun actas(
        @RequestParam params: Map<String, String>
    ): PageResponse<FilaDeActa> {
        soloConoce(params, "numero", "administrado", "codigo", "fase", "desde", "hasta", "page", "size")
        val filtros =
            FiltrosDeActas(
                numero = texto(params, "numero"),
                administrado = texto(params, "administrado"),
                codigo = texto(params, "codigo"),
                fase = Procedimiento.faseDelFiltro(params["fase"]),
                desde = fecha(params, "desde"),
                hasta = fecha(params, "hasta")
            )
        return actas.pagina(filtros, LocalDate.now(), page(params), size(params))
    }

    // 201 with the acta, flat, its referencia (PAPELETA-<id>) and its desglose
    @PostMapping("/infracciones/actas")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun registrar(
        @RequestBody pedido: PedidoActa
    ): ActaRegistrada = actas.registrar(pedido, LocalDate.now())

    @GetMapping("/infracciones/actas/{id}")
    suspend fun expediente(
        @PathVariable id: UUID
    ): ExpedienteDelActa = actas.expediente(id, LocalDate.now())

    @PostMapping("/infracciones/actas/{id}/anulacion")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun anular(
        @PathVariable id: UUID,
        @RequestBody pedido: PedidoAnulacionActa
    ): AnulacionPapeleta = actas.anular(id, pedido, LocalDate.now())

    @GetMapping("/contribuyentes/{id}/infracciones")
    suspend fun delContribuyente(
        @PathVariable id: UUID,
        @RequestParam params: Map<String, String>
    ): InfraccionesDe {
        soloConoce(params)
        return actas.delContribuyente(id, LocalDate.now())
    }

    @GetMapping("/predios/{id}/infracciones")
    suspend fun delPredio(
        @PathVariable id: UUID,
        @RequestParam params: Map<String, String>
    ): InfraccionesDe {
        soloConoce(params)
        return actas.delPredio(id, LocalDate.now())
    }
}
