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
import srtm.ParametroInvalido
import srtm.impuesto.ParametroTributario
import java.time.LocalDate
import java.util.UUID

// Normativa (#187): the catalog of the tipos and the parámetros with their double signature, under /api like the rest
// of the portal's api. a query parameter it does not know, or cannot read, is a 422 that names it
@RestController
@RequestMapping("/api/srtm")
class NormativaController(
    private val normativa: NormativaService
) {
    @GetMapping("/normativa/tipos")
    suspend fun tipos(): List<TipoDeParametro> = normativa.tipos()

    @GetMapping("/normativa/parametros")
    suspend fun parametros(
        @RequestParam params: Map<String, String>
    ): List<ParametroTributario> {
        params.keys.firstOrNull { it !in FILTROS }?.let { throw ParametroInvalido(it, "no es un filtro de esta consulta: ${FILTROS.joinToString(", ")}") }
        val vigenteEn =
            texto(params, "vigente_en")?.let {
                runCatching { LocalDate.parse(it) }.getOrNull() ?: throw ParametroInvalido("vigente_en", "no es una fecha AAAA-MM-DD: '$it'")
            }
        return normativa.parametros(texto(params, "tipo"), texto(params, "estado"), vigenteEn)
    }

    @PostMapping("/normativa/parametros")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun registrar(
        @RequestBody cuerpo: Map<String, Any?>
    ): ParametroTributario = normativa.registrar(cuerpo)

    @PatchMapping("/normativa/parametros/{id}")
    suspend fun corregir(
        @PathVariable id: UUID,
        @RequestBody cuerpo: Map<String, Any?>
    ): ParametroTributario = normativa.corregir(id, cuerpo)

    @DeleteMapping("/normativa/parametros/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun borrar(
        @PathVariable id: UUID
    ) = normativa.borrar(id)

    // always a 200: each id is verified or rejected with its motivo
    @PostMapping("/normativa/parametros/verificar")
    suspend fun verificar(
        @RequestBody pedido: PedidoVerificacion
    ): ResultadoVerificacion = normativa.verificar(pedido)

    @PostMapping("/normativa/parametros/{id}/anular")
    suspend fun anular(
        @PathVariable id: UUID,
        @RequestBody pedido: PedidoAnulacion
    ): ParametroTributario = normativa.anular(id, pedido)

    @PostMapping("/normativa/parametros/{id}/cerrar")
    suspend fun cerrar(
        @PathVariable id: UUID,
        @RequestBody pedido: PedidoCierre
    ): ParametroTributario = normativa.cerrar(id, pedido)

    // the csv itself is the body (Content-Type text/csv). without dry_run=false nothing is written: what it would do
    @PostMapping("/normativa/parametros/importar", consumes = ["text/csv", "text/plain"])
    suspend fun importar(
        @RequestParam(name = "dry_run", required = false, defaultValue = "true") dryRun: Boolean,
        @RequestBody csv: String
    ): ResultadoImportacion = normativa.importar(csv, dryRun)

    private fun texto(
        params: Map<String, String>,
        nombre: String
    ): String? = params[nombre]?.trim()?.ifEmpty { null }

    private companion object {
        val FILTROS = listOf("tipo", "estado", "vigente_en")
    }
}
