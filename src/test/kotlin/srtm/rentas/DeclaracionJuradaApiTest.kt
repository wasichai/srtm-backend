package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID

// presenting a declaración jurada from its contribuyente (pages 10-14) when it goes wrong: DeclaracionService.presentar
// registers the new predio first and removes it again when the declaración is refused, so a refused one leaves
// nothing behind; a predio that already existed stays as it was. the happy paths are RentasApiTest's and
// CodigoPredioApiTest's
class DeclaracionJuradaApiTest : SrtmApiTest() {
    @Test
    fun `a declaracion refused after its new predio was registered takes that predio away again`() {
        val contribuyente = inscribir()
        val sector = uniqueDocumento().take(4)
        val manzana = uniqueDocumento().take(2)
        val predio = mapOf("sector_catastral" to sector, "manzana_catastral" to manzana, "tipo_predio" to "PREDIO URBANO", "direccion" to "S/N")
        val antes = resumen()

        // core refuses the declaración (an option its enum lacks) once the predio is in
        rejected("POST", presentar(contribuyente), mapOf("declaracion" to declaracion("tipo_adquisicion" to "TRUEQUE"), "predio" to predio), "tipo_adquisicion")

        val despues = resumen()
        assertEquals(antes["predios"].asLong(), despues["predios"].asLong())
        assertEquals(antes["declaraciones"].asLong(), despues["declaraciones"].asLong())
        get("/api/srtm/predios/buscar?codigo=$sector-$manzana-").expectBody().jsonPath("$.totalElements").isEqualTo(0)
        get("/api/srtm/contribuyentes/$contribuyente/declaraciones").expectBody().jsonPath("$.length()").isEqualTo(0)
        // nor did it keep a code: the manzana's next one is still its first
        val dj = post(presentar(contribuyente), mapOf("declaracion" to declaracion("tipo_adquisicion" to "COMPRA"), "predio" to predio))
        assertEquals("$sector-$manzana-0001", dj["predio"]["codigo"].asString())
    }

    @Test
    fun `a declaracion refused on a predio that already existed leaves the predio as it was`() {
        val predio = predio()
        post("/api/srtm/declaraciones", mapOf("contribuyente" to inscribir(), "predio" to predio, "anio" to 2026, "secuencia_uso" to "1"))

        // a second titular must say its part of the predio
        rejected("POST", presentar(inscribir()), mapOf("declaracion" to declaracion(), "predio_id" to predio), "porcentaje_condominio")

        send("GET", "/api/srtm/predios/$predio", null, HttpStatus.OK)
        get("/api/srtm/predios/$predio/declaraciones?anio=2026").expectBody().jsonPath("$.length()").isEqualTo(1)
    }

    @Test
    fun `a declaracion jurada needs a contribuyente and a predio that exist`() {
        val antes = resumen()
        val nuevo = mapOf("sector_catastral" to uniqueDocumento().take(4), "manzana_catastral" to "01", "direccion" to "S/N")
        send("POST", presentar(UUID.randomUUID().toString()), mapOf("declaracion" to declaracion(), "predio" to nuevo), HttpStatus.NOT_FOUND)
        send("POST", presentar(inscribir()), mapOf("declaracion" to declaracion(), "predio_id" to UUID.randomUUID().toString()), HttpStatus.NOT_FOUND)
        rejected("POST", presentar(inscribir()), mapOf("declaracion" to declaracion()), "predio_id")
        assertEquals(antes["predios"].asLong(), resumen()["predios"].asLong())
    }

    private fun presentar(contribuyente: String) = "/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas"

    // the datos del predio: 2026, first secuencia de uso
    private fun declaracion(vararg extra: Pair<String, Any?>) = mapOf("anio" to 2026, "secuencia_uso" to "1") + extra
}
