package srtm.caja

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

// #166: caja's conciliación asks GET /api/srtm/caja/pagos/conciliacion?fecha=, as the service account (role CAJA),
// and compares srtm's day with its own. each test has a fecha of its own: the organization's pagos are shared
class ConciliacionApiTest : ConPagosApiTest() {
    // 3 normal pagos, 1 a cuenta, 1 annulled (its anulación of another day) and 1 repeated: the repeated and the
    // annulled are not the day's, and the exact sums are
    @Test
    fun `diaConPagosNormalesACuentaAnuladosYRepetidos`() {
        val e = Escenario()
        val cuotas = cuotasEnviadas(e)
        val normales = (0..2).map { registrado(listOf(referencia(cuotas[it]) to importe(cuotas[it]["monto"])), fecha = DIA) }
        normales.forEach { assertEquals(HttpStatus.OK, pagar(it).first) }
        val aCuenta = registrado(listOf(referencia(cuotas[3]) to "1.25"), fecha = DIA)
        assertEquals(HttpStatus.ACCEPTED, pagar(aCuenta).first)
        val anulada = registrado(listOf(referencia(cuotas[4]) to importe(cuotas[4]["monto"])), fecha = DIA)
        assertEquals(HttpStatus.OK, pagar(anulada).first)
        assertEquals(HttpStatus.OK, pagar(anulado(anulada)).first)
        assertEquals(HttpStatus.CONFLICT, pagar(normales[0]).first)

        val (estado, cuerpo) = conciliar(DIA)

        assertEquals(HttpStatus.OK, estado, cuerpo)
        val suma = (0..2).sumOf { cuotas[it]["monto"].decimalValue() }.setScale(2)
        val esperado =
            """{"fecha":"$DIA","recibidos":4,"aplicados":3,"rechazados":0,"recibidos_a_cuenta":1,""" +
                """"importe_aplicado":"${suma.toPlainString()}","importe_a_cuenta":"1.25"}"""
        assertEquals(esperado, cuerpo)
    }

    // a pago of two ordenes, one applied and one a cuenta, is a recibido and a recibido a cuenta, never an aplicado
    @Test
    fun `pagoMixtoCuentaSoloACuenta`() {
        val e = Escenario()
        val cuotas = cuotasEnviadas(e)
        val mixto = registrado(listOf(referencia(cuotas[0]) to importe(cuotas[0]["monto"]), referencia(cuotas[1]) to "0.50"), fecha = "2030-02-02")
        assertEquals(HttpStatus.ACCEPTED, pagar(mixto).first)

        val c = tree(conciliar("2030-02-02").second)

        assertEquals(1, c["recibidos"].asInt())
        assertEquals(0, c["aplicados"].asInt())
        assertEquals(1, c["recibidos_a_cuenta"].asInt())
        assertEquals(importe(cuotas[0]["monto"]), c["importe_aplicado"].asString())
        assertEquals("0.50", c["importe_a_cuenta"].asString())
    }

    @Test
    fun `unDiaSinPagosEsCero`() {
        val (estado, cuerpo) = conciliar("2030-02-03")

        assertEquals(HttpStatus.OK, estado, cuerpo)
        assertEquals(
            """{"fecha":"2030-02-03","recibidos":0,"aplicados":0,"rechazados":0,"recibidos_a_cuenta":0,"importe_aplicado":"0.00","importe_a_cuenta":"0.00"}""",
            cuerpo
        )
    }

    @Test
    fun `laFechaVaEnLaRespuesta`() {
        assertEquals("2030-02-04", tree(conciliar("2030-02-04").second)["fecha"].asString())
    }

    @Test
    fun `sinFechaEs400`() {
        assertEquals(HttpStatus.BAD_REQUEST, pedir("/api/srtm/caja/pagos/conciliacion", caja).first)
        assertEquals(HttpStatus.BAD_REQUEST, conciliar("22/10/2026").first)
        assertEquals(HttpStatus.BAD_REQUEST, conciliar("2026-13-40").first)
    }

