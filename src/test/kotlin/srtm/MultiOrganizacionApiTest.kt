package srtm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import srtm.anuncios.Llaves
import srtm.sanciones.ConSancionesApiTest
import srtm.sanciones.RESOLUCION_RECURSO

// an install with several municipalidades (one organization each): what one writes, the other neither reads nor
// numbers. A is the seeded admin's organization, B a new one; where a count must start from nothing (a correlativo)
// both are new, as the shared test db holds the seeded one's leftovers
@Tag("integration")
class MultiOrganizacionApiTest : ConSancionesApiTest() {
    @Test
    fun `what A registers B neither reads nor lists`() {
        val b = otraOrganizacion()
        val documento = uniqueDocumento()
        val contribuyente = post("/api/srtm/contribuyentes", personaNatural(documento))["id"].asString()
        val predio = post("/api/srtm/predios", mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO"))
        val codigoPredio = predio["codigo"].asString()
        val declaracion =
            post(
                "/api/srtm/declaraciones",
                mapOf("contribuyente" to contribuyente, "predio" to predio["id"].asString(), "anio" to 2026, "secuencia_uso" to "1")
            )["id"].asString()

        // A reads them (the control), B gets a 404 from every door
        for (ruta in listOf(
            "contribuyentes/$contribuyente",
            "predios/${predio["id"].asString()}",
            "declaraciones/$declaracion"
        )) {
            send("GET", "/api/srtm/$ruta", null, HttpStatus.OK)
            send("GET", "/api/srtm/$ruta", null, HttpStatus.NOT_FOUND, b)
        }
        // the contribuyente's declaraciones is a list: B gets none of A's (it answers [] for an id it does not know, not a 404)
        assertEquals(1, tree(send("GET", "/api/srtm/contribuyentes/$contribuyente/declaraciones", null, HttpStatus.OK)).size())
        assertEquals(0, tree(send("GET", "/api/srtm/contribuyentes/$contribuyente/declaraciones", null, HttpStatus.OK, b)).size())
        send("GET", "/api/objects/contribuyente/records/$contribuyente", null, HttpStatus.OK)
        send("GET", "/api/objects/contribuyente/records/$contribuyente", null, HttpStatus.NOT_FOUND, b)
        send("GET", "/api/objects/declaracion_jurada/records/$declaracion", null, HttpStatus.NOT_FOUND, b)

        // the lists
        assertEquals(1, tree(send("GET", "/api/srtm/contribuyentes?q=$documento", null, HttpStatus.OK))["totalElements"].asInt())
        assertEquals(0, tree(send("GET", "/api/srtm/contribuyentes?q=$documento", null, HttpStatus.OK, b))["totalElements"].asInt())
        assertEquals(1, tree(send("GET", "/api/srtm/predios?q=$codigoPredio", null, HttpStatus.OK))["totalElements"].asInt())
        assertEquals(0, tree(send("GET", "/api/srtm/predios?q=$codigoPredio", null, HttpStatus.OK, b))["totalElements"].asInt())
        assertEquals(0, tree(send("GET", "/api/srtm/resumen", null, HttpStatus.OK, b))["contribuyentes"].asInt())
    }

    @Test
    fun `the contribuyente's code starts at 000001 in each organization, and a document can be inscribed in both`() {
        val x = otraOrganizacion()
        val y = otraOrganizacion()
        val documento = uniqueDocumento()

        val enX = post("/api/srtm/contribuyentes", personaNatural(documento), x)
        val enY = post("/api/srtm/contribuyentes", personaNatural(documento), y)

        assertEquals("000001", enX["codigo"].asString())
        assertEquals("000001", enY["codigo"].asString())
        assertEquals("000002", post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), x)["codigo"].asString())
        assertEquals("000002", post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), y)["codigo"].asString())
        // the same document twice in one organization is still refused
        send("POST", "/api/srtm/contribuyentes", personaNatural(documento), HttpStatus.BAD_REQUEST, x)
    }

    @Test
    fun `the resoluciones' correlativo is a serie of each organization`() {
        val x = otraOrganizacion()
        val y = otraOrganizacion()

        val enX = (1..2).map { en(x) { resolucion() } }
        val enY = en(y) { resolucion() }

        assertEquals(listOf(1, 2), enX.map { it.first })
        assertEquals(listOf("RIS-$ANIO_PASADO-000001", "RIS-$ANIO_PASADO-000002"), enX.map { it.second })
        assertEquals(1 to "RIS-$ANIO_PASADO-000001", enY)
        // the RGR are another serie, also of each organization
        assertEquals("RGR-$ANIO_PASADO-000001", en(y) { recurso() })
        assertEquals("RGR-$ANIO_PASADO-000001", en(x) { recurso() })
        assertEquals(3 to "RIS-$ANIO_PASADO-000003", en(x) { resolucion() })
        assertEquals(2 to "RIS-$ANIO_PASADO-000002", en(y) { resolucion() })
    }

    @Test
    fun `the anuncios' number starts at 1 in each organization`() {
        val x = otraOrganizacion()
        val y = otraOrganizacion()

        assertEquals("AN-$ANIO_ANUNCIO-000001", en(x) { anuncio() })
        assertEquals("AN-$ANIO_ANUNCIO-000001", en(y) { anuncio() })
        assertEquals("AN-$ANIO_ANUNCIO-000002", en(x) { anuncio() })
        assertEquals("AN-$ANIO_ANUNCIO-000002", en(y) { anuncio() })
    }

    // runs `bloque` as the admin of another organization: the helpers write with `token`. beforeEach gives the next
    // test the seeded admin's again
    private fun <T> en(
        otro: String,
        bloque: () -> T
    ): T {
        val propio = token
        token = otro
        try {
            return bloque()
        } finally {
            token = propio
        }
    }

    // a RIS on a new acta of the current organization, which gets the UIT, plazos and CUIS of the year once: its
    // correlativo and número
    private fun resolucion(): Pair<Int, String> {
        val r = dictar(nuevaActa(), "$ANIO_PASADO-03-20")
        return r["correlativo"].asInt() to r["numero"].asString()
    }

    // a RGR (the recurso's resolution) on a new acta: its número
    private fun recurso(): String {
        val acta = nuevaActa()
        val descargo = descargar(acta, "$ANIO_PASADO-03-06")["id"].asString()
        return dictar(acta, "$ANIO_PASADO-03-22", RESOLUCION_RECURSO, descargo)["numero"].asString()
    }

    private fun nuevaActa(): String {
        uit(ANIO_PASADO)
        plazos(ANIO_PASADO)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$ANIO_PASADO-01-01"))
        return registrarActa(codigo, inscribir())["id"].asString()
    }

    // an anuncio of the current organization (a PANEL tasa of the year, added once): its número
    private fun anuncio(): String {
        val filas =
            tree(send("GET", "/api/objects/parametro_tributario/records?tipo=${Llaves.TASA_ANUNCIO}&vigencia_desde=$ANIO_ANUNCIO-01-01", null, HttpStatus.OK))
        if (filas["content"].size() == 0) {
            post(
                "/api/objects/parametro_tributario/records",
                mapOf(
                    "attributes" to
                        mapOf(
                            "tipo" to Llaves.TASA_ANUNCIO,
                            "clave" to "PANEL",
                            "vigencia_desde" to "$ANIO_ANUNCIO-01-01",
                            "vigencia_hasta" to "$ANIO_ANUNCIO-12-31",
                            "valor_numerico" to "12.50",
                            "norma" to "Ordenanza ficticia de prueba",
                            "fuente" to "MultiOrganizacionApiTest",
                            "transcribio" to "TEST A",
                            "verifico" to "TEST B"
                        )
                )
            )
        }
        val cuerpo =
            mapOf(
                "contribuyente" to inscribir(),
                "predio" to predio(),
                "clase" to "PANEL",
                "tipo" to "AVISO_LUMINOSO",
                "emplazamiento" to "FACHADA",
                "forma" to "RECTANGULAR",
                "denominacion" to "BODEGA DE PRUEBA",
                "direccion" to "JR. LIMA 123",
                "area" to 2.5,
                "lados" to 1,
                "cantidad" to 1,
                "fecha_autorizacion" to "$ANIO_ANUNCIO-03-02",
                "vigencia_hasta" to "$ANIO_ANUNCIO-12-31",
                "expediente" to "EXP-0003",
                "fecha_expediente" to "$ANIO_ANUNCIO-02-27",
                "licencia_texto" to "LF-0001",
                "observacion" to "Alta de prueba"
            )
        return post("/api/srtm/anuncios", cuerpo)["anuncio"]["numero"].asString()
    }

    private companion object {
        // new organizations hold no other test's rows: any past year serves
        const val ANIO_ANUNCIO = 1991
        const val CUIS = "/api/srtm/infracciones/cuis"
    }
}
