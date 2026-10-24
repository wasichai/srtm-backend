package srtm.normativa

import org.springframework.stereotype.Component
import srtm.ReglaDeEscritura
import srtm.Violaciones
import srtm.fecha
import srtm.iguales
import srtm.impuesto.PARAMETRO_TRIBUTARIO
import srtm.impuesto.ParametroTributario
import srtm.leerRegistro
import srtm.normativa.CamposParametro.ANULACION
import srtm.normativa.CamposParametro.ESTADO
import srtm.normativa.CamposParametro.TRANSCRIBIO
import srtm.normativa.CamposParametro.VERIFICO
import srtm.normativa.EstadoParametro.ANULADO
import srtm.normativa.EstadoParametro.POR_VERIFICAR
import srtm.normativa.EstadoParametro.VIGENTE
import wasichai.core.common.ConflictException

// the double signature of a parámetro, whoever writes it (#187): srtm's Normativa, model/import_parametros.py by
// /api/objects (soloDesdeElServicio is false so the importers keep working), /admin or an ADMIN.
// - a row enters VIGENTE (or without estado) with two different signatures, or POR_VERIFICAR with its transcriber
//   alone; never ANULADO;
// - a POR_VERIFICAR row changes its data and stays so; it becomes VIGENTE by another person's signature and nothing
//   else; it is deleted, not annulled;
// - a VIGENTE row is not edited: it is annulled (motivo, who and when, nothing else) and the correct value registered;
// - an ANULADO row does not change.
// a stored row without estado (before the field, until the migration) is VIGENTE. a write that changes nothing passes
@Component
class ReglaDeNormativa : ReglaDeEscritura {
    override val objetos = setOf(PARAMETRO_TRIBUTARIO)
    override val soloDesdeElServicio = false

    override fun alInsertar(
        objeto: String,
        attrs: Map<String, Any?>
    ) {
        val p = leerRegistro(ParametroTributario::class.java, attrs)
        val v = Violaciones()
        datos(p, v)
        val estado = EstadoParametro.de(attrs[ESTADO])
        when (estado) {
            VIGENTE -> {
                val firmas = v.requerido(TRANSCRIBIO, p.transcribio) and v.requerido(VERIFICO, p.verifico)
                if (firmas && firmante(p.transcribio) == firmante(p.verifico)) v.mal(VERIFICO, "es otra persona que quien transcribió")
            }
            POR_VERIFICAR -> {
                v.requerido(TRANSCRIBIO, p.transcribio)
                if (!p.verifico.isNullOrBlank()) v.mal(VERIFICO, "queda vacío hasta que otra persona lo verifique")
            }
            ANULADO -> v.mal(ESTADO, "un parámetro no nace ANULADO: se anula uno VIGENTE")
            else -> v.mal(ESTADO, "es ${EstadoParametro.TODOS.joinToString(", ")}")
        }
        if (estado != ANULADO) ANULACION.filter { lleno(attrs[it]) }.forEach { v.mal(it, "solo lo tiene un parámetro anulado") }
        v.lanzar("El parámetro")
    }

    override fun alActualizar(
        objeto: String,
        guardado: Map<String, Any?>,
        nuevo: Map<String, Any?>
    ) {
        val antes = EstadoParametro.de(guardado[ESTADO])
        val despues = EstadoParametro.de(nuevo[ESTADO])
        val cambios = (guardado.keys + nuevo.keys).filter { it != ESTADO && !iguales(guardado[it], nuevo[it]) }
        if (antes == despues && cambios.isEmpty()) return
        when (antes) {
            POR_VERIFICAR -> desdePorVerificar(objeto, despues, cambios, nuevo)
            VIGENTE -> desdeVigente(despues, cambios, guardado, nuevo)
            ANULADO -> throw ConflictException("Un parámetro ANULADO no cambia: queda como historia. Registre el valor correcto como un parámetro nuevo")
            else -> throw ConflictException("El parámetro tiene un estado que no es de normativa: $antes")
        }
    }

    private fun desdePorVerificar(
        objeto: String,
        despues: String,
        cambios: List<String>,
        nuevo: Map<String, Any?>
    ) {
        when (despues) {
            POR_VERIFICAR -> alInsertar(objeto, nuevo)
            VIGENTE -> {
                val otros = cambios - VERIFICO
                if (otros.isNotEmpty()) {
                    throw ConflictException(
                        "Al verificar un parámetro solo cambian su estado y quién lo verificó (cambia también: ${otros.joinToString(", ")}). " +
                            "Corríjalo antes, mientras está POR_VERIFICAR"
                    )
                }
                val verifico = nuevo[VERIFICO]?.toString()
                if (verifico.isNullOrBlank()) throw ConflictException("Verificar un parámetro exige quién lo verifica (verifico)")
                val transcribio = nuevo[TRANSCRIBIO]?.toString()
                if (firmante(verifico) == firmante(transcribio)) {
                    throw ConflictException("No puede verificar lo que transcribió ($transcribio): la doble firma pide otra persona")
                }
            }
            ANULADO -> throw ConflictException("Un parámetro POR_VERIFICAR no se anula: se corrige o se borra")
            else -> throw ConflictException("El estado de un parámetro es ${EstadoParametro.TODOS.joinToString(", ")}: no $despues")
        }
    }

    private fun desdeVigente(
        despues: String,
        cambios: List<String>,
        guardado: Map<String, Any?>,
        nuevo: Map<String, Any?>
    ) {
        when (despues) {
            ANULADO -> {
                val otros = cambios - ANULACION.toSet()
                if (otros.isNotEmpty()) {
                    throw ConflictException(
                        "Al anular un parámetro solo se escriben ${ANULACION.joinToString(", ")} (cambia también: ${otros.joinToString(", ")})"
                    )
                }
                val faltan = ANULACION.filter { !lleno(nuevo[it]) }
                if (faltan.isNotEmpty()) {
                    throw ConflictException(
                        "Anular un parámetro VIGENTE exige ${ANULACION.joinToString(", ")} (falta: ${faltan.joinToString(", ")})"
                    )
                }
            }
            VIGENTE -> cerrar(cambios, guardado, nuevo)
            POR_VERIFICAR -> throw ConflictException("Un parámetro VIGENTE no vuelve a POR_VERIFICAR: anúlelo y registre uno nuevo")
            else -> throw ConflictException("El estado de un parámetro es ${EstadoParametro.TODOS.joinToString(", ")}: no $despues")
        }
    }

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

    override fun alBorrar(
        objeto: String,
        guardado: Map<String, Any?>
    ) {
        val estado = EstadoParametro.de(guardado[ESTADO])
        if (estado != POR_VERIFICAR) {
            throw ConflictException("Solo se borra un parámetro POR_VERIFICAR: uno VIGENTE se anula, y uno ANULADO queda como historia (este está $estado)")
        }
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

    private fun lleno(valor: Any?) = valor != null && !(valor is String && valor.isBlank())
}
