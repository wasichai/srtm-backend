package srtm.visor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode

// #265: the predios of a lote for the visor's popup, end to end: a TERMINADA corrida of the seeded admin's
// organization (asked for by its id: other classes write corridas of the same year there), a lote of two predios (one
// with its DJ, one omiso without contribuyente) of shared codes with dashes, another lote next to it, and callers
// who may not read contribuyente or the fields of its name
class VisorLoteApiTest : SrtmApiTest() {
    private class Datos(
        val corrida: String,
        val sufijo: String,
        val conDj: String,
        val omiso: String,
        val predio: String,
        val contribuyente: String,
        val declaracion: String
    )

    private fun datos(): Datos = datosDeLaClase ?: sembrar().also { datosDeLaClase = it }

    private fun sembrar(): Datos {
        val sufijo = uniqueDocumento()
        val contribuyente = inscribir()
        val predio =
            post(
                "/api/srtm/predios",
                mapOf("codigo" to "L-$sufijo-1", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO", "sector_catastral" to "01")
            )["id"].asString()
        val declaracion =
            post(
                "/api/srtm/declaraciones",
                mapOf("contribuyente" to contribuyente, "predio" to predio, "anio" to 2026, "secuencia_uso" to "1")
            )["id"].asString()
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
                            "total_predios" to 3,
                            "procesados" to 3
                        )
                )
            )["id"].asString()

        fun fila(
            orden: Int,
            categoria: String,
            atributos: Map<String, Any?>
        ) = post(
            "/api/objects/brecha_predio/records",
            mapOf(
                "attributes" to mapOf("corrida" to corrida, "categoria" to categoria, "orden" to orden, "estimado" to false) + atributos,
                "geometries" to mapOf("lote_geom" to LOTE)
            )
        )["id"].asString()
        val conDj =
            fila(
                0,
                "SUBVALUADO",
                mapOf(
                    "predio" to predio,
                    "codigo_predio" to "L-$sufijo-1",
                    "codigo_catastro" to "L-$sufijo-1",
                    "sector" to "01",
                    "manzana" to "A",
                    "lote" to "7",
                    "confianza" to "CONFIABLE",
                    "delta_impuesto" to 300
                )
            )
        // the omiso of the same lote: a ficha of catastro the padrón lacks, its code with a space
        val omiso =
            fila(
                1,
                "OMISO",
                mapOf(
                    "codigo_catastro" to "L-$sufijo-2 B",
                    "sector" to "1",
                    "manzana" to "A",
                    "lote" to "07",
                    "confianza" to "DUDOSO",
                    "delta_impuesto" to 200,
                    "estimado" to true
                )
            )
        fila(2, "COINCIDE", mapOf("codigo_catastro" to "M-$sufijo", "sector" to "01", "manzana" to "A", "lote" to "8", "confianza" to "CONFIABLE"))
        return Datos(corrida, sufijo, conDj, omiso, predio, contribuyente, declaracion)
    }

    // GET /lotes/{codigo}/predios, the codigo url-encoded as a uri variable (as the ui's encodeURIComponent: a space
    // is %20, a dash stays), and `consulta`: status and body
    private fun pedir(
        codigo: String,
        quien: String = token,
        consulta: String = "anio=2026&corrida=${datos().corrida}"
    ): Pair<HttpStatus, String> {
        val r =
            client
                .get()
                .uri("/api/srtm/visor/lotes/{codigo}/predios?$consulta", codigo)
                .header(HttpHeaders.AUTHORIZATION, quien)
                .exchange()
                .returnResult(String::class.java)
        return HttpStatus.valueOf(r.status.value()) to
            r.responseBody
                .collectList()
                .block()!!
                .joinToString("")
    }

    private fun estado(
        codigo: String,
        esperado: HttpStatus,
        consulta: String = "anio=2026&corrida=${datos().corrida}"
    ) {
        val (actual, cuerpo) = pedir(codigo, consulta = consulta)
        assertEquals(esperado, actual, cuerpo)
    }

    private fun leer(
        codigo: String,
        quien: String = token
    ): JsonNode {
        val (actual, cuerpo) = pedir(codigo, quien)
        assertEquals(HttpStatus.OK, actual, cuerpo)
        return tree(cuerpo)
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

    @Test
    fun `a lote of two predios, one with its DJ and one omiso without contribuyente, by codigo_predio`() {
        val d = datos()
        val p = leer("L-${d.sufijo}-1")
        assertEquals(listOf(d.conDj, d.omiso), p.values().map { it["id"].asString() })
        val conDj = p[0]
        assertEquals(d.predio, conDj["predio"].asString())
        assertEquals("L-${d.sufijo}-1", conDj["codigo_predio"].asString())
        assertEquals(d.declaracion, conDj["declaracion"].asString())
        assertEquals(d.contribuyente, conDj["contribuyente"].asString())
        assertEquals("FLORES OTINIANO JUNIOR", conDj["contribuyente_nombre"].asString())
        assertEquals("SUBVALUADO", conDj["categoria"].asString())
        assertEquals("CONFIABLE", conDj["confianza"].asString())
        assertEquals(0, 300.toBigDecimal().compareTo(conDj["delta_impuesto"].decimalValue()))
        assertTrue(conDj.has("estado"))
        val omiso = p[1]
        for (campo in listOf("predio", "codigo_predio", "declaracion", "contribuyente", "contribuyente_nombre")) assertTrue(omiso[campo].isNull, campo)
        assertEquals("OMISO", omiso["categoria"].asString())
        assertEquals(
            setOf(
                "id",
                "predio",
                "codigo_predio",
                "declaracion",
                "contribuyente",
                "contribuyente_nombre",
                "categoria",
                "confianza",
                "delta_impuesto",
                "estado"
            ),
            conDj.propertyNames().toSet()
        )
        // the omiso's code (a dash and a space, url-encoded) names the same lote
        assertEquals(listOf(d.conDj, d.omiso), leer("L-${d.sufijo}-2 B").values().map { it["id"].asString() })
        // the code /lotes gives the feature
        val codigos =
            tree(send("GET", "/api/srtm/visor/lotes?anio=2026&corrida=${d.corrida}", null, HttpStatus.OK, token))["features"]
                .values()
                .map { it["properties"]["codigo"].asString() }
        assertTrue(codigos.containsAll(listOf("L-${d.sufijo}-1", "L-${d.sufijo}-2 B", "M-${d.sufijo}")), codigos.toString())
        assertEquals(1, leer("M-${d.sufijo}").size())
    }

    @Test
    fun `a lote the corrida lacks is a 404, a malformed parameter a 422`() {
        val d = datos()
        estado("NO-${d.sufijo}", HttpStatus.NOT_FOUND)
        estado("L-${d.sufijo}-1", HttpStatus.UNPROCESSABLE_CONTENT, "anio=dos")
        estado("L-${d.sufijo}-1", HttpStatus.UNPROCESSABLE_CONTENT, "anio=2026&corrida=x")
        estado("L-${d.sufijo}-1", HttpStatus.UNPROCESSABLE_CONTENT, "anio=2026&corrida=${d.corrida}&otro=1")
        estado("L-${d.sufijo}-1", HttpStatus.UNPROCESSABLE_CONTENT, "corrida=${d.corrida}")
    }

    @Test
    fun `who may not read contribuyente, or the fields of its name, gets the name in null`() {
        val d = datos()
        // the admin reads first: the titulares are cached with the names
        assertEquals("FLORES OTINIANO JUNIOR", leer("L-${d.sufijo}-1")[0]["contribuyente_nombre"].asString())
        val sinContribuyente = usuario(emptyList())
        val p = leer("L-${d.sufijo}-1", sinContribuyente)
        assertEquals(listOf(d.conDj, d.omiso), p.values().map { it["id"].asString() })
        assertTrue(p[0]["contribuyente_nombre"].isNull)
        val sinNombre = usuario(listOf(permiso("contribuyente", "READ")), ocultos = listOf("apellido_paterno"))
        // core hides it from its record too
        val registro = tree(send("GET", "/api/objects/contribuyente/records/${d.contribuyente}", null, HttpStatus.OK, sinNombre))
        assertTrue(registro["attributes"]["apellido_paterno"].let { it == null || it.isNull }, registro.toString())
        assertTrue(leer("L-${d.sufijo}-1", sinNombre)[0]["contribuyente_nombre"].isNull)
        val conNombre = usuario(listOf(permiso("contribuyente", "READ")))
        assertEquals("FLORES OTINIANO JUNIOR", leer("L-${d.sufijo}-1", conNombre)[0]["contribuyente_nombre"].asString())
    }

    @Test
    fun `the predios of a lote need what the visor needs`() {
        val d = datos()
        val sinFilas = funcionario(listOf(permiso("brecha_corrida", "READ")))
        assertEquals(HttpStatus.FORBIDDEN, pedir("L-${d.sufijo}-1", sinFilas).first)
    }

    companion object {
        private var datosDeLaClase: Datos? = null
        private const val CLAVE = "clave-del-usuario-del-lote"

        private val LOTE =
            mapOf(
                "type" to "Polygon",
                "coordinates" to
                    listOf(listOf(listOf(-75.7, -14.0), listOf(-75.6995, -14.0), listOf(-75.6995, -13.9995), listOf(-75.7, -13.9995), listOf(-75.7, -14.0)))
            )
    }
}
