package srtm.normativa

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

// Normativa's aranceles over http (#188): the double signature, one alive per clave, the anulación, that a VIGENTE
// one is edited by no door but in its sincronización, the importación, the page and the permissions. each test takes
// tramos of its own (tramo()): FICTITIOUS figures
class ArancelesApiTest : ConNormativaApiTest() {
    @Test
    fun `the same person does not verify what she transcribed - another one does`() {
        val t = tramo()
        val a = post(ARANCELES, arancel(t), ana)
        assertEquals("POR_VERIFICAR", a["estado"].asString())
        assertEquals("$t|2026", a["clave"].asString())
        assertEquals(email(ana), a["transcribio"].asString())
        assertTrue(a["verifico"].isNull, a.toString())
        val id = a["id"].asString()

        val propia = verificarAranceles(listOf(id), ana)
        assertEquals(0, propia["verificados"].size())
        assertTrue(propia["rechazados"][0]["motivo"].asString().contains("no puede verificar lo que transcribió"), propia.toString())
        assertEquals(listOf(id), verificarAranceles(listOf(id))["verificados"].elementos().map { it.asString() })
        val fila = pagina("q=$t")["content"].single()
        assertEquals("VIGENTE", fila["estado"].asString())
        assertEquals(email(beto), fila["verifico"].asString())
        assertTrue(verificarAranceles(listOf(id))["rechazados"][0]["motivo"].asString().contains("VIGENTE"))
    }

    @Test
    fun `one alive per clave - a VIGENTE duplicate is a rechazo - annulled, it is registered again`() {
        val t = tramo()
        val id = vigenteArancel(arancel(t))
        // one alive per clave
        assertTrue(tree(send("POST", ARANCELES, arancel(t, "13.00"), HttpStatus.CONFLICT, ana))["detail"].asString().contains("anúlelo"))
        // the generic api does not register a POR_VERIFICAR one: a transcriber written by hand would verify it
        val atributos = DatosDeArancel.atributos(DatosDeArancel.validar(arancel(t, "13.00")))
        val aMano = mapOf("attributes" to atributos + mapOf("estado" to "POR_VERIFICAR", "transcribio" to "OTRA"))
        val sinMarca = tree(send("POST", "/api/objects/$ARANCEL/records", aMano, HttpStatus.CONFLICT))
        assertTrue(sinMarca["detail"].asString().contains("se registra en Normativa"), sinMarca.toString())
        // a VIGENTE the importers' door let in after a POR_VERIFICAR of its clave: verifying this one names it
        val d = tramo()
        val borrador = post(ARANCELES, arancel(d), ana)["id"].asString()
        val deD = DatosDeArancel.atributos(DatosDeArancel.validar(arancel(d, "13.00")))
        val colado =
            tree(
                send(
                    "POST",
                    "/api/objects/$ARANCEL/records",
                    mapOf("attributes" to deD + mapOf("estado" to "VIGENTE", "transcribio" to "OTRA", "verifico" to "OTRO")),
                    HttpStatus.CREATED
                )
            )["id"].asString()
        val rechazo = verificarAranceles(listOf(borrador))["rechazados"].single()["motivo"].asString()
        assertEquals("ya hay un arancel VIGENTE $d 2026 ($colado): anúlelo antes", rechazo)
        send("DELETE", "$ARANCELES/$borrador", null, HttpStatus.NO_CONTENT, ana)

        rechazo("POST", "$ARANCELES/$id/anular", mapOf("motivo" to " "), "motivo")
        val anulado = tree(send("POST", "$ARANCELES/$id/anular", mapOf("motivo" to "La cifra se copió mal"), HttpStatus.OK, beto))
        assertEquals("ANULADO", anulado["estado"].asString())
        assertEquals(email(beto), anulado["anulado_por"].asString())
        assertTrue(conflicto("POST", "$ARANCELES/$id/anular", mapOf("motivo" to "otra vez")).contains("ANULADO"))

        val nuevo = post(ARANCELES, arancel(t, "13.00"), ana)["id"].asString()
        assertEquals(listOf(nuevo), verificarAranceles(listOf(nuevo))["verificados"].elementos().map { it.asString() })
        val filas = pagina("q=$t")["content"].elementos()
        assertEquals(mapOf(nuevo to "VIGENTE", id to "ANULADO"), filas.associate { it["id"].asString() to it["estado"].asString() })
        assertEquals(listOf(nuevo), pagina("q=$t&estado=VIGENTE")["content"].elementos().map { it["id"].asString() })
    }

