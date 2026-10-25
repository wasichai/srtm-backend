package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.JsonNode
import java.io.File
import java.math.BigDecimal

// #188: a declaración names the tramo de vía its predio fronts (id_aranc), and its arancel is the valor_m2 of that
// tramo's VIGENTE arancel in the DJ's year (Normativa). none VIGENTE: a 400 on id_aranc when the user names it; a
// recalculation or one annulled without a replacement keep the number stored; a copy to another year takes that
// year's, or none (#226). without id_aranc, the arancel stays as it is (an external padrón's). FICTITIOUS figures
class ArancelDeLaDeclaracionApiTest : SrtmApiTest() {
    @Test
    fun `a DJ with the id_aranc of a VIGENTE arancel takes its valor_m2 - one that names none is a 400 on id_aranc`() {
        val t = tramo()
        importarAranceles("$t,2026,25.40,ANA,BETO")
        val id = declarar("id_aranc" to t)["id"].asString()
        assertEquals(t, declaracion(id)["id_aranc"].asString())
        assertArancel("25.40", id)

        val problema = tree(send("POST", "/api/srtm/declaraciones", cuerpo("id_aranc" to "$t-NO"), HttpStatus.BAD_REQUEST))
        assertEquals("id_aranc", problema["errors"][0]["field"].asString(), problema.toString())
        assertEquals("no hay un arancel VIGENTE $t-NO para 2026", problema["errors"][0]["message"].asString())
        // nor one of another year
        importarAranceles("$t-27,2027,1,ANA,BETO")
        rejected("POST", "/api/srtm/declaraciones", cuerpo("id_aranc" to "$t-27"), "id_aranc")
        // an edit that names another tramo is checked too, one that keeps it is not
        val d = declaracion(id)
        rejected("PUT", "/api/srtm/declaraciones/$id", fields(d) + ("id_aranc" to "$t-NO"), "id_aranc")
        put("/api/srtm/declaraciones/$id", fields(d) + ("uso" to "COMERCIO"))
        assertArancel("25.40", id)
    }

    @Test
    fun `annulled without a replacement the DJ keeps its arancel - verified later, Recalcular takes the new one`() {
        val t = tramo()
        importarAranceles("$t,2026,10,ANA,BETO")
        val id = declarar("id_aranc" to t)["id"].asString()
        assertArancel("10", id)
        val vigente = aranceles(t).single()["id"].asString()
        send("POST", "$ARANCELES/$vigente/anular", mapOf("motivo" to "La cifra se copió mal"), HttpStatus.OK)
        importarAranceles("$t,2026,12,ANA,")
        recalcular(id)
        assertArancel("10", id)

        val nuevo = aranceles(t).single { it["estado"].asString() == "POR_VERIFICAR" }["id"].asString()
        val verificado = tree(send("POST", "$ARANCELES/verificar", mapOf("ids" to listOf(nuevo)), HttpStatus.OK))
        assertEquals(1, verificado["verificados"].size(), verificado.toString())
        recalcular(id)
        assertArancel("12", id)
    }

    @Test
    fun `without id_aranc the arancel is the users - an imported one stays as it is`() {
        val id = declarar()["id"].asString()
        val registro = "/api/objects/$DECLARACION/records/$id"
        val atributos = fields(tree(send("GET", registro, null, HttpStatus.OK))["attributes"])
        send("PUT", registro, mapOf("attributes" to atributos + ("arancel" to BigDecimal("35.50"))), HttpStatus.OK)
        val d = declaracion(id)
        put("/api/srtm/declaraciones/$id", fields(d) + ("uso" to "COMERCIO"))
        recalcular(id)
        assertArancel("35.50", id)
    }

    @Test
    fun `an external padrons arancel that id_aranc replaces marks the valuation as modified`() {
        val t = tramo()
        importarAranceles("$t,2026,22,ANA,BETO")
        val id = declarar()["id"].asString()
        val registro = "/api/objects/$DECLARACION/records/$id"
        val atributos = fields(tree(send("GET", registro, null, HttpStatus.OK))["attributes"])
        // the padrón's only value is its arancel
        send("PUT", registro, mapOf("attributes" to atributos + ("arancel" to BigDecimal("35.50"))), HttpStatus.OK)
        put("/api/srtm/declaraciones/$id", fields(declaracion(id)) + ("id_aranc" to t))
        assertArancel("22", id)
        val origen = tree(send("GET", "/api/srtm/declaraciones/$id/valuacion-origen", null, HttpStatus.OK))
        assertEquals(true, origen["modificada"].asBoolean(), origen.toString())
    }

