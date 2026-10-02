package srtm.arbitrios

import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import srtm.emision.IdentidadEmision
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

// the arbitrios' api tests: a year of their own with its ordinance, servicios, parameters and a declared predio
// (Escenario), on the real model. every figure and code of an ordinance is FICTITIOUS. the test db is shared and an
// ordinance is one per year: each scenario takes a year no other has, and its servicios and parameters rule only in it
abstract class ConArbitriosApiTest : SrtmApiTest() {
    @Autowired
    lateinit var arbitrios: ArbitriosService

    @Autowired
    lateinit var identidad: IdentidadEmision

    @Autowired
    lateinit var db: DatabaseClient

    // a year with its ordinance (ratified unless said), two servicios that rule only in it, a sector with its zona, the
    // uso of a casa with its tasas, and a predio of that sector declared by one contribuyente the whole year
    protected inner class Escenario(
        ratificada: Boolean = true,
        // each month's due date (ARBITRIO_VENCIMIENTO), which only the HLA reads
        vencimientos: Boolean = false
    ) {
        val anio = anioLibre()
        val sector = "S${uniqueDocumento()}"
        val codigos = listOf("L${uniqueDocumento()}", "G${uniqueDocumento()}")
        private val ordenanza =
            crear(
                ORDENANZA_ARBITRIO,
                mapOf("anio" to anio, "numero" to "000-$anio (ficticia)") +
                    (if (ratificada) mapOf("acuerdo_ratificacion" to "Acuerdo 000 (ficticio)", "fecha_ratificacion" to "$anio-01-01") else emptyMap())
            )
        val servicios =
            codigos.mapIndexed { i, codigo ->
                crear(
                    SERVICIO_ARBITRIO,
                    mapOf(
                        "codigo" to codigo,
                        "nombre" to "Servicio $codigo",
                        "orden" to i + 1,
                        "vigencia_desde" to "$anio-01-01",
                        "vigencia_hasta" to "$anio-12-31",
                        "ordenanza" to ordenanza
                    )
                )
            }

        init {
            usoCasa()
            parametro("ARBITRIO_ZONA", sector, texto = "Z1")
            parametro("ARBITRIO_USO", "0101", texto = "CASA")
            parametro("TASA_ARBITRIO", "${codigos[0]}:Z1:CASA", valor = "8.50")
            parametro("TASA_ARBITRIO", "${codigos[1]}:Z1:CASA", valor = "4.25")
            if (vencimientos) (1..12).forEach { parametro("ARBITRIO_VENCIMIENTO", "$it", texto = "$anio-${"%02d".format(it)}-28") }
        }

        val contribuyente = inscribir()
        val predio = predioDeclarado(contribuyente)

        fun determinar() = "/api/srtm/predios/$predio/arbitrios"

        fun predioDeclarado(
            contribuyente: String,
            sector: String = this.sector
        ): String {
            val predio =
                post(
                    "/api/srtm/predios",
                    mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO", "sector_catastral" to sector)
                )["id"].asString()
            crear(
                "declaracion_predial",
                mapOf(
                    "contribuyente" to contribuyente,
                    "predio" to predio,
                    "anio" to anio,
                    "secuencia_uso" to "1",
                    "porcentaje_condominio" to 100,
                    "clase_uso" to "RESIDENCIAL",
                    "sub_clase_uso" to "UNIFAMILIAR",
                    "uso" to "CASA HABITACIÓN"
                )
            )
            return predio
        }

        private fun parametro(
            tipo: String,
            clave: String,
            valor: String? = null,
            texto: String? = null
        ) = crear(
            "parametro_tributario",
            mapOf(
                "tipo" to tipo,
                "clave" to clave,
                "vigencia_desde" to "$anio-01-01",
                "vigencia_hasta" to "$anio-12-31",
                "valor_numerico" to valor,
                "texto" to texto,
                "norma" to "Ordenanza ficticia de prueba",
                "fuente" to "ArbitriosApiTest",
                "transcribio" to "TEST A",
                "verifico" to "TEST B"
            ).filterValues { it != null }
        )
    }

    protected fun pedido(anio: Int) = mapOf("anio" to anio, "observacion" to "Determinación de prueba")

    protected fun matriz(e: Escenario) = tree(send("GET", "/api/srtm/predios/${e.predio}/arbitrios?anio=${e.anio}", null, HttpStatus.OK))

    protected fun pagina(consulta: String) = tree(send("GET", "/api/srtm/arbitrios?$consulta", null, HttpStatus.OK))

    protected fun crear(
        objeto: String,
        atributos: Map<String, Any?>
    ): String = post("/api/objects/$objeto/records", mapOf("attributes" to atributos))["id"].asString()

    protected fun <T> lista(
        nodo: JsonNode,
        f: (JsonNode) -> T
    ): List<T> =
        nodo
            .iterator()
            .asSequence()
            .map(f)
            .toList()

    // a cuota of the portal's api as core's attributes
    protected fun atributos(cuota: JsonNode): Map<String, Any?> = fields(cuota) - "id"

    // a year no ordinance of the shared db has
    protected fun anioLibre(): Int {
        while (true) {
            val anio = 3000 + Random.nextInt(6000)
            if (tree(send("GET", "/api/objects/$ORDENANZA_ARBITRIO/records?anio=$anio", null, HttpStatus.OK))["totalElements"].asInt() == 0) return anio
        }
    }

    // the srtm's casa habitación, with its six-digit code, once in the shared db
    protected fun usoCasa() {
        if (tree(send("GET", "/api/objects/uso_predio/records?codigo=010101", null, HttpStatus.OK))["totalElements"].asInt() > 0) return
        crear("uso_predio", mapOf("codigo" to "010101", "clase" to "RESIDENCIAL", "sub_clase" to "UNIFAMILIAR", "uso" to "CASA HABITACIÓN"))
    }

    // in-process, as the seeded admin: the identity a request would carry
    protected fun <T> comoAdmin(bloque: suspend () -> T): T =
        runBlocking {
            val carga = String(Base64.getUrlDecoder().decode(token.removePrefix("Bearer ").split('.')[1]))
            val admin = identidad.de(UUID.fromString(tree(carga)["sub"].asString()))!!
            withContext(ReactiveSecurityContextHolder.withAuthentication(admin).asCoroutineContext()) { bloque() }
        }
}