    @Test
    fun `a VIGENTE one is edited by no door but its sincronizacion - a POR_VERIFICAR one is corrected, moved and deleted`() {
        val t = tramo()
        val id = vigenteArancel(arancel(t))
        assertTrue(conflicto("PATCH", "$ARANCELES/$id", mapOf("valor_m2" to 99)).contains("POR_VERIFICAR"))
        assertTrue(conflicto("DELETE", "$ARANCELES/$id", null).contains("POR_VERIFICAR"))
        val registro = "/api/objects/$ARANCEL/records/$id"
        val atributos = fields(tree(send("GET", registro, null, HttpStatus.OK))["attributes"])
        val generica = tree(send("PUT", registro, mapOf("attributes" to atributos + ("valor_m2" to 99)), HttpStatus.CONFLICT))
        assertTrue(generica["detail"].asString().contains("VIGENTE no se edita"), generica.toString())
        send("DELETE", registro, null, HttpStatus.CONFLICT)
        // nor annulled: Normativa annuls it and copies the anulación to catastro, which would keep it otherwise
        val anulacion = mapOf("estado" to "ANULADO", "motivo_anulacion" to "a mano", "anulado_por" to "OTRA", "anulado_en" to "2026-10-24T10:00:00Z")
        val anulada = tree(send("PUT", registro, mapOf("attributes" to atributos + anulacion), HttpStatus.CONFLICT))
        assertTrue(anulada["detail"].asString().contains("cambia en Normativa"), anulada.toString())
        // catastro's copy writes back its sincronización, in any estado
        val sincronizado = atributos + mapOf("sincronizado_en" to "2026-10-24T10:00:00Z", "error_sincronizacion" to null)
        send("PUT", registro, mapOf("attributes" to sincronizado), HttpStatus.OK)
        assertEquals("VIGENTE", pagina("q=$t")["content"].single()["estado"].asString())

        // only Normativa verifies: the generic api's POR_VERIFICAR -> VIGENTE is a 409, even with another signature
        val otro = tramo()
        val porVerificar = post(ARANCELES, arancel(otro), ana)["id"].asString()
        val suyo = "/api/objects/$ARANCEL/records/$porVerificar"
        val firmado = fields(tree(send("GET", suyo, null, HttpStatus.OK))["attributes"]) + mapOf("estado" to "VIGENTE", "verifico" to "OTRA PERSONA")
        assertTrue(tree(send("PUT", suyo, mapOf("attributes" to firmado), HttpStatus.CONFLICT))["detail"].asString().contains("se verifica en Normativa"))
        // nor corrected there, its transcriber least of all: whoever wrote it would then verify it in Normativa
        val otraFirma = fields(tree(send("GET", suyo, null, HttpStatus.OK))["attributes"]) + ("transcribio" to "OTRA PERSONA")
        assertTrue(tree(send("PUT", suyo, mapOf("attributes" to otraFirma), HttpStatus.CONFLICT))["detail"].asString().contains("se corrige en Normativa"))

        // corrected by beto, who transcribes it now; a new tramo is a new clave, which must be free
        val corregido = tree(send("PATCH", "$ARANCELES/$porVerificar", mapOf("valor_m2" to "14.25", "id_aranc" to "$otro-B"), HttpStatus.OK, beto))
        assertEquals(0, BigDecimal("14.25").compareTo(corregido["valor_m2"].decimalValue()))
        assertEquals("$otro-B|2026", corregido["clave"].asString())
        assertEquals(email(beto), corregido["transcribio"].asString())
        assertTrue(conflicto("PATCH", "$ARANCELES/$porVerificar", mapOf("id_aranc" to t)).contains("Ya hay un arancel VIGENTE"))
        rechazo("PATCH", "$ARANCELES/$porVerificar", mapOf("estado" to "VIGENTE"), "estado")
        rechazo("PATCH", "$ARANCELES/$porVerificar", mapOf("valor_m2" to -1), "valor_m2")
        send("DELETE", "$ARANCELES/$porVerificar", null, HttpStatus.NO_CONTENT, ana)
        send("GET", suyo, null, HttpStatus.NOT_FOUND)
    }

