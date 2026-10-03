package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.rentas.Records
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException
import java.math.BigDecimal

// what a record of the sanciones must be to be written, whoever writes it (rentas' CHECKs and SPEC §4), and the one
// change the CUIS admits: closing the version in force
class ReglaDeSancionesTest {
    private val regla = ReglaDeSanciones()

    // the fields a 400 names, or none
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
    fun `a well formed record of each object passes`() {
        val bien =
            mapOf(
                CODIGO_INFRACCION to Ejemplos.codigo(),
                NOTIFICACION_ADMINISTRATIVA to Ejemplos.notificacion(),
                SUBSANACION_NOTIFICACION to Ejemplos.subsanacion(),
                PAPELETA to Ejemplos.papeleta(),
                ANULACION_PAPELETA to Ejemplos.anulacion(),
                DESCARGO_PAPELETA to Ejemplos.descargo(),
                RESOLUCION_GERENCIA to Ejemplos.resolucion(),
                NOTIFICACION_RESOLUCION to Ejemplos.notificacionResolucion()
            )
        assertEquals(regla.objetos, bien.keys)
        bien.forEach { (objeto, attrs) -> assertEquals(emptyList<String>(), campos(objeto, attrs), objeto) }
        assertEquals(emptyList<String>(), campos(RESOLUCION_GERENCIA, Ejemplos.resolucion(tipo = "RECURSO", descargo = "d")))
        assertEquals(emptyList<String>(), campos(NOTIFICACION_RESOLUCION, Ejemplos.notificacionResolucion(resultado = "NO_UBICADO")))
        assertEquals(emptyList<String>(), campos(NOTIFICACION_ADMINISTRATIVA, Ejemplos.notificacion() + ("plazo_dias" to null)))
    }

    @Test
    fun `every act says why, 5 to 500 characters`() {
        regla.objetos.forEach { objeto ->
            val attrs =
                when (objeto) {
                    CODIGO_INFRACCION -> Ejemplos.codigo()
                    NOTIFICACION_ADMINISTRATIVA -> Ejemplos.notificacion()
                    SUBSANACION_NOTIFICACION -> Ejemplos.subsanacion()
                    PAPELETA -> Ejemplos.papeleta()
                    ANULACION_PAPELETA -> Ejemplos.anulacion()
                    DESCARGO_PAPELETA -> Ejemplos.descargo()
                    RESOLUCION_GERENCIA -> Ejemplos.resolucion()
                    else -> Ejemplos.notificacionResolucion()
                }
            assertEquals(listOf("observacion"), campos(objeto, attrs + ("observacion" to "Ok")), objeto)
            assertEquals(listOf("observacion"), campos(objeto, attrs + ("observacion" to "x".repeat(501))), objeto)
        }
    }

    @Test
    fun `a CUIS version - its code, its alicuotas, its lengths, its keys`() {
        val c = Ejemplos.codigo()
        assertEquals(listOf("codigo"), campos(CODIGO_INFRACCION, c + ("codigo" to "a-042") + claves("a-042")))
        assertEquals(listOf("codigo"), campos(CODIGO_INFRACCION, c + ("codigo" to "A".repeat(21)) + claves("A".repeat(21))))
        assertEquals(listOf("porcentaje_uit"), campos(CODIGO_INFRACCION, c + ("porcentaje_uit" to "0")))
        assertEquals(listOf("porcentaje_uit"), campos(CODIGO_INFRACCION, c + ("porcentaje_uit" to "100.01")))
        assertEquals(emptyList<String>(), campos(CODIGO_INFRACCION, c + ("porcentaje_uit" to "100")))
        assertEquals(listOf("porcentaje_uit_segunda"), campos(CODIGO_INFRACCION, c + ("porcentaje_uit_segunda" to "-1")))
        assertEquals(listOf("descripcion"), campos(CODIGO_INFRACCION, c + ("descripcion" to "x".repeat(501))))
        assertEquals(listOf("materia"), campos(CODIGO_INFRACCION, c + ("materia" to "x".repeat(61))))
        assertEquals(listOf("medida_complementaria"), campos(CODIGO_INFRACCION, c + ("medida_complementaria" to "x".repeat(161))))
        assertEquals(listOf("base_legal"), campos(CODIGO_INFRACCION, c + ("base_legal" to " ")))
        assertEquals(listOf("base_legal"), campos(CODIGO_INFRACCION, c + ("base_legal" to "x".repeat(201))))
        assertEquals(listOf("clave"), campos(CODIGO_INFRACCION, c + ("clave" to "ADMINISTRATIVA|A-042")))
        // one in force per codigo: its clave_vigente while open, never "" (it would be one more unique value)
        assertEquals(listOf("clave_vigente"), campos(CODIGO_INFRACCION, c + ("clave_vigente" to null)))
        assertEquals(listOf("clave_vigente"), campos(CODIGO_INFRACCION, c + ("clave_vigente" to "")))
        assertEquals(emptyList<String>(), campos(CODIGO_INFRACCION, c + mapOf("vigencia_hasta" to "2026-06-30", "clave_vigente" to null)))
        assertEquals(listOf("vigencia_hasta"), campos(CODIGO_INFRACCION, c + mapOf("vigencia_hasta" to "2025-12-31", "clave_vigente" to null)))
    }

