package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.JsonNode
import java.io.File
import java.math.BigDecimal

// #226: a DJ without id_aranc takes the arancel of its predio's vía (predio.codigo_via, Ica's SAT code, against the
// arancel's cod_via): the VIGENTE one of the DJ's year, never another year's. it stores that arancel's id_aranc and is
// a DJ that names its tramo from then on. a rústico predio never takes it; an explicit id_aranc wins; a vía without
// arancel, or whose tramos differ, leaves the arancel stored (the padrón's). FICTITIOUS figures and codes
class ArancelDeLaViaApiTest : SrtmApiTest() {
    @Test
    fun `an urbano DJ without id_aranc takes the arancel of its via - and stores its id_aranc`() {
        val via = via()
        importarAranceles("$via,2026,41.50,$via,ANA,BETO")
        val id = declarar(predioEnLaVia(via))["id"].asString()
        assertArancel("41.50", id)
        assertEquals(via, registro(id)["id_aranc"].asString())
        // from then on it is a DJ that names its tramo: an edit keeps it
        put("/api/srtm/declaraciones/$id", fields(declaracion(id)) + ("uso" to "COMERCIO"))
        assertEquals(via, registro(id)["id_aranc"].asString())
        assertArancel("41.50", id)
    }

    @Test
    fun `a DJ of the padron takes its via once the arancel is VIGENTE - Recalcular, as asignar_arancel_por_via does`() {
        val via = via()
        val id = declarar(predioEnLaVia(via))["id"].asString()
        padron(id, BigDecimal("41.50"))
        // POR_VERIFICAR: not yet
        importarAranceles("$via,2026,41.50,$via,ANA,")
        recalcular(id)
        assertTrue(registro(id)["id_aranc"].isNull, registro(id).toString())
        assertArancel("41.50", id)

        val pendiente = aranceles(via).single()["id"].asString()
        // ana transcribed it: the admin is the second signature
        send("POST", "$ARANCELES/verificar", mapOf("ids" to listOf(pendiente)), HttpStatus.OK)
        recalcular(id)
        assertEquals(via, registro(id)["id_aranc"].asString())
        assertArancel("41.50", id)
        // the same value as the padrón's: the valuation imported still describes it
        assertTrue(registro(id)["origen_modificado"].let { it == null || it.isNull || !it.asBoolean() }, registro(id).toString())
    }

    @Test
    fun `a padrons arancel the via replaces with another value marks the valuation as modified`() {
        val via = via()
        val id = declarar(predioEnLaVia(via))["id"].asString()
        padron(id, BigDecimal("35.50"))
        importarAranceles("$via,2026,41.50,$via,ANA,BETO")
        recalcular(id)
        assertArancel("41.50", id)
        assertEquals(true, registro(id)["origen_modificado"].asBoolean(), registro(id).toString())
    }

    @Test
    fun `a rustico predio never takes the arancel of its via`() {
        val via = via()
        importarAranceles("$via,2026,41.50,$via,ANA,BETO")
        val id = declarar(predioEnLaVia(via, "PREDIO RUSTICO"))["id"].asString()
        padron(id, BigDecimal("3.20"))
        recalcular(id)
        assertTrue(registro(id)["id_aranc"].isNull, registro(id).toString())
        assertArancel("3.20", id)
    }

    @Test
    fun `an explicit id_aranc wins over the via`() {
        val via = via()
        importarAranceles("$via,2026,41.50,$via,ANA,BETO\n$via-X,2026,12,,ANA,BETO")
        val id = declarar(predioEnLaVia(via), "id_aranc" to "$via-X")["id"].asString()
        assertEquals("$via-X", registro(id)["id_aranc"].asString())
        assertArancel("12", id)
    }

