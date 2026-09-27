package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.util.UUID

// the rows of every list, changed and removed by their own id: the contribuyente's medios de contacto, documentos
// sustento and relacionados, the declaración's transferentes, niveles, obras and otros frentes. a required field sent
// empty is a 400 on it and keeps what was stored; a row or a parent that does not exist is a 404. what other classes
// cover is not repeated: codes (CodigoListasApiTest), names (RelacionadosTransferentesApiTest), domicilios
// (DomicilioFiscalApiTest), an annulled declaración's rows (AnulacionApiTest)
class ListasApiTest : SrtmApiTest() {
    @Test
    fun `a medio de contacto is removed by its id, and one changed without its valor is refused`() {
        val id = inscribir()
        val celular = post("/api/srtm/contribuyentes/$id/medios-contacto", mapOf("tipo" to "TELEFONO CELULAR", "valor" to "987654321"))
        val correo = post("/api/srtm/contribuyentes/$id/medios-contacto", mapOf("tipo" to "CORREO ELECTRONICO", "valor" to "junior@perene.pe"))

        rejected("PUT", "/api/srtm/medios-contacto/${celular["id"].asString()}", fields(celular) + ("valor" to null), "valor")
        assertEquals(listOf("987654321", "junior@perene.pe"), filas("/api/srtm/contribuyentes/$id/medios-contacto").map { it["valor"].asString() })

        delete("/api/srtm/medios-contacto/${celular["id"].asString()}")
        assertEquals(listOf(correo["id"].asString()), filas("/api/srtm/contribuyentes/$id/medios-contacto").map { it["id"].asString() })
    }

    @Test
    fun `a documento sustento changed without its documento is refused`() {
        val id = inscribir()
        val poder =
            post(
                "/api/srtm/contribuyentes/$id/sustentos",
                mapOf(
                    "documento" to "PODER ESPECIAL",
                    "numero_documento" to "PE-1",
                    "tipo_presentacion" to "ORIGINAL"
                )
            )

        rejected("PUT", "/api/srtm/sustentos/${poder["id"].asString()}", fields(poder) + mapOf("documento" to null, "folios" to 3), "documento")
        assertEquals(listOf("PODER ESPECIAL"), filas("/api/srtm/contribuyentes/$id/sustentos").map { it["documento"].asString() })
    }

    @Test
    fun `a transferente is removed by its id`() {
        val declaracion = nuevaDeclaracion()
        val vendedor = post("/api/srtm/declaraciones/$declaracion/transferentes", transferente("43434352"))
        val otro = post("/api/srtm/declaraciones/$declaracion/transferentes", transferente("43434353"))

        delete("/api/srtm/transferentes/${vendedor["id"].asString()}")
        assertEquals(listOf(otro["id"].asString()), filas("/api/srtm/declaraciones/$declaracion/transferentes").map { it["id"].asString() })
    }

    @Test
    fun `a nivel is changed and removed by its id, and stays under its declaracion`() {
        val declaracion = nuevaDeclaracion()
        val nivel =
            post(
                "/api/srtm/declaraciones/$declaracion/niveles",
                mapOf(
                    "tipo_nivel" to "PISO",
                    "numero_piso" to 1,
                    "anio_construccion" to 2023,
                    "mes_construccion" to 1,
                    "material" to "LADRILLO",
                    "estado_conservacion" to "BUENO",
                    "area_construida" to 200,
                    "techos" to "C"
                )
            )
        val path = "/api/srtm/niveles/${nivel["id"].asString()}"

        // a body never moves a row to another declaración
        val cambiado = put(path, fields(nivel) + mapOf("area_construida" to 150, "techos" to "B", "declaracion" to nuevaDeclaracion()))
        assertEquals(150.0, cambiado["area_construida"].asDouble())
        assertEquals("B", cambiado["techos"].asString())
        assertEquals(declaracion, cambiado["declaracion"].asString())

        rejected("PUT", path, fields(cambiado) + ("area_construida" to null), "area_construida")
        assertEquals(listOf(150.0), filas("/api/srtm/declaraciones/$declaracion/niveles").map { it["area_construida"].asDouble() })

        delete(path)
        assertEquals(0, filas("/api/srtm/declaraciones/$declaracion/niveles").size)
    }

