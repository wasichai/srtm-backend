package srtm.visor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

// #273: the PRICO mark in the visor, end to end: a TERMINADA corrida of the seeded admin's organization (asked for by
// its id) with two predios drawn apart, one of a contribuyente marked prico and one of another who is not, and a
// caller whose role may not read contribuyente.prico
class VisorPricosApiTest : SrtmApiTest() {
    private class Datos(
        val corrida: String,
        val sufijo: String,
        val dePrico: String,
        val deOtro: String
    )

    private fun datos(): Datos = datosDeLaClase ?: sembrar().also { datosDeLaClase = it }

    private fun sembrar(): Datos {
        val sufijo = uniqueDocumento()
        val prico = inscribir()
        val otro = inscribir()
        send("PATCH", "/api/objects/contribuyente/records/$prico", mapOf("attributes" to mapOf("prico" to true)), HttpStatus.OK)

        fun predio(
            n: Int,
            contribuyente: String
        ): String {
            val id =
                post(
                    "/api/srtm/predios",
                    mapOf("codigo" to "R-$sufijo-$n", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO", "sector_catastral" to "01")
                )["id"].asString()
            post("/api/srtm/declaraciones", mapOf("contribuyente" to contribuyente, "predio" to id, "anio" to 2026, "secuencia_uso" to "1"))
            return id
        }
        val p1 = predio(1, prico)
        val p2 = predio(2, otro)
        val corrida =
            post(
                "/api/objects/brecha_corrida/records",
                mapOf(
                    "attributes" to
                        mapOf(
                            "anio" to 2026,
                            "estado" to "TERMINADA",
                            "version_catastro" to "valuacion-test",
                            "iniciado" to "2026-10-20T14:00:00Z",
                            "terminado" to "2026-10-20T15:00:00Z",
                            "total_predios" to 2,
                            "procesados" to 2
                        )
                )
            )["id"].asString()

        fun fila(
            orden: Int,
            predio: String,
            delta: Int,
            x: Double
        ) = post(
            "/api/objects/brecha_predio/records",
            mapOf(
                "attributes" to
                    mapOf(
                        "corrida" to corrida,
                        "categoria" to "SUBVALUADO",
                        "orden" to orden,
                        "estimado" to false,
                        "predio" to predio,
                        "codigo_predio" to "R-$sufijo-${orden + 1}",
                        "codigo_catastro" to "R-$sufijo-${orden + 1}",
                        "sector" to "01",
                        "manzana" to "A",
                        "lote" to "${orden + 1}",
                        "confianza" to "CONFIABLE",
                        "delta_impuesto" to delta
                    ),
                "geometries" to mapOf("lote_geom" to cuadrado(x))
            )
        )["id"].asString()
        return Datos(corrida, sufijo, fila(0, p1, 300, -75.71), fila(1, p2, 100, -75.72))
    }

    private fun cuadrado(x: Double) =
        mapOf(
            "type" to "Polygon",
            "coordinates" to listOf(listOf(listOf(x, -14.0), listOf(x + 0.0005, -14.0), listOf(x + 0.0005, -13.9995), listOf(x, -13.9995), listOf(x, -14.0)))
        )

    private fun leer(
        ruta: String,
        quien: String = token
    ): JsonNode = tree(send("GET", "/api/srtm/visor$ruta${if ('?' in ruta) "&" else "?"}anio=2026&corrida=${datos().corrida}", null, HttpStatus.OK, quien))

    // the rows of /predios by id: their prico
    private fun pricos(
        consulta: String,
        quien: String = token
    ): Map<String, Boolean> = leer("/predios?$consulta", quien)["content"].values().associate { it["id"].asString() to it["prico"].asBoolean() }

    // a caller who may read the visor and the padrón, but not contribuyente.prico
    private fun sinPrico(): String {
        val rol = uniqueName("ROL").uppercase()
        send("POST", "/api/roles", mapOf("name" to rol, "label" to rol), HttpStatus.CREATED)
        val permisos = listOf("brecha_corrida", "brecha_predio", "declaracion_predial", "contribuyente").map { permiso(it, "READ") }
        send("PUT", "/api/roles/$rol/permissions", mapOf("permissions" to permisos), HttpStatus.OK)
        send(
            "PUT",
            "/api/roles/$rol/field-permissions",
            mapOf("fields" to listOf(mapOf("objectName" to "contribuyente", "fieldName" to "prico", "read" to false, "write" to false))),
            HttpStatus.OK
        )
        val email = "${rol.lowercase()}@srtm.test"
        send("POST", "/api/users", mapOf("email" to email, "displayName" to rol, "password" to CLAVE, "roles" to listOf(rol)), HttpStatus.CREATED)
        return bearer(email, CLAVE)
    }

    @Test
    fun `the rows, the lotes and the predios of a lote say whether their titular is a PRICO, and prico filters them`() {
        val d = datos()
        assertEquals(mapOf(d.dePrico to true, d.deOtro to false), pricos("orden=codigo"))
        assertEquals(mapOf(d.dePrico to true), pricos("prico=true"))
        assertEquals(mapOf(d.deOtro to false), pricos("prico=false"))
        assertEquals(emptyMap<String, Boolean>(), pricos("prico=true&min_delta=500"))
        val features = leer("/lotes")["features"].values().associate { it["id"].asString() to it["properties"]["prico"].asBoolean() }
        assertEquals(mapOf(d.dePrico to true, d.deOtro to false), features)
        assertEquals(listOf(d.dePrico), leer("/lotes?prico=true")["features"].values().map { it["id"].asString() })
        val delLote = tree(send("GET", "/api/srtm/visor/lotes/R-${d.sufijo}-1/predios?anio=2026&corrida=${d.corrida}", null, HttpStatus.OK))
        assertEquals(listOf(true), delLote.values().map { it["prico"].asBoolean() })
        val csv = send("GET", "/api/srtm/visor/predios.csv?anio=2026&corrida=${d.corrida}&prico=true", null, HttpStatus.OK)
        assertEquals(
            2,
            csv
                .removePrefix("")
                .trimEnd()
                .split("\r\n")
                .size,
            csv
        )
        send("GET", "/api/srtm/visor/predios?anio=2026&corrida=${d.corrida}&prico=quiza", null, HttpStatus.UNPROCESSABLE_CONTENT)
    }

    @Test
    fun `the panel sums the PRICOs, and prico takes the panel of them alone`() {
        val k = leer("/panel")["kpis"]
        assertEquals(2, k["fichas"].asInt())
        assertEquals(1, k["pricos"]["predios"].asInt())
        assertEquals(0, BigDecimal(300).compareTo(k["pricos"]["delta_impuesto"].decimalValue()))
        val soloPricos = leer("/panel?prico=true")["kpis"]
        assertEquals(1, soloPricos["fichas"].asInt())
        assertEquals(0, BigDecimal(300).compareTo(soloPricos["delta_impuesto"].decimalValue()))
        assertEquals(1, leer("/panel?prico=false")["kpis"]["fichas"].asInt())
    }

    @Test
    fun `who may not read contribuyente prico sees no PRICO`() {
        val d = datos()
        // the admin first: the titulares are cached with the mark
        assertEquals(1, leer("/panel")["kpis"]["pricos"]["predios"].asInt())
        val quien = sinPrico()
        assertEquals(mapOf(d.dePrico to false, d.deOtro to false), pricos("orden=codigo", quien))
        assertEquals(emptyMap<String, Boolean>(), pricos("prico=true", quien))
        assertTrue(leer("/lotes", quien)["features"].values().none { it["properties"]["prico"].asBoolean() })
        assertTrue(leer("/panel", quien)["kpis"]["pricos"].isNull)
        assertEquals(0, leer("/panel?prico=true", quien)["kpis"]["fichas"].asInt())
        val delLote = tree(send("GET", "/api/srtm/visor/lotes/R-${d.sufijo}-1/predios?anio=2026&corrida=${d.corrida}", null, HttpStatus.OK, quien))
        assertFalse(delLote[0]["prico"].asBoolean())
    }

    companion object {
        private var datosDeLaClase: Datos? = null
        private const val CLAVE = "clave-del-usuario-de-pricos"
    }
}