    @Test
    fun `the importacion - two signatures VIGENTE, none POR_VERIFICAR, the dry run writes nothing, a second run nothing`() {
        val t = tramo()
        val csv =
            """
            # aranceles ficticios
            id_aranc,anio,valor_m2,cod_via,cuadra,lado,norma,fuente,transcribio,verifico
            $t-1,2026,12.50,V-$t,1,I,Ordenanza ficticia,prueba.csv,ANA,BETO
            $t-2,2026,0,V-$t,2,D,Ordenanza ficticia,prueba.csv,,
            $t-3,2026,-4,V-$t,3,D,Ordenanza ficticia,prueba.csv,ANA,BETO
            """.trimIndent()
        val (estado, previa) = importar(csv, null, base = ARANCELES)
        assertEquals(HttpStatus.OK, estado)
        assertEquals(true, previa!!["dry_run"].asBoolean())
        assertEquals(listOf(3, 4), previa["crear"].elementos().map { it["fila"].asInt() })
        assertEquals(listOf(5), previa["rechazos"].elementos().map { it["fila"].asInt() })
        assertEquals(0, pagina("q=$t")["totalElements"].asInt())

        val (_, hecho) = importar(csv, false, base = ARANCELES)
        assertEquals(false, hecho!!["dry_run"].asBoolean())
        assertEquals(listOf("VIGENTE", "POR_VERIFICAR"), hecho["crear"].elementos().map { it["estado"].asString() })
        val filas = pagina("q=$t")["content"].elementos().associateBy { it["id_aranc"].asString() }
        assertEquals("VIGENTE", filas.getValue("$t-1")["estado"].asString())
        assertEquals("BETO", filas.getValue("$t-1")["verifico"].asString())
        assertEquals("POR_VERIFICAR", filas.getValue("$t-2")["estado"].asString())
        assertEquals(email(ana), filas.getValue("$t-2")["transcribio"].asString())

        val (_, otra) = importar(csv, false, base = ARANCELES)
        assertEquals(2, otra!!["iguales"].asInt())
        assertEquals(0, otra["crear"].size() + otra["actualizar"].size())
        val distinto = csv.replace("$t-1,2026,12.50", "$t-1,2026,12.60")
        val (_, vigente) = importar(distinto, true, base = ARANCELES)
        assertTrue(vigente!!["rechazos"][0]["motivo"].asString().contains("VIGENTE distinto: anule primero"), vigente.toString())
        // two signatures on the POR_VERIFICAR ana transcribed would verify it: not by her, who would sign for someone else
        val firmado =
            "id_aranc,anio,valor_m2,cod_via,cuadra,lado,norma,fuente,transcribio,verifico\n" +
                "$t-2,2026,0,V-$t,2,D,Ordenanza ficticia,prueba.csv,CARLA,DORA"
        val (_, suyo) = importar(firmado, true, base = ARANCELES)
        assertEquals(listOf(PlanDeImportacion.LO_TRANSCRIBIO_USTED), suyo!!["rechazos"].elementos().map { it["motivo"].asString() })
        val (_, ajeno) = importar(firmado, true, quien = beto, base = ARANCELES)
        assertEquals(listOf("VIGENTE"), ajeno!!["actualizar"].elementos().map { it["estado"].asString() })
        assertEquals(HttpStatus.BAD_REQUEST, importar("anio,valor_m2\n2026,1", true, base = ARANCELES).first)
    }

    @Test
    fun `fifteen hundred rows go in by lotes`() {
        val t = tramo()
        val csv = "id_aranc,anio,valor_m2,transcribio,verifico\n" + (1..1500).joinToString("\n") { "$t-$it,2026,$it.5,ANA,BETO" }
        val (estado, r) = importar(csv, false, base = ARANCELES)
        assertEquals(HttpStatus.OK, estado, r.toString())
        assertEquals(1500, r!!["crear"].size())
        assertEquals(1500, pagina("q=$t-&size=1")["totalElements"].asInt())
        assertEquals(1500, importar(csv, true, base = ARANCELES).second!!["iguales"].asInt())
    }

