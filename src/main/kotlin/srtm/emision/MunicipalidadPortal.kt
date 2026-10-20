package srtm.emision

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import srtm.Permisos
import srtm.rentas.CATASTRO_FISCAL
import srtm.rentas.PREDIO
import wasichai.core.identity.CurrentUser
import java.util.UUID

// the place most of the organization's predios are in: the default of a new predio's or domicilio's ubigeo
data class Lugar(
    val ubigeo: String,
    val departamento: String?,
    val provincia: String?,
    val distrito: String?,
    val region: String?
)

// what the portal's shell shows of the caller's organization: the documents' header (name, oficina, escudo as a data:
// uri), and where its lotes are, for the map: extension [oeste, sur, este, norte] and centro [lon, lat] in EPSG:4326,
// null without any. lugar is null without predios with an ubigeo
data class MunicipalidadPortal(
    val nombre: String,
    val oficina: String?,
    val escudo: String?,
    val extension: List<Double>?,
    val centro: List<Double>?,
    val lugar: Lugar?
)

// the header's ruc, gerencia and direccion are the documents' only
fun municipalidadPortal(
    cabecera: Cabecera,
    extension: List<Double>?,
    lugar: Lugar?
) = MunicipalidadPortal(cabecera.nombre, cabecera.oficina, cabecera.escudo, extension, centroDe(extension), lugar)

// the union of the objects' extents, each [oeste, sur, este, norte]; null ones (an object without lotes) left out
fun extensionDe(cajas: List<List<Double>?>): List<Double>? {
    val llenas = cajas.filterNotNull()
    if (llenas.isEmpty()) return null
    return listOf(llenas.minOf { it[0] }, llenas.minOf { it[1] }, llenas.maxOf { it[2] }, llenas.maxOf { it[3] })
}

fun centroDe(extension: List<Double>?): List<Double>? = extension?.let { (oeste, sur, este, norte) -> listOf((oeste + este) / 2, (sur + norte) / 2) }

// the organization's lotes and predios, aggregated in sql on their tables (TablasCore): RecordService has no extent nor
// count by ubigeo, and reading every lote to find them is what this avoids. only for who may read the object
// (Permisos.puedeLeer), as its records are; a failure leaves the map without place, never the shell without header
@Component
class ZonaMunicipal(
    private val db: DatabaseClient,
    private val tablas: TablasCore,
    private val permisos: Permisos,
    private val currentUser: CurrentUser
) {
    // the lotes of the padrón and of the catastro fiscal, both on the map
    suspend fun extension(): List<Double>? =
        seguro("la extensión de los lotes") {
            extensionDe(listOf(PREDIO, CATASTRO_FISCAL).filter { permisos.puedeLeer(it) }.map { cajaDe(it) })
        }

    // the ubigeo most predios have, with the departamento, provincia, distrito and región most of those have
    suspend fun lugar(): Lugar? =
        seguro("el lugar de los predios") {
            if (!permisos.puedeLeer(PREDIO)) return@seguro null
            val t = tablaDe(PREDIO, listOf("ubigeo", "departamento", "provincia", "distrito", "region")) ?: return@seguro null
            val ubigeo = t.columna("ubigeo")
            db
                .sql(
                    """
                    SELECT $ubigeo AS ubigeo,
                           mode() WITHIN GROUP (ORDER BY ${t.columna("departamento")}) AS departamento,
                           mode() WITHIN GROUP (ORDER BY ${t.columna("provincia")}) AS provincia,
                           mode() WITHIN GROUP (ORDER BY ${t.columna("distrito")}) AS distrito,
                           mode() WITHIN GROUP (ORDER BY ${t.columna("region")}::text) AS region
                    FROM ${t.tabla}
                    WHERE btrim($ubigeo) <> ''
                    GROUP BY $ubigeo
                    ORDER BY count(*) DESC, $ubigeo
                    LIMIT 1
                    """.trimIndent()
                ).map { row, _ ->
                    Lugar(
                        row.get("ubigeo", String::class.java)!!,
                        row.get("departamento", String::class.java),
                        row.get("provincia", String::class.java),
                        row.get("distrito", String::class.java),
                        row.get("region", String::class.java)
                    )
                }.one()
                .awaitFirstOrNull()
        }

    // the extent of an object's lote_geom, in its own srid (one per column, wasichai-gis's) and then in EPSG:4326
    private suspend fun cajaDe(objeto: String): List<Double>? {
        val t = tablaDe(objeto, listOf(LOTE_GEOM)) ?: return null
        val geom = t.columna(LOTE_GEOM)
        return db
            .sql(
                """
                SELECT ST_XMin(c) AS oeste, ST_YMin(c) AS sur, ST_XMax(c) AS este, ST_YMax(c) AS norte
                FROM (SELECT ST_Transform(ST_SetSRID(ST_Extent($geom)::geometry, min(ST_SRID($geom))), 4326) AS c FROM ${t.tabla}) e
                """.trimIndent()
            ).map { row, _ ->
                listOf("oeste", "sur", "este", "norte").map { row.get(it, Double::class.javaObjectType) }
            }.one()
            .awaitFirstOrNull()
            ?.takeIf { caja -> caja.all { it != null } }
            ?.map { it!! }
    }

    private suspend fun tablaDe(
        objeto: String,
        campos: List<String>
    ): TablaCore? {
        val organizacion: UUID = currentUser.require().organizationId
        return tablas.de(objeto, campos).firstOrNull { it.organizacion == organizacion }
    }

    private suspend fun <T> seguro(
        que: String,
        bloque: suspend () -> T?
    ): T? =
        try {
            bloque()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("no se pudo calcular $que: el portal sigue sin ese dato", e)
            null
        }

    private companion object {
        const val LOTE_GEOM = "lote_geom"
        val log = LoggerFactory.getLogger(ZonaMunicipal::class.java)
    }
}

// GET /api/srtm/municipalidad: read on municipalidad, as reading the header to emit (Permisos, before the cache of
// CabeceraDocumento can answer); the lotes and the lugar only of the objects the caller reads
@RestController
@RequestMapping("/api/srtm")
class MunicipalidadController(
    private val cabecera: CabeceraDocumento,
    private val zona: ZonaMunicipal,
    private val permisos: Permisos
) {
    @GetMapping("/municipalidad")
    suspend fun municipalidad(): MunicipalidadPortal {
        permisos.exigirLeer(MUNICIPALIDAD, "consultar la municipalidad")
        return municipalidadPortal(cabecera.actual(), zona.extension(), zona.lugar())
    }
}
