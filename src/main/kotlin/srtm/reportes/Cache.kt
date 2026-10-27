package srtm.reportes

import java.time.Duration
import java.time.Instant

// what was read, for `vigencia`, the `maximo` most recently used: an older one is dropped. a vigencia of zero keeps
// nothing
class Cache<K : Any, V : Any>(
    private val vigencia: Duration,
    private val maximo: Int,
    private val ahora: () -> Instant = Instant::now
) {
    private val entradas =
        object : LinkedHashMap<K, Pair<Instant, V>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Pair<Instant, V>>?) = size > maximo
        }

    fun get(clave: K): V? =
        synchronized(entradas) {
            val (leido, valor) = entradas[clave] ?: return null
            if (leido.plus(vigencia).isAfter(ahora())) {
                valor
            } else {
                entradas.remove(clave)
                null
            }
        }

    fun put(
        clave: K,
        valor: V
    ) {
        if (vigencia.isZero || maximo <= 0) return
        synchronized(entradas) { entradas[clave] = ahora() to valor }
    }
}
