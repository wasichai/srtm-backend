package srtm.reportes

import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import java.io.ByteArrayInputStream

// #269: the titulares the reportes keep per corrida are shared by every caller, so what each one sees of them is
// decided per response: the name only to who may read contribuyente and every field of its name, its código only to
// who may read that field. each test its own corrida of the seeded admin's organization (asked for by its id), so the
// cache starts empty for it and the order of the callers is the test's: the admin first, or the restricted first
class ReportesNombresApiTest : SrtmApiTest() {
    private class Padron(
        val apellido: String,
        val documento: String,
        val contribuyente: String,
        val codigo: String,
        val predio: String,
        val sufijo: String
    )

    private class Usuarios(
        val sinContribuyente: String,
        val sinNombre: String,
        val sinCodigo: String,
        val conNombre: String
    )

    private fun padron(): Padron = padronDeLaClase ?: sembrar().also { padronDeLaClase = it }

    private fun usuarios(): Usuarios =
        usuariosDeLaClase ?: Usuarios(
            sinContribuyente = usuario(emptyList()),
            sinNombre = usuario(listOf(permiso("contribuyente", "READ")), ocultos = listOf("apellido_paterno")),
            sinCodigo = usuario(listOf(permiso("contribuyente", "READ")), ocultos = listOf("codigo")),
            conNombre = usuario(listOf(permiso("contribuyente", "READ")))
        ).also { usuariosDeLaClase = it }