    @Test
    fun `a value that is not of its field's type is a 400 that names it`() {
        assertEquals(listOf("porcentaje_uit"), campos(CODIGO_INFRACCION, Ejemplos.codigo() + ("porcentaje_uit" to "diez")))
        assertEquals(listOf("fecha_infraccion"), campos(PAPELETA, Ejemplos.papeleta() + ("fecha_infraccion" to "04/03/2026")))
    }

    @Test
    fun `an acta - a contribuyente or a predio, its multa, its number`() {
        val p = Ejemplos.papeleta()
        // rentas' papeleta_familia_ck
        assertEquals(listOf("contribuyente"), campos(PAPELETA, p + ("contribuyente" to null)))
        assertEquals(emptyList<String>(), campos(PAPELETA, p + mapOf("contribuyente" to null, "predio" to "x")))
        // obligado is explicit, never deduced
        assertEquals(listOf("obligado"), campos(PAPELETA, p + ("obligado" to null)))
        assertEquals(listOf("numero"), campos(PAPELETA, p + mapOf("numero" to " AC-1", "clave" to "ADMINISTRATIVA| AC-1")))
        assertEquals(listOf("clave"), campos(PAPELETA, p + ("clave" to "AC-0001")))
        assertEquals(listOf("hora_infraccion"), campos(PAPELETA, p + ("hora_infraccion" to "24:00")))
        assertEquals(listOf("base_imponible"), campos(PAPELETA, p + ("base_imponible" to "0")))
        assertEquals(listOf("importe_a_pagar"), campos(PAPELETA, p + ("importe_a_pagar" to "-0.01")))
        assertEquals(listOf("porcentaje_a_cobrar"), campos(PAPELETA, p + ("porcentaje_a_cobrar" to null)))
        assertEquals(listOf("lugar"), campos(PAPELETA, p + ("lugar" to "x".repeat(301))))
        assertEquals(listOf("descripcion_hecho"), campos(PAPELETA, p + ("descripcion_hecho" to "x".repeat(1001))))
    }

    @Test
    fun `an acta's names in json are the model's`() {
        val p = Records.read<Papeleta>("", Ejemplos.papeleta())
        assertEquals(BigDecimal("10"), p.porcentajeACobrar)
        assertEquals(BigDecimal("550.00"), p.importeAPagar)
        val attributes = Records.attributes(p)
        assertTrue("porcentaje_a_cobrar" in attributes && "importe_a_pagar" in attributes, attributes.keys.toString())
    }

    @Test
    fun `one of each act per thing - its clave is the id it is about`() {
        assertEquals(listOf("clave"), campos(SUBSANACION_NOTIFICACION, Ejemplos.subsanacion() + ("clave" to "otra")))
        assertEquals(listOf("clave"), campos(ANULACION_PAPELETA, Ejemplos.anulacion() + ("clave" to "otra")))
        assertEquals(listOf("motivo"), campos(ANULACION_PAPELETA, Ejemplos.anulacion() + ("motivo" to null)))
        assertEquals(listOf("clave"), campos(NOTIFICACION_RESOLUCION, Ejemplos.notificacionResolucion() + ("clave" to "r|2")))
    }

