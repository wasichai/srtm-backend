package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// the catalogs the forms search as the clerk types or cascade over: unidades urbanas and the partidas of the obras
// complementarias (enum options, ubigeo, vías and categorías de valor are RentasApiTest's, usos UsosPredioApiTest's).
// a tipo is one of the model's options: core refuses another one on its field
class CatalogosApiTest : SrtmApiTest() {
    @Test
    fun `unidades urbanas are suggested by name, tipo and ubigeo`() {
        val marca = uniqueDocumento()
        val nombre = "SOL DE LA ALAMEDA $marca"
        post(
            "/api/objects/unidad_urbana/records",
            mapOf("attributes" to mapOf("tipo_unidad_urbana" to "URBANIZACION", "nombre" to nombre, "ubigeo" to "120302"))
        )

        get("/api/srtm/unidades-urbanas?q=$marca&tipo=URBANIZACION&ubigeo=120302")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].nombre")
            .isEqualTo(nombre)
            .jsonPath("$.content[0].tipo_unidad_urbana")
            .isEqualTo("URBANIZACION")
        get("/api/srtm/unidades-urbanas?q=$marca&tipo=CERCADO")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(0)
        get("/api/srtm/unidades-urbanas?q=$marca&ubigeo=150101")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(0)
    }

    @Test
    fun `the partidas of the obras complementarias come in their order, all or those of one tipo de obra`() {
        val marca = uniqueDocumento()
        partida("PISCINAS", 2, "VASO $marca", "M3")
        partida("PISCINAS", 1, "ESCALERA $marca", "UND")
        partida("CISTERNAS", 1, "TAPA $marca", "UND")

        val piscinas = partidas("/api/srtm/obras-categorias?tipo_obra=PISCINAS")
        assertTrue(piscinas.all { it["tipo_obra"].asString() == "PISCINAS" })
        assertEquals(listOf("ESCALERA $marca", "VASO $marca"), piscinas.map { it["descripcion"].asString() }.filter { it.endsWith(marca) })
        assertEquals("M3", piscinas.single { it["descripcion"].asString() == "VASO $marca" }["unidad_medida"].asString())

        val todas = partidas("/api/srtm/obras-categorias")
        assertEquals(listOf("TAPA $marca", "ESCALERA $marca", "VASO $marca"), todas.map { it["descripcion"].asString() }.filter { it.endsWith(marca) })
        val orden = todas.map { it["tipo_obra"].asString() to it["numero"].asInt() }
        assertEquals(orden.sortedWith(compareBy({ it.first }, { it.second })), orden)
    }

    @Test
    fun `a tipo the catalog does not have is a 400 on its field`() {
        rejected("GET", "/api/srtm/unidades-urbanas?tipo=BARRIO", null, "tipo_unidad_urbana")
        rejected("GET", "/api/srtm/vias?tipo=AUTOPISTA", null, "tipo_via")
        rejected("GET", "/api/srtm/obras-categorias?tipo_obra=HORNOS", null, "tipo_obra")
    }

    // a partida as model/import_catalogos.py loads it
    private fun partida(
        tipoObra: String,
        numero: Int,
        descripcion: String,
        unidad: String
    ) = post(
        "/api/objects/obra_categoria/records",
        mapOf("attributes" to mapOf("tipo_obra" to tipoObra, "numero" to numero, "descripcion" to descripcion, "unidad_medida" to unidad))
    )

    private fun partidas(path: String): List<JsonNode> = tree(send("GET", path, null, HttpStatus.OK)).iterator().asSequence().toList()
}