    // a contribuyente of its own surname (letters only: no other class's) and its DJ of 2026 on a predio
    private fun sembrar(): Padron {
        val sufijo = uniqueDocumento()
        val apellido = "FUGA" + sufijo.map { 'A' + (it - '0') }.joinToString("")
        val documento = uniqueDocumento()
        val c = post("/api/srtm/contribuyentes", personaNatural(documento) + mapOf("apellido_paterno" to apellido))
        val predio =
            post(
                "/api/srtm/predios",
                mapOf("codigo" to "N-$sufijo", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO", "sector_catastral" to "01")
            )["id"].asString()
        post("/api/srtm/declaraciones", mapOf("contribuyente" to c["id"].asString(), "predio" to predio, "anio" to 2026, "secuencia_uso" to "1"))
        return Padron(apellido, documento, c["id"].asString(), c["codigo"].asString(), predio, sufijo)
    }

    // a new TERMINADA corrida of 2026 with one subvaluado on the padrón's predio: its id
    private fun corrida(): String {
        val p = padron()
        val corrida =
            post(
                "/api/objects/brecha_corrida/records",
                mapOf(
                    "attributes" to
                        mapOf(
                            "anio" to 2026,
                            "estado" to "TERMINADA",
                            "version_catastro" to "valuacion-test",
                            "iniciado" to "2026-10-20T14:00:00Z",
                            "terminado" to "2026-10-20T15:00:00Z",
                            "total_predios" to 1,
                            "procesados" to 1
                        )
                )
            )["id"].asString()
        post(
            "/api/objects/brecha_predio/records",
            mapOf(
                "attributes" to
                    mapOf(
                        "corrida" to corrida,
                        "categoria" to "SUBVALUADO",
                        "orden" to 0,
                        "estimado" to false,
                        "predio" to p.predio,
                        "codigo_predio" to "N-${p.sufijo}",
                        "codigo_catastro" to "N-${p.sufijo}",
                        "sector" to "01",
                        "manzana" to "A",
                        "lote" to "1",
                        "confianza" to "CONFIABLE",
                        "delta_autoavaluo" to 50000,
                        "delta_impuesto" to 300
                    )
            )
        )
        return corrida
    }

    // a caller who may read the corrida, its rows and the DJs, plus `mas`; `ocultos`, fields of contribuyente its role
    // may not read
    private fun usuario(
        mas: List<Map<String, Any?>>,
        ocultos: List<String> = emptyList()
    ): String {
        val rol = uniqueName("ROL").uppercase()
        send("POST", "/api/roles", mapOf("name" to rol, "label" to rol), HttpStatus.CREATED)
        val permisos = listOf("brecha_corrida", "brecha_predio", "declaracion_predial").map { permiso(it, "READ") } + mas
        send("PUT", "/api/roles/$rol/permissions", mapOf("permissions" to permisos), HttpStatus.OK)
        if (ocultos.isNotEmpty()) {
            send(
                "PUT",
                "/api/roles/$rol/field-permissions",
                mapOf("fields" to ocultos.map { mapOf("objectName" to "contribuyente", "fieldName" to it, "read" to false, "write" to false) }),
                HttpStatus.OK
            )
        }
        val email = "${rol.lowercase()}@srtm.test"
        send("POST", "/api/users", mapOf("email" to email, "displayName" to rol, "password" to CLAVE, "roles" to listOf(rol)), HttpStatus.CREATED)
        return bearer(email, CLAVE)
    }

    private fun json(
        ruta: String,
        quien: String
    ): String = send("GET", "/api/srtm/reportes$ruta", null, HttpStatus.OK, quien)

    private fun bytes(
        ruta: String,
        quien: String
    ): ByteArray =
        client
            .get()
            .uri("/api/srtm/reportes$ruta")
            .header(HttpHeaders.AUTHORIZATION, quien)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(ByteArray::class.java)
            .returnResult()
            .responseBody!!

    // every cell of every sheet of an xlsx
    private fun celdas(xlsx: ByteArray): String =
        ReadableWorkbook(ByteArrayInputStream(xlsx)).use { libro ->
            libro.sheets.toList().joinToString(
                "\n"
            ) { hoja -> hoja.read().joinToString("\n") { r -> (0 until r.cellCount).joinToString(";") { r.getCellText(it) } } }
        }

    // the subvaluado's row of the report, as `quien` gets it
    private fun fila(
        corrida: String,
        quien: String
    ): JsonNode = tree(json("/subvaluados?anio=2026&corrida=$corrida&q=N-${padron().sufijo}", quien))["items"].single()

    // what `quien` gets of the corrida, in json, csv and xlsx, shows neither the name nor the document.
    // `contribuyentes`: whether it may read that report
    private fun sinNombre(
        corrida: String,
        quien: String,
        contribuyentes: Boolean
    ) {
        val p = padron()
        val textos =
            mutableListOf(
                "json" to json("/subvaluados?anio=2026&corrida=$corrida", quien),
                "csv" to bytes("/subvaluados.csv?anio=2026&corrida=$corrida", quien).toString(Charsets.UTF_8),
                "xlsx" to celdas(bytes("/subvaluados.xlsx?anio=2026&corrida=$corrida", quien))
            )
        assertTrue(fila(corrida, quien)["nombreContribuyente"].isNull)
        // q does not find the row by a name the caller may not see
        assertEquals(0, tree(json("/subvaluados?anio=2026&corrida=$corrida&q=${p.apellido}", quien))["total"].asInt())
        if (contribuyentes) {
            val c = tree(json("/contribuyentes?anio=2026&corrida=$corrida", quien))["items"].single { it["contribuyente"].asString() == p.contribuyente }
            assertTrue(c["nombreContribuyente"].isNull, c.toString())
            assertEquals(0, tree(json("/contribuyentes?anio=2026&corrida=$corrida&q=${p.apellido}", quien))["total"].asInt())
            textos += "json de contribuyentes" to json("/contribuyentes?anio=2026&corrida=$corrida", quien)
            textos += "csv de contribuyentes" to bytes("/contribuyentes.csv?anio=2026&corrida=$corrida", quien).toString(Charsets.UTF_8)
            textos += "xlsx de contribuyentes" to celdas(bytes("/contribuyentes.xlsx?anio=2026&corrida=$corrida", quien))
        } else {
            send("GET", "/api/srtm/reportes/contribuyentes?anio=2026&corrida=$corrida", null, HttpStatus.FORBIDDEN, quien)
            send("GET", "/api/srtm/reportes/contribuyentes.csv?anio=2026&corrida=$corrida", null, HttpStatus.FORBIDDEN, quien)
        }
        for ((formato, texto) in textos) {
            assertFalse(p.apellido in texto, "$formato trae el nombre")
            assertFalse(p.documento in texto, "$formato trae el documento")
        }
    }

    // what `quien` gets of the corrida names the contribuyente, in json, csv and xlsx
    private fun conNombre(
        corrida: String,
        quien: String
    ) {
        val p = padron()
        val f = fila(corrida, quien)
        assertEquals("${p.apellido} OTINIANO JUNIOR", f["nombreContribuyente"].asString())
        assertEquals(p.codigo, f["codigoContribuyente"].asString())
        assertEquals(1, tree(json("/subvaluados?anio=2026&corrida=$corrida&q=${p.apellido}", quien))["total"].asInt())
        assertTrue(p.apellido in bytes("/subvaluados.csv?anio=2026&corrida=$corrida", quien).toString(Charsets.UTF_8))
        assertTrue(p.apellido in celdas(bytes("/subvaluados.xlsx?anio=2026&corrida=$corrida", quien)))
        val c = tree(json("/contribuyentes?anio=2026&corrida=$corrida", quien))["items"].single { it["contribuyente"].asString() == p.contribuyente }
        assertEquals("${p.apellido} OTINIANO JUNIOR", c["nombreContribuyente"].asString())
        assertTrue(p.apellido in bytes("/contribuyentes.csv?anio=2026&corrida=$corrida", quien).toString(Charsets.UTF_8))
    }

    @Test
    fun `the admin fills the cache first, the restricted callers get no names`() {
        val u = usuarios()
        val corrida = corrida()
        conNombre(corrida, token)
        sinNombre(corrida, u.sinContribuyente, contribuyentes = false)
        sinNombre(corrida, u.sinNombre, contribuyentes = true)
        // its contribuyente's id, not its name
        assertEquals(padron().contribuyente, fila(corrida, u.sinNombre)["contribuyente"].asString())
        conNombre(corrida, u.conNombre)
        conNombre(corrida, token)
    }

    @Test
    fun `the restricted callers read first, then the admin and they still get each their own`() {
        val u = usuarios()
        val corrida = corrida()
        sinNombre(corrida, u.sinNombre, contribuyentes = true)
        sinNombre(corrida, u.sinContribuyente, contribuyentes = false)
        // what the restricted read does not hide from who may read it
        conNombre(corrida, token)
        conNombre(corrida, u.conNombre)
        sinNombre(corrida, u.sinNombre, contribuyentes = true)
        sinNombre(corrida, u.sinContribuyente, contribuyentes = false)
    }

    @Test
    fun `who may not read the contribuyente's codigo gets it in null, and q does not find it`() {
        val u = usuarios()
        val p = padron()
        val corrida = corrida()
        conNombre(corrida, token)
        val f = fila(corrida, u.sinCodigo)
        assertTrue(f["codigoContribuyente"].isNull, f.toString())
        assertEquals("${p.apellido} OTINIANO JUNIOR", f["nombreContribuyente"].asString())
        assertEquals(0, tree(json("/subvaluados?anio=2026&corrida=$corrida&q=${p.codigo}", u.sinCodigo))["total"].asInt())
        val c = tree(json("/contribuyentes?anio=2026&corrida=$corrida", u.sinCodigo))["items"].single { it["contribuyente"].asString() == p.contribuyente }
        assertTrue(c["codigoContribuyente"].isNull, c.toString())
        for (ruta in listOf("/contribuyentes.csv", "/subvaluados.csv")) {
            assertEquals(listOf(""), columna(bytes("$ruta?anio=2026&corrida=$corrida", u.sinCodigo), "codigoContribuyente"), ruta)
        }
        ReadableWorkbook(ByteArrayInputStream(bytes("/subvaluados.xlsx?anio=2026&corrida=$corrida", u.sinCodigo))).use { libro ->
            val detalle = libro.sheets.toList()[1].read()
            val i = (0 until detalle[0].cellCount).map { detalle[0].getCellText(it) }.indexOf("Código del contribuyente")
            assertEquals(listOf(""), detalle.drop(1).map { it.getCellText(i) })
        }
    }

    // the values of a column of a csv export
    private fun columna(
        csv: ByteArray,
        nombre: String
    ): List<String> {
        val lineas =
            csv
                .toString(Charsets.UTF_8)
                .removePrefix("\uFEFF")
                .trimEnd()
                .split("\r\n")
                .map { it.split(";") }
        val i = lineas[0].indexOf(nombre)
        return lineas.drop(1).map { it[i] }
    }

    companion object {
        private var padronDeLaClase: Padron? = null
        private var usuariosDeLaClase: Usuarios? = null
        private const val CLAVE = "clave-del-usuario-de-reportes"
    }
}
