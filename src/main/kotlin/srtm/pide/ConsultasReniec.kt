package srtm.pide

import wasichai.core.common.ValidationException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// a person as its record names it (contribuyente, relacionado, transferente): what a fuente PIDE RENIEC vouches for
data class Persona(
    val tipoDocumento: String?,
    val numeroDocumento: String?,
    val apellidoPaterno: String?,
    val apellidoMaterno: String?,
    val nombres: String?,
    val fuenteInformacion: String?
)

// the consultas RENIEC answered lately, by DNI: a save with fuente PIDE RENIEC is backed by one of them, so nobody
// marks typed names as RENIEC's. in memory: they are short-lived and a consulta is asked again at no harm. with several
// instances of the backend a save could land where the consulta did not: they would share this in the database
class ConsultasReniec(
    private val vigencia: Duration,
    private val ahora: () -> Instant = Instant::now
) {
    private class Consulta(
        val datos: DatosPersona,
        val hasta: Instant
    )

    private val consultas = ConcurrentHashMap<String, Consulta>()

    fun registrar(datos: DatosPersona) {
        val momento = ahora()
        consultas.values.removeIf { it.hasta < momento }
        consultas[clave(datos.tipoDocumento, datos.numeroDocumento)] = Consulta(datos, momento.plus(vigencia))
    }

    fun vigente(
        tipo: String?,
        numero: String?
    ): DatosPersona? = consultas[clave(tipo, numero)]?.takeUnless { it.hasta < ahora() }?.datos

    // PIDE RENIEC says the names are RENIEC's: a recent consulta of that DNI must have answered them. a record that
    // already had them (anterior: as stored) keeps them while its document and names stay; its consulta is long gone
    fun respaldar(
        persona: Persona,
        anterior: Persona?
    ) {
        if (persona.fuenteInformacion != PIDE_RENIEC) return
        if (anterior?.fuenteInformacion == PIDE_RENIEC && mismaPersona(anterior, persona)) return
        val datos = vigente(persona.tipoDocumento, persona.numeroDocumento)
        if (datos != null && mismaPersona(persona, datos.persona())) return
        throw ValidationException(
            "Fuente sin consulta a RENIEC",
            "fuente_informacion",
            "PIDE RENIEC exige una consulta reciente del DNI con los mismos apellidos y nombres: vuelva a ingresar el DNI o use MANUAL"
        )
    }

    private fun DatosPersona.persona() = Persona(tipoDocumento, numeroDocumento, apellidoPaterno, apellidoMaterno, nombres, fuenteInformacion)

    private fun clave(
        tipo: String?,
        numero: String?
    ) = "${tipo?.trim()}:${numero?.trim()}"

    // RENIEC writes upper case; a blank and a missing name are the same
    private fun texto(valor: String?) =
        valor
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.uppercase()
            .orEmpty()

    private fun mismaPersona(
        a: Persona,
        b: Persona
    ): Boolean =
        listOf(Persona::tipoDocumento, Persona::numeroDocumento, Persona::apellidoPaterno, Persona::apellidoMaterno, Persona::nombres)
            .all { texto(it(a)) == texto(it(b)) }
}