    @Test
    fun `a via without arancel leaves the padrons value - and so does one whose tramos differ`() {
        val sinArancel = declarar(predioEnLaVia(via()))["id"].asString()
        padron(sinArancel, BigDecimal("35.50"))
        recalcular(sinArancel)
        assertTrue(registro(sinArancel)["id_aranc"].isNull, registro(sinArancel).toString())
        assertArancel("35.50", sinArancel)

        // two tramos (cuadras) of one vía with one value: it takes the first; with two values, none
        val pareja = via()
        importarAranceles("$pareja-A,2026,20,$pareja,ANA,BETO\n$pareja-B,2026,20.00,$pareja,ANA,BETO")
        val iguales = declarar(predioEnLaVia(pareja))["id"].asString()
        assertEquals("$pareja-A", registro(iguales)["id_aranc"].asString())
        assertArancel("20", iguales)

        val distintas = via()
        importarAranceles("$distintas-A,2026,20,$distintas,ANA,BETO\n$distintas-B,2026,21,$distintas,ANA,BETO")
        val id = declarar(predioEnLaVia(distintas))["id"].asString()
        padron(id, BigDecimal("35.50"))
        recalcular(id)
        assertTrue(registro(id)["id_aranc"].isNull, registro(id).toString())
        assertArancel("35.50", id)
    }

    @Test
    fun `each DJ takes the arancel of its own year - never the year before`() {
        val via = via()
        importarAranceles("$via,2026,41.50,$via,ANA,BETO\n$via,2027,44,$via,ANA,BETO")
        val predio = predioEnLaVia(via)
        val de2026 = declarar(predio)["id"].asString()
        val de2027 = declarar(predio, "anio" to 2027)["id"].asString()
        assertArancel("41.50", de2026)
        assertArancel("44", de2027)

        // 2028 has no arancel for the vía: the padrón's value stays, not 2027's
        val de2028 = declarar(predio, "anio" to 2028)["id"].asString()
        padron(de2028, BigDecimal("35.50"))
        recalcular(de2028)
        assertTrue(registro(de2028)["id_aranc"].isNull, registro(de2028).toString())
        assertArancel("35.50", de2028)
    }

    @Test
    fun `a predio marked varios_codigos_via never takes its via - the regla para varios codigos`() {
        val via = via()
        importarAranceles("$via,2026,41.50,$via,ANA,BETO")
        val predio = predioEnLaVia(via)
        marcar(predio)
        val id = declarar(predio)["id"].asString()
        padron(id, BigDecimal("35.50"))
        recalcular(id)
        assertTrue(registro(id)["id_aranc"].isNull, registro(id).toString())
        assertArancel("35.50", id)
    }

    @Test
    fun `the condominio takes the tramo in one pass - an edit of one condomino does not mark the others valuation`() {
        val via = via()
        val a = declarar(predioEnLaVia(via))["id"].asString()
        val b = post("/api/srtm/declaraciones/$a/condominos", mapOf("contribuyente" to inscribir(), "porcentaje_condominio" to 25))["id"].asString()
        // both from the padrón: its arancel and a value of its valuation
        for (id in listOf(a, b)) padron(id, BigDecimal("41.50"), "valor_terreno" to BigDecimal("1000"))
        importarAranceles("$via,2026,41.50,$via,ANA,BETO")
        recalcular(a)
        for (id in listOf(a, b)) {
            assertEquals(via, registro(id)["id_aranc"].asString(), registro(id).toString())
            assertArancel("41.50", id)
            assertFalse(modificada(id), registro(id).toString())
        }
        // b saved with a body read before (no id_aranc), nothing of the predio changed: b takes the tramo again, and a
        // is not written (a replicated id_aranc would mark its valuation)
        put("/api/srtm/declaraciones/$b", fields(declaracion(b)) + ("id_aranc" to null))
        assertEquals(via, registro(b)["id_aranc"].asString())
        assertEquals(via, registro(a)["id_aranc"].asString())
        assertFalse(modificada(a), registro(a).toString())
    }

