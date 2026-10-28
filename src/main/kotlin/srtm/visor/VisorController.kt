package srtm.visor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import srtm.ParametroInvalido
import srtm.brecha.Categoria
import srtm.brecha.Confianza
import srtm.pagina
import srtm.reportes.Columna
import srtm.reportes.ESTADOS_DECISION
import srtm.reportes.Exportacion
import srtm.reportes.TipoColumna
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

// the visor de brechas (#260), read only: catastro-ui's Brechas panel over srtm's own corrida (the latest TERMINADA
// of `anio`, or `corrida`), with catastro's names in snake_case, plus what srtm relays from the organization's catastro
// as its service account (the vías con arancel and the WMS images). VisorService asks READ on brecha_corrida and
// brecha_predio first. a parameter a route does not know, or cannot read, is a 422 that names it
@RestController
@RequestMapping("/api/srtm/visor")
class VisorController(
    private val visor: VisorService
) {
    @GetMapping("/panel")
    suspend fun panel(
        @RequestParam params: Map<String, String>
    ): PanelVisor {
        conocidos(params, BASE)
        return visor.panel(anio(params), corrida(params))
    }

    @GetMapping("/predios")
    suspend fun predios(
        @RequestParam params: Map<String, String>
    ): PageResponse<FilaVisor> {
        conocidos(params, BASE + FILTROS + "orden" + "page" + "size")
        val page = params["page"]?.let { entero("page", it, 0..Int.MAX_VALUE) } ?: 0
        val size = params["size"]?.let { entero("size", it, 1..PageRequest.MAX_SIZE) } ?: TAMANO
        return visor.predios(anio(params), corrida(params), filtro(params), orden(params)).second.pagina(page, size)
    }

    @GetMapping("/predios/{id}/niveles")
    suspend fun niveles(
        @PathVariable id: UUID
    ): List<NivelVisor> = visor.niveles(id)

    // orden is accepted so the table's query can go as is; a map has no order
    @GetMapping("/lotes")
    suspend fun lotes(
        @RequestParam params: Map<String, String>
    ): Map<String, Any?> {
        conocidos(params, BASE + FILTROS + "orden")
        orden(params)
        return visor.lotes(anio(params), corrida(params), filtro(params))
    }

    @GetMapping("/predios.csv")
    suspend fun csv(
        @RequestParam params: Map<String, String>
    ): ResponseEntity<ByteArray> {
        conocidos(params, BASE + FILTROS + "orden")
        val anio = anio(params)
        val (_, filas) = visor.predios(anio, corrida(params), filtro(params), orden(params))
        val bytes = withContext(Dispatchers.Default) { Exportacion.csv(COLUMNAS, filas) }
        return ResponseEntity
            .ok()
            .contentType(CSV)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename("visor-brechas-$anio.csv")
                    .build()
                    .toString()
            ).body(bytes)
    }

    // catastro's GET /api/catastro/vias/aranceles?anio, its json as it came
    @GetMapping("/vias")
    suspend fun vias(
        @RequestParam params: Map<String, String>
    ): ResponseEntity<String> {
        conocidos(params, setOf("anio"))
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePrivate())
            .body(visor.vias(anio(params)))
    }

    @GetMapping("/capas")
    suspend fun capas(): List<CapaWms> = visor.capas()

    // a GetMap of catastro's WMS, its image streamed as it arrives (never whole in memory)
    @GetMapping("/wms")
    suspend fun wms(
        @RequestParam params: MultiValueMap<String, String>
    ): ResponseEntity<Flux<DataBuffer>> {
        val imagen = visor.wms(params)
        return ResponseEntity
            .ok()
            .contentType(imagen.tipo!!)
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePrivate())
            .header("X-Content-Type-Options", "nosniff")
            .body(imagen.cuerpo)
    }

    private fun conocidos(
        params: Map<String, String>,
        validos: Set<String>
    ) {
        params.keys.firstOrNull { it !in validos }?.let { throw ParametroInvalido(it, "no existe en este endpoint") }
    }

    private fun anio(params: Map<String, String>): Int =
        params["anio"]?.let { entero("anio", it, 1900..2100) } ?: throw ParametroInvalido("anio", "es obligatorio")

    private fun corrida(params: Map<String, String>): UUID? =
        params["corrida"]?.trim()?.ifEmpty { null }?.let {
            runCatching { UUID.fromString(it) }.getOrNull() ?: throw ParametroInvalido("corrida", "no es un id: '$it'")
        }

    private fun filtro(params: Map<String, String>): FiltroVisor =
        FiltroVisor(
            categorias = lista(params, "categoria", Categoria.entries.map { it.name }),
            confianzas = lista(params, "confianza", Confianza.entries.map { it.name }),
            sector = params["sector"]?.trim()?.ifEmpty { null },
            estados = lista(params, "estado", ESTADOS_DECISION),
            supera5Uit =
                params["supera_5_uit"]?.let {
                    when (it.trim().lowercase()) {
                        "true" -> true
                        "false" -> false
                        else -> throw ParametroInvalido("supera_5_uit", "debe ser true o false")
                    }
                },
            minDelta =
                params["min_delta"]?.let {
                    it.trim().toBigDecimalOrNull()?.takeIf { d -> d >= BigDecimal.ZERO }
                        ?: throw ParametroInvalido("min_delta", "debe ser un número mayor o igual a 0")
                }
        )

    // a comma-separated list of the model's options; an option it does not have is a 422
    private fun lista(
        params: Map<String, String>,
        nombre: String,
        opciones: List<String>
    ): Set<String>? {
        val valor = params[nombre] ?: return null
        val elegidas = valor.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (elegidas.isEmpty() || elegidas.any { it !in opciones }) {
            throw ParametroInvalido(nombre, "debe ser uno o más de ${opciones.joinToString(", ")}, separados por comas")
        }
        return elegidas.toSet()
    }

    private fun orden(params: Map<String, String>): OrdenVisor =
        params["orden"]?.let {
            OrdenVisor.de(it.trim()) ?: throw ParametroInvalido("orden", "debe ser ${OrdenVisor.entries.joinToString(", ") { o -> o.parametro }}")
        } ?: OrdenVisor.DELTA_DESC

    private fun entero(
        nombre: String,
        valor: String,
        rango: IntRange
    ): Int =
        valor.trim().toIntOrNull()?.takeIf { it in rango }
            ?: throw ParametroInvalido(nombre, "debe ser un entero entre ${rango.first} y ${rango.last}")

    private companion object {
        val BASE = setOf("anio", "corrida")
        val FILTROS = setOf("categoria", "confianza", "sector", "estado", "supera_5_uit", "min_delta")
        const val TAMANO = 50
        val CSV = MediaType.parseMediaType("text/csv;charset=UTF-8")

        // the csv's columns: /predios' fields, by their json names
        val COLUMNAS: List<Columna<FilaVisor>> =
            listOf(
                Columna("codigo_catastro", "Código de catastro", "Código municipal de la ficha.", TipoColumna.TEXTO) { it.codigoCatastro },
                Columna("codigo_predio", "Código del predio", "Código del predio en el padrón de rentas.", TipoColumna.TEXTO) { it.codigoPredio },
                Columna("sector", "Sector", "Sector catastral.", TipoColumna.TEXTO) { it.sector },
                Columna("manzana", "Manzana", "Manzana catastral.", TipoColumna.TEXTO) { it.manzana },
                Columna("lote", "Lote", "Lote.", TipoColumna.TEXTO) { it.lote },
                Columna("uso", "Uso", "Código de uso del catastro.", TipoColumna.TEXTO) { it.uso },
                Columna("uso_descripcion", "Descripción del uso", "El uso de ese código en el catálogo.", TipoColumna.TEXTO) { it.usoDescripcion },
                Columna("categoria", "Categoría", "Categoría de la brecha.", TipoColumna.TEXTO) { it.categoria },
                Columna("confianza", "Confianza", "Confianza del cruce.", TipoColumna.TEXTO) { it.confianza },
                Columna("autoavaluo_declarado", "Autoavalúo declarado", "S/.", TipoColumna.DINERO) { it.autoavaluoDeclarado },
                Columna("autoavaluo_catastro", "Autoavalúo del catastro", "S/.", TipoColumna.DINERO) { it.autoavaluoCatastro },
                Columna("delta_autoavaluo", "Δ autoavalúo", "Catastro menos declarado, S/.", TipoColumna.DINERO) { it.deltaAutoavaluo },
                Columna("impuesto_declarado", "Impuesto declarado", "S/.", TipoColumna.DINERO) { it.impuestoDeclarado },
                Columna("impuesto_catastro", "Impuesto del catastro", "S/.", TipoColumna.DINERO) { it.impuestoCatastro },
                Columna("delta_impuesto", "Δ impuesto", "S/.", TipoColumna.DINERO) { it.deltaImpuesto },
                Columna("delta_arbitrios", "Δ arbitrios", "S/.", TipoColumna.DINERO) { it.deltaArbitrios },
                Columna("estimado", "Estimado", "El impuesto de un omiso, liquidado solo.", TipoColumna.BOOLEANO) { it.estimado },
                Columna("supera_5_uit", "Supera 5 UIT", "|Δ autoavalúo| mayor a 5 UIT del 1 de enero.", TipoColumna.BOOLEANO) { it.supera5Uit },
                Columna("estado", "Estado", "Decisión de catastro sobre el predio.", TipoColumna.TEXTO) { it.estado },
                Columna("con_geometria", "Con lote", "Si el predio tiene su lote dibujado.", TipoColumna.BOOLEANO) { it.conGeometria }
            )
    }
}
