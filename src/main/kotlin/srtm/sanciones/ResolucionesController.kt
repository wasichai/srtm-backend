package srtm.sanciones

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

// the acts that answer an acta (SPEC §7, Multas): its descargos, its resoluciones (RIS and RGR) with their paper, and
// the notificación of a resolución. each act answers 201 with its record, flat. «hoy» is the server's day
@RestController
@RequestMapping("/api/srtm/infracciones")
class ResolucionesController(
    private val resoluciones: ResolucionesService
) {
    // 201 with presentado_hasta, en_plazo and plazo_texto copied
    @PostMapping("/actas/{id}/descargos")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun descargo(
        @PathVariable id: UUID,
        @RequestBody pedido: PedidoDescargo
    ): DescargoPapeleta = resoluciones.registrarDescargo(id, pedido, LocalDate.now())

    // 201 with its número: RIS-AAAA-NNNNNN or RGR-AAAA-NNNNNN
    @PostMapping("/actas/{id}/resoluciones")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun resolucion(
        @PathVariable id: UUID,
        @RequestBody pedido: PedidoResolucion
    ): ResolucionGerencia = resoluciones.dictar(id, pedido, LocalDate.now())

    // inline, like the HLA: drawn again from the frozen rows
    @GetMapping("/resoluciones/{id}/pdf")
    suspend fun pdf(
        @PathVariable id: UUID
    ): ResponseEntity<ByteArray> {
        val documento = resoluciones.pdf(id)
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .inline()
                    .filename(documento.nombre)
                    .build()
                    .toString()
            ).body(documento.bytes)
    }

    // 201 with its intento, the direccion used and, when it takes effect, exigible_desde and plazo_texto
    @PostMapping("/resoluciones/{id}/notificacion")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun notificacion(
        @PathVariable id: UUID,
        @RequestBody pedido: PedidoNotificacionResolucion
    ): NotificacionResolucion = resoluciones.notificar(id, pedido, LocalDate.now())
}