    @Test
    fun `a condomino that names a tramo gives it to the condominio - not the via`() {
        val via = via()
        importarAranceles("$via,2026,41.50,$via,ANA,BETO\n$via-Y,2026,12,,ANA,BETO")
        val a = declarar(predioEnLaVia(via))["id"].asString()
        // a took the vía when it was declared: start again from the padrón, and b names its own tramo
        val b = post("/api/srtm/declaraciones/$a/condominos", mapOf("contribuyente" to inscribir(), "porcentaje_condominio" to 25))["id"].asString()
        padron(a, BigDecimal("35.50"), "id_aranc" to null)
        padron(b, BigDecimal("35.50"), "id_aranc" to "$via-Y")
        recalcular(a)
        assertEquals("$via-Y", registro(a)["id_aranc"].asString(), registro(a).toString())
        assertArancel("12", a)
        // b keeps its tramo; its arancel is its own recálculo's, as any DJ that names one
        assertEquals("$via-Y", registro(b)["id_aranc"].asString(), registro(b).toString())
        recalcular(b)
        assertArancel("12", b)
        // an edit of a keeps b's tramo
        put("/api/srtm/declaraciones/$a", fields(declaracion(a)) + ("id_aranc" to null))
        assertEquals("$via-Y", registro(b)["id_aranc"].asString())
        assertEquals("$via-Y", registro(a)["id_aranc"].asString())
    }

    @Test
    fun `a DJ declared into another year carries the tramo, not the arancel - that year's when it has one`() {
        val via = via()
        importarAranceles("$via,2026,41.50,$via,ANA,BETO")
        val contribuyente = inscribir()
        val predio = predioEnLaVia(via)
        val origen =
            post(
                "/api/srtm/declaraciones",
                mapOf(
                    "contribuyente" to contribuyente,
                    "predio" to predio,
                    "anio" to 2026,
                    "secuencia_uso" to "1",
                    "uso" to "CASA HABITACIÓN"
                )
            )
        val id = origen["id"].asString()
        assertArancel("41.50", id)

        val copia =
            post(
                "/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas",
                mapOf("predio_id" to predio, "desde" to id, "declaracion" to mapOf("anio" to 2027))
            )["declaracion"]["id"].asString()
        assertEquals(via, registro(copia)["id_aranc"].asString())
        assertTrue(registro(copia)["arancel"].isNull, registro(copia).toString())
        importarAranceles("$via,2027,44,$via,ANA,BETO")
        recalcular(copia)
        assertArancel("44", copia)

        // the rectificatoria is of the same year: it keeps both
        val rectificatoria = tree(send("POST", "/api/srtm/declaraciones/$id/rectificar", null, HttpStatus.CREATED))["declaracion"]["id"].asString()
        assertEquals(via, registro(rectificatoria)["id_aranc"].asString())
        assertArancel("41.50", rectificatoria)
    }

    @Test
    fun `a PUT that clears id_aranc takes the via again`() {
        val via = via()
        importarAranceles("$via,2026,41.50,$via,ANA,BETO")
        val id = declarar(predioEnLaVia(via))["id"].asString()
        put("/api/srtm/declaraciones/$id", fields(declaracion(id)) + ("id_aranc" to ""))
        assertEquals(via, registro(id)["id_aranc"].asString())
        assertArancel("41.50", id)
    }

    // the demo's REGISTRADOR without the aranceles: its recálculo takes no vía, and nothing fails
    @Test
    fun `a caller who may not read the aranceles recalculates without the via`() {
        val via = via()
        importarAranceles("$via,2026,41.50,$via,ANA,BETO")
        val predio = predioEnLaVia(via)
        marcar(predio)
        val id = declarar(predio)["id"].asString()
        padron(id, BigDecimal("35.50"))
        desmarcar(predio)
        val perfil = tree(File("docs/manuales/demo/perfiles.json").readText())["perfiles"].single { it["rol"].asString() == "REGISTRADOR" }
        val permisos =
            perfil["permisos"]
                .properties()
                .filter { (objeto, _) -> objeto != "arancel" }
                .flatMap { (objeto, acciones) -> acciones.iterator().asSequence().map { permiso(objeto, it.asString()) } }
        val sinAranceles = funcionario(permisos)
        send("POST", "/api/srtm/declaraciones/$id/recalcular", null, HttpStatus.OK, sinAranceles)
        assertTrue(registro(id)["id_aranc"].isNull, registro(id).toString())
        assertArancel("35.50", id)
    }

