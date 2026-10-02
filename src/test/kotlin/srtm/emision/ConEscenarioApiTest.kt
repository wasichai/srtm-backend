package srtm.emision

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import srtm.impuesto.ConParametrosApiTest
import srtm.impuesto.Parametros
import tools.jackson.databind.JsonNode
import java.util.UUID

// what the masiva's api tests share: a padrón of their own year (3 contribuyentes, 4 predios), the masiva's calls,
// the documents the endpoints emit one by one to compare with, and the lotes of an emission as their rows have them
abstract class ConEscenarioApiTest : ConParametrosApiTest() {
    @Autowired
    lateinit var tablas: TablasCore

    @Autowired
    lateinit var db: DatabaseClient

    // 3 contribuyentes and 4 predios: A declares P1 and P2, B declares P3, and B and C share P4 (condominio)
    protected class Escenario(
        // in the padrón's order: by codigo
        val contribuyentes: List<String>,
        // each contribuyente's codigo, by id
        val codigos: Map<String, String>,
        // each contribuyente's predios, in the order of its PUs: by the predio's codigo
        val predios: Map<String, List<String>>
    ) {
        // (predio, titular): one PU each
        val pus: List<Pair<String, String>> get() = contribuyentes.flatMap { c -> predios.getValue(c).map { it to c } }
    }

