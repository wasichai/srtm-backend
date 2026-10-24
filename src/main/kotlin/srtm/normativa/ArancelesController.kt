package srtm.normativa

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import srtm.sanciones.Filtros
import wasichai.core.common.PageResponse
import java.util.UUID

// Normativa's aranceles (#188), with the permissions of the object arancel. a query parameter it does not know, or
// cannot read, is a 422 that names it
@RestController
@RequestMapping("/api/srtm/normativa/aranceles")
class ArancelesController(
    private val aranceles: ArancelesService
) {
    @GetMapping
    suspend fun aranceles(
        @RequestParam params: Map<String, String>
    ): PageResponse<Arancel> {
        Filtros.soloConoce(params, "anio", "q", "estado", "page", "size")
        val anio = Filtros.texto(params, "anio")?.let { Filtros.entero(params, "anio", 0, 1900..2100) }
        return aranceles.aranceles(anio, Filtros.texto(params, "q"), Filtros.texto(params, "estado"), Filtros.page(params), Filtros.size(params))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun registrar(
        @RequestBody cuerpo: Map<String, Any?>
    ): Arancel = aranceles.registrar(cuerpo)

    @PatchMapping("/{id}")
    suspend fun corregir(
        @PathVariable id: UUID,
        @RequestBody cuerpo: Map<String, Any?>
    ): Arancel = aranceles.corregir(id, cuerpo)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun borrar(
        @PathVariable id: UUID
    ) = aranceles.borrar(id)

    // always a 200: each id is verified or rejected with its motivo
    @PostMapping("/verificar")
    suspend fun verificar(
        @RequestBody pedido: PedidoVerificacion
    ): ResultadoVerificacionAranceles = aranceles.verificar(pedido)

    @PostMapping("/{id}/anular")
    suspend fun anular(
        @PathVariable id: UUID,
        @RequestBody pedido: PedidoAnulacion
    ): ArancelAnulado = aranceles.anular(id, pedido)

    // the copies to catastro still pending or failed (#189), of the year or of every year: always a 200 with how many
    // were synchronized and each error
    @PostMapping("/sincronizar")
    suspend fun sincronizar(
        @RequestParam params: Map<String, String>
    ): Sincronizacion {
        Filtros.soloConoce(params, "anio")
        val anio = Filtros.texto(params, "anio")?.let { Filtros.entero(params, "anio", 0, 1900..2100) }
        return aranceles.sincronizar(anio)
    }

    // the csv itself is the body (Content-Type text/csv). without dry_run=false nothing is written: what it would do
    @PostMapping("/importar", consumes = ["text/csv", "text/plain"])
    suspend fun importar(
        @RequestParam(name = "dry_run", required = false, defaultValue = "true") dryRun: Boolean,
        @RequestBody csv: String
    ): ResultadoImportacionAranceles = aranceles.importar(csv, dryRun)
}