    @Test
    fun `the page - by id_aranc, q at the start of id_aranc or cod_via in any case, anio and estado`() {
        val t = tramo()
        vigenteArancel(arancel("$t-B") + ("cod_via" to "VIA-$t"))
        post(ARANCELES, arancel("$t-A"), ana)
        post(ARANCELES, arancel("$t-C") + ("anio" to 2027), ana)
        assertEquals(listOf("$t-A", "$t-B", "$t-C"), pagina("q=${t.lowercase()}")["content"].elementos().map { it["id_aranc"].asString() })
        assertEquals(listOf("$t-B"), pagina("q=via-$t")["content"].elementos().map { it["id_aranc"].asString() })
        assertEquals(listOf("$t-C"), pagina("q=$t&anio=2027")["content"].elementos().map { it["id_aranc"].asString() })
        assertEquals(listOf("$t-A", "$t-C"), pagina("q=$t&estado=POR_VERIFICAR")["content"].elementos().map { it["id_aranc"].asString() })
        val primera = pagina("q=$t&size=2&page=0")
        assertEquals(2, primera["content"].size())
        assertEquals(3, primera["totalElements"].asInt())
        assertEquals(0, primera["page"].asInt())
        assertEquals(2, primera["size"].asInt())
        // one id_aranc in several years ties on the sort: core breaks the tie by id, so the pages neither repeat nor skip
        val d = tramo()
        val delTramo = (2026..2028).map { post(ARANCELES, arancel(d) + ("anio" to it), ana)["id"].asString() }
        val paginas = (0..2).flatMap { n -> pagina("q=$d&size=1&page=$n")["content"].elementos().map { it["id"].asString() } }
        assertEquals(delTramo.toSet(), paginas.toSet())
        assertEquals(3, paginas.size)
        // a part of it, not its start
        assertEquals(0, pagina("q=${t.drop(2)}")["content"].size())
        send("GET", "$ARANCELES?otro=1", null, HttpStatus.UNPROCESSABLE_CONTENT, ana)
        send("GET", "$ARANCELES?estado=OTRO", null, HttpStatus.UNPROCESSABLE_CONTENT, ana)
    }

    @Test
    fun `without the permission - a 403`() {
        val lector = funcionario(listOf(permiso(ARANCEL, "READ")))
        send("GET", ARANCELES, null, HttpStatus.OK, lector)
        send("POST", ARANCELES, arancel(tramo()), HttpStatus.FORBIDDEN, lector)
        send("POST", "$ARANCELES/verificar", mapOf("ids" to listOf("x")), HttpStatus.FORBIDDEN, lector)
        assertEquals(HttpStatus.FORBIDDEN, importar("id_aranc,anio,valor_m2\nX,2026,1", true, lector, ARANCELES).first)
        val nadie = funcionario(listOf(permiso("contribuyente", "READ")))
        send("GET", ARANCELES, null, HttpStatus.FORBIDDEN, nadie)
    }

    // a tramo no other test uses
    private fun tramo(): String = "T${uniqueDocumento()}"

    private fun arancel(
        idAranc: String,
        valor: String = "12.50"
    ) = mapOf(
        "id_aranc" to idAranc,
        "anio" to 2026,
        "valor_m2" to valor,
        "cod_via" to "V-001",
        "cuadra" to "1",
        "lado" to "I",
        "norma" to "Ordenanza ficticia de prueba",
        "fuente" to "ArancelesApiTest"
    )

    private fun verificarAranceles(
        ids: List<String>,
        quien: String = beto
    ): JsonNode = tree(send("POST", "$ARANCELES/verificar", mapOf("ids" to ids), HttpStatus.OK, quien))

    // registered by ana and verified by beto: its id
    private fun vigenteArancel(cuerpo: Map<String, Any?>): String {
        val id = post(ARANCELES, cuerpo, ana)["id"].asString()
        check(verificarAranceles(listOf(id))["verificados"].size() == 1)
        return id
    }

    private fun pagina(consulta: String): JsonNode = tree(send("GET", "$ARANCELES?$consulta", null, HttpStatus.OK, ana))

    private companion object {
        const val ARANCELES = "/api/srtm/normativa/aranceles"
    }
}