    @Test
    fun `a notificacion's plazo is a positive number of days, or none`() {
        assertEquals(listOf("plazo_dias"), campos(NOTIFICACION_ADMINISTRATIVA, Ejemplos.notificacion() + ("plazo_dias" to 0)))
        assertEquals(listOf("plazo_dias"), campos(NOTIFICACION_ADMINISTRATIVA, Ejemplos.notificacion() + ("plazo_dias" to 32768)))
        assertEquals(listOf("numero"), campos(NOTIFICACION_ADMINISTRATIVA, Ejemplos.notificacion("np-1")))
    }

    @Test
    fun `a descargo is in plazo when filed by presentado_hasta, and the late one is recorded`() {
        // rentas' descargo_plazo_ck
        assertEquals(emptyList<String>(), campos(DESCARGO_PAPELETA, Ejemplos.descargo(fecha = "2026-03-12")))
        assertEquals(emptyList<String>(), campos(DESCARGO_PAPELETA, Ejemplos.descargo(fecha = "2026-03-13", enPlazo = false)))
        assertEquals(listOf("en_plazo"), campos(DESCARGO_PAPELETA, Ejemplos.descargo(fecha = "2026-03-13", enPlazo = true)))
        assertEquals(listOf("en_plazo"), campos(DESCARGO_PAPELETA, Ejemplos.descargo(fecha = "2026-03-12", enPlazo = false)))
        assertEquals(listOf("plazo"), campos(DESCARGO_PAPELETA, Ejemplos.descargo() + ("plazo" to null)))
        assertEquals(listOf("sustento"), campos(DESCARGO_PAPELETA, Ejemplos.descargo() + ("sustento" to "x".repeat(1001))))
    }

    @Test
    fun `a resolucion - its number, one RIS per acta, one per descargo, the fallo with the descargo`() {
        val ris = Ejemplos.resolucion()
        assertEquals(listOf("numero"), campos(RESOLUCION_GERENCIA, ris + ("numero" to "RGR-2026-000007")))
        assertEquals(listOf("numero"), campos(RESOLUCION_GERENCIA, ris + ("numero" to "RIS-2026-7")))
        assertEquals(listOf("clave_ris"), campos(RESOLUCION_GERENCIA, ris + ("clave_ris" to null)))
        val recurso = Ejemplos.resolucion(tipo = "RECURSO", descargo = "d")
        assertEquals(listOf("clave_ris"), campos(RESOLUCION_GERENCIA, recurso + ("clave_ris" to "p")))
        assertEquals(listOf("clave_descargo"), campos(RESOLUCION_GERENCIA, recurso + ("clave_descargo" to null)))
        // rentas' _recurso_ck: a RECURSO resolves a descargo
        assertEquals(
            listOf("descargo"),
            campos(RESOLUCION_GERENCIA, recurso + mapOf("descargo" to null, "clave_descargo" to null, "sentido" to null, "efecto" to null))
        )
        // rentas' _fallo_ck: (descargo != null) == (sentido != null && efecto != null)
        assertEquals(listOf("sentido"), campos(RESOLUCION_GERENCIA, recurso + ("efecto" to null)))
        assertEquals(listOf("sentido"), campos(RESOLUCION_GERENCIA, ris + mapOf("sentido" to "FUNDADO", "efecto" to "SE_DEJA_SIN_EFECTO")))
        assertEquals(listOf("sustento"), campos(RESOLUCION_GERENCIA, ris + ("sustento" to "x".repeat(1001))))
    }

