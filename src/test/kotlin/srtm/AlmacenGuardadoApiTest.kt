package srtm

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import srtm.anuncios.ANUNCIO
import srtm.anuncios.MOVIMIENTO_ANUNCIO
import srtm.impuesto.ConParametrosApiTest
import srtm.sanciones.ANULACION_PAPELETA
import srtm.sanciones.CODIGO_INFRACCION
import srtm.sanciones.DESCARGO_PAPELETA
import srtm.sanciones.NOTIFICACION_ADMINISTRATIVA
import srtm.sanciones.NOTIFICACION_RESOLUCION
import srtm.sanciones.PAPELETA
import srtm.sanciones.RESOLUCION_GERENCIA
import srtm.sanciones.SUBSANACION_NOTIFICACION
import tools.jackson.databind.JsonNode
import wasichai.core.common.ConflictException
import wasichai.core.data.RecordStore
import wasichai.core.metadata.MetadataService
import java.util.UUID
import srtm.anuncios.Ejemplos as EjemplosAnuncios
import srtm.sanciones.Ejemplos as EjemplosSanciones

// the guard before every write (srtm.AlmacenGuardado) on the whole app and PostGIS: no record of the sanciones or the
// anuncios is written through wasichai's generic api (POST/PUT/DELETE /api/objects/{objeto}/records), not even by an
// ADMIN. srtm writes them with the mark EscrituraDeSrtm (Registros puts it; here the store is called with it, as
// Registros would, until the services that write them exist). the arbitrios keep their behavior (ArbitriosApiTest,
// AnulacionApiTest)
class AlmacenGuardadoApiTest : ConParametrosApiTest() {
    @Autowired
    lateinit var recordStore: RecordStore

    @Autowired
    lateinit var metadata: MetadataService

    @Test
    fun `no record of the sanciones or the anuncios is added through the generic api, not even by an ADMIN`() {
        val c = inscribir()
        val id = UUID.randomUUID().toString()
        val registros =
            mapOf(
                CODIGO_INFRACCION to EjemplosSanciones.codigo("T-${uniqueDocumento()}"),
                NOTIFICACION_ADMINISTRATIVA to EjemplosSanciones.notificacion("NP-${uniqueDocumento()}", c),
                SUBSANACION_NOTIFICACION to EjemplosSanciones.subsanacion(id),
                PAPELETA to EjemplosSanciones.papeleta("AC-${uniqueDocumento()}", id, id, c),
                ANULACION_PAPELETA to EjemplosSanciones.anulacion(id),
                DESCARGO_PAPELETA to EjemplosSanciones.descargo(id, id),
                RESOLUCION_GERENCIA to EjemplosSanciones.resolucion(id),
                NOTIFICACION_RESOLUCION to EjemplosSanciones.notificacionResolucion(id),
                ANUNCIO to EjemplosAnuncios.anuncio(c),
                MOVIMIENTO_ANUNCIO to EjemplosAnuncios.movimiento(id, parametro = id)
            )
        for ((objeto, attrs) in registros) {
            val antes = cuantos(objeto)
            val problema = tree(send("POST", "/api/objects/$objeto/records", mapOf("attributes" to attrs), HttpStatus.FORBIDDEN))
            assertTrue(problema["detail"].asString().contains("«$objeto»"), problema.toString())
            assertEquals(antes, cuantos(objeto), "$objeto: nothing was written")
        }
    }

    @Test
    fun `what srtm adds is never edited nor deleted through the generic api, not even by an ADMIN`() {
        val c = inscribir()
        val codigo = conLaMarca(CODIGO_INFRACCION, EjemplosSanciones.codigo("T-${uniqueDocumento()}"))
        val acta = conLaMarca(PAPELETA, EjemplosSanciones.papeleta("AC-${uniqueDocumento()}", codigo, uit2026(), c))
        val anuncio = conLaMarca(ANUNCIO, EjemplosAnuncios.anuncio(c, correlativo = uniqueDocumento().toInt()))

        for ((objeto, id, cambio) in listOf(Triple(PAPELETA, acta, "importe_a_pagar" to 1), Triple(ANUNCIO, anuncio, "area" to 99))) {
            val registro = "/api/objects/$objeto/records/$id"
            val antes = tree(send("GET", registro, null, HttpStatus.OK))
            send("PUT", registro, mapOf("attributes" to atributos(antes) + cambio), HttpStatus.CONFLICT)
            send("DELETE", registro, null, HttpStatus.CONFLICT)
            assertEquals(antes, tree(send("GET", registro, null, HttpStatus.OK)), "$objeto: untouched")
        }
        send("DELETE", "/api/objects/$CODIGO_INFRACCION/records/$codigo", null, HttpStatus.CONFLICT)
    }

