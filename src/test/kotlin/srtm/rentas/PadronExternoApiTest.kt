package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

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
