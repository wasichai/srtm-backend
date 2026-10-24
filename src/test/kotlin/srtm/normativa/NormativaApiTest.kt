package srtm.normativa

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import srtm.impuesto.PARAMETRO_TRIBUTARIO
import tools.jackson.databind.JsonNode
import java.io.File
import java.math.BigDecimal

// Normativa's parámetros over http (#187): the double signature, that a VIGENTE row is edited by no door, the
// anulación, the overlaps, that the liquidación reads only the VIGENTE rows, the importación and the permissions
class NormativaApiTest : ConNormativaApiTest() {
    @Test
    fun `the same person does not verify what she transcribed - another one does`() {
        val y = anio()
        val p = registrar(uit(y))
        assertEquals("POR_VERIFICAR", p["estado"].asString())
        assertEquals(email(ana), p["transcribio"].asString())
        assertTrue(p["verifico"].isNull, p.toString())
        val id = p["id"].asString()

        val propia = verificar(listOf(id), ana)
        assertEquals(0, propia["verificados"].size())
        assertEquals(id, propia["rechazados"][0]["id"].asString())
        assertTrue(propia["rechazados"][0]["motivo"].asString().contains("no puede verificar lo que transcribió"), propia.toString())

        val otra = verificar(listOf(id), beto)
        assertEquals(listOf(id), otra["verificados"].elementos().map { it.asString() })
        val fila = parametros("tipo=UIT&vigente_en=$y-06-01").single()
        assertEquals("VIGENTE", fila["estado"].asString())
        assertEquals(email(beto), fila["verifico"].asString())
        // once VIGENTE it is not verified again
        assertTrue(verificar(listOf(id))["rechazados"][0]["motivo"].asString().contains("VIGENTE"))
    }

    @Test
    fun `a VIGENTE row is edited by no door - normativa nor the generic api - a POR_VERIFICAR one is corrected and deleted`() {
        val y = anio()
        val id = vigente(uit(y))
        val parche = mapOf("valor_numerico" to "9999.00")
        assertTrue(conflicto("PATCH", "$PARAMETROS/$id", parche).contains("POR_VERIFICAR"))
        assertTrue(conflicto("DELETE", "$PARAMETROS/$id", null).contains("POR_VERIFICAR"))

        val registro = "/api/objects/$PARAMETRO_TRIBUTARIO/records/$id"
        val atributos = fields(tree(send("GET", registro, null, HttpStatus.OK))["attributes"])
        val generica = tree(send("PUT", registro, mapOf("attributes" to atributos + parche), HttpStatus.CONFLICT))
        assertTrue(generica["detail"].asString().contains("VIGENTE no se edita"), generica.toString())
        send("DELETE", registro, null, HttpStatus.CONFLICT)

        // only Normativa verifies: the generic api's POR_VERIFICAR -> VIGENTE is a 409, even with another signature
        val porVerificar = registrar(uit(anio()))["id"].asString()
        val suyo = "/api/objects/$PARAMETRO_TRIBUTARIO/records/$porVerificar"
        val firmado = fields(tree(send("GET", suyo, null, HttpStatus.OK))["attributes"]) + mapOf("estado" to "VIGENTE", "verifico" to "OTRA PERSONA")
        val sinMarca = tree(send("PUT", suyo, mapOf("attributes" to firmado), HttpStatus.CONFLICT))
        assertTrue(sinMarca["detail"].asString().contains("se verifica en Normativa"), sinMarca.toString())

        val borrador = registrar(uit(anio()))["id"].asString()
        val corregido = tree(send("PATCH", "$PARAMETROS/$borrador", parche, HttpStatus.OK, beto))
        assertEquals(BigDecimal("9999.00"), corregido["valor_numerico"].decimalValue().setScale(2))
        // who corrects it transcribes it now: beto no longer verifies it, ana does
        assertEquals(email(beto), corregido["transcribio"].asString())
        assertEquals(1, verificar(listOf(borrador), beto)["rechazados"].size())
        val otroBorrador = registrar(uit(anio()))["id"].asString()
        send("DELETE", "$PARAMETROS/$otroBorrador", null, HttpStatus.NO_CONTENT, ana)
        send("GET", registro.replace(id, otroBorrador), null, HttpStatus.NOT_FOUND)
        // only its data
        rechazo("PATCH", "$PARAMETROS/$borrador", mapOf("estado" to "VIGENTE"), "estado")
    }

