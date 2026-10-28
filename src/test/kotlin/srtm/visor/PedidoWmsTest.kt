package srtm.visor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// what srtm checks of a GetMap before it relays it to catastro's WMS (#260): catastro-backend's PedidoWms rules, and
// only the organization's own layers of the visor's list. what passes goes on with the known parameters only
class PedidoWmsTest {
    private val predios = "predio_catastral__7c45fb26__lote_geom"
    private val tramos = "tramo_via__7c45fb26__geom"
    private val permitidas = setOf(predios, tramos)

    private fun getMap(vararg cambios: Pair<String, String?>): Map<String, List<String>> {
        val base =
            linkedMapOf(
                "service" to "WMS",
                "version" to "1.1.1",
                "request" to "GetMap",
                "layers" to predios,
                "styles" to "",
                "srs" to "EPSG:3857",
                "bbox" to "-8430000,-1240000,-8420000,-1230000",
                "width" to "256",
                "height" to "256",
                "format" to "image/png",
                "transparent" to "true"
            )
        for ((k, v) in cambios) if (v == null) base.remove(k) else base[k] = v
        return base.mapValues { listOf(it.value) }
    }

    private fun valido(params: Map<String, List<String>>): PedidoWms.Valido {
        val p = PedidoWms.leer(params, permitidas)
        assertTrue(p is PedidoWms.Valido, "$params: $p")
        return p as PedidoWms.Valido
    }

    private fun rechazado(
        params: Map<String, List<String>>,
        estado: Int = 422
    ): PedidoWms.Rechazado {
        val p = PedidoWms.leer(params, permitidas)
        assertTrue(p is PedidoWms.Rechazado, "$params: $p")
        assertEquals(estado, (p as PedidoWms.Rechazado).estado, p.toString())
        return p
    }

    @Test
    fun `a GetMap of the organization's layers goes on with its known parameters, in their order`() {
        val p = valido(getMap())
        assertEquals(listOf(predios), p.capas)
        assertEquals(
            listOf("service", "version", "request", "layers", "styles", "srs", "bbox", "width", "height", "format", "transparent"),
            p.parametros.map { it.first }
        )
        // a qualified name, any casing of the parameter names, two layers
        val dos = valido(mapOf("LAYERS" to listOf("catastro:$predios,$tramos")) + getMap("layers" to null))
        assertEquals(listOf(predios, tramos), dos.capas)
        assertEquals("$predios,$tramos", dos.parametros.toMap()["layers"])
        assertEquals("EPSG:3857", valido(getMap("srs" to null, "crs" to "EPSG:3857", "version" to "1.3.0")).parametros.toMap()["crs"])
        for (formato in listOf("image/png8", "image/jpeg", "image/webp", "IMAGE/PNG")) valido(getMap("format" to formato))
        valido(getMap("width" to "2048", "height" to "1"))
        valido(getMap("styles" to predios))
    }

    @Test
    fun `exceptions is dropped, never relayed`() {
        val p = valido(getMap("exceptions" to "application/vnd.ogc.se_inimage"))
        assertTrue(p.parametros.none { it.first == "exceptions" })
    }

    @Test
    fun `only GetMap of WMS`() {
        for (r in listOf("GetCapabilities", "GetFeatureInfo", "GetLegendGraphic", "DescribeLayer")) rechazado(getMap("request" to r))
        rechazado(getMap("request" to null))
        rechazado(getMap("service" to "WFS"))
        rechazado(getMap("service" to null))
    }

    @Test
    fun `only image formats and sizes of 1 to 2048`() {
        for (f in listOf("application/pdf", "image/svg+xml", "application/json;type=utfgrid", "text/html")) rechazado(getMap("format" to f))
        rechazado(getMap("format" to null))
        for (lado in listOf("0", "2049", "x", "-1")) {
            rechazado(getMap("width" to lado))
            rechazado(getMap("height" to lado))
        }
        rechazado(getMap("width" to null))
        rechazado(getMap("bbox" to null))
    }

    @Test
    fun `a parameter it does not know, or two values of one, is refused`() {
        for (p in listOf("sld_body", "sld", "cql_filter", "filter", "featureid", "viewparams", "info_format", "query_layers", "format_options", "otro")) {
            rechazado(getMap(p to "x"))
        }
        rechazado(mapOf("width" to listOf("256", "512")) + getMap("width" to null))
        // the same value twice is one value
        valido(mapOf("width" to listOf("256", "256")) + getMap("width" to null))
    }

    @Test
    fun `a layer of the list only, at most 10, else a 403`() {
        assertEquals(403, rechazado(getMap("layers" to "brecha_ficha__7c45fb26__lote_geom"), 403).estado)
        // another organization's layer of the same object
        rechazado(getMap("layers" to "predio_catastral__949ce38d__lote_geom"), 403)
        rechazado(getMap("layers" to "$predios,brecha_ficha__7c45fb26__lote_geom"), 403)
        rechazado(getMap("layers" to "otro:$predios"), 403)
        rechazado(getMap("layers" to "catastro:a:$predios"), 403)
        rechazado(getMap("layers" to ""))
        rechazado(getMap("layers" to null))
        val once = (1..11).map { "o${it}__7c45fb26__geom" }.toSet()
        val p = PedidoWms.leer(getMap("layers" to once.joinToString(",")), once)
        assertTrue(p is PedidoWms.Rechazado && p.estado == 422, p.toString())
    }

    @Test
    fun `a style is empty or one of its layers`() {
        rechazado(getMap("styles" to "polygon"))
        rechazado(getMap("styles" to "brecha_ficha__7c45fb26__lote_geom"))
    }

    @Test
    fun `the parameter refused is named`() {
        assertEquals("format", rechazado(getMap("format" to "text/html")).parametro)
        assertEquals("cql_filter", rechazado(getMap("cql_filter" to "INCLUDE")).parametro)
        assertEquals("layers", rechazado(getMap("layers" to "x__7c45fb26__geom"), 403).parametro)
    }
}
