package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// the CUIS of the portal on the whole app and PostGIS: the versions in force on a day with their multas at that day's
// UIT, and a new version that closes the one in force under the code's lock. FICTITIOUS figures: the UIT of 2041 is
// Ficticios.UIT (4321.00), and no year 2042 has one
class SancionesCuisApiTest : ConSancionesApiTest() {
    @Test
    fun `the catalog gives each code in force on the day with its multa per grado at that day's UIT`() {
        val uit = uit(ANIO_CON_UIT)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "2026-01-01", porcentaje = 10, segunda = 15, tercera = null))

        val catalogo = catalogo("vigentes_a=$ANIO_CON_UIT-03-01&q=$codigo")
        assertEquals("$ANIO_CON_UIT-03-01", catalogo["vigentes_a"].asString())
        assertEquals(4321.00, catalogo["uit"]["valor"].asDouble())
        assertEquals(ANIO_CON_UIT, catalogo["uit"]["anio"].asInt())
        assertEquals(uit, catalogo["uit"]["parametro_id"].asString())
        assertEquals(0, catalogo["faltan"].size())
        val c = catalogo["codigos"].filas().single()
        assertEquals(codigo, c["codigo"].asString())
        // 4321.00 × 10 % and × 15 %, rounded once to the céntimo; no % for the third time: no multa, never a 0
        assertEquals(432.10, c["multa"].asDouble())
        assertEquals(648.15, c["multa_segunda"].asDouble())
        assertTrue(c["multa_tercera"].isNull, c.toString())
        assertEquals("ADMINISTRATIVA|$codigo", c["clave_vigente"].asString())

        // materia and q match a part, in any case; a version not yet in force is not listed
        assertEquals(1, catalogo("vigentes_a=$ANIO_CON_UIT-03-01&materia=comer&q=${codigo.lowercase()}")["codigos"].size())
        assertEquals(0, catalogo("vigentes_a=$ANIO_CON_UIT-03-01&materia=TRANSPORTE&q=$codigo")["codigos"].size())
        assertEquals(0, catalogo("vigentes_a=2025-12-31&q=$codigo")["codigos"].size())
        assertEquals(1, catalogo("vigentes_a=$ANIO_CON_UIT-03-01&q=licencia (ficticio)")["codigos"].filas().count { it["codigo"].asString() == codigo })
    }

    @Test
    fun `without a UIT for the day the multas are null and faltan names it`() {
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "2026-01-01"))

        val catalogo = catalogo("vigentes_a=$ANIO_SIN_UIT-05-01&q=$codigo")
        assertTrue(catalogo["uit"].isNull, catalogo.toString())
        assertEquals(listOf("UIT $ANIO_SIN_UIT"), catalogo["faltan"].filas().map { it.asString() })
        val c = catalogo["codigos"].filas().single()
        assertTrue(c["multa"].isNull && c["multa_segunda"].isNull && c["multa_tercera"].isNull, c.toString())
    }

    @Test
    fun `a filter the catalog does not know, or cannot read, is a 422 that names it`() {
        assertEquals("anio", tree(send("GET", "$CUIS?anio=2026", null, HttpStatus.UNPROCESSABLE_CONTENT))["errors"][0]["field"].asString())
        assertEquals("vigentes_a", tree(send("GET", "$CUIS?vigentes_a=01/03/2026", null, HttpStatus.UNPROCESSABLE_CONTENT))["errors"][0]["field"].asString())
    }

    @Test
    fun `a new version closes the one in force, which keeps its values and the acta that used it`() {
        val uit = uit(ANIO_CON_UIT)
        val codigo = nuevoCodigo()
        val primera = post(CUIS, version(codigo, "2026-01-01", porcentaje = 10))
        assertTrue(primera["cerrada"].isNull, primera.toString())
        assertEquals(codigo, primera["codigo"].asString())
        assertEquals("ADMINISTRATIVA|$codigo|2026-01-01", primera["clave"].asString())
        assertEquals("ADMINISTRATIVA", primera["familia"].asString())
        val v1 = primera["id"].asString()
        // an acta of march, explained with the version of its day
        val acta = acta(v1, uit, inscribir())

        val segunda = post(CUIS, version(codigo, "2026-07-01", porcentaje = 12, segunda = 18))
        assertEquals("2026-07-01", segunda["vigencia_desde"].asString())
        assertTrue(segunda["vigencia_hasta"].isNull, segunda.toString())
        assertEquals("ADMINISTRATIVA|$codigo", segunda["clave_vigente"].asString())
        val cerrada = segunda["cerrada"]
        assertEquals(v1, cerrada["id"].asString())
        assertEquals("2026-06-30", cerrada["vigencia_hasta"].asString())
        assertTrue(cerrada["clave_vigente"].isNull, cerrada.toString())

        // the closed version keeps every figure; the acta still names it
        val guardada = registro(CODIGO_INFRACCION, v1)
        assertEquals(10.0, guardada["porcentaje_uit"].asDouble())
        assertEquals(15.0, guardada["porcentaje_uit_segunda"].asDouble())
        assertEquals("2026-06-30", guardada["vigencia_hasta"].asString())
        assertEquals(v1, registro(PAPELETA, acta)["codigo_infraccion"].asString())

        // each day, its version
        assertEquals(10.0, catalogo("vigentes_a=2026-06-30&q=$codigo")["codigos"].filas().single()["porcentaje_uit"].asDouble())
        assertEquals(12.0, catalogo("vigentes_a=2026-07-01&q=$codigo")["codigos"].filas().single()["porcentaje_uit"].asDouble())
    }

    @Test
    fun `a version already there is a 409, one not after the one in force a 422, a bad field a 400`() {
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "2026-01-01"))
        post(CUIS, version(codigo, "2026-07-01"))

        // the same version again, with the code as typed
        val repetida = tree(send("POST", CUIS, version(" ${codigo.lowercase()} ", "2026-07-01"), HttpStatus.CONFLICT))
        assertTrue(repetida["detail"].asString().contains("ya existe"), repetida.toString())
        // before the one in force, or overlapping a closed one
        for (desde in listOf("2026-06-01", "2025-12-01")) {
            val problema = tree(send("POST", CUIS, version(codigo, desde), HttpStatus.UNPROCESSABLE_CONTENT))
            assertEquals("vigencia_desde", problema["errors"][0]["field"].asString(), problema.toString())
        }
        rejected("POST", CUIS, version(codigo, "2027-01-01") + ("observacion" to "no"), "observacion")
        rejected("POST", CUIS, version(codigo, "2027-01-01", porcentaje = 10001), "porcentaje_uit")
        rejected("POST", CUIS, version(codigo, "2027-01-01") + ("familia" to "TRANSITO"), "familia")
        // nothing of those was written
        assertEquals(2, versiones(codigo).size)
    }

    @Test
    fun `two new versions of one code at once never overlap`() {
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "2026-01-01"))
        val clerks = Executors.newFixedThreadPool(2)
        try {
            // the same version twice: one is written, the other finds it (409)
            val mismas = enParalelo(clerks, version(codigo, "2026-04-01"), version(codigo, "2026-04-01", porcentaje = 11))
            assertEquals(listOf(HttpStatus.CREATED, HttpStatus.CONFLICT), mismas.sorted(), mismas.toString())
            // two different ones: each closes the one in force when its turn comes, or (when the later one went first)
            // does not start after it (422)
            val distintas = enParalelo(clerks, version(codigo, "2026-08-01"), version(codigo, "2026-09-01"))
            assertTrue(
                HttpStatus.CREATED in distintas && distintas.all { it == HttpStatus.CREATED || it == HttpStatus.UNPROCESSABLE_CONTENT },
                distintas.toString()
            )
        } finally {
            clerks.shutdown()
        }
        val todas = versiones(codigo).sortedBy { it["vigencia_desde"].asString() }
        assertEquals(1, todas.count { !it["clave_vigente"].isNull }, todas.toString())
        assertTrue(todas.last()["vigencia_hasta"].isNull, todas.toString())
        todas.zipWithNext().forEach { (antes, despues) ->
            val desde = LocalDate.parse(despues["vigencia_desde"].asString())
            assertEquals(desde.minusDays(1).toString(), antes["vigencia_hasta"].asString(), todas.toString())
        }
    }

    @Test
    fun `the body model-import_cuis-py sends is accepted`() {
        // read_cuis: every cell a string, an empty one left out, familia ADMINISTRATIVA, the code upper-cased
        val codigo = nuevoCodigo()
        val fila =
            mapOf(
                "familia" to "ADMINISTRATIVA",
                "codigo" to codigo,
                "descripcion" to "No exhibir la licencia (ficticio)",
                "porcentaje_uit" to "10",
                "porcentaje_uit_segunda" to "12.5",
                "base_legal" to "Ordenanza ficticia 001",
                "vigencia_desde" to "2026-01-01",
                "observacion" to "Carga de prueba del CUIS"
            )
        val creada = post(CUIS, fila)
        assertEquals(12.5, creada["porcentaje_uit_segunda"].asDouble())
        assertTrue(creada["materia"].isNull && creada["porcentaje_uit_tercera"].isNull, creada.toString())
    }

    @Test
    fun `only srtm writes the CUIS, and who may only read gets a 403 that names the object`() {
        val codigo = nuevoCodigo()
        val id = post(CUIS, version(codigo, "2026-01-01"))["id"].asString()
        val registro = "/api/objects/$CODIGO_INFRACCION/records/$id"
        val atributos = fields(tree(send("GET", registro, null, HttpStatus.OK))["attributes"])
        send("PUT", registro, mapOf("attributes" to atributos + ("porcentaje_uit" to 12)), HttpStatus.CONFLICT)
        send("DELETE", registro, null, HttpStatus.CONFLICT)
        send("POST", "/api/objects/$CODIGO_INFRACCION/records", mapOf("attributes" to Ejemplos.codigo(nuevoCodigo())), HttpStatus.FORBIDDEN)

        val lector = funcionario(listOf(permiso(null, "READ")))
        assertEquals(1, tree(send("GET", "$CUIS?q=$codigo", null, HttpStatus.OK, lector))["codigos"].size())
        val problema = tree(send("POST", CUIS, version(codigo, "2027-01-01"), HttpStatus.FORBIDDEN, lector))
        assertTrue(problema["detail"].asString().contains(CODIGO_INFRACCION), problema.toString())
        assertEquals(1, versiones(codigo).size)
    }

    @Test
    fun `a derogation ends the version in force with no new one, and the catalog stops listing it`() {
        val codigo = nuevoCodigo()
        val id = post(CUIS, version(codigo, "2026-01-01"))["id"].asString()

        val derogada = tree(send("POST", DEROGACION, mapOf("codigo" to " ${codigo.lowercase()} ", "vigencia_hasta" to "2026-05-06"), HttpStatus.OK))
        assertEquals(id, derogada["id"].asString())
        assertEquals("2026-05-06", derogada["vigencia_hasta"].asString())
        assertTrue(derogada["clave_vigente"].isNull, derogada.toString())
        assertEquals(10.0, registro(CODIGO_INFRACCION, id)["porcentaje_uit"].asDouble(), "it keeps its figures")
        assertEquals(1, catalogo("vigentes_a=2026-05-06&q=$codigo")["codigos"].size(), "its last day")
        assertEquals(0, catalogo("vigentes_a=2026-05-07&q=$codigo")["codigos"].size(), "the day after")

        // once only; a code the CUIS does not have is a 404; a day before it started a 422; a missing field a 400
        send("POST", DEROGACION, mapOf("codigo" to codigo, "vigencia_hasta" to "2026-06-01"), HttpStatus.CONFLICT)
        send("POST", DEROGACION, mapOf("codigo" to nuevoCodigo(), "vigencia_hasta" to "2026-06-01"), HttpStatus.NOT_FOUND)
        val otro = nuevoCodigo()
        post(CUIS, version(otro, "2026-01-01"))
        val antes = tree(send("POST", DEROGACION, mapOf("codigo" to otro, "vigencia_hasta" to "2025-12-31"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals("vigencia_hasta", antes["errors"][0]["field"].asString(), antes.toString())
        rejected("POST", DEROGACION, mapOf("codigo" to otro), "vigencia_hasta")
        // a new version after the derogation is the code's next one
        val despues = post(CUIS, version(codigo, "2026-08-01"))
        assertTrue(despues["cerrada"].isNull, despues.toString())
    }

    @Test
    fun `a derogation asks the permission to create the CUIS`() {
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "2026-01-01"))
        val lector = funcionario(listOf(permiso(null, "READ")))
        val problema = tree(send("POST", DEROGACION, mapOf("codigo" to codigo, "vigencia_hasta" to "2026-05-06"), HttpStatus.FORBIDDEN, lector))
        assertTrue(problema["detail"].asString().contains(CODIGO_INFRACCION), problema.toString())
        assertTrue(versiones(codigo).single()["vigencia_hasta"].isNull)
    }

    @Test
    fun `a version as long as the CUIEMA of Perené is written`() {
        val codigo = nuevoCodigo()
        val larga =
            version(codigo, "2026-01-01") +
                mapOf("descripcion" to "d".repeat(1000), "medida_complementaria" to "m".repeat(500), "base_legal" to "b".repeat(2000))
        assertEquals(2000, post(CUIS, larga)["base_legal"].asString().length)
        rejected("POST", CUIS, version(nuevoCodigo(), "2026-01-01") + ("base_legal" to "b".repeat(2001)), "base_legal")
    }

    private fun catalogo(query: String): JsonNode = tree(send("GET", "$CUIS?$query", null, HttpStatus.OK))

    private fun versiones(codigo: String): List<JsonNode> =
        tree(send("GET", "/api/objects/$CODIGO_INFRACCION/records?codigo=$codigo&size=50", null, HttpStatus.OK))["content"].filas().map { it["attributes"] }

    private fun enParalelo(
        clerks: ExecutorService,
        vararg cuerpos: Map<String, Any?>
    ): List<HttpStatus> =
        cuerpos
            .map { cuerpo -> CompletableFuture.supplyAsync({ exchange("POST", CUIS, cuerpo).first }, clerks) }
            .map { it.join() }

    private companion object {
        const val CUIS = "/api/srtm/infracciones/cuis"
        const val DEROGACION = "$CUIS/derogacion"
        const val ANIO_CON_UIT = 2041
        const val ANIO_SIN_UIT = 2042
    }
}