    @Test
    fun `a VIGENTE row is annulled with its motivo and registered again`() {
        val y = anio()
        val id = vigente(uit(y))
        // one alive per key
        assertTrue(tree(send("POST", PARAMETROS, uit(y, "9100.00"), HttpStatus.CONFLICT, ana))["detail"].asString().contains("anúlelo"))
        rechazo("POST", "$PARAMETROS/$id/anular", mapOf("motivo" to " "), "motivo")

        val anulado = tree(send("POST", "$PARAMETROS/$id/anular", mapOf("motivo" to "La cifra se copió mal"), HttpStatus.OK, beto))
        assertEquals("ANULADO", anulado["estado"].asString())
        assertEquals("La cifra se copió mal", anulado["motivo_anulacion"].asString())
        assertEquals(email(beto), anulado["anulado_por"].asString())
        assertTrue(!anulado["anulado_en"].isNull, anulado.toString())
        assertTrue(conflicto("POST", "$PARAMETROS/$id/anular", mapOf("motivo" to "otra vez")).contains("ANULADO"))

        val nuevo = registrar(uit(y, "9100.00"))["id"].asString()
        assertEquals(listOf(nuevo), verificar(listOf(nuevo))["verificados"].elementos().map { it.asString() })
        val filas = parametros("tipo=UIT&vigente_en=$y-06-01")
        assertEquals(mapOf(nuevo to "VIGENTE", id to "ANULADO"), filas.associate { it["id"].asString() to it["estado"].asString() })
        assertEquals(listOf(nuevo), parametros("tipo=UIT&estado=VIGENTE&vigente_en=$y-06-01").map { it["id"].asString() })
    }

    @Test
    fun `verifying a row that overlaps a VIGENTE one is a rechazo that names it`() {
        val y = anio()
        val otro = vigente(uit(y))
        val abierto = registrar(uit(y) + ("vigencia_desde" to "$y-06-01") + ("vigencia_hasta" to null))["id"].asString()
        val libre = registrar(uit(anio()))["id"].asString()
        val r = verificar(listOf(abierto, libre, "no-es-un-id"))
        assertEquals(listOf(libre), r["verificados"].elementos().map { it.asString() })
        val motivos = r["rechazados"].elementos().associate { it["id"].asString() to it["motivo"].asString() }
        assertTrue(motivos.getValue(abierto).contains("se solapa con UIT $y-01-01..$y-12-31") && motivos.getValue(abierto).contains(otro), motivos.toString())
        assertTrue(motivos.containsKey("no-es-un-id"), motivos.toString())
    }

    @Test
    fun `an open VIGENTE row is closed once, from its vigencia_desde on, and a later one is verified after it`() {
        // a tasa of a clase no other test uses: an open row would rule every later year of the others
        val y = anio()
        val tasa = uit(y) + mapOf("tipo" to "TASA_ANUNCIO", "clave" to "LETRERO", "vigencia_hasta" to null)
        val abierto = vigente(tasa)
        val siguiente = registrar(tasa + ("vigencia_desde" to "$y-07-01") + ("valor_numerico" to "9100.00"))["id"].asString()
        val solapado = verificar(listOf(siguiente))["rechazados"][0]["motivo"].asString()
        assertTrue(solapado.contains("cierre la vigencia de TASA_ANUNCIO LETRERO $y-01-01.."), solapado)

        rechazo("POST", "$PARAMETROS/$abierto/cerrar", mapOf("vigencia_hasta" to "${y - 1}-12-31"), "vigencia_hasta")
        val cerrado = tree(send("POST", "$PARAMETROS/$abierto/cerrar", mapOf("vigencia_hasta" to "$y-06-30"), HttpStatus.OK, beto))
        assertEquals("$y-06-30", cerrado["vigencia_hasta"].asString())
        assertEquals("VIGENTE", cerrado["estado"].asString())
        assertEquals(BigDecimal("9000.00"), cerrado["valor_numerico"].decimalValue().setScale(2))
        assertTrue(conflicto("POST", "$PARAMETROS/$abierto/cerrar", mapOf("vigencia_hasta" to "$y-08-31")).contains("ya está cerrada"))
        // nor through the generic api
        val registro = "/api/objects/$PARAMETRO_TRIBUTARIO/records/$abierto"
        val atributos = fields(tree(send("GET", registro, null, HttpStatus.OK))["attributes"])
        send("PUT", registro, mapOf("attributes" to atributos + ("vigencia_hasta" to "$y-08-31")), HttpStatus.CONFLICT)

        assertEquals(listOf(siguiente), verificar(listOf(siguiente))["verificados"].elementos().map { it.asString() })
        // one POR_VERIFICAR is not closed: it is corrected
        val borrador = registrar(tasa + ("clave" to "BANDEROLA"))["id"].asString()
        assertTrue(conflicto("POST", "$PARAMETROS/$borrador/cerrar", mapOf("vigencia_hasta" to "2100-01-01")).contains("POR_VERIFICAR"))
        send(
            "POST",
            "$PARAMETROS/$abierto/cerrar",
            mapOf("vigencia_hasta" to "$y-06-30"),
            HttpStatus.FORBIDDEN,
            funcionario(listOf(permiso(PARAMETRO_TRIBUTARIO, "READ")))
        )
    }

