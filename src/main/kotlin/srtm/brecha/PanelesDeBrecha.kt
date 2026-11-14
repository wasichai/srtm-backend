package srtm.brecha

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.beans.factory.annotation.Value
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import srtm.emision.TablasCore
import srtm.rentas.Registros
import srtm.reportes.Cache
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// #306: GET /brecha/{anio}/panel. the corrida's rows are counted in sql, grouped by categoría, confianza and sector on
// brecha_predio's table of the organization (TablasCore), never read one by one: some 80 000 rows read through
// RecordService (with their lotes) took ~75 s on every request; the groups are a few hundred. Brecha.panelDe makes the
// panel of them, as the run makes its own of its rows. only for who may read brecha_corrida and brecha_predio
// (BrechaController asks both before calling): the reportes and the visor read the same rows on the same terms.
// the panel of a TERMINADA corrida never changes: it is kept per organization, year and corrida, with no vigencia, the
// srtm.brecha.paneles most recently used. a corrida that ends TERMINADA warms its panel (Precalentador) and drops the
// one warmed before it for the same organization and year; the latest TERMINADA of a year is read on every request, so
// a new one is answered at once and an older one (?corrida=) keeps its own entry. a corrida not TERMINADA is counted
// on every request: its rows still change. the cache is this instance's memory, like the reportes'
@Service
class PanelesDeBrecha(
    private val db: DatabaseClient,
    private val tablas: TablasCore,
    private val registros: Registros,
    @param:Value("\${srtm.brecha.paneles:32}") maximo: Int
) {
    private val paneles = Cache<ClavePanel, PanelBrecha>(null, maximo)

    // the corrida last warmed per organization and year
    private val precalentados = ConcurrentHashMap<Pair<UUID, Int>, String>()

    // the panel of corrida `r` of `anio` (BrechaService.corridaDelAnio's, read as the caller) in `organizacion`
    suspend fun panel(
        organizacion: UUID,
        anio: Int,
        r: RegistroCorrida
    ): PanelBrecha {
        if (r.estado != TERMINADA) return contar(organizacion, r)
        return paneles.obtener(ClavePanel(organizacion, anio, r.id!!)) { contar(organizacion, r) }
    }

    // a corrida that ended TERMINADA: its panel counted before anyone asks for it. no permission is asked: Precalentador
    // runs it as the platform of the organization, and the route asks them before reading the cache
    suspend fun precalentar(
        organizacion: UUID,
        anio: Int,
        corrida: String
    ) {
        precalentados.put(organizacion to anio, corrida)?.takeIf { it != corrida }?.let { paneles.quitar(ClavePanel(organizacion, anio, it)) }
        panel(organizacion, anio, registros.get(BRECHA_CORRIDA, RegistroCorrida::class.java, UUID.fromString(corrida)))
    }

    // whether the panel of the corrida is kept: for the tests
    internal fun precalentado(
        organizacion: UUID,
        anio: Int,
        corrida: String
    ): Boolean = paneles.get(ClavePanel(organizacion, anio, corrida)) != null

    private suspend fun contar(
        organizacion: UUID,
        r: RegistroCorrida
    ): PanelBrecha = Brecha.panelDe(r, grupos(organizacion, r.id!!))

    // the corrida's rows by categoría, confianza and sector, in the organization's own table (none: no rows)
    suspend fun grupos(
        organizacion: UUID,
        corrida: String
    ): List<GrupoBrecha> {
        val t = tablas.de(BRECHA_PREDIO, CAMPOS).firstOrNull { it.organizacion == organizacion } ?: return emptyList()
        val c = { campo: String -> t.columna(campo) }
        val sumas = SUMADOS.joinToString(", ") { "sum(${c(it)}) AS $it" }
        return db
            .sql(
                "SELECT ${c("categoria")}::text AS categoria, ${c("confianza")}::text AS confianza, ${c("sector")} AS sector, " +
                    "count(*) AS predios, count(${c("autoavaluo_declarado")}) AS con_declarado, $sumas " +
                    "FROM ${t.tabla} WHERE ${c("corrida")} = :corrida GROUP BY 1, 2, 3"
            ).bind("corrida", UUID.fromString(corrida))
            .map { row, _ ->
                fun decimal(alias: String) = row.get(alias)?.let { BigDecimal(it.toString()) }
                GrupoBrecha(
                    categoria = row.get("categoria", String::class.java),
                    confianza = row.get("confianza", String::class.java),
                    sector = row.get("sector", String::class.java),
                    predios = (row.get("predios") as Number).toInt(),
                    conDeclarado = (row.get("con_declarado") as Number).toInt(),
                    autoavaluoDeclarado = decimal("autoavaluo_declarado"),
                    autoavaluoCatastro = decimal("autoavaluo_catastro"),
                    deltaAutoavaluo = decimal("delta_autoavaluo"),
                    deltaImpuesto = decimal("delta_impuesto"),
                    deltaArbitrios = decimal("delta_arbitrios")
                )
            }.all()
            .asFlow()
            .toList()
    }

    // a panel's key: the corrida's id is unique, the organization and the year keep each entry where it belongs
    private data class ClavePanel(
        val organizacion: UUID,
        val anio: Int,
        val corrida: String
    )

    private companion object {
        // the fields summed, each sum named as its field
        val SUMADOS = listOf("autoavaluo_declarado", "autoavaluo_catastro", "delta_autoavaluo", "delta_impuesto", "delta_arbitrios")
        val CAMPOS = listOf("corrida", "categoria", "confianza", "sector") + SUMADOS
    }
}
