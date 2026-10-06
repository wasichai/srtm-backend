package srtm

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.anuncios.ANUNCIO
import srtm.anuncios.ReglaDeAnuncios
import srtm.arbitrios.ANULACION_CUOTA_ARBITRIO
import srtm.arbitrios.CUOTA_ARBITRIO
import srtm.arbitrios.ReglaDeArbitrios
import srtm.sanciones.CODIGO_INFRACCION
import srtm.sanciones.PAPELETA
import srtm.sanciones.ReglaDeSanciones
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.ValidationException
import wasichai.core.common.WasichaiException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordWrite
import java.io.File
import java.util.UUID
import srtm.anuncios.Ejemplos as EjemplosAnuncios
import srtm.sanciones.Ejemplos as EjemplosSanciones

// the guard wasichai calls before every write (srtm.GuardiaDeEscritura), without Spring nor database: which writes it
// lets through and which it refuses, with the mark EscrituraDeSrtm and without it. a write that passes returns, one
// that does not throws, and nothing would reach the store
class GuardiaDeEscrituraTest {
    private val guardia = GuardiaDeEscritura(listOf(ReglaDeArbitrios(), ReglaDeSanciones(), ReglaDeAnuncios()))

    private val sanciones = ReglaDeSanciones().objetos
    private val anuncios = ReglaDeAnuncios().objetos

    @Test
    fun `without the mark no record of the sanciones or the anuncios is added, not even a valid one`() {
        val validos = mapOf(CODIGO_INFRACCION to EjemplosSanciones.codigo(), PAPELETA to EjemplosSanciones.papeleta(), ANUNCIO to EjemplosAnuncios.anuncio())
        (sanciones + anuncios).forEach { objeto ->
            val e = falla<ForbiddenException> { guardia.beforeWrite(escritura(objeto, RecordChangeKind.CREATED, validos[objeto].orEmpty())) }
            assertTrue(e.message.contains("«$objeto»") && e.message.contains("ADMIN"), e.message)
        }
    }

    @Test
    fun `with the mark a valid record is added, and one that breaks its invariants is a 400 that names the field`() {
        runBlocking {
            withContext(EscrituraDeSrtm) {
                guardia.beforeWrite(escritura(CODIGO_INFRACCION, RecordChangeKind.CREATED, EjemplosSanciones.codigo()))
                guardia.beforeWrite(escritura(ANUNCIO, RecordChangeKind.CREATED, EjemplosAnuncios.anuncio()))
            }
        }
        val e =
            falla<ValidationException>(true) {
                guardia.beforeWrite(escritura(PAPELETA, RecordChangeKind.CREATED, EjemplosSanciones.papeleta() + ("contribuyente" to null)))
            }
        assertEquals(listOf("contribuyente"), e.violations.map { it.field })
    }

    // the platform (asPlatform) and the automations write with no user and in their own coroutine: no mark either
    @Test
    fun `a write of the platform or of an automation is refused for the sanciones, not for the arbitrios`() {
        val e =
            falla<ForbiddenException> {
                guardia.beforeWrite(escritura(PAPELETA, RecordChangeKind.CREATED, EjemplosSanciones.papeleta(), usuario = null))
            }
        assertTrue(e.message.contains("«$PAPELETA»"), e.message)
        runBlocking { guardia.beforeWrite(escritura(CUOTA_ARBITRIO, RecordChangeKind.CREATED, CUOTA, usuario = null)) }
    }

    @Test
    fun `nothing of the sanciones or the anuncios is edited, deleted or moved, with the mark or without it`() {
        (sanciones + anuncios - CODIGO_INFRACCION).forEach { objeto ->
            listOf(false, true).forEach { marca ->
                falla<ConflictException>(marca) { guardia.beforeWrite(escritura(objeto, RecordChangeKind.UPDATED, mapOf("fecha" to "2026-03-04"))) }
            }
        }
        (sanciones + anuncios).forEach { objeto ->
            listOf(false, true).forEach { marca ->
                falla<ConflictException>(marca) { guardia.beforeWrite(escritura(objeto, RecordChangeKind.DELETED)) }
                falla<ConflictException>(marca) { guardia.beforeWrite(escritura(objeto, RecordChangeKind.TRANSITIONED)) }
            }
        }
    }

    @Test
    fun `a CUIS version in force is closed once, by srtm - the generic api cannot even do that`() {
        val guardada = EjemplosSanciones.codigo()
        val cierre = guardada + mapOf("vigencia_hasta" to "2026-06-30", "clave_vigente" to null)

        fun cambio(
            antes: Map<String, Any?>,
            pedido: Map<String, Any?>
        ) = escritura(CODIGO_INFRACCION, RecordChangeKind.UPDATED, pedido, antes)

        // the change the rule allows, without the mark: a 403 before writing
        falla<ForbiddenException> { guardia.beforeWrite(cambio(guardada, cierre)) }
        // with it, it passes
        runBlocking { withContext(EscrituraDeSrtm) { guardia.beforeWrite(cambio(guardada, cierre)) } }
        // a change of anything else is a 409 either way
        falla<ConflictException>(true) { guardia.beforeWrite(cambio(guardada, cierre + ("porcentaje_uit" to "12"))) }
        // a field left out of the request is one core's update clears: a change as well
        falla<ConflictException>(true) { guardia.beforeWrite(cambio(guardada, cierre - "porcentaje_uit")) }
        // and a closed version is not closed again
        falla<ConflictException>(true) { guardia.beforeWrite(cambio(cierre, cierre + ("vigencia_hasta" to "2026-07-31"))) }
    }

