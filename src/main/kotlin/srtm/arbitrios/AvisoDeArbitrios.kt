package srtm.arbitrios

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.core.json.JsonWriteFeature
import tools.jackson.databind.json.JsonMapper

// what the redeterminaciones of a request did (#158): the cuotas written and annulled, the cuotas left as they are
// because they were paid in caja (#165), and why one could not be done (null when every one could)
data class ResultadoRedeterminacion(
    val redeterminadas: Int = 0,
    val anuladas: Int = 0,
    val error: String? = null,
    val pagadas: Int = 0
) {
    operator fun plus(otro: ResultadoRedeterminacion) =
        ResultadoRedeterminacion(
            redeterminadas + otro.redeterminadas,
            anuladas + otro.anuladas,
            listOfNotNull(error, otro.error).joinToString("; ").ifEmpty { null },
            pagadas + otro.pagadas
        )
}

// #158: a write of a declaración answers with what it did to its predio's arbitrios, whatever it answers itself (a
// declaración, a ficha, a nivel, a 204): the header Srtm-Arbitrios, {"redeterminadas":N,"anuladas":N,"error":…,"pagadas":N} in
// json with every non-ascii character escaped (JSON.parse reads it as is). absent when no redeterminación ran: no
// change of what the arbitrios read, or a predio without cuotas of the year. the request's sum travels in its Reactor
// context, so no response dto and no controller carries it
@Component
class AvisoDeArbitrios : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain
    ): Mono<Void> {
        val suma = Suma()
        exchange.response.beforeCommit {
            suma.total?.let { exchange.response.headers.set(ENCABEZADO, JSON.writeValueAsString(it)) }
            Mono.empty()
        }
        return chain.filter(exchange).contextWrite { it.put(Suma::class.java, suma) }
    }

    class Suma {
        @Volatile
        var total: ResultadoRedeterminacion? = null
            private set

        @Synchronized
        fun sumar(r: ResultadoRedeterminacion) {
            total = total?.plus(r) ?: r
        }
    }

    companion object {
        const val ENCABEZADO = "Srtm-Arbitrios"

        private val JSON: JsonMapper = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build()

        // added to the request's header; outside a request (a test, a job) nothing
        suspend fun anotar(r: ResultadoRedeterminacion) {
            currentCoroutineContext()[ReactorContext]
                ?.context
                ?.getOrEmpty<Suma>(Suma::class.java)
                ?.orElse(null)
                ?.sumar(r)
        }
    }
}