    private fun marcar(predio: String) = marca(predio, true)

    private fun desmarcar(predio: String) = marca(predio, false)

    private fun marca(
        predio: String,
        valor: Boolean
    ) {
        val ruta = "/api/objects/$PREDIO/records/$predio"
        val atributos = fields(tree(send("GET", ruta, null, HttpStatus.OK))["attributes"])
        send("PUT", ruta, mapOf("attributes" to atributos + ("varios_codigos_via" to valor)), HttpStatus.OK)
    }

    private fun modificada(id: String): Boolean = registro(id)["origen_modificado"].let { it != null && !it.isNull && it.asBoolean() }

    private fun via(): String = "V${uniqueDocumento()}-01"

    // a predio of the srtm with an external padrón's codigo_via, written by core's api as the import does
    private fun predioEnLaVia(
        via: String,
        tipo: String = "PREDIO URBANO"
    ): String {
        val id = post("/api/srtm/predios", mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to tipo))["id"].asString()
        val ruta = "/api/objects/$PREDIO/records/$id"
        val atributos = fields(tree(send("GET", ruta, null, HttpStatus.OK))["attributes"])
        send("PUT", ruta, mapOf("attributes" to atributos + ("codigo_via" to via)), HttpStatus.OK)
        return id
    }

    private fun declarar(
        predio: String,
        vararg extra: Pair<String, Any?>
    ): JsonNode =
        post(
            "/api/srtm/declaraciones",
            mapOf("contribuyente" to inscribir(), "predio" to predio, "anio" to 2026, "secuencia_uso" to "1", "uso" to "CASA HABITACIÓN") + extra
        )

    // id_aranc,anio,valor_m2,cod_via,transcribio,verifico lines, written
    private fun importarAranceles(lineas: String) {
        val result =
            client
                .post()
                .uri("$ARANCELES/importar?dry_run=false")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType("text", "csv"))
                .bodyValue("id_aranc,anio,valor_m2,cod_via,transcribio,verifico\n$lineas")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(200, result.status.value(), result.responseBody)
        assertEquals(0, tree(result.responseBody!!)["rechazos"].size(), result.responseBody)
    }

    private fun aranceles(q: String): List<JsonNode> =
        tree(send("GET", "$ARANCELES?q=$q&estado=POR_VERIFICAR", null, HttpStatus.OK))["content"]
            .iterator()
            .asSequence()
            .toList()

    private fun declaracion(id: String): JsonNode = tree(send("GET", "/api/srtm/declaraciones/$id", null, HttpStatus.OK))["declaracion"]

    private fun registro(id: String): JsonNode = tree(send("GET", "/api/objects/$DECLARACION/records/$id", null, HttpStatus.OK))["attributes"]

    private fun recalcular(id: String) = send("POST", "/api/srtm/declaraciones/$id/recalcular", null, HttpStatus.OK)

    // an external padrón's arancel, written by core's api as an import would
    private fun padron(
        id: String,
        arancel: BigDecimal,
        vararg otros: Pair<String, Any?>
    ) {
        val ruta = "/api/objects/$DECLARACION/records/$id"
        val atributos = fields(tree(send("GET", ruta, null, HttpStatus.OK))["attributes"])
        send("PUT", ruta, mapOf("attributes" to atributos + ("arancel" to arancel) + otros), HttpStatus.OK)
    }

    // the arancel stored, read from core
    private fun assertArancel(
        esperado: String,
        id: String
    ) {
        val arancel = registro(id)["arancel"]
        assertTrue(arancel != null && !arancel.isNull, "sin arancel en $id")
        assertEquals(0, BigDecimal(esperado).compareTo(arancel.decimalValue()), "esperado $esperado, guardado $arancel")
    }

    private companion object {
        const val ARANCELES = "/api/srtm/normativa/aranceles"
    }
}
