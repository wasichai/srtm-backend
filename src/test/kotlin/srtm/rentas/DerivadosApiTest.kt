package srtm.rentas

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// #156: every write of a declaración or of its niveles, obras and otros frentes recomputes what it derives of them
// (areas, longitud de frente) and its autoavalúo, valued by a stub of catastro-backend that answers 10 per m² of
// terreno and 100 per m² of each construcción; then its condominio's values. the suite's own organization has no
// catastro: there the autoavalúo is the user's
class DerivadosApiTest : SrtmApiTest() {
    // the organization of the catastro (srtm.catastro.organizacion), with the model and a uso of the catalog
    private fun conCatastro(): String =
        organizacionDelCatastro ?: run {
            val email = "admin@$SLUG.test"
            val clave = "clave-de-la-organizacion-del-catastro"
            post("/api/organizations", mapOf("name" to SLUG, "slug" to SLUG, "adminEmail" to email, "adminPassword" to clave))
            val suyo = bearer(email, clave)
            aplicarModelo(suyo)
            val uso = mapOf("codigo" to "010101", "clase" to "RESIDENCIAL", "sub_clase" to "UNIFAMILIAR", "uso" to "CASA HABITACIÓN")
            post("/api/objects/uso_predio/records", mapOf("attributes" to uso), suyo)
            // #235: two usos of the sub clase TIENDAS, its 0203 the prefix a declaración without uso is valued with
            for ((codigo, nombre) in listOf("020301" to "DE PRODUCTOS AGRÍCOLAS COMESTIBLES", "020326" to "TIENDA MÚLTIPLE DE DIFERENTE RUBRO")) {
                val tienda = mapOf("codigo" to codigo, "clase" to "COMERCIAL", "sub_clase" to "TIENDAS", "uso" to nombre)
                post("/api/objects/uso_predio/records", mapOf("attributes" to tienda), suyo)
            }
            suyo.also { organizacionDelCatastro = it }
        }

    @BeforeEach
    fun valuada() {
        MODO.set(Modo.VALUA)
    }

