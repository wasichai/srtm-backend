package srtm.normativa

import srtm.ReglaDeEscritura
import srtm.Violaciones
import srtm.iguales
import srtm.normativa.CamposParametro.ANULACION
import srtm.normativa.CamposParametro.ESTADO
import srtm.normativa.CamposParametro.TRANSCRIBIO
import srtm.normativa.CamposParametro.VERIFICO
import srtm.normativa.EstadoParametro.ANULADO
import srtm.normativa.EstadoParametro.POR_VERIFICAR
import srtm.normativa.EstadoParametro.VIGENTE
import wasichai.core.common.ConflictException

// the double signature of a normativa record (a parámetro, #187; an arancel, #188), whoever writes it: srtm's
// Normativa, an importer by /api/objects (soloDesdeElServicio is false so the importers keep working), /admin or an
// ADMIN.
// - a record enters VIGENTE (or without estado) with two different signatures, or POR_VERIFICAR with its transcriber
//   alone; never ANULADO;
// - a POR_VERIFICAR record changes its data and stays so; it becomes VIGENTE by another person's signature and nothing
//   else; it is deleted, not annulled;
// - a VIGENTE record is not edited (but by editarVigente): it is annulled (motivo, who and when, nothing else) and the
//   correct value registered;
// - an ANULADO record does not change.
// - a POR_VERIFICAR record is born and changes only with srtm's mark (sinLaMarcaAlInsertar, sinLaMarca): only
//   Normativa registers, corrects and verifies it.
// a stored record without estado is VIGENTE. a write that changes nothing passes, and so does one that changes only
// `libres` (fields any estado may change: the sincronización of an arancel)
abstract class ReglaDeDobleFirma(
    // as the messages name it, a masculine noun: "parámetro", "arancel"
    private val nombre: String,
    // its route under /api/srtm/normativa: "parametros", "aranceles"
    private val ruta: String,
    private val libres: Set<String> = emptySet()
) : ReglaDeEscritura {
    override val soloDesdeElServicio = false

    // the record's own data: each field that does not fit, in v. alta: a new record (not a correction)
    protected abstract fun datos(
        attrs: Map<String, Any?>,
        v: Violaciones,
        alta: Boolean
    )

    // the change of a VIGENTE record to VIGENTE, `cambios` its fields that differ (libres aside). by default none
    protected open fun editarVigente(
        cambios: List<String>,
        guardado: Map<String, Any?>,
        nuevo: Map<String, Any?>
    ): Unit =
        throw ConflictException(
            "Un $nombre VIGENTE no se edita: anúlelo y registre el valor correcto, que vuelve a pasar por la doble firma " +
                "(cambia: ${cambios.joinToString(", ")})"
        )

    override fun alInsertar(
        objeto: String,
        attrs: Map<String, Any?>
    ) = comprobar(attrs, alta = true)

    private fun comprobar(
        attrs: Map<String, Any?>,
        alta: Boolean
    ) {
        val v = Violaciones()
        datos(attrs, v, alta)
        val estado = EstadoParametro.de(attrs[ESTADO])
        val transcribio = attrs[TRANSCRIBIO]?.toString()
        val verifico = attrs[VERIFICO]?.toString()
        when (estado) {
            VIGENTE -> {
                val firmas = v.requerido(TRANSCRIBIO, transcribio) and v.requerido(VERIFICO, verifico)
                if (firmas && firmante(transcribio) == firmante(verifico)) v.mal(VERIFICO, "es otra persona que quien transcribió")
            }
            POR_VERIFICAR -> {
                v.requerido(TRANSCRIBIO, transcribio)
                if (!verifico.isNullOrBlank()) v.mal(VERIFICO, "queda vacío hasta que otra persona lo verifique")
            }
            ANULADO -> v.mal(ESTADO, "un $nombre no nace ANULADO: se anula uno VIGENTE")
            else -> v.mal(ESTADO, "es ${EstadoParametro.TODOS.joinToString(", ")}")
        }
        if (estado != ANULADO) ANULACION.filter { lleno(attrs[it]) }.forEach { v.mal(it, "solo lo tiene un $nombre anulado") }
        v.lanzar("El $nombre")
    }

    // a POR_VERIFICAR record is Normativa's alone: it registers it signed by the user, corrects it signing it with who
    // corrects it, and verifies it with the user's signature against the other records of its key. so without the mark
    // no POR_VERIFICAR record is born nor changes (a transcriber written by hand would let its writer verify it): the
    // importers' door stays the insert of a VIGENTE record with the document's two signatures. it is still deleted
    override fun sinLaMarcaAlInsertar(
        objeto: String,
        attrs: Map<String, Any?>
    ): String? =
        if (EstadoParametro.de(attrs[ESTADO]) == POR_VERIFICAR) {
            "Un $nombre POR_VERIFICAR se registra en Normativa (POST /api/srtm/normativa/$ruta), con la firma de quien lo " +
                "transcribe: por la API genérica solo entra VIGENTE, con las dos firmas del documento"
        } else {
            null
        }

    override fun sinLaMarca(
        objeto: String,
        guardado: Map<String, Any?>,
        nuevo: Map<String, Any?>
    ): String? {
        if (EstadoParametro.de(guardado[ESTADO]) != POR_VERIFICAR) return null
        if ((guardado.keys + nuevo.keys).all { iguales(guardado[it], nuevo[it]) }) return null
        return if (EstadoParametro.de(nuevo[ESTADO]) == VIGENTE) {
            "Un $nombre POR_VERIFICAR se verifica en Normativa (POST /api/srtm/normativa/$ruta/verificar), con la firma de " +
                "quien verifica: no por la API genérica"
        } else {
            "Un $nombre POR_VERIFICAR se corrige en Normativa (PATCH /api/srtm/normativa/$ruta/{id}), con la firma de quien " +
                "lo corrige: no por la API genérica"
        }
    }

    override fun alActualizar(
        objeto: String,
        guardado: Map<String, Any?>,
        nuevo: Map<String, Any?>
    ) {
        val antes = EstadoParametro.de(guardado[ESTADO])
        val despues = EstadoParametro.de(nuevo[ESTADO])
        val cambios = (guardado.keys + nuevo.keys).filter { it != ESTADO && it !in libres && !iguales(guardado[it], nuevo[it]) }
        if (antes == despues && cambios.isEmpty()) return
        when (antes) {
            POR_VERIFICAR -> desdePorVerificar(despues, cambios, nuevo)
            VIGENTE -> desdeVigente(despues, cambios, guardado, nuevo)
            ANULADO -> throw ConflictException("Un $nombre ANULADO no cambia: queda como historia. Registre el valor correcto como un $nombre nuevo")
            else -> throw ConflictException("El $nombre tiene un estado que no es de normativa: $antes")
        }
    }

    private fun desdePorVerificar(
        despues: String,
        cambios: List<String>,
        nuevo: Map<String, Any?>
    ) {
        when (despues) {
            POR_VERIFICAR -> comprobar(nuevo, alta = false)
            VIGENTE -> {
                val otros = cambios - VERIFICO
                if (otros.isNotEmpty()) {
                    throw ConflictException(
                        "Al verificar un $nombre solo cambian su estado y quién lo verificó (cambia también: ${otros.joinToString(", ")}). " +
                            "Corríjalo antes, mientras está POR_VERIFICAR"
                    )
                }
                val verifico = nuevo[VERIFICO]?.toString()
                if (verifico.isNullOrBlank()) throw ConflictException("Verificar un $nombre exige quién lo verifica (verifico)")
                val transcribio = nuevo[TRANSCRIBIO]?.toString()
                if (firmante(verifico) == firmante(transcribio)) {
                    throw ConflictException("No puede verificar lo que transcribió ($transcribio): la doble firma pide otra persona")
                }
            }
            ANULADO -> throw ConflictException("Un $nombre POR_VERIFICAR no se anula: se corrige o se borra")
            else -> throw ConflictException("El estado de un $nombre es ${EstadoParametro.TODOS.joinToString(", ")}: no $despues")
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
                        "Al anular un $nombre solo se escriben ${ANULACION.joinToString(", ")} (cambia también: ${otros.joinToString(", ")})"
                    )
                }
                val faltan = ANULACION.filter { !lleno(nuevo[it]) }
                if (faltan.isNotEmpty()) {
                    throw ConflictException(
                        "Anular un $nombre VIGENTE exige ${ANULACION.joinToString(", ")} (falta: ${faltan.joinToString(", ")})"
                    )
                }
            }
            VIGENTE -> editarVigente(cambios, guardado, nuevo)
            POR_VERIFICAR -> throw ConflictException("Un $nombre VIGENTE no vuelve a POR_VERIFICAR: anúlelo y registre uno nuevo")
            else -> throw ConflictException("El estado de un $nombre es ${EstadoParametro.TODOS.joinToString(", ")}: no $despues")
        }
    }

    override fun alBorrar(
        objeto: String,
        guardado: Map<String, Any?>
    ) {
        val estado = EstadoParametro.de(guardado[ESTADO])
        if (estado != POR_VERIFICAR) {
            throw ConflictException("Solo se borra un $nombre POR_VERIFICAR: uno VIGENTE se anula, y uno ANULADO queda como historia (este está $estado)")
        }
    }

    protected fun lleno(valor: Any?) = valor != null && !(valor is String && valor.isBlank())
}
