package srtm.emision

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import srtm.rentas.Registros
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.identity.CurrentUser
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// the institutional header every emitted document opens with (templates/emision/cabecera.html): the municipalidad's
// name, oficina, ruc, gerencia and direccion, and its escudo. a blank line is null: the template leaves it out
data class Cabecera(
    val nombre: String,
    val oficina: String? = null,
    val ruc: String? = null,
    val gerencia: String? = null,
    val direccion: String? = null,
    // a data: uri, ready for <img src>; openhtmltopdf renders with no base uri, so a path would not resolve
    val escudo: String? = null
)

const val MUNICIPALIDAD = "municipalidad"

// the object municipalidad: one record per organization, edited in the admin
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Municipalidad(
    val id: String? = null,
    val nombre: String? = null,
    val oficina: String? = null,
    val ruc: String? = null,
    val gerencia: String? = null,
    val direccion: String? = null
)

// the header out of the organization's records (oldest first): the first one, or only `porDefecto`
// (srtm.municipalidad.nombre) when there is none, so documents print before anyone fills the record in
fun cabeceraDe(
    municipalidades: List<Municipalidad>,
    porDefecto: String,
    escudo: String?
): Cabecera {
    val m = municipalidades.firstOrNull()
    return Cabecera(
        nombre = lleno(m?.nombre) ?: porDefecto,
        oficina = lleno(m?.oficina),
        ruc = lleno(m?.ruc),
        gerencia = lleno(m?.gerencia),
        direccion = lleno(m?.direccion),
        escudo = escudo
    )
}

// a png or a jpg as a data: uri, told apart by its first bytes. anything else stops the app at startup: a
// misconfigured escudo is found when deploying, not on the first document
fun escudoDe(
    bytes: ByteArray,
    nombre: String
): String {
    val tipo =
        when {
            bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(PNG) -> "image/png"
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
            else -> error("srtm.municipalidad.escudo: $nombre no es un PNG ni un JPG")
        }
    return "data:$tipo;base64,${Base64.getEncoder().encodeToString(bytes)}"
}

// srtm.municipalidad.escudo (classpath:… or file:…) read once; empty is no escudo
fun escudoConfigurado(
    ubicacion: String,
    leer: (String) -> ByteArray
): String? = ubicacion.trim().takeIf { it.isNotEmpty() }?.let { escudoDe(leer(it), it) }

private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private fun lleno(valor: String?): String? = valor?.trim()?.takeIf { it.isNotEmpty() }

// the caller's organization's header. read as the caller (Registros: core applies its permissions, so whoever emits
// needs read on municipalidad, as on parametro_tributario) and kept srtm.municipalidad.cache (a minute) per
// organization: the masiva does not read it per document, and an edit in the admin shows within that time
@Service
class CabeceraDocumento(
    private val registros: Registros,
    private val currentUser: CurrentUser,
    recursos: ResourceLoader,
    @param:Value("\${srtm.municipalidad.nombre}") private val porDefecto: String,
    @param:Value("\${srtm.municipalidad.escudo:}") escudo: String,
    @param:Value("\${srtm.municipalidad.cache:1m}") private val vigencia: Duration
) {
    private val escudo: String? =
        escudoConfigurado(escudo) { ubicacion ->
            recursos.getResource(ubicacion).let { r ->
                check(r.exists()) { "srtm.municipalidad.escudo: no existe $ubicacion" }
                r.inputStream.use { it.readBytes() }
            }
        }

    private val cache = ConcurrentHashMap<UUID, Pair<Instant, Cabecera>>()

    suspend fun actual(): Cabecera {
        val organizacion = currentUser.require().organizationId
        val ahora = Instant.now()
        cache[organizacion]?.takeIf { (leida, _) -> leida.plus(vigencia).isAfter(ahora) }?.let { return it.second }
        val municipalidades = registros.all(MUNICIPALIDAD, Municipalidad::class.java, sort = "created_at")
        if (municipalidades.size > 1) log.warn("la organización $organizacion tiene ${municipalidades.size} municipalidades: se usa la más antigua")
        return cabeceraDe(municipalidades, porDefecto, escudo).also { cache[organizacion] = ahora to it }
    }

    private companion object {
        val log = LoggerFactory.getLogger(CabeceraDocumento::class.java)
    }
}
