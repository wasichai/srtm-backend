package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.UUID

// #124: what an external padrón (Ica) brings and the srtm's forms do not ask for. the fields are optional in the
// model and the portal's dtos do not know them: they are written by the importer (core's record api) and a portal
// save, which merges over what is stored, keeps them. ica has no month of construction: a nivel goes without it
class PadronExternoApiTest : SrtmApiTest() {
    @Test
    fun `a nivel without its month of construction is registered`() {
        val nivel = post("/api/srtm/declaraciones/${nuevaDeclaracion()}/niveles", NIVEL)

        assertTrue(nivel["mes_construccion"].isNull, nivel.toString())
        assertEquals(2015, nivel["anio_construccion"].asInt())
    }

    @Test
    fun `the external padron's fields survive a portal save of the predio, the declaracion and the nivel`() {
        val predio = post("/api/srtm/predios", mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO"))
        val declaracion =
            post(
                "/api/srtm/declaraciones",
                mapOf(
                    "contribuyente" to inscribir(),
                    "predio" to predio["id"].asString(),
                    "anio" to 2026,
                    "secuencia_uso" to "1"
                )
            )
        val nivel = post("/api/srtm/declaraciones/${declaracion["id"].asString()}/niveles", NIVEL)
        val cambios =
            listOf(
                Triple(PREDIO, predio, PREDIO_ICA),
                Triple(DECLARACION, declaracion, DECLARACION_ICA),
                Triple(NIVEL_CONSTRUCCION, nivel, NIVEL_ICA)
            )
        for ((objeto, registro, ica) in cambios) importar(objeto, registro["id"].asString(), ica)

        put("/api/srtm/predios/${predio["id"].asString()}", fields(predio) + ("referencia" to "FRENTE AL MERCADO"))
        put("/api/srtm/declaraciones/${declaracion["id"].asString()}", fields(declaracion) + ("area_terreno" to 200))
        put("/api/srtm/niveles/${nivel["id"].asString()}", fields(nivel) + ("area_construida" to 150))

        for ((objeto, registro, ica) in cambios) assertImportado(objeto, registro["id"].asString(), ica)
        assertEquals("FRENTE AL MERCADO", registro(PREDIO, predio["id"].asString())["referencia"].asString())
        assertEquals(150.0, registro(NIVEL_CONSTRUCCION, nivel["id"].asString())["area_construida"].asDouble())
    }

    // a condómino's declaración copies what the source declares of the predio: the padrón's valuation and its niveles'
    // too, although the dtos the copy goes through do not know them
    @Test
    fun `a condomino added from an external padron's declaracion keeps its valuation and that of its niveles`() {
        val origen = nuevaDeclaracion()
        val nivel = post("/api/srtm/declaraciones/$origen/niveles", NIVEL)
        importar(DECLARACION, origen, DECLARACION_ICA)
        importar(NIVEL_CONSTRUCCION, nivel["id"].asString(), NIVEL_ICA)

        val nuevo = post("/api/srtm/declaraciones/$origen/condominos", mapOf("contribuyente" to inscribir(), "porcentaje_condominio" to 25))

        assertImportado(DECLARACION, nuevo["id"].asString(), DECLARACION_ICA)
        val copias = tree(send("GET", "/api/srtm/declaraciones/${nuevo["id"].asString()}/niveles", null, HttpStatus.OK))
        assertEquals(1, copias.size(), copias.toString())
        assertImportado(NIVEL_CONSTRUCCION, copias[0]["id"].asString(), NIVEL_ICA)
    }

    @Test
    fun `a via and a unidad urbana of the catalog carry their code`() {
        val nombre = "ICA ${uniqueDocumento()}"
        val via = crear(VIA, mapOf("tipo_via" to "JIRON", "nombre" to nombre, "codigo" to "V0123"))
        val unidad = crear(UNIDAD_URBANA, mapOf("tipo_unidad_urbana" to "URBANIZACION", "nombre" to "ICA ${uniqueDocumento()}", "codigo" to "U045"))

        assertEquals("V0123", registro(VIA, via)["codigo"].asString())
        assertEquals("U045", registro(UNIDAD_URBANA, unidad)["codigo"].asString())
        // the portal's catalog still lists it, by its name
        val vias = tree(send("GET", "/api/srtm/vias?q=$nombre&tipo=JIRON", null, HttpStatus.OK))
        assertEquals(1, vias["totalElements"].asInt(), vias.toString())
        assertEquals(nombre, vias["content"][0]["nombre"].asString(), vias.toString())
    }

    // #132: what the dtos do not carry, read back for the portal to show. only the fields with a value, and only the
    // niveles that have any
    @Test
    fun `the valuacion of the external padron is read with its predio and its niveles`() {
        val (declaracion, predio) = declaracionConPredio()
        val importado = post("/api/srtm/declaraciones/$declaracion/niveles", NIVEL)["id"].asString()
        val propio = post("/api/srtm/declaraciones/$declaracion/niveles", NIVEL + ("numero_piso" to 2))["id"].asString()
        importar(PREDIO, predio, PREDIO_ICA - "codigo_via_armonizacion")
        importar(DECLARACION, declaracion, DECLARACION_ICA)
        importar(NIVEL_CONSTRUCCION, importado, NIVEL_ICA)

        val origen = tree(send("GET", "/api/srtm/declaraciones/$declaracion/valuacion-origen", null, HttpStatus.OK))

        assertValores(PREDIO_ICA - "codigo_via_armonizacion", origen["predio"])
        assertValores(DECLARACION_ICA, origen["declaracion"])
        assertEquals(setOf(importado), origen["niveles"].propertyNames().toSet(), origen.toString())
        assertValores(NIVEL_ICA, origen["niveles"][importado])
        assertTrue(origen["niveles"][propio] == null, origen.toString())
    }

    @Test
    fun `a declaracion with nothing of an external padron has empty blocks`() {
        val declaracion = nuevaDeclaracion()
        post("/api/srtm/declaraciones/$declaracion/niveles", NIVEL)

        val origen = tree(send("GET", "/api/srtm/declaraciones/$declaracion/valuacion-origen", null, HttpStatus.OK))

        for (bloque in listOf("predio", "declaracion", "niveles")) assertEquals(0, origen[bloque].size(), origen.toString())
    }

    @Test
    fun `the valuacion of an unknown declaracion is a 404`() {
        send("GET", "/api/srtm/declaraciones/${UUID.randomUUID()}/valuacion-origen", null, HttpStatus.NOT_FOUND)
    }

    // the same checks as the declaración's ficha: core's, on the records read
    @Test
    fun `a role that cannot read the declaracion is refused its valuacion as its ficha`() {
        val declaracion = nuevaDeclaracion()
        importar(DECLARACION, declaracion, DECLARACION_ICA)
        val funcionario = funcionario(listOf(permiso(PREDIO, "READ"), permiso(NIVEL_CONSTRUCCION, "READ")))

        val ficha = exchange("GET", "/api/srtm/declaraciones/$declaracion", null, funcionario)
        val origen = exchange("GET", "/api/srtm/declaraciones/$declaracion/valuacion-origen", null, funcionario)

        assertEquals(HttpStatus.FORBIDDEN, ficha.first, ficha.second)
        assertEquals(ficha.first, origen.first, origen.second)
    }

    // the predio's ficha, without a declaración, carries the predio's block too
    @Test
    fun `the predio's ficha carries the codes of the external padron`() {
        val (_, predio) = declaracionConPredio()
        importar(PREDIO, predio, PREDIO_ICA)

        val ficha = tree(send("GET", "/api/srtm/predios/$predio", null, HttpStatus.OK))

        assertValores(PREDIO_ICA, ficha["valuacion_origen"]["predio"])
        val sinPadron = tree(send("GET", "/api/srtm/predios/${predio()}", null, HttpStatus.OK))
        assertEquals(0, sinPadron["valuacion_origen"]["predio"].size(), sinPadron.toString())
    }

    // #148: the valuation is the padrón's, frozen, and the portal's saves keep it. once the declaración or its niveles,
    // obras or frentes change, it says so (modificada); the motivo is the portal's own (MotivoApiTest)
    @Test
    fun `an edit of a declaracion with an external padron's valuation marks it modificada`() {
        val declaracion = nuevaDeclaracion()
        importar(DECLARACION, declaracion, DECLARACION_ICA)
        assertFalse(modificada(declaracion))

        put("/api/srtm/declaraciones/$declaracion", fields(ficha(declaracion)) + ("otros_datos" to "EDITADA"))

        assertTrue(modificada(declaracion))
        assertValores(DECLARACION_ICA, valuacion(declaracion)["declaracion"])
    }

    // the valuation only on a nivel: the declaración's own fields are empty
    @Test
    fun `every write of the niveles, obras and frentes of a declaracion with a valuation marks it, not its motivo`() {
        val escrituras =
            mapOf<String, (String, JsonNode, JsonNode, JsonNode) -> Unit>(
                "agregar nivel" to { d, _, _, _ -> post("/api/srtm/declaraciones/$d/niveles", NIVEL + ("numero_piso" to 2)) },
                "cambiar nivel" to { _, n, _, _ -> put("/api/srtm/niveles/${n["id"].asString()}", fields(n) + ("area_construida" to 150)) },
                "quitar nivel" to { _, n, _, _ -> delete("/api/srtm/niveles/${n["id"].asString()}") },
                "agregar obra" to { d, _, _, _ -> post("/api/srtm/declaraciones/$d/obras", OBRA) },
                "cambiar obra" to { _, _, o, _ -> put("/api/srtm/obras/${o["id"].asString()}", fields(o) + ("cantidad" to 3)) },
                "quitar obra" to { _, _, o, _ -> delete("/api/srtm/obras/${o["id"].asString()}") },
                "agregar frente" to { d, _, _, _ -> post("/api/srtm/declaraciones/$d/frentes", FRENTE) },
                "cambiar frente" to { _, _, _, f -> put("/api/srtm/frentes/${f["id"].asString()}", fields(f) + ("frontis" to 9.5)) },
                "quitar frente" to { _, _, _, f -> delete("/api/srtm/frentes/${f["id"].asString()}") }
            )
        for ((escritura, escribir) in escrituras) {
            val declaracion = nuevaDeclaracion()
            val nivel = post("/api/srtm/declaraciones/$declaracion/niveles", NIVEL)
            val obra = post("/api/srtm/declaraciones/$declaracion/obras", OBRA)
            val frente = post("/api/srtm/declaraciones/$declaracion/frentes", FRENTE)
            importar(NIVEL_CONSTRUCCION, nivel["id"].asString(), NIVEL_ICA)
            assertFalse(modificada(declaracion), escritura)

            escribir(declaracion, nivel, obra, frente)

            assertTrue(modificada(declaracion), escritura)
            assertEquals("INSCRIPCION", registro(DECLARACION, declaracion)["motivo"].asString(), escritura)
        }
    }

    @Test
    fun `a declaracion without an external padron's valuation is never marked`() {
        val declaracion = nuevaDeclaracion()
        val nivel = post("/api/srtm/declaraciones/$declaracion/niveles", NIVEL)
        post("/api/srtm/declaraciones/$declaracion/obras", OBRA)
        put("/api/srtm/niveles/${nivel["id"].asString()}", fields(nivel) + ("area_construida" to 150))
        put("/api/srtm/declaraciones/$declaracion", fields(ficha(declaracion)) + ("otros_datos" to "EDITADA"))

        assertFalse(modificada(declaracion))
        assertTrue(registro(DECLARACION, declaracion)["origen_modificado"].let { it == null || it.isNull })
    }

    // a condómino's copy is faithful to its source: the valuation and the mark as the source has them, and copying
    // its niveles, obras and frentes is no edit of either
    @Test
    fun `a condomino's copy takes the valuation's mark from its source`() {
        val (fuente, _) = conValuacion()
        val fiel = condomino(fuente)
        assertFalse(modificada(fiel))
        assertFalse(modificada(fuente))

        val (editada, nivel) = conValuacion()
        put("/api/srtm/niveles/${nivel["id"].asString()}", fields(nivel) + ("area_construida" to 150))
        assertTrue(modificada(condomino(editada)))
        assertFalse(modificada(fiel))
    }

    // a declaración with two niveles, an obra and a frente, and the valuation of an external padrón on it and on its
    // first nivel
    private fun conValuacion(): Pair<String, JsonNode> {
        val declaracion = nuevaDeclaracion()
        val nivel = post("/api/srtm/declaraciones/$declaracion/niveles", NIVEL)
        post("/api/srtm/declaraciones/$declaracion/niveles", NIVEL + ("numero_piso" to 2))
        post("/api/srtm/declaraciones/$declaracion/obras", OBRA)
        post("/api/srtm/declaraciones/$declaracion/frentes", FRENTE)
        importar(DECLARACION, declaracion, DECLARACION_ICA)
        importar(NIVEL_CONSTRUCCION, nivel["id"].asString(), NIVEL_ICA)
        return declaracion to nivel
    }

    private fun condomino(declaracion: String): String =
        post("/api/srtm/declaraciones/$declaracion/condominos", mapOf("contribuyente" to inscribir(), "porcentaje_condominio" to 25))["id"].asString()

    private fun ficha(declaracion: String) = tree(send("GET", "/api/srtm/declaraciones/$declaracion", null, HttpStatus.OK))["declaracion"]

    private fun valuacion(declaracion: String) = tree(send("GET", "/api/srtm/declaraciones/$declaracion/valuacion-origen", null, HttpStatus.OK))

    private fun modificada(declaracion: String): Boolean {
        val origen = valuacion(declaracion)
        assertTrue(origen["modificada"]?.isBoolean == true, origen.toString())
        return origen["modificada"].asBoolean()
    }

    private fun declaracionConPredio(): Pair<String, String> {
        val declaracion = nuevaDeclaracion()
        return declaracion to registro(DECLARACION, declaracion)["predio"].asString()
    }

    // exactly these fields, a decimal compared by value
    private fun assertValores(
        esperado: Map<String, Any>,
        leido: JsonNode
    ) {
        assertEquals(esperado.keys, leido.propertyNames().toSet(), leido.toString())
        for ((campo, valor) in esperado) {
            if (valor is BigDecimal) {
                assertEquals(0, valor.compareTo(leido[campo].decimalValue()), "$campo: $leido")
            } else {
                assertEquals(valor, leido[campo].asString(), "$campo: $leido")
            }
        }
    }

    // what the importer does: core's record api, whose update replaces every field (so it sends them all)
    private fun importar(
        objeto: String,
        id: String,
        ica: Map<String, Any>
    ) {
        send("PUT", "/api/objects/$objeto/records/$id", mapOf("attributes" to fields(registro(objeto, id)) + ica), HttpStatus.OK)
    }

    // a decimal is compared by value: core may hand it back with another scale
    private fun assertImportado(
        objeto: String,
        id: String,
        ica: Map<String, Any>
    ) {
        val guardado = registro(objeto, id)
        for ((campo, valor) in ica) {
            val leido = guardado[campo]
            if (valor is BigDecimal) {
                assertEquals(0, valor.compareTo(leido.decimalValue()), "$objeto.$campo: $guardado")
            } else {
                assertEquals(valor, leido.asString(), "$objeto.$campo: $guardado")
            }
        }
    }

    private fun crear(
        objeto: String,
        attributes: Map<String, Any?>
    ): String = tree(send("POST", "/api/objects/$objeto/records", mapOf("attributes" to attributes), HttpStatus.CREATED))["id"].asString()

    private fun registro(
        objeto: String,
        id: String
    ): JsonNode = tree(send("GET", "/api/objects/$objeto/records/$id", null, HttpStatus.OK))["attributes"]

    private companion object {
        // no mes_construccion: ica does not bring it
        val NIVEL =
            mapOf(
                "tipo_nivel" to "PISO",
                "numero_piso" to 1,
                "anio_construccion" to 2015,
                "material" to "LADRILLO",
                "estado_conservacion" to "BUENO",
                "area_construida" to 120
            )
        val OBRA =
            mapOf(
                "ingreso" to "POR CATEGORIAS",
                "material" to "LADRILLO",
                "tipo_obra" to "MUROS PERIMETRICOS O CERCOS",
                "estado_conservacion" to "BUENO",
                "anio_construccion" to 2024,
                "mes_construccion" to 2,
                "numero_piso" to 1,
                "cantidad" to 2,
                "metrado" to 50
            )
        val FRENTE = mapOf("tipo_via" to "AVENIDA", "via" to "ANDRES AVELINO CACERES", "frontis" to 7, "lado" to "IMPAR")
        val PREDIO_ICA =
            mapOf("codigo_via" to "V0123", "codigo_unidad_urbana" to "U045", "codigo_hu_armonizacion" to "HU-01", "codigo_via_armonizacion" to "VA-02")
        val DECLARACION_ICA =
            mapOf(
                "arancel" to BigDecimal("35.50"),
                "valor_terreno" to BigDecimal("7100"),
                "valor_construccion" to BigDecimal("48250.75"),
                "valor_otras_instalaciones" to BigDecimal("1200")
            )
        val NIVEL_ICA =
            mapOf(
                "clasificacion" to "CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA",
                "valor_unitario" to BigDecimal("402.08"),
                "incremento" to BigDecimal("0"),
                "porcentaje_depreciacion" to BigDecimal("12"),
                "valor_total" to BigDecimal("48250.75")
            )
    }
}
