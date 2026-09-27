package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// the padrón as model/import_predios.py imports it (and model/normalizar_padron.py leaves it), through the portal:
// buscar en tributario by its types and kilómetro, its ubicación saved with the srtm's abbreviations, and the
// padrón's format of the secuencia de uso
class PadronNormalizadoApiTest : SrtmApiTest() {
    @Test
    fun `buscar en tributario finds a padron predio by its tipo de via, zona and kilometro`() {
        val via = "LIMA ${uniqueDocumento()}"
        val codigo = post("/api/objects/predio/records", mapOf("attributes" to padron(via)))["attributes"]["codigo"].asString()

        get("/api/srtm/predios/buscar?tipo_via=JIRON&via=$via&tipo_zona=CERCADO&kilometro=23.5")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].codigo")
            .isEqualTo(codigo)
        get("/api/srtm/predios/buscar?tipo_via=CALLE&via=$via")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(0)
        get("/api/srtm/predios/buscar?tipo_zona=URBANIZACION&via=$via")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(0)
    }

    @Test
    fun `a padron predio's ubicacion saved in the portal reads as the srtm writes it, its type once`() {
        val via = "LIMA ${uniqueDocumento()}"
        val id = post("/api/objects/predio/records", mapOf("attributes" to padron(via)))["id"].asString()

        val saved = put("/api/srtm/predios/$id", padron(via) + PERENE)
        assertEquals("JR. $via, N° 12, MZ. A, LT. 5, KM. 23.5, CERCADO II MESETA, JUNIN-CHANCHAMAYO-PERENE", saved["direccion"].asString())

        // one saved before normalizar_padron.py ran: the vía still carries its type
        val antes = put("/api/srtm/predios/$id", padron(via) + PERENE + ("via" to "JIRON $via"))
        assertEquals("JIRON $via, N° 12, MZ. A, LT. 5, KM. 23.5, CERCADO II MESETA, JUNIN-CHANCHAMAYO-PERENE", antes["direccion"].asString())
    }

    @Test
    fun `a declaration's secuencia de uso has the padron's three digits`() {
        val contribuyente = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()))["id"].asString()
        val dj =
            post(
                "/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas",
                mapOf(
                    "declaracion" to mapOf("tipo_adquisicion" to "COMPRA", "fecha_adquisicion" to "2024-09-04", "fecha_presentacion" to "2026-09-24"),
                    "predio" to
                        mapOf(
                            "sector_catastral" to uniqueDocumento().take(2),
                            "manzana_catastral" to uniqueDocumento().take(2),
                            "condicion" to "URBANO",
                            "tipo_via" to "AVENIDA",
                            "via" to "MARGINAL"
                        ) + PERENE
                )
            )
        val declaracion = dj["declaracion"]
        assertEquals("001", declaracion["secuencia_uso"].asString())

        val edited = put("/api/srtm/declaraciones/${declaracion["id"].asString()}", fields(declaracion) + ("secuencia_uso" to "2"))
        assertEquals("002", edited["secuencia_uso"].asString())
    }

    // a predio as model/import_predios.py creates it from "JIRON <via> Nro.: 12 Mz.: A Lt.: 5 Km.: 23.5 CERCADO II MESETA"
    private fun padron(via: String): Map<String, Any?> =
        mapOf(
            "codigo" to "P-${uniqueDocumento()}",
            "condicion" to "URBANO",
            "direccion" to "JIRON $via Nro.: 12 Mz.: A Lt.: 5 Km.: 23.5 CERCADO II MESETA",
            "tipo_via" to "JIRON",
            "via" to via,
            "numero" to "12",
            "manzana" to "A",
            "lote" to "5",
            "kilometro" to "23.5",
            "tipo_zona" to "CERCADO",
            "habilitacion_urbana" to "II MESETA"
        )

    private companion object {
        val PERENE = mapOf("departamento" to "JUNIN", "provincia" to "CHANCHAMAYO", "distrito" to "PERENE")
    }
}
