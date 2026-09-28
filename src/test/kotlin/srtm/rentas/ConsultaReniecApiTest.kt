package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import srtm.pide.ConsultaDocumento
import srtm.pide.DatosPersona

// PIDE RENIEC (pages 3, 8 and 15): the portal asks a DNI's names, and a contribuyente, relacionado or transferente
// saved with fuente PIDE RENIEC needs that consulta, with the same names. RENIEC is a double here: never the real
// PIDE
class ConsultaReniecApiTest : SrtmApiTest() {
    // knows every DNI but DESCONOCIDO, all by the same names
    @TestConfiguration
    class Doble {
        @Bean
        @Primary
        fun reniecDoble(): ConsultaDocumento =
            object : ConsultaDocumento {
                override suspend fun consultar(
                    tipo: String,
                    numero: String
                ): DatosPersona? =
                    if (tipo != "DNI" ||
                        numero == DESCONOCIDO
                    ) {
                        null
                    } else {
                        DatosPersona(tipo, numero, "FLORES", "OTINIANO", "JUNIOR PAOLO", "SOLTERO")
                    }
            }
    }

    @Test
    fun `a DNI answers RENIEC's names, and another document or one RENIEC does not know is a 404`() {
        val dni = uniqueDocumento()
        val datos = tree(send("GET", "/api/srtm/documentos/DNI/$dni", null, HttpStatus.OK))
        assertEquals("FLORES", datos["apellido_paterno"].asString())
        assertEquals("OTINIANO", datos["apellido_materno"].asString())
        assertEquals("JUNIOR PAOLO", datos["nombres"].asString())
        assertEquals("SOLTERO", datos["estado_civil"].asString())
        assertEquals(dni, datos["numero_documento"].asString())
        assertEquals("PIDE RENIEC", datos["fuente_informacion"].asString())

        send("GET", "/api/srtm/documentos/DNI/$DESCONOCIDO", null, HttpStatus.NOT_FOUND)
        send("GET", "/api/srtm/documentos/DNI/4355456", null, HttpStatus.NOT_FOUND)
        send("GET", "/api/srtm/documentos/RUC/20131312955", null, HttpStatus.NOT_FOUND)
        client
            .get()
            .uri("/api/srtm/documentos/DNI/$dni")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `a contribuyente with PIDE RENIEC needs the consulta of its DNI, with the same names`() {
        val dni = uniqueDocumento()
        val nuevo = contribuyente(dni) + ("fuente_informacion" to "PIDE RENIEC")
        rejected("POST", "/api/srtm/contribuyentes", nuevo, "fuente_informacion")

        consultar(dni)
        rejected("POST", "/api/srtm/contribuyentes", nuevo + ("nombres" to "JUNIOR"), "fuente_informacion")
        val inscrito = post("/api/srtm/contribuyentes", nuevo)
        assertEquals("PIDE RENIEC", inscrito["fuente_informacion"].asString())
        assertEquals("FLORES OTINIANO JUNIOR PAOLO", inscrito["nombre_completo"].asString())

        // its names stay RENIEC's while nobody changes them; changed, they are the clerk's
        val id = inscrito["id"].asString()
        put("/api/srtm/contribuyentes/$id", fields(inscrito) + ("observacion" to "SIN CAMBIOS EN LOS NOMBRES"))
        rejected("PUT", "/api/srtm/contribuyentes/$id", fields(inscrito) + ("nombres" to "PAOLO"), "fuente_informacion")
        val manual = put("/api/srtm/contribuyentes/$id", fields(inscrito) + mapOf("nombres" to "PAOLO", "fuente_informacion" to "MANUAL"))
        assertEquals("PAOLO", manual["nombres"].asString())
    }

    @Test
    fun `relacionados and transferentes with PIDE RENIEC need the consulta too`() {
        val contribuyente = post("/api/srtm/contribuyentes", contribuyente(uniqueDocumento()))["id"].asString()
        val dni = uniqueDocumento()
        val conyuge = persona(dni) + mapOf("tipo_relacionado" to "CONYUGE", "fuente_informacion" to "PIDE RENIEC")
        rejected("POST", "/api/srtm/contribuyentes/$contribuyente/relacionados", conyuge, "fuente_informacion")
        val transferente =
            persona(dni) +
                mapOf(
                    "fuente_informacion" to "PIDE RENIEC",
                    "porcentaje_transferido" to 50,
                    "departamento" to "JUNIN",
                    "provincia" to "CHANCHAMAYO",
                    "distrito" to "PERENE",
                    "descripcion_domicilio" to "JR. LIMA 123"
                )
        val declaracion = declaracion(contribuyente)
        rejected("POST", "/api/srtm/declaraciones/$declaracion/transferentes", transferente, "fuente_informacion")

        consultar(dni)
        val relacionado = post("/api/srtm/contribuyentes/$contribuyente/relacionados", conyuge)
        assertEquals("PIDE RENIEC", relacionado["fuente_informacion"].asString())
        val otro = fields(relacionado) + ("apellido_paterno" to "FLOREZ")
        rejected("PUT", "/api/srtm/relacionados/${relacionado["id"].asString()}", otro, "fuente_informacion")
        assertEquals("PIDE RENIEC", post("/api/srtm/declaraciones/$declaracion/transferentes", transferente)["fuente_informacion"].asString())
    }

    private fun consultar(dni: String) = send("GET", "/api/srtm/documentos/DNI/$dni", null, HttpStatus.OK)

    // RENIEC's names for the double's every DNI
    private fun persona(dni: String) =
        mapOf(
            "tipo_documento" to "DNI",
            "numero_documento" to dni,
            "apellido_paterno" to "FLORES",
            "apellido_materno" to "OTINIANO",
            "nombres" to "JUNIOR PAOLO"
        )

    private fun contribuyente(dni: String) = persona(dni) + mapOf("tipo_contribuyente" to "PERSONA NATURAL", "sexo" to "HOMBRE", "estado_civil" to "SOLTERO")

    // a declaration of the contribuyente on a new predio: its id
    private fun declaracion(contribuyente: String): String {
        val predio = post("/api/srtm/predios", mapOf("codigo" to "R-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO"))
        return post(
            "/api/srtm/declaraciones",
            mapOf("contribuyente" to contribuyente, "predio" to predio["id"].asString(), "anio" to 2026, "secuencia_uso" to "1")
        )["id"].asString()
    }

    private companion object {
        const val DESCONOCIDO = "99999999"
    }
}
