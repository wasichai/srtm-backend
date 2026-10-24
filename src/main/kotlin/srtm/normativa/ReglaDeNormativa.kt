package srtm.normativa

import org.springframework.stereotype.Component
import srtm.Violaciones
import srtm.fecha
import srtm.impuesto.PARAMETRO_TRIBUTARIO
import srtm.impuesto.ParametroTributario
import srtm.leerRegistro
import wasichai.core.common.ConflictException

// the double signature of a parámetro (#187, ReglaDeDobleFirma), whoever writes it: srtm's Normativa,
// model/import_parametros.py by /api/objects, /admin or an ADMIN. its one edition of a VIGENTE row is closing its open
// vigencia (cerrar). a stored row without estado (before the field, until the migration) is VIGENTE
@Component
class ReglaDeNormativa : ReglaDeDobleFirma("parámetro", "parametros") {
    override val objetos = setOf(PARAMETRO_TRIBUTARIO)

    override fun datos(
        attrs: Map<String, Any?>,
        v: Violaciones,
        alta: Boolean
    ) = datos(leerRegistro(ParametroTributario::class.java, attrs), v)

    override fun editarVigente(
        cambios: List<String>,
        guardado: Map<String, Any?>,
        nuevo: Map<String, Any?>
    ) = cerrar(cambios, guardado, nuevo)

    // the one edition of a VIGENTE row: closing its open vigencia (vigencia_hasta empty) on a day from vigencia_desde on,
    // so a later row of its tipo and clave can rule after it without overlapping
    private fun cerrar(
        cambios: List<String>,
        guardado: Map<String, Any?>,
        nuevo: Map<String, Any?>
    ) {
        if (cambios != listOf(VIGENCIA_HASTA)) {
            throw ConflictException(
                "Un parámetro VIGENTE no se edita: anúlelo y registre el valor correcto, que vuelve a pasar por la doble firma, " +
                    "o cierre su vigencia (cambia: ${cambios.joinToString(", ")})"
            )
        }
        if (lleno(guardado[VIGENCIA_HASTA])) throw ConflictException("La vigencia del parámetro ya está cerrada (${guardado[VIGENCIA_HASTA]}): no se cambia")
        val desde = fecha(nuevo["vigencia_desde"])
        val hasta = fecha(nuevo[VIGENCIA_HASTA]) ?: throw ConflictException("Cerrar la vigencia de un parámetro exige su vigencia_hasta")
        if (desde != null && hasta.isBefore(desde)) throw ConflictException("La vigencia_hasta ($hasta) no es anterior a vigencia_desde ($desde)")
    }

    // the data of any row: a known tipo, its vigencia (hasta not before desde) and its value
    private fun datos(
        p: ParametroTributario,
        v: Violaciones
    ) {
        val tipo = TiposDeParametro.de(p.tipo)
        if (v.requerido("tipo", p.tipo) && tipo == null) v.mal("tipo", "no es un tipo de normativa (GET /api/srtm/normativa/tipos)")
        v.requerido("vigencia_desde", p.vigenciaDesde)
        if (p.vigenciaDesde != null && p.vigenciaHasta != null && p.vigenciaHasta.isBefore(p.vigenciaDesde)) {
            v.mal("vigencia_hasta", "no es anterior a vigencia_desde")
        }
        if (p.valorNumerico == null && p.texto.isNullOrBlank() && tipo?.admiteVacio != true) {
            v.mal(if (tipo?.valor == ClaseDeValor.TEXTO) "texto" else "valor_numerico", "es obligatorio: el valor_numerico o el texto")
        }
    }

    private companion object {
        const val VIGENCIA_HASTA = "vigencia_hasta"
    }
}
