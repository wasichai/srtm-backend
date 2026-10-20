package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// what the portal's shell gets of the caller's organization: the header's name, oficina and escudo, and where its
// lotes are (the union of each object's extent, its centre) for the map
class MunicipalidadPortalTest {
    @Test
    fun `the extent is the union of every object's, those without lotes left out`() {
        val predios = listOf(-75.23, -10.95, -75.22, -10.94)
        val catastro = listOf(-75.25, -10.96, -75.225, -10.945)

        assertEquals(listOf(-75.25, -10.96, -75.22, -10.94), extensionDe(listOf(predios, null, catastro)))
        assertEquals(predios, extensionDe(listOf(null, predios)))
    }

    @Test
    fun `without lotes there is no extent nor centre`() {
        assertNull(extensionDe(emptyList()))
        assertNull(extensionDe(listOf(null, null)))
        assertNull(centroDe(null))
    }

    @Test
    fun `the centre is the middle of the extent`() {
        val centro = centroDe(listOf(-75.25, -10.96, -75.21, -10.94))!!
        assertEquals(-75.23, centro[0], 1e-9)
        assertEquals(-10.95, centro[1], 1e-9)
    }

    @Test
    fun `the portal shows the header's name, oficina and escudo, never its ruc, gerencia or direccion`() {
        val cabecera =
            Cabecera(
                "MUNICIPALIDAD DE EQUIS",
                oficina = "RENTAS",
                ruc = "20195238961",
                gerencia = "GERENCIA",
                direccion = "JR. LIMA 123",
                escudo = "data:image/png;base64,AA=="
            )
        val extension = listOf(-75.25, -10.96, -75.21, -10.94)
        val lugar = Lugar("120302", "JUNIN", "CHANCHAMAYO", "PERENE", "SELVA")

        val portal = municipalidadPortal(cabecera, extension, lugar)

        assertEquals(
            MunicipalidadPortal("MUNICIPALIDAD DE EQUIS", "RENTAS", "data:image/png;base64,AA==", extension, centroDe(extension), lugar),
            portal
        )
        assertEquals(MunicipalidadPortal("RESPALDO", null, null, null, null, null), municipalidadPortal(Cabecera("RESPALDO"), null, null))
    }
}
