package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

// GET /api/srtm/municipalidad: the portal's header and map, of the caller's organization only. the header is the
// documents' (CabeceraDocumento: its record, else the fallback name; its escudo as a data: uri); the extent and the
// lugar come from the organization's own predios and catastro fiscal
class MunicipalidadApiTest : SrtmApiTest() {
    @Test
    fun `two organizations see each its own municipalidad, escudo, lotes and lugar`() {
        val x = otraOrganizacion()
        val y = otraOrganizacion()
        subirEscudo(municipalidad(mapOf("nombre" to "MUNICIPALIDAD DE EQUIS", "oficina" to "RENTAS"), x), png(), token = x)
        municipalidad(mapOf("nombre" to "MUNICIPALIDAD DE YE"), y)
        predio(x, "120302", "JUNIN", "CHANCHAMAYO", "PERENE", "SELVA", cuadrado(-75.2250, -10.9480))
        predio(x, "120302", "JUNIN", "CHANCHAMAYO", "PERENE", "SELVA", cuadrado(-75.2200, -10.9450))
        predio(x, "120301", "JUNIN", "CHANCHAMAYO", "CHANCHAMAYO", "SELVA", null)
        lote(x, cuadrado(-75.2300, -10.9500))
        predio(y, "110101", "ICA", "ICA", "ICA", "COSTA", cuadrado(-75.7300, -14.0700))

        val enX = municipalidadDe(x)
        val enY = municipalidadDe(y)

        assertEquals("MUNICIPALIDAD DE EQUIS", enX["nombre"].asString())
        assertEquals("RENTAS", enX["oficina"].asString())
        assertTrue(enX["escudo"].asString().startsWith("data:image/png;base64,"), enX.toString())
        // the predios' and the lote's polygons together
        assertExtension(listOf(-75.2300, -10.9500, -75.2195, -10.9445), enX)
        assertEquals(-75.22475, enX["centro"][0].asDouble(), 1e-4)
        // the ubigeo most of its predios have
        assertEquals("120302", enX["lugar"]["ubigeo"].asString())
        assertEquals("PERENE", enX["lugar"]["distrito"].asString())
        assertEquals("SELVA", enX["lugar"]["region"].asString())

        assertEquals("MUNICIPALIDAD DE YE", enY["nombre"].asString())
        assertTrue(vacio(enY, "oficina") && vacio(enY, "escudo"), enY.toString())
        assertExtension(listOf(-75.7300, -14.0700, -75.7295, -14.0695), enY)
        assertEquals("110101", enY["lugar"]["ubigeo"].asString())
        assertEquals("COSTA", enY["lugar"]["region"].asString())
    }

    @Test
    fun `an organization without record, predios nor lotes gets its own name and nothing else`() {
        val x = otraOrganizacion()
        val nombre = tree(send("GET", "/api/organizations/current", null, HttpStatus.OK, x))["name"].asString()

        val enX = municipalidadDe(x)

        assertEquals(nombre, enX["nombre"].asString())
        for (campo in listOf("oficina", "escudo", "extension", "centro", "lugar")) assertTrue(vacio(enX, campo), "$campo: $enX")
    }

    @Test
    fun `it needs read on municipalidad, and shows the lotes only to who may read them`() {
        predio(token, "120302", "JUNIN", "CHANCHAMAYO", "PERENE", "SELVA", cuadrado(-75.2250, -10.9480))
        val sinPermiso = funcionario(listOf(permiso("predio", "READ")))
        val soloMunicipalidad = funcionario(listOf(permiso("municipalidad", "READ")))

        val problema = tree(send("GET", "/api/srtm/municipalidad", null, HttpStatus.FORBIDDEN, sinPermiso))
        assertTrue("municipalidad" in problema["detail"].asString(), problema.toString())

        val enSolo = municipalidadDe(soloMunicipalidad)
        assertTrue(vacio(enSolo, "extension") && vacio(enSolo, "lugar"), enSolo.toString())
        // the admin reads the predios: the same organization's extent
        assertTrue(!vacio(municipalidadDe(token), "extension"))
    }

    private fun municipalidadDe(token: String): JsonNode = tree(send("GET", "/api/srtm/municipalidad", null, HttpStatus.OK, token))

    // null or left out
    private fun vacio(
        nodo: JsonNode,
        campo: String
    ) = nodo[campo]?.isNull ?: true

    private fun assertExtension(
        esperada: List<Double>,
        municipalidad: JsonNode
    ) {
        val extension = municipalidad["extension"]
        assertEquals(4, extension.size(), municipalidad.toString())
        esperada.forEachIndexed { i, v -> assertEquals(v, extension[i].asDouble(), 1e-4, "extension[$i]: $municipalidad") }
    }

    private fun predio(
        token: String,
        ubigeo: String,
        departamento: String,
        provincia: String,
        distrito: String,
        region: String,
        geometria: Map<String, Any?>?
    ) {
        post(
            "/api/srtm/predios",
            mapOf(
                "codigo" to "T-${uniqueDocumento()}",
                "direccion" to "JR. LIMA 123",
                "tipo_predio" to "PREDIO URBANO",
                "ubigeo" to ubigeo,
                "departamento" to departamento,
                "provincia" to provincia,
                "distrito" to distrito,
                "region" to region,
                "lote_geom" to geometria
            ),
            token
        )
    }

    private fun lote(
        token: String,
        geometria: Map<String, Any?>
    ) {
        post(
            "/api/srtm/catastro",
            mapOf("codigo_cpu" to "CPU-${uniqueDocumento()}", "tipo_predio" to "PREDIO URBANO", "ubigeo" to "120302", "lote_geom" to geometria),
            token
        )
    }

    private fun png(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private companion object {
        // a lote of 0.0005° a side from its south-west corner, GeoJSON in EPSG:4326
        fun cuadrado(
            oeste: Double,
            sur: Double
        ) = mapOf(
            "type" to "Polygon",
            "coordinates" to
                listOf(
                    listOf(
                        listOf(oeste, sur),
                        listOf(oeste + 0.0005, sur),
                        listOf(oeste + 0.0005, sur + 0.0005),
                        listOf(oeste, sur + 0.0005),
                        listOf(oeste, sur)
                    )
                )
        )
    }
}