    // a cuota and its anulación are appendOnly in the model: core refuses their change, delete or transition before
    // the guard is asked (ArbitriosApiTest). the rule only checks a new cuota, which needs no mark
    @Test
    fun `a cuota is added without the mark if it holds its invariantes, and the anulación has no rule`() {
        val malas = falla<ValidationException> { guardia.beforeWrite(escritura(CUOTA_ARBITRIO, RecordChangeKind.CREATED, mapOf("periodo" to 13))) }
        assertTrue("periodo" in malas.violations.map { it.field })
        // a value not of its field's type is a 400 that names the field, not a 500
        val tipo = falla<ValidationException> { guardia.beforeWrite(escritura(CUOTA_ARBITRIO, RecordChangeKind.CREATED, CUOTA + ("anio" to "dos"))) }
        assertEquals(listOf("anio"), tipo.violations.map { it.field })
        runBlocking {
            guardia.beforeWrite(escritura(CUOTA_ARBITRIO, RecordChangeKind.CREATED, CUOTA))
            guardia.beforeWrite(escritura(ANULACION_CUOTA_ARBITRIO, RecordChangeKind.CREATED, emptyMap()))
            guardia.beforeWrite(escritura(ANULACION_CUOTA_ARBITRIO, RecordChangeKind.DELETED))
        }
    }

    @Test
    fun `what has no rule passes without the mark`() {
        runBlocking {
            guardia.beforeWrite(escritura("contribuyente", RecordChangeKind.CREATED, mapOf("nombre" to "X")))
            guardia.beforeWrite(escritura("contribuyente", RecordChangeKind.UPDATED, mapOf("nombre" to "Y"), mapOf("nombre" to "X")))
            guardia.beforeWrite(escritura("contribuyente", RecordChangeKind.DELETED, before = mapOf("nombre" to "Y")))
            guardia.beforeWrite(escritura("contribuyente", RecordChangeKind.TRANSITIONED, before = mapOf("nombre" to "Y")))
        }
    }

    @Test
    fun `an object has one rule at most`() {
        val e = assertThrows(IllegalStateException::class.java) { GuardiaDeEscritura(listOf(ReglaDeArbitrios(), ReglaDeArbitrios())) }
        assertTrue(e.message!!.contains(CUOTA_ARBITRIO), e.message)
    }

    // each module has its rule, and none knows the others: sanciones and anuncios do not import each other
    @Test
    fun `the sanciones and the anuncios do not know each other`() {
        fun importa(
            modulo: String,
            otro: String
        ) = File("src/main/kotlin/srtm/$modulo").walk().filter { it.isFile }.any { it.readText().contains("import srtm.$otro.") }
        assertTrue(!importa("sanciones", "anuncios") && !importa("anuncios", "sanciones"))
        assertTrue(!importa("sanciones", "arbitrios") && !importa("anuncios", "arbitrios"))
    }

    // the call fails with T: with the mark when `marca`
    private inline fun <reified T : WasichaiException> falla(
        marca: Boolean = false,
        crossinline bloque: suspend () -> Unit
    ): T =
        assertThrows(T::class.java) {
            runBlocking { if (marca) withContext(EscrituraDeSrtm) { bloque() } else bloque() }
        }

    // a write as wasichai hands it to the guard: a new record has no id yet, and `usuario` null is the platform's
    private fun escritura(
        objeto: String,
        kind: RecordChangeKind,
        attrs: Map<String, Any?>? = null,
        before: Map<String, Any?>? = null,
        usuario: UUID? = USUARIO
    ) = RecordWrite(ORGANIZACION, usuario, UUID.randomUUID(), objeto, if (kind == RecordChangeKind.CREATED) null else ID, kind, before, attrs)

    private companion object {
        val ORGANIZACION: UUID = UUID.randomUUID()
        val USUARIO: UUID = UUID.randomUUID()
        val ID: UUID = UUID.randomUUID()

        // a valid cuota, as the determination writes it (CuotaArbitrioTest)
        val CUOTA =
            mapOf(
                "predio" to "p",
                "contribuyente" to "c",
                "servicio" to "s",
                "parametro" to "t",
                "anio" to 2026,
                "periodo" to 1,
                "monto" to "10",
                "parametro_aplicado" to "TASA_ARBITRIO:LIMPIEZA:Z1:CASA",
                "fecha_calculo" to "2026-03-01",
                "observacion" to "Se determina para la prueba",
                "clave" to "p|s|2026|1|1"
            )
    }
}
