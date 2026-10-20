package srtm.emision

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ResourceLoader
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import srtm.rentas.Registros
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.identity.CurrentUser
import wasichai.core.organization.OrganizationRepository
import wasichai.files.FileService
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

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

// its IMAGE field (wasichai-files)
const val ESCUDO = "escudo"

// the object municipalidad: one record per organization, edited in the admin
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Municipalidad(
    val id: String? = null,
    val nombre: String? = null,
    val oficina: String? = null,
    val ruc: String? = null,
    val gerencia: String? = null,
    val direccion: String? = null,
    // the IMAGE field's descriptor (wasichai-files: id, name, contentType, size, sha256), only to know there is one:
    // its bytes come through FileService
    val escudo: Map<String, Any?>? = null
)

// the header out of the organization's records (oldest first): the first one, or only `porDefecto` (nombreDeRespaldo)
// when there is none, so documents print before anyone fills the record in
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

// the name a header carries while its organization has no municipalidad record: srtm.municipalidad.nombre when set,
// else the organization's own name in core (so an install with several municipalidades never prints another's),
// else its slug; never empty, whatever core knows
fun nombreDeRespaldo(
    configurado: String,
    organizacion: String?,
    slug: String?,
    id: UUID
): String = lleno(configurado) ?: lleno(organizacion) ?: lleno(slug) ?: id.toString()

// a png or a jpg as a data: uri, told apart by its first bytes. anything else stops the app at startup: a
// misconfigured escudo is found when deploying, not on the first document
fun escudoDe(
    bytes: ByteArray,
    nombre: String
): String {
    val tipo = tipoDeImagen(bytes) ?: error("srtm.municipalidad.escudo: $nombre no es un PNG ni un JPG")
    return "data:$tipo;base64,${Base64.getEncoder().encodeToString(bytes)}"
}

// the escudo a header prints: the municipalidad record's when it has one (`leer` brings its bytes, by the record's
// id), else `configurado` (srtm.municipalidad.escudo). one the record has that cannot be read, or that is no png or
// jpg the pdf can draw, prints none and is logged: an escudo uploaded in the admin never stops a document
suspend fun escudoElegido(
    municipalidad: Municipalidad?,
    configurado: String?,
    leer: suspend (UUID) -> ByteArray
): String? {
    if (municipalidad?.escudo == null) return configurado
    val id = UUID.fromString(municipalidad.id)
    val bytes =
        try {
            leer(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logEscudo.warn("no se pudo leer el escudo de la municipalidad $id: se imprime sin escudo", e)
            return null
        }
    if (tipoDeImagen(bytes) == null || !seDibuja(bytes)) {
        logEscudo.warn("el escudo de la municipalidad $id no es un PNG ni un JPG legible: se imprime sin escudo")
        return null
    }
    return escudoDe(bytes, "el escudo de la municipalidad $id")
}

private fun tipoDeImagen(bytes: ByteArray): String? =
    when {
        bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(PNG) -> "image/png"
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        else -> null
    }

// the whole image decodes: a cut or damaged file has the right first bytes too
private fun seDibuja(bytes: ByteArray): Boolean =
    try {
        ImageIO.read(ByteArrayInputStream(bytes)) != null
    } catch (_: IOException) {
        false
    }

private val logEscudo = LoggerFactory.getLogger(CabeceraDocumento::class.java)

// srtm.municipalidad.escudo (classpath:… or file:…) read once; empty is no escudo
fun escudoConfigurado(
    ubicacion: String,
    leer: (String) -> ByteArray
): String? = ubicacion.trim().takeIf { it.isNotEmpty() }?.let { escudoDe(leer(it), it) }

private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private fun lleno(valor: String?): String? = valor?.trim()?.takeIf { it.isNotEmpty() }

// the caller's organization's header. read as the caller (Registros and FileService: core applies its permissions, so
// whoever emits needs read on municipalidad, as on parametro_tributario; the masiva's workers are its creator) and kept
// srtm.municipalidad.cache (a minute) per organization: the masiva does not read it per document, and an edit in the
// admin, the escudo included, shows within that time
@Service
class CabeceraDocumento(
    private val registros: Registros,
    private val archivos: FileService,
    private val currentUser: CurrentUser,
    // wasichai's own repository, not part of its public api (ADR-024): read for the organization's name, as BrechaService does
    private val organizaciones: OrganizationRepository,
    recursos: ResourceLoader,
    @param:Value("\${srtm.municipalidad.nombre:}") private val configurado: String,
    @param:Value("\${srtm.municipalidad.escudo:}") escudo: String,
    @param:Value("\${srtm.municipalidad.cache:1m}") private val vigencia: Duration
) {
    private val escudoDeConfig: String? =
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
        val datos = organizaciones.findById(organizacion)
        val respaldo = nombreDeRespaldo(configurado, datos?.name, datos?.slug, organizacion)
        val escudo = escudoElegido(municipalidades.firstOrNull(), escudoDeConfig) { id -> bytesDe(archivos.open(MUNICIPALIDAD, id, ESCUDO).content) }
        return cabeceraDe(municipalidades, respaldo, escudo).also { cache[organizacion] = ahora to it }
    }

    // the file whole: an escudo is small (model.json caps it at 1 MiB)
    private suspend fun bytesDe(contenido: Flux<DataBuffer>): ByteArray {
        val junto = DataBufferUtils.join(contenido).awaitSingle()
        try {
            return ByteArray(junto.readableByteCount()).also { junto.read(it) }
        } finally {
            DataBufferUtils.release(junto)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(CabeceraDocumento::class.java)
    }
}