    @Test
    fun `an obra's total metrado is cantidad by metrado after every change, whatever the body says`() {
        val declaracion = nuevaDeclaracion()
        val obra =
            post(
                "/api/srtm/declaraciones/$declaracion/obras",
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
            )
        val path = "/api/srtm/obras/${obra["id"].asString()}"

        val cambiada = put(path, fields(obra) + mapOf("cantidad" to 3, "metrado" to 12.5, "total_metrado" to 1))
        assertEquals(37.5, cambiada["total_metrado"].asDouble())
        assertEquals(listOf(37.5), filas("/api/srtm/declaraciones/$declaracion/obras").map { it["total_metrado"].asDouble() })

        rejected("PUT", path, fields(cambiada) + ("metrado" to null), "metrado")
        assertEquals(listOf(37.5), filas("/api/srtm/declaraciones/$declaracion/obras").map { it["total_metrado"].asDouble() })

        delete(path)
        assertEquals(0, filas("/api/srtm/declaraciones/$declaracion/obras").size)
    }

    @Test
    fun `an otro frente is changed and removed by its id`() {
        val declaracion = nuevaDeclaracion()
        val frente =
            post(
                "/api/srtm/declaraciones/$declaracion/frentes",
                mapOf(
                    "tipo_via" to "AVENIDA",
                    "via" to "ANDRES AVELINO CACERES",
                    "frontis" to 7,
                    "lado" to "IMPAR"
                )
            )
        val path = "/api/srtm/frentes/${frente["id"].asString()}"

        val cambiado = put(path, fields(frente) + mapOf("frontis" to 9.5, "lado" to "PAR"))
        assertEquals(9.5, cambiado["frontis"].asDouble())
        assertEquals("PAR", cambiado["lado"].asString())

        rejected("PUT", path, fields(cambiado) + ("via" to null), "via")
        assertEquals(listOf("ANDRES AVELINO CACERES"), filas("/api/srtm/declaraciones/$declaracion/frentes").map { it["via"].asString() })

        delete(path)
        assertEquals(0, filas("/api/srtm/declaraciones/$declaracion/frentes").size)
    }

    @Test
    fun `a row or a parent that does not exist is a 404, in every list`() {
        val nadie = UUID.randomUUID()
        val listas =
            listOf("domicilios", "relacionados", "medios-contacto", "sustentos").map { "contribuyentes" to it } +
                listOf("transferentes", "niveles", "obras", "frentes").map { "declaraciones" to it }
        for ((padre, lista) in listas) {
            send("POST", "/api/srtm/$padre/$nadie/$lista", emptyMap<String, Any?>(), HttpStatus.NOT_FOUND)
            send("PUT", "/api/srtm/$lista/$nadie", emptyMap<String, Any?>(), HttpStatus.NOT_FOUND)
            send("DELETE", "/api/srtm/$lista/$nadie", null, HttpStatus.NOT_FOUND)
        }
    }

    private fun transferente(dni: String) =
        mapOf(
            "porcentaje_transferido" to 50,
            "tipo_documento" to "DNI",
            "numero_documento" to dni,
            "apellido_paterno" to "NEIRA",
            "nombres" to "DUBERLI",
            "departamento" to "JUNIN",
            "provincia" to "CHANCHAMAYO",
            "distrito" to "PERENE",
            "descripcion_domicilio" to "JR. LIMA 123"
        )

    // the rows of a list, in its order
    private fun filas(path: String): List<JsonNode> = tree(send("GET", path, null, HttpStatus.OK)).iterator().asSequence().toList()
}
