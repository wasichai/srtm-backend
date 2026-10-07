package srtm.rentas

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import wasichai.core.common.PageResponse
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

// the caller's view of an object (MetadataService.definitionOf): a field their roles lock comes with
// editable = false, one they cannot read does not come at all
class RegistrosTest {
    @Test
    fun `a locked field, an unreadable one and a key that is no field are not sent`() {
        val definition = definicion(campo("codigo"), campo("observacion", editable = false))
        val attributes = mapOf("codigo" to "000012", "observacion" to "PRIMERA VISITA", "oculto" to "X", "otro" to 1)
        assertEquals(mapOf("codigo" to "000012"), Registros.soloEscribibles(definition, attributes))
    }

    @Test
    fun `a locked geometry is not sent either`() {
        val definition = definicion(campo("lote_geom", editable = false), campo("ubicacion"))
        val punto = mapOf("type" to "Point", "coordinates" to listOf(-75.2250, -10.9480))
        assertEquals(
            mapOf("ubicacion" to punto),
            Registros.soloEscribibles(definition, mapOf("lote_geom" to punto, "ubicacion" to punto))
        )
    }

    @Test
    fun `a writable field sent as null is still sent, because core's update is a full replace`() {
        val definition = definicion(campo("codigo"), campo("observacion"))
        assertEquals(
            mapOf("codigo" to "000012", "observacion" to null),
            Registros.soloEscribibles(definition, mapOf("codigo" to "000012", "observacion" to null))
        )
    }

    // lowest (TOPE_ARBITRIO's registro of a predio, #87): one row, sorted up by the field, among the records of the
    // filters that have it; and, when asked, that lack another field. core gets the query, nothing is read in full
    @Test
    fun `lowest reads one row sorted up by the field, among those that have it and lack the one asked`() =
        runBlocking {
            val pedidas = mutableListOf<RecordQuery>()
            var filas = listOf(RecordResponse("dj-1", null, null, mapOf("fecha_presentacion" to "2020-03-10", "anio" to 2020)))
            val records =
                Mockito.mock(RecordService::class.java) { invocacion ->
                    if (invocacion.method.name != "list") return@mock null
                    pedidas += invocacion.arguments[1] as RecordQuery
                    PageResponse(filas, 0, 1, filas.size.toLong(), 1)
                }
            val registros = Registros(records, Mockito.mock(MetadataService::class.java))
            val definition = definicion(campo("fecha_presentacion"), campo("anio"))

            assertEquals("2020-03-10", registros.lowest(DECLARACION, "fecha_presentacion", mapOf("predio" to "p-1")))
            val porFecha = pedidas.last()
            assertEquals(0, porFecha.page.page)
            assertEquals(1, porFecha.page.size)
            assertEquals("fecha_presentacion", porFecha.sort)
            assertFalse(porFecha.descending)
            assertEquals(mapOf("predio" to "p-1"), porFecha.filters)
            assertEquals(listOf("fecha_presentacion IS NOT NULL"), porFecha.criteria.map { it.condition(definition) { error("nada que ligar") } })

            assertEquals("2020", registros.lowest(DECLARACION, "anio", mapOf("predio" to "p-1"), vacio = "fecha_presentacion"))
            val porAnio = pedidas.last()
            assertEquals("anio", porAnio.sort)
            assertEquals(
                listOf("anio IS NOT NULL", "fecha_presentacion IS NULL"),
                porAnio.criteria.map { it.condition(definition) { error("nada que ligar") } }
            )

            filas = emptyList()
            assertNull(registros.lowest(DECLARACION, "fecha_presentacion", mapOf("predio" to "p-2")))
        }

    private val objeto = UUID.randomUUID()

    private fun definicion(vararg campos: CustomField) =
        ObjectDefinition(
            CustomObject(objeto, UUID.randomUUID(), CONTRIBUYENTE, "Contribuyente", "Contribuyentes", null, true, "t_contribuyente", null, null),
            campos.toList()
        )

    private fun campo(
        name: String,
        editable: Boolean = true
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
        editable = editable
    )
}