    protected fun escenario(anio: Int): Escenario {
        uit(anio)
        val inscritos = List(3) { post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento())) }
        val codigos = inscritos.associate { it["id"].asString() to it["codigo"].asString() }
        val (a, b, c) = inscritos.map { it["id"].asString() }
        val predios = List(4) { "T-${uniqueDocumento()}" }.map { codigo -> predioDe(codigo) to codigo }
        val (p1, p2, p3, p4) = predios.map { it.first }
        declarar(a, p1, anio)
        declarar(a, p2, anio)
        declarar(b, p3, anio)
        declarar(b, p4, anio)
        declarar(c, p4, anio, "porcentaje_condominio" to 40)
        val codigoPredio = predios.toMap()
        return Escenario(
            listOf(a, b, c).sortedBy { codigos.getValue(it) },
            codigos,
            mapOf(a to listOf(p1, p2), b to listOf(p3, p4), c to listOf(p4)).mapValues { (_, ps) -> ps.sortedBy { codigoPredio.getValue(it) } }
        )
    }

    // the padrón's contribuyentes as the masiva reads them, in its order
    protected fun aEmitir(e: Escenario): List<ContribuyenteAEmitir> =
        e.contribuyentes.map { c ->
            ContribuyenteAEmitir(UUID.fromString(c), e.codigos.getValue(c), "FLORES OTINIANO JUNIOR", e.predios.getValue(c).map(UUID::fromString))
        }

    // the documents of `contribuyentes` as the endpoints emit them one by one: each one's HR, then its PUs
    protected fun documentos(
        e: Escenario,
        anio: Int,
        contribuyentes: List<String> = e.contribuyentes
    ): List<ByteArray> =
        contribuyentes.flatMap { c ->
            listOf(documento("/api/srtm/contribuyentes/$c/hr?anio=$anio")) +
                e.predios.getValue(c).map { p -> documento("/api/srtm/predios/$p/pu?anio=$anio&contribuyente=$c") }
        }

    // the final pdf has those documents, in that order, and nothing else
    protected fun esLaConcatenacion(
        pdf: ByteArray,
        documentos: List<ByteArray>
    ) {
        assertEquals(documentos.sumOf { paginas(it) }, paginas(pdf))
        assertEquals(sinHora(documentos.joinToString("") { texto(it) }), sinHora(texto(pdf)))
    }

    // the header prints when each document was emitted, to the minute: the masiva and the documents asked for after it
    // may fall on different minutes. the dates without a time (the cuotas' due dates) still count
    private fun sinHora(texto: String) = texto.replace(Regex("""\d{2}/\d{2}/\d{4} \d{2}:\d{2}"""), "<emitido>")

    private fun predioDe(codigo: String): String =
        post("/api/srtm/predios", mapOf("codigo" to codigo, "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO"))["id"].asString()

    protected fun declarar(
        contribuyente: String,
        predio: String,
        anio: Int,
        vararg extra: Pair<String, Any?>
    ) {
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to contribuyente,
                "predio" to predio,
                "anio" to anio,
                "secuencia_uso" to "1",
                "valor_autoavaluo" to 10000.50,
                "deduccion" to 0
            ) + extra
        )
    }

    // the csv's UIT of 2026 for the test's year: the HR liquidates with the UIT in force on 1 january. the tramos and
    // the mínimo have no end date, and ConParametrosApiTest loads them
    protected fun uit(anio: Int) {
        post(
            "/api/objects/parametro_tributario/records",
            mapOf(
                "attributes" to
                    mapOf(
                        "tipo" to "UIT",
                        "vigencia_desde" to "$anio-01-01",
                        "vigencia_hasta" to "$anio-12-31",
                        "valor_numerico" to Parametros.uit(2026),
                        "transcribio" to "TEST",
                        "verifico" to "TEST"
                    )
            )
        )
    }

    // a 202: the job's id
    protected fun emitir(
        anio: Int,
        formato: String,
        token: String = this.token
    ): String {
        val (status, cuerpo) = exchange("POST", "/api/srtm/emisiones", mapOf("anio" to anio, "formato" to formato), token)
        assertEquals(HttpStatus.ACCEPTED, status, cuerpo)
        return tree(cuerpo)["id"].asString()
    }

    // polls the job until `listo` (by default, until it ends)
    protected fun esperar(
        id: String,
        token: String = this.token,
        listo: (JsonNode) -> Boolean = { it["estado"].asString() in setOf("TERMINADA", "FALLIDA") }
    ): JsonNode {
        val limite = System.nanoTime() + 90_000_000_000L
        while (true) {
            val job = tree(send("GET", "/api/srtm/emisiones/$id", null, HttpStatus.OK, token))
            if (listo(job)) return job
            assertTrue(System.nanoTime() < limite, "la emisión no avanza: $job")
            Thread.sleep(100)
        }
    }

    // polls until `listo`
    protected fun hastaQue(
        que: String,
        listo: () -> Boolean
    ) {
        val limite = System.nanoTime() + 90_000_000_000L
        while (!listo()) {
            assertTrue(System.nanoTime() < limite, que)
            Thread.sleep(100)
        }
    }

    protected class Respuesta(
        val status: HttpStatus,
        val tipo: MediaType?,
        val disposicion: String?,
        val longitud: Long,
        val cuerpo: ByteArray
    )

    protected fun descargar(
        id: String,
        token: String = this.token
    ): Respuesta = bajar("/api/srtm/emisiones/$id/archivo", token)

    // a pdf the endpoints emit one by one
    protected fun documento(path: String): ByteArray {
        val r = bajar(path)
        assertEquals(HttpStatus.OK, r.status, String(r.cuerpo))
        return r.cuerpo
    }

    protected fun bajar(
        path: String,
        token: String = this.token
    ): Respuesta {
        val result =
            client
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectBody(ByteArray::class.java)
                .returnResult()
        return Respuesta(
            HttpStatus.valueOf(result.status.value()),
            result.responseHeaders.contentType,
            result.responseHeaders.getFirst(HttpHeaders.CONTENT_DISPOSITION),
            result.responseHeaders.contentLength,
            result.responseBody ?: ByteArray(0)
        )
    }

    // every lote and emission still to run, of any class, is failed: a test's own groups of workers then take or
    // assemble only that test's emission
    protected fun soloLasDelTest() {
        runBlocking {
            for (t in tablas.de(EMISION_LOTE, listOf("estado"))) {
                db
                    .sql("UPDATE ${t.tabla} SET ${t.columna("estado")} = 'FALLIDO' WHERE ${t.columna("estado")} IN ('PENDIENTE', 'EN_PROCESO')")
                    .fetch()
                    .awaitRowsUpdated()
            }
            for (t in tablas.de(EMISION_MASIVA, listOf("estado"))) {
                db
                    .sql(
                        "UPDATE ${t.tabla} SET ${t.columna("estado")} = 'FALLIDA' WHERE ${t.columna("estado")} IN ('PENDIENTE', 'EN_PROCESO', 'ENSAMBLANDO')"
                    ).fetch()
                    .awaitRowsUpdated()
            }
        }
    }

    // a new organization with the model applied, as core provisions one: its admin's token
    protected fun otraOrganizacion(): String {
        val slug = uniqueName("org").lowercase()
        val email = "admin@$slug.test"
        val clave = "clave-de-la-otra-organizacion"
        post("/api/organizations", mapOf("name" to slug, "slug" to slug, "adminEmail" to email, "adminPassword" to clave))
        val suyo = bearer(email, clave)
        aplicarModelo(suyo)
        return suyo
    }

    // a lote as its row has it
    protected data class FilaLote(
        val id: UUID,
        val numero: Int,
        val estado: String,
        val tomadoPor: String?,
        val intentos: Int?
    )

    // the emission's lotes by numero, in whichever organization it is
    protected fun lotesDe(emision: String): List<FilaLote> =
        runBlocking {
            tablas.de(EMISION_LOTE, listOf("emision", "numero", "estado", "tomado_por", "intentos")).flatMap { t ->
                db
                    .sql(
                        "SELECT id, ${t.columna("numero")} AS numero, ${t.columna("estado")} AS estado, ${t.columna("tomado_por")} AS tomado_por, " +
                            "${t.columna("intentos")} AS intentos FROM ${t.tabla} WHERE ${t.columna("emision")} = :emision"
                    ).bind("emision", UUID.fromString(emision))
                    .map { row, _ ->
                        FilaLote(
                            row.get("id", UUID::class.java)!!,
                            row.get("numero", Long::class.javaObjectType)!!.toInt(),
                            row.get("estado", String::class.java)!!,
                            row.get("tomado_por", String::class.java),
                            row.get("intentos", Long::class.javaObjectType)?.toInt()
                        )
                    }.all()
                    .asFlow()
                    .toList()
            }
        }.sortedBy { it.numero }

    // a lote's fields straight on its table, as an instance leaves them (core's PUT replaces the whole record).
    // `latido` is how long ago it beat
    protected fun lote(
        id: UUID,
        estado: String,
        tomadoPor: String?,
        intentos: Int,
        latidoHace: String
    ) {
        runBlocking {
            for (t in tablas.de(EMISION_LOTE, listOf("estado", "tomado_por", "intentos", "latido"))) {
                db
                    .sql(
                        "UPDATE ${t.tabla} SET ${t.columna("estado")} = :estado, ${t.columna("tomado_por")} = :tomado, " +
                            "${t.columna("intentos")} = :intentos, ${t.columna("latido")} = now() - CAST(:hace AS interval) WHERE id = :id"
                    ).bind("estado", estado)
                    .bind("tomado", tomadoPor ?: "")
                    .bind("intentos", intentos)
                    .bind("hace", latidoHace)
                    .bind("id", id)
                    .fetch()
                    .awaitRowsUpdated()
            }
        }
    }
}