    // caja's conciliación reads the answer with aplicadoDe (caja-backend, Conciliacion.kt:170-220): only a 200; fecha,
    // when present, is the day asked; recibidos, aplicados and rechazados are json integers >= 0; importe_aplicado is a
    // json STRING [0-9]+(.[0-9]+)? of scale <= 2 (a number fails); recibidos_a_cuenta is an optional integer and
    // importe_a_cuenta an optional string of the same rules. a day with a cuenta and one without it are both read
    @Test
    fun `contratoDeCaja`() {
        val e = Escenario()
        val cuotas = cuotasEnviadas(e)
        pagar(registrado(listOf(referencia(cuotas[0]) to importe(cuotas[0]["monto"])), fecha = "2030-02-05"))
        pagar(registrado(listOf(referencia(cuotas[1]) to "0.10"), fecha = "2030-02-05"))
        for (dia in listOf("2030-02-05", "2030-02-06")) {
            val (estado, cuerpo) = conciliar(dia)
            assertEquals(HttpStatus.OK, estado, cuerpo)
            val j = tree(cuerpo)
            j["fecha"]?.let { assertEquals(dia, it.asString()) }
            listOf("recibidos", "aplicados", "rechazados").forEach { entero(j, it, obligatorio = true) }
            entero(j, "recibidos_a_cuenta", obligatorio = false)
            cadena(j, "importe_aplicado", obligatorio = true)
            cadena(j, "importe_a_cuenta", obligatorio = false)
        }
    }

    @Test
    fun `sinTokenEs401`() {
        assertEquals(HttpStatus.UNAUTHORIZED, pedir("/api/srtm/caja/pagos/conciliacion?fecha=$DIA", null).first)
    }

    @Test
    fun `usuarioSinPermisoEs403`() {
        val sin = funcionario(listOf(permiso(ObjetosCaja.ORDEN_CAJA, "READ")))

        assertEquals(HttpStatus.FORBIDDEN, pedir("/api/srtm/caja/pagos/conciliacion?fecha=$DIA", sin).first)
    }

    // an organization without caja: caja asks again later (503, like the pagos)
    @Test
    fun `sinCajaEs503`() {
        val (estado, cuerpo) = pedir("/api/srtm/caja/pagos/conciliacion?fecha=$DIA", otraOrganizacion())

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, estado, cuerpo)
        assertEquals("""{"motivo":"caja no está configurada para esta municipalidad"}""", cuerpo)
    }

    // the pagos of another organization are not the day's: a day's pago is only seen by its own organization's caja
    @Test
    fun `noCuentaLosPagosDeOtraOrganizacion`() {
        val e = Escenario()
        val cuotas = cuotasEnviadas(e)
        assertEquals(HttpStatus.OK, pagar(registrado(listOf(referencia(cuotas[0]) to importe(cuotas[0]["monto"])), fecha = "2030-02-07")).first)

        // the seeded organization has no caja: nothing of this one's pagos crosses
        val (estado, _) = pedir("/api/srtm/caja/pagos/conciliacion?fecha=2030-02-07", semilla)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, estado)
    }

    private fun conciliar(fecha: String) = pedir("/api/srtm/caja/pagos/conciliacion?fecha=$fecha", caja)

    private fun pedir(
        ruta: String,
        quien: String?
    ): Pair<HttpStatus, String> {
        val spec = client.get().uri(ruta)
        val result =
            (quien?.let { spec.header(HttpHeaders.AUTHORIZATION, it) } ?: spec)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to (result.responseBody ?: "")
    }

    private fun entero(
        j: JsonNode,
        campo: String,
        obligatorio: Boolean
    ) {
        val n = j[campo]
        if (n == null || n.isNull) {
            assertTrue(!obligatorio, "falta $campo")
            return
        }
        assertTrue(n.isIntegralNumber && n.asLong() >= 0, "$campo no es un entero >= 0: $n")
    }

    private fun cadena(
        j: JsonNode,
        campo: String,
        obligatorio: Boolean
    ) {
        val n = j[campo]
        if (n == null || n.isNull) {
            assertTrue(!obligatorio, "falta $campo")
            return
        }
        assertTrue(n.isString, "$campo no es una cadena: $n")
        val t = n.asString()
        assertTrue(Regex("[0-9]+(\\.[0-9]+)?").matches(t), "$campo no es un importe: $t")
        assertTrue(BigDecimal(t).scale() <= 2, "$campo tiene más de 2 decimales: $t")
    }

    private companion object {
        const val DIA = "2030-02-01"
    }
}
