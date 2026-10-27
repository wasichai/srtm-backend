package srtm.reportes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

// what was read, the `maximo` most recently used: an older one is dropped. with a vigencia, each is kept that long (a
// vigencia of zero keeps nothing); without one (null), until the maximo drops it: what never changes, like the rows
// of a TERMINADA corrida (#255). a maximo of zero keeps nothing. obtener reads a missing value once: who asks for it
// while it is being read waits for that read; a read that fails is neither kept nor shared, the next one reads again
class Cache<K : Any, V : Any>(
    private val vigencia: Duration?,
    private val maximo: Int,
    private val ahora: () -> Instant = Instant::now
) {
    private val entradas =
        object : LinkedHashMap<K, Pair<Instant, V>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Pair<Instant, V>>?) = size > maximo
        }

    // the reads in progress, by key; guarded by entradas
    private val enCurso = HashMap<K, CompletableDeferred<V>>()

    fun get(clave: K): V? = synchronized(entradas) { vigente(clave) }

    fun put(
        clave: K,
        valor: V
    ) {
        synchronized(entradas) { poner(clave, valor) }
    }

    fun quitar(clave: K) {
        synchronized(entradas) { entradas.remove(clave) }
    }

    suspend fun obtener(
        clave: K,
        cargar: suspend () -> V
    ): V {
        while (true) {
            val (lectura, propia) =
                synchronized(entradas) {
                    vigente(clave)?.let { return it }
                    enCurso[clave]?.let { it to false } ?: (CompletableDeferred<V>().also { enCurso[clave] = it } to true)
                }
            if (!propia) {
                try {
                    return lectura.await()
                } catch (e: CancellationException) {
                    // ours: stop; the reader's (its request went away): read it here
                    currentCoroutineContext().ensureActive()
                } catch (_: Exception) {
                    // the read failed: this one tries again
                }
                continue
            }
            try {
                val valor = cargar()
                synchronized(entradas) {
                    poner(clave, valor)
                    enCurso.remove(clave)
                }
                lectura.complete(valor)
                return valor
            } catch (e: Throwable) {
                synchronized(entradas) { enCurso.remove(clave) }
                lectura.completeExceptionally(e)
                throw e
            }
        }
    }

    // under entradas
    private fun vigente(clave: K): V? {
        val (leido, valor) = entradas[clave] ?: return null
        if (vigencia == null || leido.plus(vigencia).isAfter(ahora())) return valor
        entradas.remove(clave)
        return null
    }

    // under entradas
    private fun poner(
        clave: K,
        valor: V
    ) {
        if (vigencia?.isZero == true || maximo <= 0) return
        entradas[clave] = ahora() to valor
    }
}