    @Test
    fun `a CUIS version is closed once, by srtm, and one version of a codigo is in force at a time`() {
        val codigo = "T-${uniqueDocumento()}"
        val primera = conLaMarca(CODIGO_INFRACCION, EjemplosSanciones.codigo(codigo, "2026-01-01"))
        val registro = "/api/objects/$CODIGO_INFRACCION/records/$primera"
        val guardada = tree(send("GET", registro, null, HttpStatus.OK))
        val cierre = atributos(guardada) + mapOf("vigencia_hasta" to "2026-06-30", "clave_vigente" to null)

        // the generic api does not close it: only srtm's service, under its lock
        send("PUT", registro, mapOf("attributes" to cierre), HttpStatus.FORBIDDEN)
        // nor changes anything else
        send("PUT", registro, mapOf("attributes" to atributos(guardada) + ("porcentaje_uit" to 12)), HttpStatus.CONFLICT)
        assertEquals(guardada, tree(send("GET", registro, null, HttpStatus.OK)))

        // a second version in force of the same codigo: clave_vigente is unique
        assertThrows(DuplicateKeyException::class.java) { conLaMarca(CODIGO_INFRACCION, EjemplosSanciones.codigo(codigo, "2026-07-01")) }

        // srtm closes it and adds the next one; the closed one keeps every figure
        cerrar(primera, cierre)
        val cerrada = tree(send("GET", registro, null, HttpStatus.OK))["attributes"]
        assertEquals("2026-06-30", cerrada["vigencia_hasta"].asString())
        assertTrue(cerrada["clave_vigente"].isNull, cerrada.toString())
        assertEquals(10.0, cerrada["porcentaje_uit"].asDouble())
        val segunda = conLaMarca(CODIGO_INFRACCION, EjemplosSanciones.codigo(codigo, "2026-07-01"))

        // once
        assertThrows(ConflictException::class.java) { cerrar(primera, cierre + ("vigencia_hasta" to "2026-05-31")) }

        // many closed versions of a codigo: their empty clave_vigente does not clash
        cerrar(
            segunda,
            atributos(tree(send("GET", "/api/objects/$CODIGO_INFRACCION/records/$segunda", null, HttpStatus.OK))) +
                mapOf("vigencia_hasta" to "2026-09-30", "clave_vigente" to null)
        )
        conLaMarca(CODIGO_INFRACCION, EjemplosSanciones.codigo(codigo, "2026-10-01"))
        val versiones = tree(send("GET", "/api/objects/$CODIGO_INFRACCION/records?codigo=$codigo&size=10", null, HttpStatus.OK))["content"]
        val vigentes: List<String> =
            versiones
                .iterator()
                .asSequence()
                .map { it["attributes"]["clave_vigente"] }
                .map { if (it.isNull) "" else it.asString() }
                .sorted()
                .toList()
        assertEquals(listOf("", "", "ADMINISTRATIVA|$codigo"), vigentes)
    }

    // the admin's organization and id
    private val yo: JsonNode by lazy { tree(send("GET", "/api/auth/me", null, HttpStatus.OK)) }

    private fun organizacion() = UUID.fromString(yo["organizationId"].asString())

    private fun usuario() = UUID.fromString(yo["userId"].asString())

    // a record written as srtm writes it (Registros, with the mark), through the app's RecordStore: its id
    private fun conLaMarca(
        objeto: String,
        attrs: Map<String, Any?>
    ): String =
        runBlocking {
            withContext(EscrituraDeSrtm) {
                recordStore.insert(metadata.loadDefinition(organizacion(), objeto), organizacion(), usuario(), attrs, emptyMap()).id.toString()
            }
        }

    private fun cerrar(
        id: String,
        attrs: Map<String, Any?>
    ) {
        runBlocking {
            withContext(EscrituraDeSrtm) {
                recordStore.update(
                    metadata.loadDefinition(organizacion(), CODIGO_INFRACCION),
                    organizacion(),
                    usuario(),
                    UUID.fromString(id),
                    attrs,
                    emptyMap()
                )
            }
        }
    }

    private fun cuantos(objeto: String) = tree(send("GET", "/api/objects/$objeto/records?size=1", null, HttpStatus.OK))["totalElements"].asLong()

    // the UIT row of 2026 (model/data/parametros-predial.csv)
    private fun uit2026(): String =
        tree(send("GET", "/api/objects/parametro_tributario/records?tipo=UIT&vigencia_desde=2026-01-01", null, HttpStatus.OK))["content"][0]["id"].asString()

    // a record as a request body: its attributes
    private fun atributos(registro: JsonNode): Map<String, Any?> = fields(registro["attributes"])
}
