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
import srtm.arbitrios.CuotasInmutables
import srtm.sanciones.CODIGO_INFRACCION
import srtm.sanciones.PAPELETA
import srtm.sanciones.ReglaDeSanciones
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.common.WasichaiException
import wasichai.core.data.ObjectWorkflowState
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRow
import wasichai.core.data.RecordStore
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition
import java.io.File
import java.lang.reflect.Modifier
import java.util.UUID
import srtm.anuncios.Ejemplos as EjemplosAnuncios
import srtm.sanciones.Ejemplos as EjemplosSanciones

// the guard before every write (srtm.AlmacenGuardado), without Spring nor database: what it lets reach core's store
// and what it does not, with the mark EscrituraDeSrtm and without it. a test store notes each call that reaches it and
// answers findById with the row it is given
class AlmacenGuardadoTest {
    private val llegadas = mutableListOf<String>()
    private val almacen = AlmacenQueAnota(llegadas)
    private val guardado = AlmacenGuardado(almacen, listOf(CuotasInmutables(), ReglaDeSanciones(), ReglaDeAnuncios()))

    private val sanciones = ReglaDeSanciones().objetos
    private val anuncios = ReglaDeAnuncios().objetos

    @Test
    fun `without the mark no record of the sanciones or the anuncios is added, not even a valid one`() {
        val validos = mapOf(CODIGO_INFRACCION to EjemplosSanciones.codigo(), PAPELETA to EjemplosSanciones.papeleta(), ANUNCIO to EjemplosAnuncios.anuncio())
        (sanciones + anuncios).forEach { objeto ->
            val e = falla<ForbiddenException> { guardado.insert(definicion(objeto), ORGANIZACION, USUARIO, validos[objeto].orEmpty(), emptyMap()) }
            assertTrue(e.message.contains("«$objeto»") && e.message.contains("ADMIN"), e.message)
        }
        assertEquals(emptyList<String>(), llegadas)
    }

    @Test
    fun `with the mark a valid record is added, and one that breaks its invariants is a 400 that names the field`() {
        runBlocking {
            withContext(EscrituraDeSrtm) {
                guardado.insert(definicion(CODIGO_INFRACCION), ORGANIZACION, USUARIO, EjemplosSanciones.codigo(), emptyMap())
                guardado.insert(definicion(ANUNCIO), ORGANIZACION, USUARIO, EjemplosAnuncios.anuncio(), emptyMap())
            }
        }
        val e =
            falla<ValidationException> {
                withContext(EscrituraDeSrtm) {
                    guardado.insert(definicion(PAPELETA), ORGANIZACION, USUARIO, EjemplosSanciones.papeleta() + ("contribuyente" to null), emptyMap())
                }
            }
        assertEquals(listOf("contribuyente"), e.violations.map { it.field })
        assertEquals(listOf("insert $CODIGO_INFRACCION", "insert $ANUNCIO"), llegadas)
    }

    @Test
    fun `nothing of the sanciones or the anuncios is edited, deleted or moved, with the mark or without it`() {
        (sanciones + anuncios - CODIGO_INFRACCION).forEach { objeto ->
            listOf(false, true).forEach { marca ->
                falla<ConflictException>(marca) { guardado.update(definicion(objeto), ORGANIZACION, USUARIO, ID, mapOf("fecha" to "2026-03-04"), emptyMap()) }
            }
        }
        (sanciones + anuncios).forEach { objeto ->
            listOf(false, true).forEach { marca ->
                falla<ConflictException>(marca) { guardado.delete(definicion(objeto), ORGANIZACION, ID) }
                falla<ConflictException>(marca) { guardado.transitionState(definicion(objeto), ORGANIZACION, USUARIO, ID, null, "OTRO") }
            }
        }
        assertTrue(llegadas.none { it.startsWith("update") || it.startsWith("delete") || it.startsWith("transition") }, llegadas.toString())
    }

    @Test
    fun `a CUIS version in force is closed once, by srtm - the generic api cannot even do that`() {
        val codigo = definicion(CODIGO_INFRACCION, EjemplosSanciones.codigo().keys)
        val cierre = EjemplosSanciones.codigo() + mapOf("vigencia_hasta" to "2026-06-30", "clave_vigente" to null)
        almacen.fila = EjemplosSanciones.codigo()

        // the change the rule allows, without the mark: a 403 before writing
        falla<ForbiddenException> { guardado.update(codigo, ORGANIZACION, USUARIO, ID, cierre, emptyMap()) }
        // with it, it reaches the store
        runBlocking { withContext(EscrituraDeSrtm) { guardado.update(codigo, ORGANIZACION, USUARIO, ID, cierre, emptyMap()) } }
        // a change of anything else is a 409 either way
        falla<ConflictException>(true) { guardado.update(codigo, ORGANIZACION, USUARIO, ID, cierre + ("porcentaje_uit" to "12"), emptyMap()) }
        // and a closed version is not closed again
        almacen.fila = cierre
        falla<ConflictException>(true) { guardado.update(codigo, ORGANIZACION, USUARIO, ID, cierre + ("vigencia_hasta" to "2026-07-31"), emptyMap()) }

        assertEquals(listOf("update $CODIGO_INFRACCION"), llegadas.filter { it.startsWith("update") })
    }