    @Test
    fun `a UIT POR_VERIFICAR is not what the liquidación reads, the VIGENTE one is`() {
        val y = anio()
        // the tramos and the mínimo, open since 2004: the shipped csv, VIGENTE by its two signatures
        val (status, _) = importar(File("model/data/parametros-predial.csv").readText(), dryRun = false)
        assertEquals(HttpStatus.OK, status)
        val contribuyente = inscribir()
        post(
            "/api/srtm/declaraciones",
            mapOf("contribuyente" to contribuyente, "predio" to predio(), "anio" to y, "secuencia_uso" to "1", "valor_autoavaluo" to BigDecimal("60000.00"))
        )

        val id = registrar(uit(y, "9000.00"))["id"].asString()
        assertEquals(listOf("UIT $y"), liquidacion(contribuyente, y)["faltan"].elementos().map { it.asString() })

        verificar(listOf(id))
        val l = liquidacion(contribuyente, y)
        assertTrue(l["faltan"].isEmpty, l.toString())
        assertEquals(BigDecimal("9000.00"), l["uit"].decimalValue().setScale(2))
    }

    @Test
    fun `the importación - two signatures VIGENTE, one or none POR_VERIFICAR - and the dry run writes nothing`() {
        val y = anio()
        val csv =
            """
            # parámetros ficticios de prueba
            tipo,clave,vigencia_desde,vigencia_hasta,valor_numerico,texto,norma,fuente,transcribio,verifico
            UIT,,$y-01-01,$y-12-31,9300.00,,Norma ficticia,prueba,"ANA, $y-01-05","BETO, $y-01-09"
            TASA_ANUNCIO,PANEL,$y-01-01,$y-12-31,12.50,,Ordenanza ficticia,prueba,"ANA, $y-01-05",
            PLAZO,DESCARGO_PAPELETA,$y-01-01,$y-12-31,5,DIAS_HABILES,Ordenanza ficticia,prueba,,
            UIT,,$y-06-01,,9400.00,,Norma ficticia,prueba,ANA,BETO
            """.trimIndent()

        val (seco, previo) = importar(csv, dryRun = null)
        assertEquals(HttpStatus.OK, seco)
        assertEquals(true, previo!!["dry_run"].asBoolean())
        assertEquals(listOf("VIGENTE", "POR_VERIFICAR", "POR_VERIFICAR"), previo["crear"].elementos().map { it["estado"].asString() })
        assertEquals(listOf(6), previo["rechazos"].elementos().map { it["fila"].asInt() })
        assertTrue(previo["rechazos"][0]["motivo"].asString().contains("se solapa"), previo.toString())
        assertEquals(emptyList<JsonNode>(), delAnio(y))

        val (_, hecho) = importar(csv, dryRun = false)
        assertEquals(false, hecho!!["dry_run"].asBoolean())
        val filas = delAnio(y).associateBy { it["tipo"].asString() }
        assertEquals("VIGENTE", filas.getValue("UIT")["estado"].asString())
        assertEquals("BETO, $y-01-09", filas.getValue("UIT")["verifico"].asString())
        assertEquals("POR_VERIFICAR", filas.getValue("TASA_ANUNCIO")["estado"].asString())
        assertEquals("ANA, $y-01-05", filas.getValue("TASA_ANUNCIO")["transcribio"].asString())
        assertEquals(email(ana), filas.getValue("PLAZO")["transcribio"].asString())

        // idempotent
        val (_, otra) = importar(csv, dryRun = false)
        assertEquals(3, otra!!["iguales"].asInt())
        assertEquals(0, otra["crear"].size() + otra["actualizar"].size())
        // a malformed file: nothing
        assertEquals(HttpStatus.BAD_REQUEST, importar("clave\nX", dryRun = false).first)
    }

