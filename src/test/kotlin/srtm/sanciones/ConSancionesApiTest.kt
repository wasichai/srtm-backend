package srtm.sanciones

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import srtm.EscrituraDeSrtm
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import wasichai.core.data.RecordStore
import wasichai.core.metadata.MetadataService
import java.util.UUID

// an api test of the sanciones: a FICTITIOUS UIT for a year no other test uses (decision 14), the CUIS and the
// notificaciones written through srtm's endpoints, and what B3 has no endpoint for yet (an acta) written as srtm's
// services write it: through the app's RecordStore, with the mark
abstract class ConSancionesApiTest : SrtmApiTest() {
    @Autowired
    lateinit var recordStore: RecordStore

    @Autowired
    lateinit var metadata: MetadataService

    // the UIT row of `anio` (Ficticios.UIT by default), added once: its id
    protected fun uit(
        anio: Int,
        valor: String = Ficticios.UIT
    ): String {
        val filas = tree(send("GET", "/api/objects/parametro_tributario/records?tipo=UIT&vigencia_desde=$anio-01-01", null, HttpStatus.OK))["content"]
        if (filas.size() > 0) return filas[0]["id"].asString()
        val attrs =
            mapOf(
                "tipo" to "UIT",
                "vigencia_desde" to "$anio-01-01",
                "vigencia_hasta" to "$anio-12-31",
                "valor_numerico" to valor,
                "norma" to "Norma ficticia de prueba",
                "transcribio" to "PRUEBA",
                "verifico" to "OTRA PRUEBA"
            )
        return post("/api/objects/parametro_tributario/records", mapOf("attributes" to attrs))["id"].asString()
    }

    // a code nobody else has
    protected fun nuevoCodigo() = "T-${uniqueDocumento()}"

    // the body of POST /infracciones/cuis: 10 % the first time, 15 % the second, none the third
    protected fun version(
        codigo: String,
        desde: String,
        porcentaje: Any = 10,
        segunda: Any? = 15,
        tercera: Any? = null
    ): Map<String, Any?> =
        mapOf(
            "codigo" to codigo,
            "descripcion" to "No exhibir la licencia (ficticio)",
            "materia" to "COMERCIO",
            "porcentaje_uit" to porcentaje,
            "porcentaje_uit_segunda" to segunda,
            "porcentaje_uit_tercera" to tercera,
            "medida_complementaria" to "Clausura temporal",
            "base_legal" to "Ordenanza ficticia 001",
            "vigencia_desde" to desde,
            "observacion" to "Carga de prueba del CUIS"
        )

    // the body of POST /infracciones/notificaciones
    protected fun notificacion(
        numero: String,
        fecha: String,
        contribuyente: String?,
        plazo: Int? = 5
    ): Map<String, Any?> =
        mapOf(
            "numero" to numero,
            "fecha" to fecha,
            "contribuyente" to contribuyente,
            "predio" to null,
            "direccion" to "JR. LIMA 123",
            "motivo" to "Letrero sin licencia",
            "plazo_dias" to plazo,
            "observacion" to "Notificación de prueba"
        )

    // an acta written as srtm's acta service will (B4), naming `previa` when given: its id
    protected fun acta(
        codigo: String,
        uit: String,
        obligado: String,
        previa: String? = null
    ): String = conLaMarca(PAPELETA, Ejemplos.papeleta("AC-${uniqueDocumento()}", codigo, uit, obligado) + ("notificacion_previa" to previa))

    protected fun registro(
        objeto: String,
        id: String
    ): JsonNode = tree(send("GET", "/api/objects/$objeto/records/$id", null, HttpStatus.OK))["attributes"]

    // a json array's elements
    protected fun JsonNode.filas(): List<JsonNode> = iterator().asSequence().toList()

    // the admin's organization and id
    private val yo: JsonNode by lazy { tree(send("GET", "/api/auth/me", null, HttpStatus.OK)) }

    private fun conLaMarca(
        objeto: String,
        attrs: Map<String, Any?>
    ): String {
        val organizacion = UUID.fromString(yo["organizationId"].asString())
        val usuario = UUID.fromString(yo["userId"].asString())
        return runBlocking {
            withContext(EscrituraDeSrtm) {
                recordStore.insert(metadata.loadDefinition(organizacion, objeto), organizacion, usuario, attrs, emptyMap()).id.toString()
            }
        }
    }
}