    @Test
    fun `the arbitrios keep their behavior - added without the mark, never edited nor deleted`() {
        val cuota = definicion(CUOTA_ARBITRIO)
        val malas = falla<ValidationException> { guardado.insert(cuota, ORGANIZACION, USUARIO, mapOf("periodo" to 13), emptyMap()) }
        assertTrue("periodo" in malas.violations.map { it.field })
        runBlocking { guardado.insert(definicion(ANULACION_CUOTA_ARBITRIO), ORGANIZACION, USUARIO, emptyMap(), emptyMap()) }
        listOf(CUOTA_ARBITRIO, ANULACION_CUOTA_ARBITRIO).forEach { objeto ->
            val e = falla<ConflictException> { guardado.update(definicion(objeto), ORGANIZACION, USUARIO, ID, emptyMap(), emptyMap()) }
            assertTrue(e.message.startsWith("Una cuota de arbitrio y su anulación no se editan ni se borran"), e.message)
            falla<ConflictException> { guardado.delete(definicion(objeto), ORGANIZACION, ID) }
            falla<ConflictException> { guardado.transitionState(definicion(objeto), ORGANIZACION, USUARIO, ID, null, "OTRO") }
        }
        assertEquals(listOf("insert $ANULACION_CUOTA_ARBITRIO"), llegadas.filter { !it.startsWith("findById") })
    }

    @Test
    fun `what has no rule passes without the mark, and so do the reads`() {
        runBlocking {
            val contribuyente = definicion("contribuyente")
            guardado.insert(contribuyente, ORGANIZACION, USUARIO, emptyMap(), emptyMap())
            guardado.update(contribuyente, ORGANIZACION, USUARIO, ID, emptyMap(), emptyMap())
            guardado.transitionState(contribuyente, ORGANIZACION, USUARIO, ID, null, "OTRO")
            guardado.delete(contribuyente, ORGANIZACION, ID)
            guardado.findById(definicion(PAPELETA), ORGANIZACION, ID)
            guardado.query(definicion(ANUNCIO), ORGANIZACION, RecordQuery(PageRequest.of(0, 1)))
        }
        assertEquals(
            listOf(
                "insert contribuyente",
                "update contribuyente",
                "transitionState contribuyente",
                "delete contribuyente",
                "findById $PAPELETA",
                "query $ANUNCIO"
            ),
            llegadas
        )
    }

    @Test
    fun `an object has one rule at most`() {
        val e = assertThrows(IllegalStateException::class.java) { AlmacenGuardado(almacen, listOf(CuotasInmutables(), CuotasInmutables())) }
        assertTrue(e.message!!.contains(CUOTA_ARBITRIO), e.message)
    }

    // the guard delegates by `by`: a write method a new version of wasichai added to the RecordStore would pass without
    // asking any rule. this fails first, and forces a decision on what the guard does with it
    @Test
    fun `wasichai's RecordStore has the methods the guard knows`() {
        val metodos =
            RecordStore::class.java.declaredMethods
                .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet()
        assertEquals(setOf("insert", "update", "transitionState", "delete", "findById", "query"), metodos)
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

    private fun definicion(
        objeto: String,
        campos: Collection<String> = emptyList()
    ): ObjectDefinition {
        val id = UUID.randomUUID()
        return ObjectDefinition(
            CustomObject(id, ORGANIZACION, objeto, objeto, objeto, null, true, "t_$objeto", null, null),
            campos.map { campo(id, it) }
        )
    }

    private fun campo(
        objeto: UUID,
        name: String
    ) = CustomField(
        id = UUID.randomUUID(),
        objectId = objeto,
        name = name,
        label = name,
        type = FieldType.TEXT,
        columnName = name,
        required = false,
        unique = false,
        defaultValue = null,
        description = null,
        position = 0,
        enumOptions = null,
        relationTargetObjectId = null,
        visible = true,
        editable = true
    )

    // notes each call as «method object»; findById answers `fila`
    private class AlmacenQueAnota(
        private val llegadas: MutableList<String>
    ) : RecordStore {
        var fila: Map<String, Any?> = emptyMap()

        private fun fila(
            metodo: String,
            definition: ObjectDefinition
        ): RecordRow {
            llegadas += "$metodo ${definition.obj.name}"
            return RecordRow(UUID.randomUUID(), null, null, fila)
        }

        override suspend fun insert(
            definition: ObjectDefinition,
            organizationId: UUID,
            userId: UUID,
            attributes: Map<String, Any?>,
            sections: Map<String, Map<String, Any?>>,
            workflow: ObjectWorkflowState
        ) = fila("insert", definition)

        override suspend fun update(
            definition: ObjectDefinition,
            organizationId: UUID,
            userId: UUID,
            id: UUID,
            attributes: Map<String, Any?>,
            sections: Map<String, Map<String, Any?>>,
            withState: Boolean
        ) = fila("update", definition)

        override suspend fun transitionState(
            definition: ObjectDefinition,
            organizationId: UUID,
            userId: UUID,
            id: UUID,
            from: String?,
            to: String
        ) = fila("transitionState", definition)

        override suspend fun delete(
            definition: ObjectDefinition,
            organizationId: UUID,
            id: UUID
        ): Boolean {
            fila("delete", definition)
            return true
        }

        override suspend fun findById(
            definition: ObjectDefinition,
            organizationId: UUID,
            id: UUID,
            createdBy: UUID?,
            withState: Boolean
        ) = fila("findById", definition)

        override suspend fun query(
            definition: ObjectDefinition,
            organizationId: UUID,
            query: RecordQuery
        ): PageResponse<RecordRow> {
            fila("query", definition)
            return PageResponse(emptyList(), 0, 1, 0, 0)
        }
    }

    private companion object {
        val ORGANIZACION: UUID = UUID.randomUUID()
        val USUARIO: UUID = UUID.randomUUID()
        val ID: UUID = UUID.randomUUID()
    }
}
