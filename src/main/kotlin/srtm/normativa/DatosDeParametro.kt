package srtm.normativa

import srtm.Violaciones
import srtm.impuesto.ParametroTributario
import srtm.leerRegistro
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException

// the data of a parámetro as Normativa takes it (POST and PATCH /normativa/parametros, each row of an importación):
// CamposParametro.DATOS, a text trimmed and an empty one null. stricter than ReglaDeNormativa, which every door runs:
// the tipo's clave is there exactly when the catalog says it uses one, and its value is the one the catalog names
object DatosDeParametro {
    // the body's data, every other key a 400 (the signatures and the estado are Normativa's to write)
    fun de(cuerpo: Map<String, Any?>): Map<String, Any?> {
        val ajenos = cuerpo.keys.filter { it !in CamposParametro.DATOS }
        if (ajenos.isNotEmpty()) {
            throw ValidationException(
                "El parámetro solo lleva sus datos",
                ajenos.map { FieldViolation(it, "no es un dato del parámetro: ${CamposParametro.DATOS.joinToString(", ")}") }
            )
        }
        return cuerpo.mapValues { (_, valor) -> if (valor is String) valor.trim().ifEmpty { null } else valor }
    }

    // the data as a row: a 400 that names each field that does not fit
    fun validar(datos: Map<String, Any?>): ParametroTributario {
        val p = leerRegistro(ParametroTributario::class.java, datos.filterKeys { it in CamposParametro.DATOS })
        val v = Violaciones()
        val tipo = TiposDeParametro.de(p.tipo)
        if (v.requerido("tipo", p.tipo) && tipo == null) v.mal("tipo", "no es un tipo de normativa (GET /api/srtm/normativa/tipos)")
        v.requerido("vigencia_desde", p.vigenciaDesde)
        if (p.vigenciaDesde != null && p.vigenciaHasta != null && p.vigenciaHasta.isBefore(p.vigenciaDesde)) {
            v.mal("vigencia_hasta", "no es anterior a vigencia_desde")
        }
        if (tipo != null) {
            val clave = p.clave?.trim().orEmpty()
            if (tipo.usaClave && clave.isEmpty()) v.mal("clave", "es obligatoria en ${tipo.tipo}")
            if (!tipo.usaClave && clave.isNotEmpty()) v.mal("clave", "va vacía en ${tipo.tipo}: es un solo valor")
            when (tipo.valor) {
                ClaseDeValor.NUMERICO -> if (p.valorNumerico == null) v.mal("valor_numerico", "es obligatorio en ${tipo.tipo}")
                ClaseDeValor.TEXTO -> if (p.texto.isNullOrBlank() && !tipo.admiteVacio) v.mal("texto", "es obligatorio en ${tipo.tipo}")
            }
        }
        v.lanzar("El parámetro")
        return p.copy(clave = p.clave?.trim()?.ifEmpty { null })
    }

    // the data fields in which two rows differ: a number by its value, an empty text as null
    fun cambios(
        a: ParametroTributario,
        b: ParametroTributario
    ): List<String> =
        listOfNotNull(
            "tipo".takeIf { a.tipo != b.tipo },
            "clave".takeIf { a.clave.orEmpty() != b.clave.orEmpty() },
            "vigencia_desde".takeIf { a.vigenciaDesde != b.vigenciaDesde },
            "vigencia_hasta".takeIf { a.vigenciaHasta != b.vigenciaHasta },
            "valor_numerico".takeIf { !mismoNumero(a, b) },
            "texto".takeIf { a.texto.orEmpty() != b.texto.orEmpty() },
            "norma".takeIf { a.norma.orEmpty() != b.norma.orEmpty() },
            "fuente".takeIf { a.fuente.orEmpty() != b.fuente.orEmpty() }
        )

    // a row's data as the attributes a write sends: every data field, null included (a replace clears what is left out)
    fun atributos(p: ParametroTributario): Map<String, Any?> =
        mapOf(
            "tipo" to p.tipo,
            "clave" to p.clave,
            "vigencia_desde" to p.vigenciaDesde?.toString(),
            "vigencia_hasta" to p.vigenciaHasta?.toString(),
            "valor_numerico" to p.valorNumerico,
            "texto" to p.texto,
            "norma" to p.norma,
            "fuente" to p.fuente
        )

    private fun mismoNumero(
        a: ParametroTributario,
        b: ParametroTributario
    ): Boolean {
        val x = a.valorNumerico
        val y = b.valorNumerico
        return if (x == null || y == null) x == y else x.compareTo(y) == 0
    }
}
