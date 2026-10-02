package srtm.emision

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

// model/model.json
const val EMISION_LOTE = "emision_lote"

internal val CAMPOS_LOTE =
    listOf("emision", "numero", "contribuyentes", "estado", "tomado_por", "latido", "intentos", "procesados", "documentos", "errores", "parte")
internal val CAMPOS_EMISION =
    listOf("anio", "formato", "estado", "total", "procesados", "errores", "archivo", "tamano", "mensaje", "iniciado", "terminado", "latido")

// the emission as a kind of job by lotes: its lotes carry contribuyentes and count documentos, and a take returns the
// emission's formato with its anio
val TIPO_EMISION =
    TipoLotes(
        trabajo = EMISION_MASIVA,
        lote = EMISION_LOTE,
        relacion = "emision",
        carga = "contribuyentes",
        producidos = "documentos",
        camposTrabajo = CAMPOS_EMISION,
        camposLote = CAMPOS_LOTE,
        delTrabajo = listOf("formato")
    )

// a lote a worker of this instance took: what it needs to generate its part
data class LoteTomado(
    override val id: UUID,
    override val organizacion: UUID,
    val emision: UUID,
    val numero: Int,
    val contribuyentes: List<ContribuyenteAEmitir>,
    override val intentos: Int,
    val creadoPor: UUID?,
    val anio: Int,
    val formato: FormatoEmision
) : TomaDeLote {
    override val trabajo: UUID get() = emision
}

// a lote as the assembly reads it
data class ParteLote(
    val id: UUID,
    val numero: Int,
    val estado: String,
    val procesados: Int,
    val documentos: Int,
    val errores: List<ErrorEmision>,
    // the key its worker wrote, for whoever reads the row: the assembly rebuilds it from the numero (claveParte)
    val parte: String?
)

// the emission's lotes and the one of its jobs, which the lotes point to
internal suspend fun TablasCore.lotes(organizacion: UUID? = null): List<TablasLote> = lotes(TIPO_EMISION, organizacion)

// the lotes of the masivas (wasichai/srtm-backend#53, #54): MaquinaLotes on emision_lote, with the emission's types
@Component
class LotesEmision(
    db: DatabaseClient,
    tablas: TablasCore
) {
    private val maquina = MaquinaLotes(db, tablas, TIPO_EMISION)

    // the next lote of the oldest emission EN_PROCESO (MaquinaLotes.tomar). null: none to take
    suspend fun tomar(
        instancia: String,
        lease: Duration
    ): LoteTomado? =
        maquina.tomar(instancia, lease)?.let {
            LoteTomado(
                id = it.id,
                organizacion = it.organizacion,
                emision = it.trabajo,
                numero = it.numero,
                contribuyentes = contribuyentesDe(it.carga),
                intentos = it.intentos,
                creadoPor = it.creadoPor,
                anio = it.anio,
                formato = FormatoEmision.valueOf(it.delTrabajo.getValue("formato")!!)
            )
        }

    // the lease renewed: false if the lote is no longer this instance's
    suspend fun latir(
        lote: LoteTomado,
        instancia: String
    ): Boolean = maquina.latir(lote, instancia)

    // the lote's progress, and its emission's with it. a progress saved is a beat too
    suspend fun avanzar(
        lote: LoteTomado,
        instancia: String,
        procesados: Int,
        errores: List<ErrorEmision>
    ): Boolean = maquina.avanzar(lote, instancia, procesados, erroresJson(errores))

    // the lote's part is in the almacén under `parte`: TERMINADO, with what it counted
    suspend fun terminar(
        lote: LoteTomado,
        instancia: String,
        procesados: Int,
        documentos: Int,
        errores: List<ErrorEmision>,
        parte: String
    ): Boolean = maquina.terminar(lote, instancia, procesados, documentos, erroresJson(errores), parte)

    // the lote could not be generated (its attempts are over): FALLIDO, with why
    suspend fun fallar(
        lote: LoteTomado,
        instancia: String,
        errores: List<ErrorEmision>
    ): Boolean = maquina.fallar(lote, instancia, erroresJson(errores))

    // the lote is let go (the instance stops): PENDIENTE again, for the next taker, which counts one more attempt
    suspend fun liberar(
        lote: LoteTomado,
        instancia: String
    ): Boolean = maquina.liberar(lote, instancia)

    // null: the row is gone (its emission was deleted)
    suspend fun estado(lote: LoteTomado): String? = maquina.estado(lote)

    // the emission's lotes still to generate go FALLIDO. how many
    suspend fun cancelar(
        organizacion: UUID,
        emision: UUID
    ): Int = maquina.cancelar(organizacion, emision)

    // the emission's lote rows, before the emission itself can go. how many
    suspend fun borrar(
        organizacion: UUID,
        emision: UUID
    ): Int = maquina.borrar(organizacion, emision)

    // the emission's lotes by numero, the order of their parts
    suspend fun partes(
        organizacion: UUID,
        emision: UUID
    ): List<ParteLote> =
        maquina.partes(organizacion, emision).map {
            ParteLote(it.id, it.numero, it.estado, it.procesados, it.producidos, erroresDe(it.errores), it.parte)
        }
}
