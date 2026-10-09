package srtm.impuesto

import org.junit.jupiter.api.BeforeEach
import org.springframework.http.HttpStatus
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode

// an api test that liquidates: the verified parameters loaded the way model/import_parametros.py loads them
abstract class ConParametrosApiTest : SrtmApiTest() {
    @BeforeEach
    fun parametros() {
        // the test db is shared by the suite (the arbitrios' tests add rows too): load each row of the csv once
        val stored = mutableSetOf<Triple<String, String, String>>()
        var page = 0
        do {
            val result = tree(send("GET", "/api/objects/parametro_tributario/records?size=200&page=$page", null, HttpStatus.OK))
            result["content"].iterator().forEach { stored += clave(it["attributes"]) }
            page++
        } while (page < result["totalPages"].asInt())
        for (p in Parametros.predial) {
            val attributes =
                mapOf(
                    "tipo" to p.tipo,
                    "clave" to p.clave,
                    "vigencia_desde" to p.vigenciaDesde.toString(),
                    "vigencia_hasta" to p.vigenciaHasta?.toString(),
                    "valor_numerico" to p.valorNumerico,
                    "texto" to p.texto,
                    "norma" to p.norma,
                    "fuente" to p.fuente,
                    "transcribio" to p.transcribio,
                    "verifico" to p.verifico
                ).filterValues { it != null }
            if (Triple(p.tipo, p.clave ?: "", p.vigenciaDesde.toString()) !in stored) {
                post("/api/objects/parametro_tributario/records", mapOf("attributes" to attributes))
            }
        }
    }

    private fun clave(attributes: JsonNode) =
        Triple(attributes["tipo"].asString(), attributes["clave"]?.takeUnless { it.isNull }?.asString() ?: "", attributes["vigencia_desde"].asString())
}
