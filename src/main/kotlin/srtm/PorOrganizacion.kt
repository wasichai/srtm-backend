package srtm

import wasichai.core.common.ConflictException
import java.util.UUID

// the clients of an install, each its organization's (the key: its id or its slug). repetidas: the organizations two
// entries of the map name, with their keys. dosEntradas words the 409 for the keys that name one organization twice
class PorOrganizacion<T : Any>(
    porOrganizacion: Map<String, T>,
    repetidas: Map<String, Collection<String>> = emptyMap(),
    private val dosEntradas: (Collection<String>) -> String
) {
    private val entradas = porOrganizacion.mapKeys { it.key.trim() }
    private val repetidas = repetidas.mapKeys { it.key.trim() }

    val configurado: Boolean get() = entradas.isNotEmpty() || repetidas.isNotEmpty()

    // some key is a slug: the caller's organization has to be read for it
    val necesitaSlug: Boolean = (entradas.keys + repetidas.keys).any { comoId(it) == null }

    fun claves(): Set<String> = entradas.keys + repetidas.keys

    // this organization's client, or null when it has none. a key that is an id matches by id; else by slug (without
    // case). named twice (by its slug and by its id, or by two entries of the map): 409, never one of the two at random
    fun de(
        id: UUID,
        slug: String?
    ): T? {
        repetidas.entries.firstOrNull { esDe(it.key, id, slug) }?.let { throw ConflictException(dosEntradas(it.value)) }
        val suyas = entradas.filterKeys { esDe(it, id, slug) }
        if (suyas.size > 1) throw ConflictException(dosEntradas(suyas.keys))
        return suyas.values.firstOrNull()
    }

    private fun esDe(
        clave: String,
        id: UUID,
        slug: String?
    ): Boolean {
        val comoId = comoId(clave)
        return if (comoId != null) comoId == id else slug != null && slug.equals(clave, ignoreCase = true)
    }

    private fun comoId(clave: String) = runCatching { UUID.fromString(clave) }.getOrNull()
}
