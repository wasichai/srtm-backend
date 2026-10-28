package srtm.visor

// a GetMap the visor relays to catastro's WMS (#260), checked here before anything leaves srtm, with
// catastro-backend's PedidoWms rules (catastro checks it again behind its nginx's auth_request): a raster GetMap of
// at most MAX_CAPAS layers, at most MAX_LADO pixels a side, in an image format, with no parameter it does not know
// (an SLD or a CQL filter could reach another layer). its layers must be of `permitidas`, the organization's own
// layers of the visor's list: one of another organization or object is a 403, anything else it cannot serve a 422.
// exceptions is dropped (catastro's nginx sets its own); what passes goes on with the known parameters only, in
// their order, its layers by their bare names
sealed interface PedidoWms {
    data class Valido(
        val parametros: List<Pair<String, String>>,
        val capas: List<String>
    ) : PedidoWms

    data class Rechazado(
        val estado: Int,
        val parametro: String,
        val motivo: String
    ) : PedidoWms

    companion object {
        const val WORKSPACE = "catastro"
        const val MAX_CAPAS = 10
        const val MAX_LADO = 2048

        // what goes on, in this order
        val CONOCIDOS = listOf("service", "version", "request", "layers", "styles", "srs", "crs", "bbox", "width", "height", "format", "transparent")
        private val DESCARTADOS = setOf("exceptions")
        private val FORMATOS = setOf("image/png", "image/png8", "image/jpeg", "image/webp")

        fun leer(
            params: Map<String, List<String>>,
            permitidas: Set<String>
        ): PedidoWms {
            val valores = mutableMapOf<String, MutableSet<String>>()
            for ((nombre, lista) in params) {
                val clave = nombre.trim().lowercase()
                if (clave in DESCARTADOS) continue
                if (clave !in CONOCIDOS) return Rechazado(422, nombre, "no es un parámetro de un GetMap que se reenvíe")
                valores.getOrPut(clave) { linkedSetOf() } += lista.map { it.trim() }
            }
            valores.entries.firstOrNull { it.value.size > 1 }?.let { return Rechazado(422, it.key, "trae más de un valor") }
            val unico = valores.mapValues { it.value.single() }

            if (unico["service"]?.lowercase() != "wms") return Rechazado(422, "service", "debe ser WMS")
            if (unico["request"]?.lowercase() != "getmap") return Rechazado(422, "request", "debe ser GetMap")
            if (unico["format"]?.lowercase() !in FORMATOS) return Rechazado(422, "format", "debe ser ${FORMATOS.joinToString()}")
            for (lado in listOf("width", "height")) {
                if (unico[lado]?.toIntOrNull() !in 1..MAX_LADO) return Rechazado(422, lado, "debe ser un entero de 1 a $MAX_LADO")
            }
            if (unico["bbox"].isNullOrEmpty()) return Rechazado(422, "bbox", "es obligatorio")

            val capas =
                nombres(unico["layers"]) ?: return Rechazado(403, "layers", "nombra una capa de otro workspace")
            if (capas.isEmpty()) return Rechazado(422, "layers", "debe nombrar una capa")
            if (capas.size > MAX_CAPAS) return Rechazado(422, "layers", "nombra más de $MAX_CAPAS capas")
            capas.firstOrNull { it !in permitidas }?.let { return Rechazado(403, "layers", "la capa $it no es una capa del visor de esta organización") }
            // a layer's style is named after it (wasichai-gis): one of its layers', or none (the default)
            val estilos = nombres(unico["styles"]) ?: return Rechazado(422, "styles", "nombra un estilo de otro workspace")
            estilos.firstOrNull { it !in capas }?.let { return Rechazado(422, "styles", "el estilo $it no es el de una de sus capas") }

            val parametros =
                CONOCIDOS.mapNotNull { clave ->
                    val valor = unico[clave] ?: return@mapNotNull null
                    clave to if (clave == "layers") capas.joinToString(",") else valor
                }
            return Valido(parametros, capas)
        }

        // the names of a list, without the workspace; null when one names another workspace
        private fun nombres(lista: String?): List<String>? =
            lista
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { nombre ->
                    val partes = nombre.split(':')
                    when {
                        partes.size == 1 -> nombre
                        partes.size == 2 && partes[0] == WORKSPACE -> partes[1]
                        else -> return null
                    }
                }.distinct()
    }
}