    @Test
    fun `the rectificatoria and the copy to another year take id_aranc - the copy has no arancel without one that year`() {
        val t = tramo()
        importarAranceles("$t,2026,30,ANA,BETO")
        val contribuyente = inscribir()
        val predio = predio()
        val id = declarar("contribuyente" to contribuyente, "predio" to predio, "id_aranc" to t)["id"].asString()

        val rectificatoria = tree(send("POST", "/api/srtm/declaraciones/$id/rectificar", null, HttpStatus.CREATED))["declaracion"]
        val r = rectificatoria["id"].asString()
        assertEquals(t, rectificatoria["id_aranc"].asString())
        assertArancel("30", r)

        val copia =
            post(
                "/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas",
                mapOf("predio_id" to predio, "desde" to r, "declaracion" to mapOf("anio" to 2027))
            )["declaracion"]
        assertEquals(t, copia["id_aranc"].asString())
        // #226: a DJ of 2027 never carries 2026's arancel: none VIGENTE in 2027, none
        assertTrue(registro(copia["id"].asString())["arancel"].isNull, copia.toString())
        // with that year's arancel, Recalcular takes it
        importarAranceles("$t,2027,33,ANA,BETO")
        recalcular(copia["id"].asString())
        assertArancel("33", copia["id"].asString())
    }

    @Test
    fun `a condomino declares the same tramo de via - id_aranc is the predio's`() {
        val t = tramo()
        importarAranceles("$t,2026,20,ANA,BETO\n$t-B,2026,21,ANA,BETO")
        val id = declarar("id_aranc" to t)["id"].asString()
        val condomino = post("/api/srtm/declaraciones/$id/condominos", mapOf("contribuyente" to inscribir(), "porcentaje_condominio" to 25))
        val otro = condomino["id"].asString()
        assertEquals(t, declaracion(otro)["id_aranc"].asString())
        assertArancel("20", otro)
        // an edit of one reaches the other
        put("/api/srtm/declaraciones/$id", fields(declaracion(id)) + ("id_aranc" to "$t-B"))
        assertEquals("$t-B", declaracion(otro)["id_aranc"].asString())
        assertArancel("21", otro)
    }

    // #195: every read of the DJ carries the arancel stored, read-only
    @Test
    fun `the DJ reads its arancel - with id_aranc the VIGENTE valor_m2, everywhere the DJ is answered`() {
        val t = tramo()
        importarAranceles("$t,2026,25.40,ANA,BETO")
        val contribuyente = inscribir()
        val predio = predio()
        val creada = declarar("contribuyente" to contribuyente, "predio" to predio, "id_aranc" to t)
        val id = creada["id"].asString()
        assertLeido("25.40", creada)
        assertLeido("25.40", declaracion(id))
        assertLeido("25.40", tree(recalcular(id)))
        assertLeido("25.40", put("/api/srtm/declaraciones/$id", fields(declaracion(id)) + ("uso" to "COMERCIO")))
        for (lista in listOf("/api/srtm/contribuyentes/$contribuyente/declaraciones", "/api/srtm/predios/$predio/declaraciones")) {
            val filas = tree(send("GET", "$lista?anio=2026", null, HttpStatus.OK))
            assertLeido("25.40", filas.single { it["declaracion"]["id"].asString() == id }["declaracion"])
        }
        // a new VIGENTE one: the response of Recalcular already has it
        val vigente = aranceles(t).single()["id"].asString()
        send("POST", "$ARANCELES/$vigente/anular", mapOf("motivo" to "La cifra se copió mal"), HttpStatus.OK)
        importarAranceles("$t,2026,26,ANA,BETO")
        assertLeido("26", tree(recalcular(id)))
    }

    @Test
    fun `without id_aranc the DJ reads the padrons arancel - null without one`() {
        val id = declarar()["id"].asString()
        assertTrue(declaracion(id)["arancel"].isNull, declaracion(id).toString())
        padron(id, BigDecimal("35.50"))
        assertLeido("35.50", declaracion(id))
    }

    @Test
    fun `a DJ read and sent back keeps its arancel - with id_aranc or the padrons, with arancel or without it`() {
        val t = tramo()
        importarAranceles("$t,2026,25.40,ANA,BETO")
        val conTramo = declarar("id_aranc" to t)["id"].asString()
        val delPadron = declarar()["id"].asString()
        padron(delPadron, BigDecimal("35.50"))
        for ((id, esperado) in listOf(conTramo to "25.40", delPadron to "35.50")) {
            val d = fields(declaracion(id))
            assertLeido(esperado, put("/api/srtm/declaraciones/$id", d))
            assertArancel(esperado, id)
            // a PUT without arancel does not clear it
            assertLeido(esperado, put("/api/srtm/declaraciones/$id", d - "arancel"))
            assertArancel(esperado, id)
            // nor does a null one
            assertLeido(esperado, put("/api/srtm/declaraciones/$id", d + ("arancel" to null)))
            assertArancel(esperado, id)
        }
    }

