package srtm.sanciones

import com.fasterxml.jackson.annotation.JsonUnwrapped
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.Candado
import srtm.Candados
import srtm.Observacion
import srtm.impuesto.ParametrosTributarios
import srtm.rentas.Records
import srtm.rentas.Registros
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

// POST /infracciones/cuis: a new version of a code, as model/import_cuis.py and the portal send it. familia is
// ADMINISTRATIVA when not given; the code is trimmed and upper-cased
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoVersionCuis(
    val familia: String? = null,
    val codigo: String? = null,
    val descripcion: String? = null,
    val materia: String? = null,
    val porcentajeUit: BigDecimal? = null,
    val porcentajeUitSegunda: BigDecimal? = null,
    val porcentajeUitTercera: BigDecimal? = null,
    val medidaComplementaria: String? = null,
    val baseLegal: String? = null,
    val vigenciaDesde: LocalDate? = null,
    val observacion: String? = null
)

// its 201: the new version, flat, and the one it closed (null for a code's first version)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class VersionCuisCreada(
    @get:JsonUnwrapped val version: CodigoInfraccion,
    val cerrada: CodigoInfraccion?
)

// the UIT a figure was computed with: the parametro_tributario row read, and the ejercicio it is applied to
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class UitAplicada(
    val valor: BigDecimal,
    val anio: Int,
    val parametroId: String
)

// a code of the catalog with its multa at the UIT of the day, per grado: null without a UIT, or without the grado's %
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CuisConMultas(
    @get:JsonUnwrapped val codigo: CodigoInfraccion,
    val multa: BigDecimal?,
    val multaSegunda: BigDecimal?,
    val multaTercera: BigDecimal?
)

// GET /infracciones/cuis: always a 200; what keeps the multas from being computed goes in faltan ("UIT 2026")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CatalogoCuis(
    val vigentesA: LocalDate,
    val uit: UitAplicada?,
    val faltan: List<String>,
    val codigos: List<CuisConMultas>
)

// the CUIS of the portal: the versions in force on a day, and a new version. a version is never edited: the new one
// closes the one in force (srtm.sanciones.Cuis), in one transaction under the code's lock
@Service
class CuisService(
    private val registros: Registros,
    private val parametros: ParametrosTributarios,
    private val permisos: Permisos,
    private val candados: Candados,
    transacciones: ReactiveTransactionManager
) {
    private val transaccion = TransactionalOperator.create(transacciones)

    // the versions in force on `vigentesA`, by code, each with its multas at that day's UIT. materia and q (the code or
    // the description) match a part, in any case
    suspend fun catalogo(
        vigentesA: LocalDate,
        materia: String?,
        q: String?
    ): CatalogoCuis {
        val uit = Multas.uit(parametros.todos(), vigentesA)
        val fila = uit.valor
        val codigos =
            registros
                .all(CODIGO_INFRACCION, CodigoInfraccion::class.java, filters = mapOf("familia" to ADMINISTRATIVA))
                .filter { Cuis.rigeEn(it, vigentesA) }
                .filter { materia == null || contiene(it.materia, materia) }
                .filter { q == null || contiene(it.codigo, q) || contiene(it.descripcion, q) }
                .sortedBy { it.codigo }
                .map { c ->
                    fun multa(grado: String) = fila?.valorNumerico?.let { Multas.calcular(c, it, grado).valor?.importeAPagar }
                    CuisConMultas(c, multa(PRIMERA), multa(SEGUNDA), multa(TERCERA_O_MAS))
                }
        return CatalogoCuis(
            vigentesA,
            fila?.let { UitAplicada(it.valorNumerico!!, vigentesA.year, it.id!!) },
            uit.faltan,
            codigos
        )
    }

    // a new version: the one in force is closed the day before (vigencia_hasta, clave_vigente emptied) and this one is
    // added, or neither. under the advisory lock of familia|codigo the versions are read again, so two new versions of
    // one code never overlap. a version already there (same familia|codigo|vigencia_desde) is a 409; one that does not
    // start after the one in force, or after a closed one it would overlap, a 422
    suspend fun nuevaVersion(pedido: PedidoVersionCuis): VersionCuisCreada {
        permisos.exigirCrear(CODIGO_INFRACCION, "agregar una versión del CUIS")
        val nueva = version(pedido)
        val familia = nueva.familia!!
        val codigo = nueva.codigo!!
        return try {
            transaccion.executeAndAwait {
                candados.bloquear(Candado.CODIGO_INFRACCION, claveVigenteDe(familia, codigo))
                val versiones =
                    registros.all(CODIGO_INFRACCION, CodigoInfraccion::class.java, filters = mapOf("familia" to familia, "codigo" to codigo))
                if (versiones.any { it.clave == nueva.clave }) throw repetida(nueva)
                val cerrada =
                    Cuis.versionNueva(versiones, nueva.vigenciaDesde!!)?.let {
                        registros.replace(
                            CODIGO_INFRACCION,
                            CodigoInfraccion::class.java,
                            UUID.fromString(it.id),
                            mapOf("vigencia_hasta" to it.vigenciaHasta.toString(), "clave_vigente" to null)
                        )
                    }
                VersionCuisCreada(registros.create(CODIGO_INFRACCION, CodigoInfraccion::class.java, Records.attributes(nueva)), cerrada)
            }
        } catch (_: DuplicateKeyException) {
            // another transaction got there first: what it wrote stands
            throw repetida(nueva)
        }
    }

    // the record to add, every invariant checked before the lock: a 400 that names each field
    private fun version(p: PedidoVersionCuis): CodigoInfraccion {
        val familia = p.familia?.trim()?.ifEmpty { null } ?: ADMINISTRATIVA
        val codigo = p.codigo?.let(Cuis::normalizar)?.ifEmpty { null }
        val desde = p.vigenciaDesde
        val c =
            CodigoInfraccion(
                familia = familia,
                codigo = codigo,
                descripcion = p.descripcion.limpio(),
                materia = p.materia.limpio(),
                porcentajeUit = p.porcentajeUit,
                porcentajeUitSegunda = p.porcentajeUitSegunda,
                porcentajeUitTercera = p.porcentajeUitTercera,
                medidaComplementaria = p.medidaComplementaria.limpio(),
                baseLegal = p.baseLegal.limpio(),
                vigenciaDesde = desde,
                vigenciaHasta = null,
                observacion = p.observacion?.trim(),
                clave = if (codigo != null && desde != null) claveDeCodigo(familia, codigo, desde) else null,
                claveVigente = codigo?.let { claveVigenteDe(familia, it) }
            )
        val malas = invariantes(c)
        if (familia != ADMINISTRATIVA) {
            throw ValidationException("La familia '$familia' no es del CUIS de srtm", "familia", "es $ADMINISTRATIVA")
        }
        if (malas.isNotEmpty()) throw ValidationException("La versión del CUIS no es válida", malas)
        return c.copy(observacion = Observacion.de(c.observacion))
    }

    private fun repetida(c: CodigoInfraccion) =
        ConflictException("La versión de ${c.codigo} desde el ${c.vigenciaDesde} ya existe: un cambio es una versión nueva, con otra vigencia_desde")

    private fun String?.limpio() = this?.trim()?.ifEmpty { null }

    private fun contiene(
        texto: String?,
        parte: String
    ) = texto != null && texto.lowercase(Locale.ROOT).contains(parte.lowercase(Locale.ROOT))
}