    private fun contribuyente(org: String) = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), org)["id"].asString()

    private fun predioDe(org: String) =
        post(
            "/api/srtm/predios",
            mapOf("codigo" to "DV-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO"),
            org
        )["id"].asString()

    // a declaración of 2026 on 200 m² of terreno, casa habitación
    private fun declarar(
        org: String,
        extra: Map<String, Any?> = emptyMap(),
        contribuyente: String = contribuyente(org),
        predio: String = predioDe(org)
    ): String =
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to contribuyente,
                "predio" to predio,
                "anio" to 2026,
                "secuencia_uso" to "1",
                "area_terreno" to 200,
                "clase_uso" to "RESIDENCIAL",
                "sub_clase_uso" to "UNIFAMILIAR",
                "uso" to "CASA HABITACIÓN"
            ) + extra,
            org
        )["id"].asString()

    private fun dj(
        id: String,
        org: String
    ): JsonNode = tree(send("GET", "/api/srtm/declaraciones/$id", null, HttpStatus.OK, org))

    private fun declaracion(
        id: String,
        org: String
    ): JsonNode = dj(id, org)["declaracion"]

    private fun piso(
        id: String,
        org: String,
        numero: Int,
        area: Int,
        comun: Int? = null
    ): String =
        post(
            "/api/srtm/declaraciones/$id/niveles",
            NIVEL + mapOf("numero_piso" to numero, "area_construida" to area) + (comun?.let { mapOf("area_comun" to it) } ?: emptyMap()),
            org
        )["id"].asString()

    private fun dec(node: JsonNode?) = node?.takeUnless { it.isNull }?.decimalValue()

    private fun assertDec(
        esperado: String?,
        node: JsonNode?
    ) {
        if (esperado == null) {
            assertTrue(node == null || node.isNull, node.toString())
        } else {
            assertEquals(0, BigDecimal(esperado).compareTo(dec(node)), "$esperado != $node")
        }
    }

    @Test
    fun `a piso added raises the area, the autoavaluo and its condominio's values, and one removed lowers them`() {
        val org = conCatastro()
        val c = contribuyente(org)
        val id = declarar(org, contribuyente = c)
        // a declaración without niveles: no area, its terreno valued
        declaracion(id, org).let {
            assertDec("0", it["area_construida"])
            assertDec("2000", it["valor_autoavaluo"])
            assertDec("2000", it["valor_afecto"])
        }

        piso(id, org, 1, 100, comun = 10)
        val segundo = piso(id, org, 2, 50)

        declaracion(id, org).let {
            assertDec("150", it["area_construida"])
            assertDec("10", it["area_comun_construida"])
            assertDec("17000", it["valor_autoavaluo"])
            assertDec("17000", it["valor_condominio"])
            assertDec("17000", it["valor_afecto"])
            assertFalse(it["autoavaluo_por_revisar"]?.asBoolean() ?: false, it.toString())
        }
        assertDec("17000", tree(send("GET", "/api/srtm/contribuyentes/$c/liquidacion?anio=2026", null, HttpStatus.OK, org))["base"])

        send("DELETE", "/api/srtm/niveles/$segundo", null, HttpStatus.NO_CONTENT, org)

        declaracion(id, org).let {
            assertDec("100", it["area_construida"])
            assertDec("12000", it["valor_autoavaluo"])
            assertDec("12000", it["valor_afecto"])
        }
        assertDec("12000", tree(send("GET", "/api/srtm/contribuyentes/$c/liquidacion?anio=2026", null, HttpStatus.OK, org))["base"])
    }

    @Test
    fun `a nivel changed or made inactive counts as it is now`() {
        val org = conCatastro()
        val id = declarar(org)
        val nivel = piso(id, org, 1, 100)

        put("/api/srtm/niveles/$nivel", NIVEL + mapOf("area_construida" to 80), org)
        assertDec("80", declaracion(id, org)["area_construida"])
        assertDec("10000", declaracion(id, org)["valor_autoavaluo"])

        put("/api/srtm/niveles/$nivel", NIVEL + mapOf("area_construida" to 80, "estado" to "INACTIVO"), org)
        assertDec("0", declaracion(id, org)["area_construida"])
        assertDec("2000", declaracion(id, org)["valor_autoavaluo"])
    }

    @Test
    fun `the longitud de frente is the frontis principal and the otros frentes`() {
        val org = conCatastro()
        val id = declarar(org, mapOf("frontis_principal" to 10))
        assertDec("10", declaracion(id, org)["longitud_frente"])

        val frente = post("/api/srtm/declaraciones/$id/frentes", FRENTE + mapOf("frontis" to 4.5), org)["id"].asString()
        assertDec("14.5", declaracion(id, org)["longitud_frente"])

        put("/api/srtm/frentes/$frente", FRENTE + mapOf("frontis" to 6), org)
        assertDec("16", declaracion(id, org)["longitud_frente"])

        // the frontis principal changes in the declaración; what a client sends of the derived ones is not taken
        val d = declaracion(id, org)
        put(
            "/api/srtm/declaraciones/$id",
            fields(d) + mapOf("frontis_principal" to 12, "longitud_frente" to 999, "area_construida" to 999, "area_comun_construida" to 999),
            org
        )
        declaracion(id, org).let {
            assertDec("12", it["frontis_principal"])
            assertDec("18", it["longitud_frente"])
            assertDec("0", it["area_construida"])
            assertDec("0", it["area_comun_construida"])
        }

        send("DELETE", "/api/srtm/frentes/$frente", null, HttpStatus.NO_CONTENT, org)
        assertDec("12", declaracion(id, org)["longitud_frente"])
    }

    @Test
    fun `an edit without the frontis principal keeps it, and 0 clears it`() {
        val org = conCatastro()
        val id = declarar(org, mapOf("frontis_principal" to 10))

        // srtm-ui before #156 never sends it
        put("/api/srtm/declaraciones/$id", fields(declaracion(id, org)) - "frontis_principal" + ("numero_habitantes" to 4), org)
        declaracion(id, org).let {
            assertDec("10", it["frontis_principal"])
            assertDec("10", it["longitud_frente"])
        }

        put("/api/srtm/declaraciones/$id", fields(declaracion(id, org)) + ("frontis_principal" to 0), org)
        declaracion(id, org).let {
            assertDec("0", it["frontis_principal"])
            assertDec("0", it["longitud_frente"])
        }
    }

    @Test
    fun `an edit of a declaracion never migrated takes its longitud de frente as its frontis principal`() {
        val org = conCatastro()
        val id = declarar(org)
        // as imported: a longitud de frente and no frontis principal
        val guardada = tree(send("GET", "/api/objects/declaracion_predial/records/$id", null, HttpStatus.OK, org))["attributes"]
        send(
            "PUT",
            "/api/objects/declaracion_predial/records/$id",
            mapOf("attributes" to fields(guardada) + mapOf("frontis_principal" to null, "longitud_frente" to 7)),
            HttpStatus.OK,
            org
        )

        put("/api/srtm/declaraciones/$id", fields(declaracion(id, org)) - "frontis_principal", org)

        declaracion(id, org).let {
            assertDec("7", it["frontis_principal"])
            assertDec("7", it["longitud_frente"])
        }
    }

    @Test
    fun `a client that still sends the longitud de frente alone declares its frontis principal`() {
        val org = conCatastro()
        val id = declarar(org, mapOf("longitud_frente" to 8.5))
        declaracion(id, org).let {
            assertDec("8.5", it["frontis_principal"])
            assertDec("8.5", it["longitud_frente"])
        }
    }

    @Test
    fun `with catastro the autoavaluo is catastro's, whatever the client sends`() {
        val org = conCatastro()
        val id = declarar(org, mapOf("valor_autoavaluo" to 123))
        assertEquals(false, dj(id, org)["autoavaluo_manual"].asBoolean())
        assertDec("2000", declaracion(id, org)["valor_autoavaluo"])

        put("/api/srtm/declaraciones/$id", fields(declaracion(id, org)) + ("valor_autoavaluo" to 1), org)
        assertDec("2000", declaracion(id, org)["valor_autoavaluo"])

        // an engine input of the declaración: its terreno
        put("/api/srtm/declaraciones/$id", fields(declaracion(id, org)) + ("area_terreno" to 300), org)
        assertDec("3000", declaracion(id, org)["valor_autoavaluo"])
    }

    @Test
    fun `not valued, the autoavaluo stays and is marked with catastro's motivo until it is valued again`() {
        val org = conCatastro()
        val id = declarar(org)
        piso(id, org, 1, 100)
        MODO.set(Modo.MOTIVO)

        piso(id, org, 2, 50)

        declaracion(id, org).let {
            assertDec("150", it["area_construida"])
            assertDec("12000", it["valor_autoavaluo"])
            assertEquals(true, it["autoavaluo_por_revisar"].asBoolean(), it.toString())
            assertEquals("Falta el porcentaje de actualización de 2027", it["autoavaluo_motivo"].asString())
        }

        MODO.set(Modo.VALUA)
        // the button «Recalcular»: the same, without a change
        send("POST", "/api/srtm/declaraciones/$id/recalcular", null, HttpStatus.OK, org)

        declaracion(id, org).let {
            assertDec("17000", it["valor_autoavaluo"])
            assertFalse(it["autoavaluo_por_revisar"]?.asBoolean() ?: false, it.toString())
            assertTrue(it["autoavaluo_motivo"] == null || it["autoavaluo_motivo"].isNull, it.toString())
        }
    }

    @Test
    fun `catastro failing marks the autoavaluo as not answered and the write goes through`() {
        val org = conCatastro()
        val id = declarar(org)
        MODO.set(Modo.FALLA)

        piso(id, org, 1, 100)

        declaracion(id, org).let {
            assertDec("100", it["area_construida"])
            assertDec("2000", it["valor_autoavaluo"])
            assertEquals(true, it["autoavaluo_por_revisar"].asBoolean(), it.toString())
            assertTrue(it["autoavaluo_motivo"].asString().startsWith("El catastro no respondió: "), it.toString())
        }
    }

    @Test
    fun `without catastro the autoavaluo is the user's, and the areas still derive`() {
        // the suite's own organization
        val id = declarar(token, mapOf("valor_autoavaluo" to 50000, "area_construida" to 999))
        assertEquals(true, dj(id, token)["autoavaluo_manual"].asBoolean())
        declaracion(id, token).let {
            assertDec("50000", it["valor_autoavaluo"])
            assertDec("0", it["area_construida"])
        }

        piso(id, token, 1, 100)
        put("/api/srtm/declaraciones/$id", fields(declaracion(id, token)) + ("valor_autoavaluo" to 60000))

        declaracion(id, token).let {
            assertDec("100", it["area_construida"])
            assertDec("60000", it["valor_autoavaluo"])
            assertDec("60000", it["valor_afecto"])
            assertFalse(it["autoavaluo_por_revisar"]?.asBoolean() ?: false, it.toString())
        }
    }

    @Test
    fun `a condomino's values follow its own percent of the recomputed autoavaluo`() {
        val org = conCatastro()
        val id = declarar(org)
        piso(id, org, 1, 100)
        val otra = post("/api/srtm/declaraciones/$id/condominos", mapOf("contribuyente" to contribuyente(org), "porcentaje_condominio" to 40), org)
        val otraId = otra["id"].asString()
        declaracion(otraId, org).let {
            assertDec("100", it["area_construida"])
            assertDec("12000", it["valor_autoavaluo"])
            assertDec("4800", it["valor_condominio"])
        }

        piso(id, org, 2, 50)

        // each its own % of the new autoavalúo: the copy got the piso too (#157)
        declaracion(id, org).let {
            assertDec("17000", it["valor_autoavaluo"])
            assertDec("60", it["porcentaje_condominio"])
            assertDec("10200", it["valor_condominio"])
        }
        declaracion(otraId, org).let {
            assertDec("17000", it["valor_autoavaluo"])
            assertDec("40", it["porcentaje_condominio"])
            assertDec("6800", it["valor_condominio"])
        }
    }

    // #157: a condómino added from `id`, at 50 % unless told: with `id` alone, `id` keeps the rest
    private fun condomino(
        id: String,
        org: String,
        porcentaje: Int = 50
    ): String =
        post("/api/srtm/declaraciones/$id/condominos", mapOf("contribuyente" to contribuyente(org), "porcentaje_condominio" to porcentaje), org)["id"]
            .asString()

    private fun filas(
        id: String,
        lista: String,
        org: String
    ): List<JsonNode> = tree(send("GET", "/api/srtm/declaraciones/$id/$lista", null, HttpStatus.OK, org)).iterator().asSequence().toList()

    // a row as an import leaves it: written straight to Core, without a fila
    private fun importada(
        objeto: String,
        declaracion: String,
        campos: Map<String, Any?>,
        org: String
    ): String = post("/api/objects/$objeto/records", mapOf("attributes" to campos + ("declaracion" to declaracion)), org)["id"].asString()

    private fun registro(
        objeto: String,
        id: String,
        org: String
    ): JsonNode = tree(send("GET", "/api/objects/$objeto/records/$id", null, HttpStatus.OK, org))["attributes"]

    @Test
    fun `a piso added in one condomino is added to the others with its fila, each valued with its own percent`() {
        val org = conCatastro()
        val a = declarar(org)
        val b = condomino(a, org)

        val nivel = piso(a, org, 1, 100)

        val fila = filas(a, "niveles", org).single { it["id"].asString() == nivel }["fila"].asString()
        val copia = filas(b, "niveles", org).single()
        assertEquals(fila, copia["fila"].asString())
        assertDec("100", copia["area_construida"])
        for (d in listOf(a, b)) {
            declaracion(d, org).let {
                assertDec("100", it["area_construida"])
                assertDec("12000", it["valor_autoavaluo"])
                assertDec("50", it["porcentaje_condominio"])
                assertDec("6000", it["valor_condominio"])
            }
        }
    }

    @Test
    fun `a piso changed in any condomino changes in the others, and one removed goes from all`() {
        val org = conCatastro()
        val a = declarar(org)
        val b = condomino(a, org)
        val nivel = piso(a, org, 1, 100)
        val suyo = filas(b, "niveles", org).single()["id"].asString()

        put("/api/srtm/niveles/$suyo", NIVEL + mapOf("area_construida" to 80), org)

        assertDec("80", filas(a, "niveles", org).single()["area_construida"])
        for (d in listOf(a, b)) {
            declaracion(d, org).let {
                assertDec("80", it["area_construida"])
                assertDec("10000", it["valor_autoavaluo"])
                assertDec("5000", it["valor_condominio"])
            }
        }

        send("DELETE", "/api/srtm/niveles/$nivel", null, HttpStatus.NO_CONTENT, org)

        assertTrue(filas(b, "niveles", org).isEmpty())
        declaracion(b, org).let {
            assertDec("0", it["area_construida"])
            assertDec("2000", it["valor_autoavaluo"])
            assertDec("1000", it["valor_condominio"])
        }
    }

    @Test
    fun `obras and otros frentes are replicated in the condominos too`() {
        val org = conCatastro()
        val a = declarar(org, mapOf("frontis_principal" to 10))
        val b = condomino(a, org)

        val obra = post("/api/srtm/declaraciones/$a/obras", OBRA, org)
        val frente = post("/api/srtm/declaraciones/$a/frentes", FRENTE, org)

        assertEquals(obra["fila"].asString(), filas(b, "obras", org).single()["fila"].asString())
        assertEquals(frente["fila"].asString(), filas(b, "frentes", org).single()["fila"].asString())
        assertDec("14", declaracion(b, org)["longitud_frente"])

        val suyo = filas(b, "frentes", org).single()["id"].asString()
        put("/api/srtm/frentes/$suyo", FRENTE + mapOf("frontis" to 6), org)
        assertDec("16", declaracion(a, org)["longitud_frente"])

        send("DELETE", "/api/srtm/obras/${obra["id"].asString()}", null, HttpStatus.NO_CONTENT, org)
        send("DELETE", "/api/srtm/frentes/$suyo", null, HttpStatus.NO_CONTENT, org)
        assertTrue(filas(b, "obras", org).isEmpty())
        assertTrue(filas(a, "frentes", org).isEmpty())
        assertDec("10", declaracion(a, org)["longitud_frente"])
    }

    @Test
    fun `the predio's data changed in one condomino reaches the others, the titular's does not`() {
        val org = conCatastro()
        val a = declarar(org)
        val b = condomino(a, org)

        put(
            "/api/srtm/declaraciones/$a",
            fields(declaracion(a, org)) + mapOf("area_terreno" to 300, "numero_habitantes" to 5, "fecha_adquisicion" to "2020-05-04", "deduccion" to 100),
            org
        )

        declaracion(b, org).let {
            assertDec("300", it["area_terreno"])
            assertEquals(5, it["numero_habitantes"].asInt())
            assertDec("3000", it["valor_autoavaluo"])
            assertDec("1500", it["valor_condominio"])
            assertDec("1500", it["valor_afecto"])
            assertTrue(it["fecha_adquisicion"] == null || it["fecha_adquisicion"].isNull, it.toString())
        }
        declaracion(a, org).let {
            assertDec("1500", it["valor_condominio"])
            assertDec("1400", it["valor_afecto"])
        }
    }

    @Test
    fun `a condomino moved to another secuencia leaves its old condominio's predio as it is`() {
        val org = conCatastro()
        val a = declarar(org)
        val b = condomino(a, org)

        put(
            "/api/srtm/declaraciones/$a",
            fields(declaracion(a, org)) + mapOf("secuencia_uso" to "2", "area_terreno" to 300, "porcentaje_condominio" to 100),
            org
        )

        declaracion(b, org).let {
            assertDec("200", it["area_terreno"])
            assertDec("2000", it["valor_autoavaluo"])
        }
    }

    @Test
    fun `an annulled or sustituida condomino gets no replica, the rectificatoria does`() {
        val org = conCatastro()
        val a = declarar(org)
        val b = condomino(a, org, 30)
        put("/api/srtm/declaraciones/$a", fields(declaracion(a, org)) + ("porcentaje_condominio" to 40), org)
        val c = condomino(a, org, 30)
        send("POST", "/api/srtm/declaraciones/$c/anular", mapOf("motivo_anulacion" to "Declarada dos veces"), HttpStatus.OK, org)
        val r = tree(send("POST", "/api/srtm/declaraciones/$a/rectificar", null, HttpStatus.CREATED, org))["declaracion"]["id"].asString()

        piso(b, org, 1, 100)

        assertEquals(1, filas(r, "niveles", org).size)
        assertTrue(filas(a, "niveles", org).isEmpty())
        assertTrue(filas(c, "niveles", org).isEmpty())
        assertDec("12000", declaracion(r, org)["valor_autoavaluo"])
        assertDec("2000", declaracion(a, org)["valor_autoavaluo"])
    }

    @Test
    fun `a replica marks the valuation de origen of the condomino it reaches`() {
        val org = conCatastro()
        val a = declarar(org)
        val b = condomino(a, org)
        // b's valuation, as an external padrón brings it
        send(
            "PUT",
            "/api/objects/declaracion_predial/records/$b",
            mapOf("attributes" to fields(registro("declaracion_predial", b, org)) + ("arancel" to 25)),
            HttpStatus.OK,
            org
        )

        piso(a, org, 1, 100)

        assertEquals(true, registro("declaracion_predial", b, org)["origen_modificado"].asBoolean())
        assertTrue(registro("declaracion_predial", a, org)["origen_modificado"].let { it == null || it.isNull })
    }

    @Test
    fun `an imported row without fila pairs by its natural key, and an ambiguous one is left alone`() {
        val org = conCatastro()
        val a = declarar(org)
        val b = condomino(a, org)
        val nivel = NIVEL - "numero_piso" - "area_construida"
        val deA = importada("nivel_construccion", a, nivel + mapOf("numero_piso" to 1, "area_construida" to 100), org)
        val deB = importada("nivel_construccion", b, nivel + mapOf("numero_piso" to 1, "area_construida" to 100), org)
        val segundoDeA = importada("nivel_construccion", a, nivel + mapOf("numero_piso" to 2, "area_construida" to 50), org)
        importada("nivel_construccion", b, nivel + mapOf("numero_piso" to 2, "area_construida" to 50), org)
        importada("nivel_construccion", b, nivel + mapOf("numero_piso" to 2, "area_construida" to 30), org)

        put("/api/srtm/niveles/$deA", NIVEL + mapOf("numero_piso" to 1, "area_construida" to 90), org)

        val fila = registro("nivel_construccion", deA, org)["fila"].asString()
        registro("nivel_construccion", deB, org).let {
            assertEquals(fila, it["fila"].asString())
            assertDec("90", it["area_construida"])
        }

        // two pisos 2 in b: which one is not known
        put("/api/srtm/niveles/$segundoDeA", NIVEL + mapOf("numero_piso" to 2, "area_construida" to 60), org)

        val pisos2 = filas(b, "niveles", org).filter { it["numero_piso"].asInt() == 2 }
        assertEquals(listOf("50", "30"), pisos2.map { dec(it["area_construida"])!!.stripTrailingZeros().toPlainString() })
        assertTrue(pisos2.all { it["fila"] == null || it["fila"].isNull }, pisos2.toString())
        assertDec("170", declaracion(b, org)["area_construida"])

        send("DELETE", "/api/srtm/niveles/$deA", null, HttpStatus.NO_CONTENT, org)
        assertEquals(listOf(2, 2), filas(b, "niveles", org).map { it["numero_piso"].asInt() })
    }

    @Test
    fun `a rectificatoria is valued once, its rows keep their fila`() {
        val org = conCatastro()
        val id = declarar(org)
        val nivel = piso(id, org, 1, 100)
        piso(id, org, 2, 50)
        post("/api/srtm/declaraciones/$id/obras", OBRA, org)
        post("/api/srtm/declaraciones/$id/frentes", FRENTE, org)
        val fila = tree(send("GET", "/api/srtm/declaraciones/$id/niveles", null, HttpStatus.OK, org)).first { it["id"].asString() == nivel }["fila"]
        assertNotNull(fila?.takeUnless { it.isNull }, "a nivel added has its fila")
        PEDIDOS.set(0)

        val r = tree(send("POST", "/api/srtm/declaraciones/$id/rectificar", null, HttpStatus.CREATED, org))["declaracion"]

        assertEquals(1, PEDIDOS.get())
        val rid = r["id"].asString()
        declaracion(rid, org).let {
            assertDec("150", it["area_construida"])
            assertDec("17000", it["valor_autoavaluo"])
            assertDec("4", it["longitud_frente"])
        }
        val niveles = tree(send("GET", "/api/srtm/declaraciones/$rid/niveles", null, HttpStatus.OK, org))
        val filas =
            niveles
                .iterator()
                .asSequence()
                .map { it["fila"].asString() }
                .toList()
        assertTrue(fila!!.asString() in filas, filas.toString())
    }

    // #235: an Ica DJ imported with its clase and sub clase but no uso, its floor with the padrón's clasificación,
    // rectified with a new floor that has none: catastro has no uso to depreciate it with (D1) unless the uso is the
    // sub clase's code
    @Test
    fun `a rectificatoria of a dj without uso values a new floor with its sub clase's uso`() {
        val org = conCatastro()
        val id = declarar(org, mapOf("clase_uso" to "COMERCIAL", "sub_clase_uso" to "TIENDAS", "uso" to null))
        val nivel = piso(id, org, 1, 100)
        val padron = registro("nivel_construccion", nivel, org)
        send(
            "PUT",
            "/api/objects/nivel_construccion/records/$nivel",
            mapOf("attributes" to fields(padron) + (CLASIFICACION to TIENDAS)),
            HttpStatus.OK,
            org
        )
        send("POST", "/api/srtm/declaraciones/$id/recalcular", null, HttpStatus.OK, org)
        assertDec("12000", declaracion(id, org)["valor_autoavaluo"])
        val rid = tree(send("POST", "/api/srtm/declaraciones/$id/rectificar", null, HttpStatus.CREATED, org))["declaracion"]["id"].asString()

        piso(rid, org, 2, 50)
        send("POST", "/api/srtm/declaraciones/$rid/recalcular", null, HttpStatus.OK, org)

        assertEquals("020300", JSON.readTree(PEDIDO.get())["uso"]?.asString(), PEDIDO.get())
        declaracion(rid, org).let {
            assertDec("150", it["area_construida"])
            assertDec("17000", it["valor_autoavaluo"])
            assertFalse(it["autoavaluo_por_revisar"]?.asBoolean() ?: false, it.toString())
        }
    }

    // #241: a nivel added in the portal carries its own clasificación, the RNT tabla catastro depreciates it with: a DJ
    // without uso is valued with it (D1). an edit that does not send it keeps it, a blank one clears it, and one out
    // of the padrón's enum is refused
    @Test
    fun `a nivel added in the portal carries its own clasificacion, which catastro depreciates it with`() {
        val org = conCatastro()
        val id = declarar(org, mapOf("clase_uso" to null, "sub_clase_uso" to null, "uso" to null))
        val nivel = post("/api/srtm/declaraciones/$id/niveles", NIVEL + (CLASIFICACION to TIENDAS), org)

        assertEquals(TIENDAS, nivel["clasificacion"].asString())
        assertEquals(TIENDAS, JSON.readTree(PEDIDO.get())["construcciones"][0]["clasificacion"].asString(), PEDIDO.get())
        declaracion(id, org).let {
            assertDec("12000", it["valor_autoavaluo"])
            assertFalse(it["autoavaluo_por_revisar"]?.asBoolean() ?: false, it.toString())
        }
        val nid = nivel["id"].asString()
        assertEquals(TIENDAS, filas(id, "niveles", org).single()["clasificacion"].asString())

        // srtm-ui before #241 does not send it: the nivel keeps it
        put("/api/srtm/niveles/$nid", fields(nivel) - CLASIFICACION + ("area_construida" to 120), org)
        assertEquals(TIENDAS, registro("nivel_construccion", nid, org)[CLASIFICACION].asString())
        assertDec("14000", declaracion(id, org)["valor_autoavaluo"])

        // a blank one clears it: without uso nor clasificación catastro has no tabla
        val limpio = put("/api/srtm/niveles/$nid", fields(nivel) + (CLASIFICACION to ""), org)
        assertTrue(limpio[CLASIFICACION] == null || limpio[CLASIFICACION].isNull, limpio.toString())
        assertTrue(declaracion(id, org)["autoavaluo_por_revisar"].asBoolean())

        for ((metodo, ruta) in listOf("PUT" to "/api/srtm/niveles/$nid", "POST" to "/api/srtm/declaraciones/$id/niveles")) {
            val problema = tree(send(metodo, ruta, fields(nivel) - "id" + (CLASIFICACION to "PALACIO"), HttpStatus.BAD_REQUEST, org))
            assertEquals(CLASIFICACION, problema["errors"][0]["field"].asString(), problema.toString())
        }
        assertEquals(1, filas(id, "niveles", org).size)
    }

    // #237: an Ica DJ with the padrón's arancel (no tramo de vía) and otras instalaciones (#208), rectified and recomputed:
    // catastro still gets that arancel and the obras are still added. another year keeps the obras, not the arancel
    @Test
    fun `a rectificatoria keeps the padron's arancel and otras instalaciones, another year only the obras`() {
        val org = conCatastro()
        val id = declarar(org)
        piso(id, org, 1, 100)
        send(
            "PUT",
            "/api/objects/declaracion_predial/records/$id",
            mapOf("attributes" to fields(registro("declaracion_predial", id, org)) + PADRON_ICA),
            HttpStatus.OK,
            org
        )
        send("POST", "/api/srtm/declaraciones/$id/recalcular", null, HttpStatus.OK, org)
        assertDec("15000", declaracion(id, org)["valor_autoavaluo"])
        assertDec("35.50", JSON.readTree(PEDIDO.get())["arancelM2"])

        val rid = tree(send("POST", "/api/srtm/declaraciones/$id/rectificar", null, HttpStatus.CREATED, org))["declaracion"]["id"].asString()
        assertDec("15000", declaracion(rid, org)["valor_autoavaluo"])
        send("POST", "/api/srtm/declaraciones/$rid/recalcular", null, HttpStatus.OK, org)

        assertDec("35.50", JSON.readTree(PEDIDO.get())["arancelM2"])
        declaracion(rid, org).let {
            assertDec("15000", it["valor_autoavaluo"])
            assertDec("3000", it["valor_obras"])
        }

        val contribuyente = declaracion(rid, org)["contribuyente"].asString()
        val siguiente =
            post(
                "/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas",
                mapOf("predio_id" to declaracion(rid, org)["predio"].asString(), "desde" to rid, "declaracion" to mapOf("anio" to 2027)),
                org
            )["declaracion"]["id"].asString()
        send("POST", "/api/srtm/declaraciones/$siguiente/recalcular", null, HttpStatus.OK, org)

        assertDec(null, JSON.readTree(PEDIDO.get())["arancelM2"])
        declaracion(siguiente, org).let {
            assertDec("15000", it["valor_autoavaluo"])
            assertDec("3000", it["valor_obras"])
        }
    }

    @Test
    fun `a fila is the backend's`() {
        val org = conCatastro()
        val id = declarar(org)
        val propia = UUID.randomUUID().toString()
        val nivel = post("/api/srtm/declaraciones/$id/niveles", NIVEL + ("fila" to propia), org)
        val fila = nivel["fila"].asString()
        assertTrue(fila != propia, "a client's fila is not taken")

        val cambiado = put("/api/srtm/niveles/${nivel["id"].asString()}", NIVEL + mapOf("fila" to propia, "area_construida" to 90), org)
        assertEquals(fila, cambiado["fila"].asString())
    }

    companion object {
        private val SLUG = "derivados-${UUID.randomUUID().toString().take(8)}"
        private var organizacionDelCatastro: String? = null

        private enum class Modo { VALUA, MOTIVO, FALLA }

        private val MODO = AtomicReference(Modo.VALUA)

        // how many valuaciones catastro was asked for
        private val PEDIDOS = AtomicInteger()

        // the last valuación catastro was asked for
        private val PEDIDO = AtomicReference<String>()
        private const val TIENDAS = "TIENDAS DEPOSITOS CENTROS DE RECREACION CLUBES E INSTITUCIONES"
        private val JSON = JsonMapper.builder().build()

        val NIVEL =
            mapOf(
                "tipo_nivel" to "PISO",
                "numero_piso" to 1,
                "anio_construccion" to 2016,
                "mes_construccion" to 1,
                "material" to "CONCRETO",
                "estado_conservacion" to "BUENO",
                "area_construida" to 100,
                "muros_columnas" to "C",
                "techos" to "D",
                "puertas_ventanas" to "E"
            )
        val OBRA = AutoavaluoSugeridoApiTest.OBRA
        val FRENTE = mapOf("tipo_via" to "JIRON", "via" to "AREQUIPA", "numero" to "45", "frontis" to 4)

        // what Ica's padrón brings of a declaración (fictitious): its arancel, without a tramo de vía, and its obras
        val PADRON_ICA =
            mapOf("arancel" to BigDecimal("35.50"), "valor_terreno" to BigDecimal("7100"), "valor_otras_instalaciones" to BigDecimal("3000"))

        private const val SIN_VALOR =
            """{"anio":2026,"version":"valuacion-v2","region":"SELVA","autoavaluo":null,
            "motivo":"Falta el porcentaje de actualización de 2027","llave":"PARAMETRO_FALTANTE:PORCENTAJE_ACTUALIZACION:2027",
            "detalle":null}"""

        private const val SIN_TABLA =
            """{"anio":2026,"version":"valuacion-v2","region":"SELVA","autoavaluo":null,
            "motivo":"No hay tabla de depreciación del RNT para el uso «sin uso» (D1): sin ella lo construido no se deprecia",
            "llave":"TABLA_DE_DEPRECIACION:","detalle":null}"""

        // 10 per m² of terreno, 100 per m² of each construcción. as catastro, without uso a construcción without its
        // clasificación has no tabla to depreciate with (D1)
        private fun valuar(pedido: String): String {
            val p = JSON.readTree(pedido)
            val sinUso = p["uso"] == null || p["uso"].isNull
            if (sinUso && p["construcciones"].any { it["clasificacion"] == null || it["clasificacion"].isNull }) return SIN_TABLA
            val terreno = p["areaTerreno"]?.takeUnless { it.isNull }?.decimalValue() ?: BigDecimal.ZERO
            val construido = p["construcciones"].sumOf { it["areaConstruida"]?.takeUnless { a -> a.isNull }?.decimalValue() ?: BigDecimal.ZERO }
            val autoavaluo = terreno * BigDecimal(10) + construido * BigDecimal(100)
            return """{"anio":2026,"version":"valuacion-v2","region":"SELVA","autoavaluo":$autoavaluo,"motivo":null,"llave":null,"detalle":null}"""
        }

        private val CATASTRO =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/api/auth/token") { ex ->
                    ex.requestBody.readAllBytes()
                    responder(ex, 200, """{"token":"t-derivados","expiresAt":"2999-01-01T00:00:00Z","serviceAccount":"srtm"}""")
                }
                createContext("/api/catastro/valuacion") { ex ->
                    val pedido = ex.requestBody.readAllBytes().decodeToString()
                    PEDIDOS.incrementAndGet()
                    PEDIDO.set(pedido)
                    when (MODO.get()) {
                        Modo.VALUA -> responder(ex, 200, valuar(pedido))
                        Modo.MOTIVO -> responder(ex, 200, SIN_VALOR)
                        Modo.FALLA -> responder(ex, 500, "{}")
                    }
                }
                start()
            }

        private fun responder(
            ex: HttpExchange,
            estado: Int,
            cuerpo: String
        ) {
            val bytes = cuerpo.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(estado, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        @JvmStatic
        @DynamicPropertySource
        fun catastro(registro: DynamicPropertyRegistry) {
            registro.add("srtm.catastro.enabled") { true }
            registro.add("srtm.catastro.url") { "http://127.0.0.1:${CATASTRO.address.port}" }
            registro.add("srtm.catastro.client-id") { "srtm-derivados" }
            registro.add("srtm.catastro.client-secret") { "secreto-de-prueba" }
            registro.add("srtm.catastro.organizacion") { SLUG }
        }

        @JvmStatic
        @AfterAll
        fun parar() = CATASTRO.stop(0)
    }
}