    @Test
    fun `without the permission each act is a 403 that names it`() {
        val lector = funcionario(listOf(permiso(PARAMETRO_TRIBUTARIO, "READ")))
        send("GET", "/api/srtm/normativa/tipos", null, HttpStatus.OK, lector)
        send("GET", PARAMETROS, null, HttpStatus.OK, lector)
        val id = registrar(uit(anio()))["id"].asString()
        val sinCrear = tree(send("POST", PARAMETROS, uit(anio()), HttpStatus.FORBIDDEN, lector))
        assertTrue(sinCrear["detail"].asString().contains("parametro_tributario"), sinCrear.toString())
        send("POST", "$PARAMETROS/verificar", mapOf("ids" to listOf(id)), HttpStatus.FORBIDDEN, lector)
        send("POST", "$PARAMETROS/$id/anular", mapOf("motivo" to "x"), HttpStatus.FORBIDDEN, lector)
        send("PATCH", "$PARAMETROS/$id", mapOf("valor_numerico" to "1"), HttpStatus.FORBIDDEN, lector)
        send("DELETE", "$PARAMETROS/$id", null, HttpStatus.FORBIDDEN, lector)
        assertEquals(HttpStatus.FORBIDDEN, importar("tipo,vigencia_desde\n", dryRun = true, quien = lector).first)

        val nadie = funcionario(listOf(permiso("contribuyente", "READ")))
        send("GET", PARAMETROS, null, HttpStatus.FORBIDDEN, nadie)
        send("GET", "/api/srtm/normativa/tipos", null, HttpStatus.FORBIDDEN, nadie)
    }

    @Test
    fun `the catalog and the filters`() {
        val tipos = tree(send("GET", "/api/srtm/normativa/tipos", null, HttpStatus.OK, ana))
        val uit = tipos.elementos().first { it["tipo"].asString() == "UIT" }
        assertEquals(false, uit["usa_clave"].asBoolean())
        assertEquals("NUMERICO", uit["valor"].asString())
        assertEquals("impuesto", uit["modulo"].asString())
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, exchange("GET", "$PARAMETROS?otro=1", null, ana).first)
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, exchange("GET", "$PARAMETROS?vigente_en=ayer", null, ana).first)
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, exchange("GET", "$PARAMETROS?estado=OTRO", null, ana).first)
        // the catalog's shape: a TRAMO_PREDIAL has its clave, a UIT none
        rechazo("POST", PARAMETROS, uit(anio()) + ("tipo" to "TRAMO_PREDIAL"), "clave")
        rechazo("POST", PARAMETROS, uit(anio()) + ("clave" to "1"), "clave")
    }

    // the rows that start in `anio`: the test's own (the shipped csv's tramos are open since 2004)
    private fun delAnio(anio: Int) = parametros("vigente_en=$anio-03-01").filter { it["vigencia_desde"].asString().startsWith("$anio") }

    private fun liquidacion(
        contribuyente: String,
        anio: Int
    ): JsonNode = tree(send("GET", "/api/srtm/contribuyentes/$contribuyente/liquidacion?anio=$anio", null, HttpStatus.OK))

    // a 409's detail, as ana
    private fun conflicto(
        metodo: String,
        ruta: String,
        cuerpo: Map<String, Any?>?
    ): String = tree(send(metodo, ruta, cuerpo, HttpStatus.CONFLICT, ana))["detail"].asString()

    // a 400 that names `campo`, as ana
    private fun rechazo(
        metodo: String,
        ruta: String,
        cuerpo: Map<String, Any?>,
        campo: String
    ) {
        val problema = tree(send(metodo, ruta, cuerpo, HttpStatus.BAD_REQUEST, ana))
        assertTrue(problema["errors"].elementos().any { it["field"].asString() == campo }, problema.toString())
    }
}
