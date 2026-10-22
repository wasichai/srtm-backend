package srtm.caja

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.core.json.JsonWriteFeature
import tools.jackson.databind.json.JsonMapper

// #164: a write of srtm answers with what it did to its contribuyentes' ordenes in caja, whatever it answers itself:
// the header Srtm-Caja, {"encoladas":N,"enviadas":N,"sustituidas":N,"retiradas":N,"pagadas_en_caja":N,"conflictos":N,
// "pendientes":N,"error":…} in json with every non-ascii character escaped, as Srtm-Arbitrios (AvisoDeArbitrios).
// absent when no sincronización ran: no caja, or no contribuyente of the write with ordenes of the year. the request's
// sum travels in its Reactor context
@Component
class AvisoDeCaja : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain
    ): Mono<Void> {
        val suma = Suma()
        exchange.response.beforeCommit {
            suma.total?.let { exchange.response.headers.set(ENCABEZADO, valor(it)) }
            Mono.empty()
        }
        return chain.filter(exchange).contextWrite { it.put(Suma::class.java, suma) }
    }

    class Suma {
        @Volatile
        var total: ResumenCaja? = null
            private set

        @Synchronized
        fun sumar(r: ResumenCaja) {
            total = total?.plus(r) ?: r
        }
    }

    companion object {
        const val ENCABEZADO = "Srtm-Caja"

        private val JSON: JsonMapper = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build()

        // the header's keys, in its order: sin_documento is the POST's, not a write's
        fun valor(r: ResumenCaja): String =
            JSON.writeValueAsString(
                linkedMapOf(
                    "encoladas" to r.encoladas,
                    "enviadas" to r.enviadas,
                    "sustituidas" to r.sustituidas,
                    "retiradas" to r.retiradas,
                    "pagadas_en_caja" to r.pagadasEnCaja,
                    "conflictos" to r.conflictos,
                    "pendientes" to r.pendientes,
                    "error" to r.error
                )
            )

        // added to the request's header; outside a request (a job) nothing
        suspend fun anotar(r: ResumenCaja) {
            currentCoroutineContext()[ReactorContext]
                ?.context
                ?.getOrEmpty<Suma>(Suma::class.java)
                ?.orElse(null)
                ?.sumar(r)
        }
    }
}
