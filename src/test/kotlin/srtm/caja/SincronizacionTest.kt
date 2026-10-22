package srtm.caja

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class SincronizacionTest {
    private fun deseada(
        ranura: String = "ARB|p|s|2026|1|1",
        version: Int = 1,
        importe: String = "10.00",
        pagada: Boolean = false
    ) = OrdenDeseada(
        referencia = "$ranura-v$version",
        ranura = ranura,
        tipo = TipoOrdenCaja.ARBITRIO,
        importe = BigDecimal(importe),
        concepto = "c",
        detalle = null,
        cuotaArbitrio = UUID.randomUUID(),
        cuotaPredial = null,
        pagada = pagada
    )

    private fun actual(
        estado: String,
        ranura: String = "ARB|p|s|2026|1|1",
        version: Int = 1,
        sustituyeA: String? = null
    ) = OrdenCaja(
        id = UUID.randomUUID().toString(),
        referencia = "$ranura-v$version",
        ranura = ranura,
        estado = estado,
        sustituyeA = sustituyeA,
        importe = BigDecimal("10.00")
    )

    @Test
    fun mismaReferenciaNoHaceNada() {
        for (estado in listOf(EstadoOrdenCaja.ENVIADA, EstadoOrdenCaja.RETIRADA, EstadoOrdenCaja.CONFLICTO)) {
            val plan = planificar(listOf(deseada()), listOf(actual(estado)))
            assertEquals(Plan(emptyList(), emptyList(), emptyList()), plan, estado)
        }
    }

    @Test
    fun ranuraNuevaEsAlta() {
        val d = deseada()
        val plan = planificar(listOf(d), emptyList())
        assertEquals(listOf(Alta(d, null)), plan.altas)
        assertTrue(plan.retiros.isEmpty() && plan.cancelar.isEmpty())
    }

    @Test
    fun otraVersionSustituyeALaEnviada() {
        val d = deseada(version = 2)
        val plan = planificar(listOf(d), listOf(actual(EstadoOrdenCaja.ENVIADA)))
        assertEquals(listOf(Alta(d, "ARB|p|s|2026|1|1-v1")), plan.altas)
        assertTrue(plan.cancelar.isEmpty() && plan.retiros.isEmpty())
    }

    @Test
    fun otraVersionCancelaLaQueNoSalio() {
        val v1 = actual(EstadoOrdenCaja.POR_ENVIAR)
        val d = deseada(version = 2)
        val plan = planificar(listOf(d), listOf(v1))
        assertEquals(listOf(Alta(d, null)), plan.altas)
        assertEquals(listOf(UUID.fromString(v1.id)), plan.cancelar)
    }

    @Test
    fun pagadaNoSeToca() {
        val enCaja =
            planificar(
                listOf(deseada(version = 2)),
                listOf(actual(EstadoOrdenCaja.PAGADA_EN_CAJA), actual(EstadoOrdenCaja.POR_ENVIAR, version = 2))
            )
        assertEquals(Plan(emptyList(), emptyList(), emptyList()), enCaja)
        val deseadaPagada =
            planificar(
                listOf(deseada(version = 2, pagada = true)),
                listOf(actual(EstadoOrdenCaja.ENVIADA))
            )
        assertEquals(Plan(emptyList(), emptyList(), emptyList()), deseadaPagada)
        val sinDeseada =
            planificar(
                emptyList(),
                listOf(actual(EstadoOrdenCaja.PAGADA_EN_CAJA), actual(EstadoOrdenCaja.ENVIADA, version = 2))
            )
        assertEquals(Plan(emptyList(), emptyList(), emptyList()), sinDeseada)
    }

    @Test
    fun sinDeseadaSeRetira() {
        val enviada = actual(EstadoOrdenCaja.ENVIADA)
        val porEnviar = actual(EstadoOrdenCaja.POR_ENVIAR, version = 2)
        val plan = planificar(emptyList(), listOf(enviada, porEnviar))
        assertEquals(listOf(Retiro(UUID.fromString(enviada.id), enviada.referencia!!)), plan.retiros)
        assertEquals(listOf(UUID.fromString(porEnviar.id)), plan.cancelar)
        assertTrue(plan.altas.isEmpty())
    }

    @Test
    fun ceroSeRetira() {
        val enviada = actual(EstadoOrdenCaja.ENVIADA)
        val plan = planificar(listOf(deseada(version = 2, importe = "0.00")), listOf(enviada))
        assertTrue(plan.altas.isEmpty())
        assertEquals(listOf(Retiro(UUID.fromString(enviada.id), enviada.referencia!!)), plan.retiros)
    }

    @Test
    fun dosCambiosAntesDeEnviarSustituyenALaEnviada() {
        val v1 = actual(EstadoOrdenCaja.ENVIADA)
        val v2 = actual(EstadoOrdenCaja.POR_ENVIAR, version = 2, sustituyeA = v1.referencia)
        val d = deseada(version = 3)
        val plan = planificar(listOf(d), listOf(v1, v2))
        assertEquals(listOf(Alta(d, v1.referencia)), plan.altas)
        assertEquals(listOf(UUID.fromString(v2.id)), plan.cancelar)
        assertTrue(plan.retiros.isEmpty())
    }

    @Test
    fun conflictoNoEsVigente() {
        val v1 = actual(EstadoOrdenCaja.ENVIADA)
        val v2 = actual(EstadoOrdenCaja.CONFLICTO, version = 2)
        val d = deseada(version = 3)
        val plan = planificar(listOf(d), listOf(v1, v2))
        assertEquals(listOf(Alta(d, v1.referencia)), plan.altas)
        assertTrue(plan.cancelar.isEmpty() && plan.retiros.isEmpty())
    }

    @Test
    fun mismoImporteOtraVersionTambienSustituye() {
        val d = deseada(version = 2, importe = "10.00")
        val plan = planificar(listOf(d), listOf(actual(EstadoOrdenCaja.ENVIADA)))
        assertEquals(1, plan.altas.size)
        assertEquals("ARB|p|s|2026|1|1-v1", plan.altas[0].sustituyeA)
    }

    @Test
    fun ranuraDeArbitrioSinLaVersion() {
        assertEquals("ARB|p|s|2026|1|3", ranuraArbitrio("p|s|2026|1|3|2"))
        assertEquals("ARB|p|s|2026|1", ranuraArbitrio("p|s|2026|1|2"))
        assertEquals("ARB-p|s|2026|1|3|2", referenciaArbitrio("p|s|2026|1|3|2"))
    }

    @Test
    fun referenciaPredialMideMenosDe120() {
        val c = UUID.randomUUID()
        val ref = referenciaPredial(c, 2026, 4, 12)
        assertEquals("PREDIAL-$c-2026-C4-v12", ref)
        assertTrue(ref.length < 120)
    }
}
