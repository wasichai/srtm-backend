package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import srtm.rentas.SrtmApiTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

// the header of an install with several municipalidades: each organization's PU opens with its own municipalidad
// record, the escudo uploaded in the admin (an IMAGE field of wasichai-files) included. read as the caller, a minute
// per organization (0s in the tests). srtm.municipalidad.escudo is empty here: without a record's escudo, none
class CabeceraApiTest : SrtmApiTest() {
    @Value("\${wasichai.files.local.path}")
    lateinit var archivos: String

    @Test
    fun `two organizations print each its own name and escudo`() {
        val x = otraOrganizacion()
        val y = otraOrganizacion()
        subirEscudo(municipalidad(mapOf("nombre" to "MUNICIPALIDAD DE EQUIS"), x), imagen("png", 4, 4), token = x)
        subirEscudo(municipalidad(mapOf("nombre" to "MUNICIPALIDAD DE YE"), y), imagen("jpg", 6, 3), "escudo.jpg", y)

        val enX = pu(x)
        val enY = pu(y)

        assertTrue("MUNICIPALIDAD DE EQUIS" in texto(enX) && "MUNICIPALIDAD DE YE" !in texto(enX), texto(enX))
        assertTrue("MUNICIPALIDAD DE YE" in texto(enY) && "MUNICIPALIDAD DE EQUIS" !in texto(enY), texto(enY))
        assertEquals(listOf("4x4"), tamanosDeImagenes(enX))
        assertEquals(listOf("6x3"), tamanosDeImagenes(enY))
    }

    @Test
    fun `a new escudo shows in the next document`() {
        val x = otraOrganizacion()
        val id = municipalidad(mapOf("nombre" to "MUNICIPALIDAD DE EQUIS"), x)
        subirEscudo(id, imagen("png", 4, 4), token = x)
        assertEquals(listOf("4x4"), tamanosDeImagenes(pu(x)))

        subirEscudo(id, imagen("png", 8, 2), token = x)

        assertEquals(listOf("8x2"), tamanosDeImagenes(pu(x)))
    }

    @Test
    fun `a record without escudo prints none, and an organization without record its own name`() {
        val x = otraOrganizacion()
        val sinRegistro = pu(x)
        val nombre = tree(send("GET", "/api/organizations/current", null, HttpStatus.OK, x))["name"].asString()
        assertTrue(nombre.uppercase() in texto(sinRegistro), texto(sinRegistro))
        assertEquals(0, imagenes(sinRegistro))

        municipalidad(mapOf("nombre" to "MUNICIPALIDAD SIN ESCUDO"), x)

        val sinEscudo = pu(x)
        assertTrue("MUNICIPALIDAD SIN ESCUDO" in texto(sinEscudo), texto(sinEscudo))
        assertEquals(0, imagenes(sinEscudo))
    }

    @Test
    fun `an escudo the store lost prints none and never fails the pu`() {
        val x = otraOrganizacion()
        val escudo = subirEscudo(municipalidad(mapOf("nombre" to "MUNICIPALIDAD DE EQUIS"), x), imagen("png", 4, 4), token = x)
        val organizacion = tree(send("GET", "/api/organizations/current", null, HttpStatus.OK, x))["id"].asString()
        // the local store's key: <organization id>/<file id>
        assertTrue(Files.deleteIfExists(Path.of(archivos, organizacion, escudo["id"].asString())))

        val pu = pu(x)

        assertTrue("MUNICIPALIDAD DE EQUIS" in texto(pu), texto(pu))
        assertEquals(0, imagenes(pu))
    }

    @Test
    fun `the escudo takes only a png or a jpg`() {
        val x = otraOrganizacion()
        val id = municipalidad(mapOf("nombre" to "MUNICIPALIDAD DE EQUIS"), x)
        val (status, cuerpo) = subida(id, imagen("gif", 4, 4), "escudo.gif", x)
        assertEquals(HttpStatus.BAD_REQUEST, status, cuerpo)
        assertTrue("image/gif" in cuerpo, cuerpo)
    }

    // the PU of a new predio of `token`'s organization, declared in 2026 by a new contribuyente
    private fun pu(token: String): ByteArray {
        val predio =
            post("/api/srtm/predios", mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO"), token)
        val titular = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), token)
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to titular["id"].asString(),
                "predio" to predio["id"].asString(),
                "anio" to 2026,
                "secuencia_uso" to "1",
                "valor_autoavaluo" to 10000.50,
                "deduccion" to 0
            ),
            token
        )
        val result =
            client
                .get()
                .uri("/api/srtm/predios/${predio["id"].asString()}/pu?anio=2026")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectBody(ByteArray::class.java)
                .returnResult()
        val cuerpo = result.responseBody ?: ByteArray(0)
        assertEquals(HttpStatus.OK, HttpStatus.valueOf(result.status.value()), String(cuerpo))
        return cuerpo
    }

    private fun imagen(
        formato: String,
        ancho: Int,
        alto: Int
    ): ByteArray {
        val img = BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(img, formato, it) }.toByteArray()
    }
}
