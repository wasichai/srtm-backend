package srtm.caja

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import srtm.emision.TablaCore
import srtm.emision.TablasCore
import wasichai.core.common.ConflictException
import wasichai.core.identity.CurrentUser
import wasichai.core.organization.OrganizationRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// an orden_caja paid in caja, its cuota (de arbitrio or de predial) and the recibo that paid it (serie-numero)
data class OrdenPagada(
    val orden: String,
    val cuotaArbitrio: String?,
    val cuotaPredial: String?,
    val recibo: String
)

// #165: what was paid in caja, in ONE place. an orden_caja (and its cuota) is paid when a pago_caja_detalle aplicado
// points to it, and its pago_caja (PAGO_REGISTRADO) has no PAGO_ANULADO whose pago_original is its pago_id. the saldo
// a favor of a contribuyente is the importe of its detalles not applied, of the pagos not annulled.
// read in sql on the caller's organization's tables (TablasCore), not as the caller: whether a cuota was paid decides
// what srtm may annul or redetermine, and a clerk who may not read the pagos must not be let past it. an organization
// without caja has none, and nothing is read
@Component
class Pagadas(
    private val cajas: Cajas,
    private val currentUser: CurrentUser,
    private val organizaciones: OrganizationRepository,
    private val tablas: TablasCore,
    private val db: DatabaseClient
) {
    // a table's physical name and columns do not change once core made them: read once per organization
    private val cache = ConcurrentHashMap<Pair<UUID, String>, TablaCore>()

    // the contribuyentes' ordenes paid
    suspend fun deContribuyentes(ids: Collection<String>): List<OrdenPagada> = pagadas("contribuyente", ids)

    // which of these ordenes are paid
    suspend fun deOrdenes(ids: Collection<String>): List<OrdenPagada> = pagadas("orden", ids)

    // the cuotas de arbitrio of the contribuyentes paid: cuota id -> recibo
    suspend fun cuotasArbitrio(contribuyentes: Collection<String>): Map<String, String> =
        deContribuyentes(contribuyentes).filter { it.cuotaArbitrio != null }.associate { it.cuotaArbitrio!! to it.recibo }

    // the contribuyente's saldo a favor: 0.00 when it has none
    suspend fun saldoAFavor(contribuyente: String): BigDecimal {
        val cero = BigDecimal.ZERO.setScale(2)
        val organizacion = conCaja() ?: return cero
        val (d, p) = tablasDePago(organizacion) ?: return cero
        val suma =
            db
                .sql(
                    "SELECT coalesce(sum(d.${d.columna("importe")}), 0) AS saldo FROM ${d.tabla} d JOIN ${p.tabla} p ON p.id = d.${d.columna("pago")} " +
                        "WHERE coalesce(d.${d.columna("aplicado")}, false) = false AND d.${d.columna("contribuyente")} = :contribuyente " +
                        "AND p.${p.columna("tipo")} = :registrado AND ${noAnulado(p)}"
                ).bind("contribuyente", UUID.fromString(contribuyente))
                .bind("registrado", TipoPagoCaja.PAGO_REGISTRADO)
                .bind("anulado", TipoPagoCaja.PAGO_ANULADO)
                .map { row, _ -> BigDecimal(row.get("saldo").toString()) }
                .one()
                .awaitFirstOrNull()
        return (suma ?: cero).setScale(2, RoundingMode.HALF_UP)
    }

    private suspend fun pagadas(
        campo: String,
        ids: Collection<String>
    ): List<OrdenPagada> {
        if (ids.isEmpty()) return emptyList()
        val organizacion = conCaja() ?: return emptyList()
        val (d, p) = tablasDePago(organizacion) ?: return emptyList()
        val o = tabla(organizacion, ObjetosCaja.ORDEN_CAJA, listOf("cuota_arbitrio", "cuota_predial")) ?: return emptyList()
        return db
            .sql(
                "SELECT d.${d.columna("orden")} AS orden, o.${o.columna("cuota_arbitrio")} AS cuota_arbitrio, " +
                    "o.${o.columna("cuota_predial")} AS cuota_predial, p.${p.columna("recibo_serie")} AS serie, p.${p.columna("recibo_numero")} AS numero " +
                    "FROM ${d.tabla} d JOIN ${p.tabla} p ON p.id = d.${d.columna("pago")} JOIN ${o.tabla} o ON o.id = d.${d.columna("orden")} " +
                    "WHERE d.${d.columna("aplicado")} = true AND d.${d.columna(campo)} IN (:ids) " +
                    "AND p.${p.columna("tipo")} = :registrado AND ${noAnulado(p)}"
            ).bind("ids", ids.distinct().map(UUID::fromString))
            .bind("registrado", TipoPagoCaja.PAGO_REGISTRADO)
            .bind("anulado", TipoPagoCaja.PAGO_ANULADO)
            .map { row, _ ->
                OrdenPagada(
                    row.get("orden", UUID::class.java)!!.toString(),
                    row.get("cuota_arbitrio", UUID::class.java)?.toString(),
                    row.get("cuota_predial", UUID::class.java)?.toString(),
                    Imputacion.recibo(row.get("serie", String::class.java), row.get("numero", String::class.java))
                )
            }.all()
            .asFlow()
            .toList()
    }

    // the pago p (a PAGO_REGISTRADO) has no PAGO_ANULADO
    private fun noAnulado(p: TablaCore) =
        "NOT EXISTS (SELECT 1 FROM ${p.tabla} a WHERE a.${p.columna("tipo")} = :anulado AND a.${p.columna("pago_original")} = p.${p.columna("pago_id")})"

    private suspend fun tablasDePago(organizacion: UUID): Pair<TablaCore, TablaCore>? {
        val d = tabla(organizacion, ObjetosCaja.PAGO_CAJA_DETALLE, listOf("importe", "aplicado", "pago", "orden", "contribuyente")) ?: return null
        val p = tabla(organizacion, ObjetosCaja.PAGO_CAJA, listOf("pago_id", "tipo", "pago_original", "recibo_serie", "recibo_numero")) ?: return null
        return d to p
    }

    // the organization's table of `objeto`, when it has the object
    private suspend fun tabla(
        organizacion: UUID,
        objeto: String,
        campos: List<String>
    ): TablaCore? {
        val clave = organizacion to objeto
        cache[clave]?.let { return it }
        val t = tablas.de(objeto, campos).firstOrNull { it.organizacion == organizacion } ?: return null
        cache[clave] = t
        return t
    }

    // the caller's organization, when it has a caja (CajaService.cajaDelLlamante); one configured twice has none
    private suspend fun conCaja(): UUID? {
        if (!cajas.configurado) return null
        val organizacion = currentUser.require().organizationId
        val slug = if (cajas.necesitaSlug) organizaciones.findById(organizacion)?.slug else null
        return try {
            cajas.de(organizacion, slug)?.let { organizacion }
        } catch (_: ConflictException) {
            null
        }
    }
}
