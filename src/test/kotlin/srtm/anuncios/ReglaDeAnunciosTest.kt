package srtm.anuncios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException

// what an anuncio and its movimientos must be to be written, whoever writes them (rentas' CHECKs and SPEC §4)
class ReglaDeAnunciosTest {
    private val regla = ReglaDeAnuncios()

    private fun campos(
        objeto: String,
        attrs: Map<String, Any?>
    ): List<String> =
        try {
            regla.alInsertar(objeto, attrs)
            emptyList()
        } catch (e: ValidationException) {
            e.violations.map { it.field }
        }

    @Test
    fun `a well formed anuncio and each movimiento pass`() {
        assertEquals(setOf(ANUNCIO, MOVIMIENTO_ANUNCIO), regla.objetos)
        assertEquals(emptyList<String>(), campos(ANUNCIO, Ejemplos.anuncio()))
        assertEquals(emptyList<String>(), campos(ANUNCIO, Ejemplos.anuncio() + ("vigencia_hasta" to null)))
        listOf(
            AUTORIZACION,
            RENOVACION,
            CESE,
            RETIRO
        ).forEach { assertEquals(emptyList<String>(), campos(MOVIMIENTO_ANUNCIO, Ejemplos.movimiento(tipo = it)), it) }
    }

    @Test
    fun `an anuncio - its number, area, lados, cantidad, vigencia and lengths`() {
        val a = Ejemplos.anuncio()
        assertEquals(listOf("numero"), campos(ANUNCIO, a + ("numero" to "AN-2026-1")))
        assertEquals(listOf("area"), campos(ANUNCIO, a + ("area" to "0")))
        assertEquals(listOf("lados"), campos(ANUNCIO, a + ("lados" to 0)))
        assertEquals(listOf("cantidad"), campos(ANUNCIO, a + ("cantidad" to 0)))
        assertEquals(listOf("vigencia_hasta"), campos(ANUNCIO, a + ("vigencia_hasta" to "2026-03-01")))
        assertEquals(listOf("denominacion"), campos(ANUNCIO, a + ("denominacion" to "x".repeat(241))))
        assertEquals(listOf("emplazamiento"), campos(ANUNCIO, a + ("emplazamiento" to "x".repeat(31))))
        assertEquals(listOf("licencia_texto"), campos(ANUNCIO, a + ("licencia_texto" to "x".repeat(41))))
        assertEquals(listOf("contribuyente"), campos(ANUNCIO, a + ("contribuyente" to null)))
        assertEquals(listOf("observacion"), campos(ANUNCIO, a + ("observacion" to "Ok")))
        // an empty key is null: "" would be one more unique value
        assertEquals(listOf("clave_idempotencia"), campos(ANUNCIO, a + ("clave_idempotencia" to "")))
        assertEquals(listOf("clave_idempotencia"), campos(ANUNCIO, a + ("clave_idempotencia" to "x".repeat(65))))
        assertEquals(emptyList<String>(), campos(ANUNCIO, a + ("clave_idempotencia" to "x".repeat(64))))
    }

    @Test
    fun `an accrual is its ejercicio, referencia, tasa and the row it was read from - and only an accrual`() {
        // rentas' _devengo_ck and _tasa_check
        val autorizacion = Ejemplos.movimiento()
        listOf("anio", "referencia_cargo", "tasa", "parametro").forEach { falta ->
            assertTrue("referencia_cargo" in campos(MOVIMIENTO_ANUNCIO, autorizacion + (falta to null)), falta)
        }
        assertEquals(listOf("tasa"), campos(MOVIMIENTO_ANUNCIO, autorizacion + ("tasa" to "0")))
        assertEquals(listOf("referencia_cargo"), campos(MOVIMIENTO_ANUNCIO, autorizacion + ("referencia_cargo" to "ANUNCIO-otro-2026")))
        val cese = Ejemplos.movimiento(tipo = CESE)
        assertEquals(
            listOf("referencia_cargo"),
            campos(MOVIMIENTO_ANUNCIO, cese + mapOf("anio" to 2026, "referencia_cargo" to "ANUNCIO-a-2026", "tasa" to "12.50", "parametro" to "t"))
        )
    }

    @Test
    fun `one act of each per anuncio - and a renewal per ejercicio`() {
        assertEquals(listOf("clave"), campos(MOVIMIENTO_ANUNCIO, Ejemplos.movimiento() + ("clave" to "a|AUTORIZACION|2026")))
        assertEquals(listOf("clave"), campos(MOVIMIENTO_ANUNCIO, Ejemplos.movimiento(tipo = RENOVACION) + ("clave" to "a|RENOVACION")))
        assertEquals("a|RENOVACION|2027", claveDeMovimiento("a", RENOVACION, 2027))
        assertEquals("a|CESE", claveDeMovimiento("a", CESE))
        assertEquals("ANUNCIO-a-2027", referenciaDeCargo("a", 2027))
        assertEquals("AN-2026-000042", numeroDeAnuncio(2026, 42))
    }

    @Test
    fun `the cese and the retiro say why, have no vigencia, and only they say why`() {
        // rentas' _motivo_ck
        assertEquals(listOf("motivo"), campos(MOVIMIENTO_ANUNCIO, Ejemplos.movimiento(tipo = RETIRO) + ("motivo" to null)))
        assertEquals(listOf("motivo"), campos(MOVIMIENTO_ANUNCIO, Ejemplos.movimiento() + ("motivo" to "Porque sí")))
        assertEquals(listOf("vigencia_hasta"), campos(MOVIMIENTO_ANUNCIO, Ejemplos.movimiento(tipo = CESE) + ("vigencia_hasta" to "2026-12-31")))
        assertEquals(listOf("motivo"), campos(MOVIMIENTO_ANUNCIO, Ejemplos.movimiento(tipo = CESE) + ("motivo" to "x".repeat(501))))
    }

    @Test
    fun `nothing of the anuncios changes, nor is deleted`() {
        regla.objetos.forEach { objeto ->
            val e = assertThrows(ConflictException::class.java) { regla.alActualizar(objeto, emptyMap(), emptyMap()) }
            assertTrue(e.message.contains("solo se agrega"), e.message)
            assertThrows(ConflictException::class.java) { regla.alBorrar(objeto) }
            assertThrows(ConflictException::class.java) { regla.alCambiarDeEstado(objeto) }
        }
        assertTrue(regla.soloDesdeElServicio)
    }
}
