package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// the code of a predio registered in the portal without sector or manzana catastral (the srtm asks for neither,
// page 14), and two clerks registering in the same manzana at once
class CodigoPredioApiTest : SrtmApiTest() {
    @Test
    fun `a declaracion jurada on a new predio without sector or manzana gives it a code of the portal's own series`() {
        val contribuyente = inscribir()
        val predio =
            mapOf(
                "tipo_predio" to "PREDIO URBANO",
                "tipo_via" to "AVENIDA",
                "via" to "MARGINAL",
                "departamento" to "JUNIN",
                "provincia" to "CHANCHAMAYO",
                "distrito" to "PERENE"
            )
        val first = post("/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas", mapOf("predio" to predio))
        val second = post("/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas", mapOf("predio" to predio))
        val codigo = first["predio"]["codigo"].asString()
        assertTrue(Regex("P-\\d{6}").matches(codigo), codigo)
        assertEquals(siguienteCodigoPropio(codigo), second["predio"]["codigo"].asString())
        assertTrue(first["declaracion"]["numero_declaracion"].asInt() > 0)
    }

    @Test
    fun `a new predio on a lote of the catastro takes the lote's municipal code, while no predio has it`() {
        val cpu = "CPU-${uniqueDocumento()}"
        val municipal = "M-${uniqueDocumento()}"
        post("/api/srtm/catastro", mapOf("codigo_cpu" to cpu, "codigo_predio_municipal" to municipal, "tipo_predio" to "PREDIO URBANO"))
        val first = post("/api/srtm/predios", mapOf("codigo_cpu" to cpu, "direccion" to "S/N"))
        assertEquals(municipal, first["codigo"].asString())
        // a second predio on that lote: the code is taken
        val second = post("/api/srtm/predios", mapOf("codigo_cpu" to cpu, "direccion" to "S/N"))
        assertTrue(second["codigo"].asString().startsWith(PREFIJO_PROPIO), second["codigo"].asString())
    }

    @Test
    fun `a code the client sends that another predio has is a 400 on codigo`() {
        val codigo = "T-${uniqueDocumento()}"
        post("/api/srtm/predios", mapOf("codigo" to codigo, "direccion" to "S/N"))
        val (status, body) =
            exchange(
                "POST",
                "/api/srtm/contribuyentes/${inscribir()}/declaraciones-juradas",
                mapOf("predio" to mapOf("codigo" to codigo, "direccion" to "S/N"))
            )
        assertEquals(HttpStatus.BAD_REQUEST, status, body)
        assertEquals("codigo", tree(body)["errors"][0]["field"].asString())
    }

    @Test
    fun `two clerks registering predios in the same manzana at once both get a code`() {
        val sector = uniqueDocumento().take(4)
        val manzana = uniqueDocumento().take(2)
        val barrier = CyclicBarrier(2)
        val clerks = Executors.newFixedThreadPool(2)
        val results =
            try {
                List(2) {
                    CompletableFuture.supplyAsync({
                        barrier.await(30, TimeUnit.SECONDS)
                        exchange("POST", "/api/srtm/predios", mapOf("sector_catastral" to sector, "manzana_catastral" to manzana, "direccion" to "S/N"))
                    }, clerks)
                }.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                clerks.shutdownNow()
            }
        assertEquals(listOf(HttpStatus.CREATED, HttpStatus.CREATED), results.map { it.first }, results.toString())
        assertEquals(setOf("$sector-$manzana-0001", "$sector-$manzana-0002"), results.map { tree(it.second)["codigo"].asString() }.toSet())
    }
}