    @Test
    fun `an arancel the client sends is ignored - with id_aranc and without it, as before`() {
        val t = tramo()
        importarAranceles("$t,2026,25.40,ANA,BETO")
        val creada = declarar("id_aranc" to t, "arancel" to 99)
        val id = creada["id"].asString()
        assertLeido("25.40", creada)
        assertLeido("25.40", put("/api/srtm/declaraciones/$id", fields(declaracion(id)) + ("arancel" to 99)))
        assertArancel("25.40", id)

        val sinTramo = declarar("arancel" to 99)
        assertTrue(sinTramo["arancel"].isNull, sinTramo.toString())
        val otro = sinTramo["id"].asString()
        padron(otro, BigDecimal("35.50"))
        assertLeido("35.50", put("/api/srtm/declaraciones/$otro", fields(declaracion(otro)) + ("arancel" to 99)))
        assertArancel("35.50", otro)

        val contribuyente = inscribir()
        val presentada =
            post(
                "/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas",
                mapOf("predio_id" to predio(), "declaracion" to mapOf("anio" to 2026, "uso" to "CASA HABITACIÓN", "id_aranc" to t, "arancel" to 99))
            )["declaracion"]
        assertLeido("25.40", presentada)
    }

    // the demo's REGISTRADOR (docs/manuales/demo/perfiles.json, read as it is) reads the aranceles its DJ names
    @Test
    fun `the registrador of the demo profiles names a tramo de via`() {
        val t = tramo()
        importarAranceles("$t,2026,18,ANA,BETO")
        val perfil = tree(File("docs/manuales/demo/perfiles.json").readText())["perfiles"].single { it["rol"].asString() == "REGISTRADOR" }
        val permisos = perfil["permisos"].properties().flatMap { (objeto, acciones) -> acciones.iterator().asSequence().map { permiso(objeto, it.asString()) } }
        val registrador = funcionario(permisos)
        send("GET", "$ARANCELES?q=$t", null, HttpStatus.OK, registrador)
        val id = tree(send("POST", "/api/srtm/declaraciones", cuerpo("id_aranc" to t), HttpStatus.CREATED, registrador))["id"].asString()
        assertArancel("18", id)
        send("POST", ARANCELES, mapOf("id_aranc" to t, "anio" to 2027, "valor_m2" to 1), HttpStatus.FORBIDDEN, registrador)
    }

    private fun tramo(): String = "DJ${uniqueDocumento()}"

    // id_aranc,anio,valor_m2,transcribio,verifico lines, written
    private fun importarAranceles(lineas: String) {
        val result =
            client
                .post()
                .uri("$ARANCELES/importar?dry_run=false")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType("text", "csv"))
                .bodyValue("id_aranc,anio,valor_m2,transcribio,verifico\n$lineas")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(200, result.status.value(), result.responseBody)
        assertEquals(0, tree(result.responseBody!!)["rechazos"].size(), result.responseBody)
    }

    private fun aranceles(t: String): List<JsonNode> =
        tree(send("GET", "$ARANCELES?q=$t", null, HttpStatus.OK))["content"]
            .iterator()
            .asSequence()
            .filter { it["id_aranc"].asString() == t }
            .toList()

    private fun cuerpo(vararg extra: Pair<String, Any?>) =
        mapOf("contribuyente" to inscribir(), "predio" to predio(), "anio" to 2026, "secuencia_uso" to "1", "uso" to "CASA HABITACIÓN") + extra

    private fun declarar(vararg extra: Pair<String, Any?>): JsonNode = post("/api/srtm/declaraciones", cuerpo(*extra))

    private fun declaracion(id: String): JsonNode = tree(send("GET", "/api/srtm/declaraciones/$id", null, HttpStatus.OK))["declaracion"]

    private fun registro(id: String): JsonNode = tree(send("GET", "/api/objects/$DECLARACION/records/$id", null, HttpStatus.OK))["attributes"]

    private fun recalcular(id: String) = send("POST", "/api/srtm/declaraciones/$id/recalcular", null, HttpStatus.OK)

    // an external padrón's arancel, written by core's api as an import would
    private fun padron(
        id: String,
        arancel: BigDecimal
    ) {
        val registro = "/api/objects/$DECLARACION/records/$id"
        val atributos = fields(tree(send("GET", registro, null, HttpStatus.OK))["attributes"])
        send("PUT", registro, mapOf("attributes" to atributos + ("arancel" to arancel)), HttpStatus.OK)
    }

    // the arancel a read of the DJ answers (#195)
    private fun assertLeido(
        esperado: String,
        declaracion: JsonNode
    ) {
        val arancel = declaracion["arancel"]
        assertTrue(arancel != null && !arancel.isNull, "sin arancel en $declaracion")
        assertEquals(0, BigDecimal(esperado).compareTo(arancel.decimalValue()), "esperado $esperado, leído $arancel")
    }

    // the arancel stored, read from core
    private fun assertArancel(
        esperado: String,
        id: String
    ) {
        val arancel = tree(send("GET", "/api/objects/$DECLARACION/records/$id", null, HttpStatus.OK))["attributes"]["arancel"]
        assertTrue(arancel != null && !arancel.isNull, "sin arancel en $id")
        assertEquals(0, BigDecimal(esperado).compareTo(arancel.decimalValue()), "esperado $esperado, guardado $arancel")
    }

    private companion object {
        const val ARANCELES = "/api/srtm/normativa/aranceles"
    }
}
