package srtm

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// the mark of a write srtm makes through its own api (/api/srtm/...): an element of the coroutine's context.
// srtm.rentas.Registros puts it around each create and replace, and RecordService calls the RecordStore in that same
// coroutine, so AlmacenGuardado sees it before writing. a write without it came in by another door (wasichai's generic
// api, /admin, a module), and an object whose rule is soloDesdeElServicio is not written by it. an http client cannot
// set it: it is an object of the process, not a value of the request (caja-backend's EscrituraDeCaja)
object EscrituraDeSrtm : AbstractCoroutineContextElement(Clave) {
    object Clave : CoroutineContext.Key<EscrituraDeSrtm>

    override fun toString() = "EscrituraDeSrtm"
}