    @Test
    fun `a notificacion de resolucion takes effect with its exigible_desde and plazo, and only then`() {
        // rentas' notificacion_exigibilidad_ck
        val notificada = Ejemplos.notificacionResolucion()
        assertEquals(listOf("exigible_desde"), campos(NOTIFICACION_RESOLUCION, notificada + ("plazo" to null)))
        assertEquals(listOf("exigible_desde"), campos(NOTIFICACION_RESOLUCION, notificada + ("exigible_desde" to null)))
        val noUbicado = Ejemplos.notificacionResolucion(resultado = "NO_UBICADO")
        assertEquals(listOf("exigible_desde"), campos(NOTIFICACION_RESOLUCION, noUbicado + mapOf("exigible_desde" to "2026-04-14", "plazo" to "t")))
        assertEquals(listOf("exigible_desde"), campos(NOTIFICACION_RESOLUCION, notificada + ("exigible_desde" to "2026-03-22")))
        assertEquals(listOf("intento", "clave"), campos(NOTIFICACION_RESOLUCION, notificada + ("intento" to 0)))
        assertEquals(listOf("notificador"), campos(NOTIFICACION_RESOLUCION, notificada + ("notificador" to "x".repeat(61))))
    }

    // closing a version of the CUIS: the one change the store lets through, with the mark (AlmacenGuardadoTest)

    private val guardado = Ejemplos.codigo()
    private val cerrado = guardado + mapOf("vigencia_hasta" to "2026-06-30", "clave_vigente" to null)

    @Test
    fun `a CUIS version in force is closed once - its vigencia_hasta and clave_vigente, nothing else`() {
        regla.alActualizar(CODIGO_INFRACCION, guardado, cerrado)
        // the same version read back from the database: numbers and dates as core gives them
        regla.alActualizar(CODIGO_INFRACCION, guardado + ("porcentaje_uit" to BigDecimal("10.0000")), cerrado)
        // closed the day it started: it ruled one day
        regla.alActualizar(CODIGO_INFRACCION, guardado, cerrado + ("vigencia_hasta" to "2026-01-01"))

        val yaCerrada = assertThrows(ConflictException::class.java) { regla.alActualizar(CODIGO_INFRACCION, cerrado, cerrado) }
        assertTrue(yaCerrada.message.contains("una sola vez"), yaCerrada.message)
        val otro =
            assertThrows(ConflictException::class.java) {
                regla.alActualizar(CODIGO_INFRACCION, guardado, cerrado + ("porcentaje_uit" to "12"))
            }
        assertTrue(otro.message.contains("porcentaje_uit"), otro.message)
        // core's update clears what the request leaves out: a field missing from it is a change
        assertThrows(ConflictException::class.java) { regla.alActualizar(CODIGO_INFRACCION, guardado, cerrado + ("materia" to null)) }
        // nothing changed is no closing either
        assertThrows(ConflictException::class.java) { regla.alActualizar(CODIGO_INFRACCION, guardado, guardado) }
        assertThrows(ConflictException::class.java) { regla.alActualizar(CODIGO_INFRACCION, guardado, guardado + ("clave_vigente" to null)) }

        val antes =
            assertThrows(ValidationException::class.java) { regla.alActualizar(CODIGO_INFRACCION, guardado, cerrado + ("vigencia_hasta" to "2025-12-31")) }
        assertEquals("vigencia_hasta", antes.violations.single().field)
        val sigueVigente =
            assertThrows(ValidationException::class.java) {
                regla.alActualizar(CODIGO_INFRACCION, guardado, cerrado + ("clave_vigente" to "ADMINISTRATIVA|A-042"))
            }
        assertEquals("clave_vigente", sigueVigente.violations.single().field)
    }

    @Test
    fun `nothing else of the sanciones changes, nor is deleted`() {
        (regla.objetos - CODIGO_INFRACCION).forEach { objeto ->
            val e = assertThrows(ConflictException::class.java) { regla.alActualizar(objeto, mapOf("fecha" to "2026-03-04"), mapOf("fecha" to "2026-03-04")) }
            assertTrue(e.message.contains("solo se agrega"), e.message)
        }
        regla.objetos.forEach { objeto ->
            assertThrows(ConflictException::class.java) { regla.alBorrar(objeto) }
            assertThrows(ConflictException::class.java) { regla.alCambiarDeEstado(objeto) }
        }
        assertTrue(regla.soloDesdeElServicio)
    }

    private fun claves(codigo: String) = mapOf("clave" to "ADMINISTRATIVA|$codigo|2026-01-01", "clave_vigente" to "ADMINISTRATIVA|$codigo")
}
