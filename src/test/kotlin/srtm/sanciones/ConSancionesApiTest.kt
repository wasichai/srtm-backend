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

// an api test of the sanciones: a FICTITIOUS UIT, PLAZO and FERIADOS rows for years no other test uses (decision 14),
// and the CUIS, the notificaciones, the actas, the descargos, the resoluciones and their notificaciones written through
// srtm's endpoints (conLaMarca writes a record raw, as srtm's services do, for what an endpoint would refuse). an acta
// is dated in the past (no act is dated after today), so its UIT is the one of ANIO_PASADO
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

    // a parametro_tributario row of `tipo` and `clave` for the whole of `anio`, added once: its id
    protected fun parametro(
        tipo: String,
        clave: String,
        anio: Int,
        valor: String? = null,
        texto: String? = null
    ): String {
        val filas =
            tree(
                send("GET", "/api/objects/parametro_tributario/records?tipo=$tipo&clave=$clave&vigencia_desde=$anio-01-01", null, HttpStatus.OK)
            )["content"]
        if (filas.size() > 0) return filas[0]["id"].asString()
        val attrs =
            mapOf(
                "tipo" to tipo,
                "clave" to clave,
                "vigencia_desde" to "$anio-01-01",
                "vigencia_hasta" to "$anio-12-31",
                "valor_numerico" to valor,
                "texto" to texto,
                "norma" to "Norma ficticia de prueba",
                "transcribio" to "PRUEBA",
                "verifico" to "OTRA PRUEBA"
            )
        return post("/api/objects/parametro_tributario/records", mapOf("attributes" to attrs))["id"].asString()
    }

    // the plazos of `anio` (FICTITIOUS: 5 business days for a descargo, 15 to impugnar a resolución), and its FERIADOS row
    // unless `conFeriados` is false (`feriados`: its movable ones, none by default)
    protected fun plazos(
        anio: Int,
        descargo: Int = 5,
        recurso: Int = 15,
        conFeriados: Boolean = true,
        feriados: String? = null
    ) {
        parametro(Llaves.PLAZO, Llaves.DESCARGO_PAPELETA, anio, "$descargo", Llaves.DIAS_HABILES)
        parametro(Llaves.PLAZO, Llaves.RG_RECURSO, anio, "$recurso", Llaves.DIAS_HABILES)
        if (conFeriados) parametro(Llaves.FERIADOS, Llaves.feriados(anio), anio, texto = feriados)
    }

    // the body of POST /infracciones/actas/{id}/descargos
    protected fun pedidoDescargo(
        fecha: String,
        tipo: String = "DESCARGO",
        expediente: String = "EXP-${uniqueDocumento()}"
    ): Map<String, Any?> =
        mapOf(
            "numero_expediente" to expediente,
            "tipo_recurso" to tipo,
            "fecha" to fecha,
            "sustento" to "Tenía la licencia en trámite",
            "observacion" to "Descargo de prueba"
        )

    // the body of POST /infracciones/actas/{id}/resoluciones, as the portal sends it: what does not apply, null
    protected fun pedidoResolucion(
        fecha: String?,
        tipo: String = RESOLUCION_ADMINISTRATIVA,
        descargo: String? = null,
        sentido: String? = descargo?.let { "INFUNDADO" },
        efecto: String? = descargo?.let { SE_MANTIENE },
        sancion: String? = null
    ): Map<String, Any?> =
        mapOf(
            "tipo" to tipo,
            "descargo" to descargo,
            "sentido" to sentido,
            "efecto" to efecto,
            "sustento" to "Se constató la infracción",
            "sancion_accesoria" to sancion,
            "observacion" to "Resolución de prueba"
        ) + (if (fecha == null) emptyMap() else mapOf("fecha" to fecha))

    // the body of POST /infracciones/resoluciones/{id}/notificacion: direccion null takes the obligado's domicilio fiscal
    protected fun pedidoNotificacion(
        fecha: String,
        resultado: String = "NOTIFICADO",
        direccion: String? = "JR. LIMA 123"
    ): Map<String, Any?> =
        mapOf(
            "fecha_diligencia" to fecha,
            "modalidad" to "PERSONAL",
            "resultado" to resultado,
            "notificador" to "NOTIFICADOR DE PRUEBA",
            "direccion" to direccion,
            "receptor" to null,
            "documento_receptor" to null,
            "vinculo" to null,
            "acuse" to null,
            "observacion" to "Notificación de prueba"
        )

    // the acts that answer an acta, through their endpoints: each one's 201
    protected fun descargar(
        acta: String,
        fecha: String,
        tipo: String = "DESCARGO"
    ): JsonNode = post("$ACTAS/$acta/descargos", pedidoDescargo(fecha, tipo))

    protected fun dictar(
        acta: String,
        fecha: String,
        tipo: String = RESOLUCION_ADMINISTRATIVA,
        descargo: String? = null,
        efecto: String? = descargo?.let { SE_MANTIENE }
    ): JsonNode = post("$ACTAS/$acta/resoluciones", pedidoResolucion(fecha, tipo, descargo, efecto = efecto))

    protected fun notificar(
        resolucion: String,
        fecha: String,
        resultado: String = "NOTIFICADO",
        direccion: String? = "JR. LIMA 123"
    ): JsonNode = post("$RESOLUCIONES/$resolucion/notificacion", pedidoNotificacion(fecha, resultado, direccion))

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

    // the body of POST /infracciones/actas: an acta of `codigo` (the CUIS code's text) on `fecha`, PRIMERA, naming the
    // obligado as its contribuyente. the optional fields go as explicit nulls, as the portal sends them
    protected fun pedidoActa(
        codigo: String,
        obligado: String,
        fecha: String = "$ANIO_PASADO-03-04",
        previa: String? = null,
        numero: String = "AC-${uniqueDocumento()}",
        reincidencia: String = "PRIMERA",
        contribuyente: String? = obligado,
        predio: String? = null
    ): Map<String, Any?> =
        mapOf(
            "numero" to numero,
            "fecha_infraccion" to fecha,
            "hora_infraccion" to "10:30",
            "lugar" to "JR. LIMA 123",
            "codigo" to codigo,
            "reincidencia" to reincidencia,
            "obligado" to obligado,
            "contribuyente" to contribuyente,
            "predio" to predio,
            "notificacion_previa" to previa,
            "expediente" to null,
            "inspector" to null,
            "descripcion_hecho" to null,
            "observacion" to "Acta de prueba"
        )

    // an acta registered through srtm's endpoint (a UIT of its year must be there): the 201
    protected fun registrarActa(
        codigo: String,
        obligado: String,
        fecha: String = "$ANIO_PASADO-03-04",
        previa: String? = null
    ): JsonNode = post(ACTAS, pedidoActa(codigo, obligado, fecha, previa))

    // an acta written raw, as ActasService writes it, for a test whose dates have no UIT (the CUIS' own): its id
    protected fun acta(
        codigo: String,
        uit: String,
        obligado: String
    ): String = conLaMarca(PAPELETA, Ejemplos.papeleta("AC-${uniqueDocumento()}", codigo, uit, obligado))

    protected fun registro(
        objeto: String,
        id: String
    ): JsonNode = tree(send("GET", "/api/objects/$objeto/records/$id", null, HttpStatus.OK))["attributes"]

    // a json array's elements
    protected fun JsonNode.filas(): List<JsonNode> = iterator().asSequence().toList()

    // the admin's organization and id
    private val yo: JsonNode by lazy { tree(send("GET", "/api/auth/me", null, HttpStatus.OK)) }

    // a record written as srtm's services write it (through the app's RecordStore, with the mark), for a state no
    // endpoint would write (an acta whose days have no UIT): its id
    protected fun conLaMarca(
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

    companion object {
        const val ACTAS = "/api/srtm/infracciones/actas"
        const val RESOLUCIONES = "/api/srtm/infracciones/resoluciones"

        // past years no other test gives a UIT: the first with Ficticios.UIT (once uit(ANIO_PASADO) runs), the second never
        const val ANIO_PASADO = 1991
        const val ANIO_PASADO_SIN_UIT = 1990
    }
}
