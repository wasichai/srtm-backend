package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldType
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
