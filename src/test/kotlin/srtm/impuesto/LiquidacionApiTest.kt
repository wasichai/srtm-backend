package srtm.impuesto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

// GET /api/srtm/contribuyentes/{id}/liquidacion: the impuesto predial of a contribuyente's vigentes declaraciones,
// with the verified parameters loaded the way model/import_parametros.py loads them
class LiquidacionApiTest : SrtmApiTest() {
    @BeforeEach
    fun parametros() {
        // the test db is shared by the suite: load each row of the csv once
        val stored =
            tree(send("GET", "/api/objects/parametro_tributario/records?size=500", null, HttpStatus.OK))["content"]
                .iterator()
                .asSequence()
                .map { clave(it["attributes"]) }
                .toSet()
        for (p in Parametros.predial) {
            val attributes =
                mapOf(
                    "tipo" to p.tipo,
                    "clave" to p.clave,
                    "vigencia_desde" to p.vigenciaDesde.toString(),
                    "vigencia_hasta" to p.vigenciaHasta?.toString(),
                    "valor_numerico" to p.valorNumerico,
                    "texto" to p.texto,
                    "norma" to p.norma,
                    "fuente" to p.fuente,
                    "transcribio" to p.transcribio,
                    "verifico" to p.verifico
                ).filterValues { it != null }
            if (Triple(p.tipo, p.clave ?: "", p.vigenciaDesde.toString()) !in stored) {
                post("/api/objects/parametro_tributario/records", mapOf("attributes" to attributes))
            }
        }
    }

    @Test
    fun `two vigentes declaraciones add their valor afecto, the annulled one does not`() {
        val a = inscribir()
        // a predio of its own, 60000.00
        declarar(a, predio(), autoavaluo = "60000.00")
        // a predio held with another condómino who has 40 %: a's part is 60 % of 50000.00, 30000.00
        val compartido = predio()
        declarar(a, compartido, autoavaluo = "50000.00")
        declarar(inscribir(), compartido, autoavaluo = "50000.00", porcentaje = 40)
        // annulled: it counts nothing
        val anulada = declarar(a, predio(), autoavaluo = "1000000.00")
        send("POST", "/api/srtm/declaraciones/$anulada/anular", mapOf("motivo_anulacion" to "Declarada dos veces"), HttpStatus.OK)

        val l = liquidacion(a, 2026)

        // by hand: base 90000.00; up to 15 UIT at the first rate, the rest (under 60 UIT) at the second
        val uit = Parametros.uit(2026)
        val base = BigDecimal("90000.00")
        val corte = uit * Parametros.valor("TRAMO_PREDIAL_LIMITE", "1")
        val tramo1 = pct(corte, Parametros.valor("TRAMO_PREDIAL", "1"))
        val tramo2 = pct(base - corte, Parametros.valor("TRAMO_PREDIAL", "2"))
        val anual = tramo1 + tramo2
        val cuarto = anual.divide(BigDecimal(4), 2, RoundingMode.HALF_UP)

        assertEquals(2026, l["anio"].asInt())
        assertEquals(uit, l["uit"].let(::dec))
        assertEquals(base, l["base"].let(::dec))
        assertEquals(listOf(tramo1, tramo2, dos(BigDecimal.ZERO)), lista(l["tramos"]).map { it["impuesto"].let(::dec) })
        assertEquals(listOf(dos(corte), dos(base - corte), dos(BigDecimal.ZERO)), lista(l["tramos"]).map { it["monto"].let(::dec) })
        assertEquals(anual, l["impuestoCalculado"].let(::dec))
        assertEquals(pct(uit, Parametros.valor("PREDIAL_MINIMO")), l["minimo"].let(::dec))
        assertEquals(false, l["minimoAplicado"].asBoolean())
        assertEquals(anual, l["impuestoAnual"].let(::dec))
        assertEquals(listOf(cuarto, cuarto, cuarto, anual - cuarto * BigDecimal(3)), lista(l["cuotas"]).map { it["monto"].let(::dec) })
        assertEquals(listOf("2026-02-27", "2026-05-29", "2026-08-31", "2026-11-30"), lista(l["cuotas"]).map { it["vencimiento"].asString() })
        assertTrue(lista(l["faltan"]).isEmpty())
    }

    @Test
    fun `a year without its parameters says which are missing and no figure`() {
        val a = inscribir()
        declarar(a, predio(), autoavaluo = "60000.00", anio = 2027)

        val l = liquidacion(a, 2027)
        assertEquals(listOf("UIT 2027"), lista(l["faltan"]).map { it.asString() })
        assertEquals(BigDecimal("60000.00"), l["base"].let(::dec))
        for (campo in listOf("uit", "impuestoCalculado", "minimo", "minimoAplicado", "impuestoAnual")) {
            assertTrue(l.has(campo) && l[campo].isNull, "$campo: $l")
        }
        assertTrue(lista(l["tramos"]).isEmpty())
        assertTrue(lista(l["cuotas"]).isEmpty())
    }

    @Test
    fun `a contribuyente that does not exist is a 404`() {
        send("GET", "/api/srtm/contribuyentes/${UUID.randomUUID()}/liquidacion?anio=2026", null, HttpStatus.NOT_FOUND)
    }

    private fun liquidacion(
        contribuyente: String,
        anio: Int
    ): JsonNode = tree(send("GET", "/api/srtm/contribuyentes/$contribuyente/liquidacion?anio=$anio", null, HttpStatus.OK))

    private fun declarar(
        contribuyente: String,
        predio: String,
        autoavaluo: String,
        porcentaje: Int? = null,
        anio: Int = 2026
    ): String =
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to contribuyente,
                "predio" to predio,
                "anio" to anio,
                "secuencia_uso" to "1",
                "porcentaje_condominio" to porcentaje,
                "valor_autoavaluo" to BigDecimal(autoavaluo)
            )
        )["id"].asString()

    private fun clave(attributes: JsonNode) =
        Triple(attributes["tipo"].asString(), attributes["clave"]?.takeUnless { it.isNull }?.asString() ?: "", attributes["vigencia_desde"].asString())

    private fun pct(
        monto: BigDecimal,
        porcentaje: BigDecimal
    ): BigDecimal = (monto * porcentaje).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)

    private fun dos(valor: BigDecimal): BigDecimal = valor.setScale(2, RoundingMode.HALF_UP)

    // an amount of the response, to the centimo: json reads 5500.00 back as 5500.0
    private fun dec(node: JsonNode): BigDecimal = dos(node.decimalValue())

    private fun lista(node: JsonNode): List<JsonNode> = node.iterator().asSequence().toList()
}
