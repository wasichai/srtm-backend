package srtm

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
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import java.util.UUID
import srtm.anuncios.Ejemplos as EjemplosAnuncios
import srtm.sanciones.Ejemplos as EjemplosSanciones

// the guard wasichai calls before every write (srtm.GuardiaDeEscritura) on the whole app and PostGIS: no record of the
// sanciones or the anuncios is written through wasichai's generic api (POST/PUT/DELETE /api/objects/{objeto}/records),
// not even by an ADMIN. srtm writes them with the mark EscrituraDeSrtm (Registros puts it; here RecordService is called
// with it, as Registros does). the arbitrios keep their behavior (ArbitriosApiTest, AnulacionApiTest)
class GuardiaDeEscrituraApiTest : ConParametrosApiTest() {
    @Autowired
    lateinit var records: RecordService

    @Test
    fun `no record of the sanciones or the anuncios is added through the generic api, not even by an ADMIN`() {
        // the records each one points to, real: a relation to a missing one is core's 400 (D29), before the guard
        val c = inscribir()
        val uit = uit2026()
        val codigo = conLaMarca(CODIGO_INFRACCION, EjemplosSanciones.codigo("T-${uniqueDocumento()}"))
        val acta = conLaMarca(PAPELETA, EjemplosSanciones.papeleta("AC-${uniqueDocumento()}", codigo, uit, c))
        val notificacion = conLaMarca(NOTIFICACION_ADMINISTRATIVA, EjemplosSanciones.notificacion("NP-${uniqueDocumento()}", c))
        val anuncio = conLaMarca(ANUNCIO, EjemplosAnuncios.anuncio(c, correlativo = uniqueDocumento().toInt()))
        // a year only this class gives rows to, so no service numbers a resolución in it; numero is unique
        val correlativo = uniqueDocumento().take(6).toInt() + 1
        val resolucion =
            conLaMarca(
                RESOLUCION_GERENCIA,
                EjemplosSanciones.resolucion(acta) +
                    mapOf(
                        "anio" to ANIO_SIN_SERVICIO,
                        "correlativo" to correlativo,
                        "numero" to "RIS-$ANIO_SIN_SERVICIO-%06d".format(correlativo),
                        "plazo" to uit
                    )
            )
        val registros =
            mapOf(
                CODIGO_INFRACCION to EjemplosSanciones.codigo("T-${uniqueDocumento()}"),
                NOTIFICACION_ADMINISTRATIVA to EjemplosSanciones.notificacion("NP-${uniqueDocumento()}", c),
                SUBSANACION_NOTIFICACION to EjemplosSanciones.subsanacion(notificacion),
                PAPELETA to EjemplosSanciones.papeleta("AC-${uniqueDocumento()}", codigo, uit, c),
                ANULACION_PAPELETA to EjemplosSanciones.anulacion(acta),
                DESCARGO_PAPELETA to EjemplosSanciones.descargo(acta, uit),
                RESOLUCION_GERENCIA to EjemplosSanciones.resolucion(acta) + ("plazo" to uit),
                NOTIFICACION_RESOLUCION to EjemplosSanciones.notificacionResolucion(resolucion) + ("plazo" to uit),
                ANUNCIO to EjemplosAnuncios.anuncio(c),
                MOVIMIENTO_ANUNCIO to EjemplosAnuncios.movimiento(anuncio, parametro = uit)
            )
        for ((objeto, attrs) in registros) {
            val antes = cuantos(objeto)
            val problema = tree(send("POST", "/api/objects/$objeto/records", mapOf("attributes" to attrs), HttpStatus.FORBIDDEN))
            assertTrue(problema["detail"].asString().contains("«$objeto»"), problema.toString())
            assertEquals(antes, cuantos(objeto), "$objeto: nothing was written")
        }
    }

    // core checks the relation targets before any guard (D29): a missing one is a 400 on its field, not the guard's 403
    @Test
    fun `a relation to a record that does not exist is a 400 that names it, and nothing is written`() {
        val antes = cuantos(ANULACION_PAPELETA)
        val problema =
            tree(
                send(
                    "POST",
                    "/api/objects/$ANULACION_PAPELETA/records",
                    mapOf("attributes" to EjemplosSanciones.anulacion(UUID.randomUUID().toString())),
                    HttpStatus.BAD_REQUEST
                )
            )
        assertEquals(
            listOf("papeleta"),
            problema["errors"]
                .iterator()
                .asSequence()
                .map { it["field"].asString() }
                .toList(),
            problema.toString()
        )
        assertEquals(antes, cuantos(ANULACION_PAPELETA))
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

    // a record written as srtm writes it (Registros: RecordService as the caller, with the mark): its id
    private fun conLaMarca(
        objeto: String,
        attrs: Map<String, Any?>
    ): String = comoAdmin { withContext(EscrituraDeSrtm) { records.create(objeto, RecordRequest(attrs)).id } }

    private fun cerrar(
        id: String,
        attrs: Map<String, Any?>
    ) {
        comoAdmin { withContext(EscrituraDeSrtm) { records.update(CODIGO_INFRACCION, UUID.fromString(id), RecordRequest(attrs)) } }
    }

    private fun cuantos(objeto: String) = tree(send("GET", "/api/objects/$objeto/records?size=1", null, HttpStatus.OK))["totalElements"].asLong()

    // the UIT row of 2026 (model/data/parametros-predial.csv)
    private fun uit2026(): String =
        tree(send("GET", "/api/objects/parametro_tributario/records?tipo=UIT&vigencia_desde=2026-01-01", null, HttpStatus.OK))["content"][0]["id"].asString()

    // a record as a request body: its attributes
    private fun atributos(registro: JsonNode): Map<String, Any?> = fields(registro["attributes"])

    private companion object {
        // years only this class gives rows to
        const val ANIO_SIN_SERVICIO = 1984
    }
}
