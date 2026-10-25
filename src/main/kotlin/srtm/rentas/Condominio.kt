package srtm.rentas

import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode

// a predio's titulares in a year and secuencia de uso are its condominio: each declares apart, so condición, % and
// values follow from the whole group. the service recomputes the group after every write of one of them. a
// transferente is a former owner: it does not change anyone's %

const val PROPIETARIO_UNICO = "PROPIETARIO UNICO"
const val CONDOMINO = "CONDOMINO"

private val CIEN = BigDecimal(100)

// besides propietario único, what a titular who holds the predio alone may declare itself
private val TITULAR_UNICO = setOf(PROPIETARIO_UNICO, "SOCIEDAD CONYUGAL", "POSEEDOR")

// the condominio a declaración belongs to. a secuencia stored before it had three digits ("1") is the same as "001"
fun grupoDe(d: Declaracion) = Triple(d.predio, d.anio, d.secuenciaUso?.let(::secuenciaUso))

// a sole titular holds 100 %; two or more are condóminos, each with the % it declares. valor_condominio is that % of
// the autoavalúo (to the cent, half up) and valor_afecto what the deducción leaves of it
fun condominio(grupo: List<Declaracion>): List<Declaracion> =
    grupo.map { d ->
        val solo = grupo.size == 1
        val porcentaje = if (solo) CIEN else d.porcentajeCondominio
        val valorCondominio =
            if (d.valorAutoavaluo == null || porcentaje == null) null else d.valorAutoavaluo.multiply(porcentaje).divide(CIEN, 2, RoundingMode.HALF_UP)
        d.copy(
            condicionPropiedad = if (!solo) CONDOMINO else d.condicionPropiedad?.takeIf { it in TITULAR_UNICO } ?: PROPIETARIO_UNICO,
            porcentajeCondominio = porcentaje,
            valorCondominio = valorCondominio,
            valorAfecto = valorCondominio?.subtract(d.deduccion ?: BigDecimal.ZERO)?.max(BigDecimal.ZERO)?.setScale(2, RoundingMode.HALF_UP)
        )
    }

// the group once `d` is written into it (d first), `otros` as stored. a titular who joins (seUne) a predio held by a
// sole titular takes its share out of that titular's 100 %, which was never declared; otherwise the parts must fit
// in 100 %
fun condominioCon(
    d: Declaracion,
    otros: List<Declaracion>,
    seUne: Boolean
): List<Declaracion> {
    if (otros.any { it.contribuyente == d.contribuyente }) {
        throw ValidationException("Este contribuyente ya declara el predio en ese año y secuencia de uso", "contribuyente", "ya declara este predio")
    }
    if (otros.isEmpty()) return condominio(listOf(d))
    val porcentaje = d.porcentajeCondominio?.takeIf { it.signum() > 0 }
    if (seUne && otros.size == 1) {
        if (porcentaje == null || porcentaje >= CIEN) {
            throw ValidationException(
                "El predio ya tiene un titular: el % de propiedad del nuevo condómino sale del suyo y debe ser mayor que 0 y menor que 100",
                PORCENTAJE,
                "debe ser mayor que 0 y menor que 100"
            )
        }
        return condominio(listOf(d, otros.single().copy(porcentajeCondominio = CIEN - porcentaje)))
    }
    if (porcentaje == null) throw ValidationException("Indica el % de propiedad del condómino", PORCENTAJE, "debe ser mayor que 0")
    val suma = otros.sumOf { it.porcentajeCondominio ?: BigDecimal.ZERO } + porcentaje
    if (suma > CIEN) {
        throw ValidationException(
            "Los % de propiedad del predio sumarían ${suma.stripTrailingZeros().toPlainString()} % (máximo 100 %)",
            PORCENTAJE,
            "excede el 100 %"
        )
    }
    return condominio(listOf(d) + otros)
}

// a new condómino's declaración: what the source declares of the predio (its year and secuencia, características,
// autoavalúo, inhabitabilidad; the external padrón's valuation is copied apart, PADRON_EXTERNO_DECLARACION), not what
// belongs to its titular (adquisición, documentos, condición especial, deducción, otros datos)
fun delPredio(origen: Declaracion) =
    Declaracion(
        predio = origen.predio,
        anio = origen.anio,
        secuenciaUso = origen.secuenciaUso,
        uso = origen.uso,
        clasificacion = origen.clasificacion,
        estadoConstruccion = origen.estadoConstruccion,
        areaTerreno = origen.areaTerreno,
        areaConstruida = origen.areaConstruida,
        longitudFrente = origen.longitudFrente,
        numeroHabitantes = origen.numeroHabitantes,
        valorAutoavaluo = origen.valorAutoavaluo,
        inhabitableTipoDocumento = origen.inhabitableTipoDocumento,
        inhabitableNumeroResolucion = origen.inhabitableNumeroResolucion,
        inhabitableFechaResolucion = origen.inhabitableFechaResolucion,
        inhabitableFechaInicio = origen.inhabitableFechaInicio,
        claseUso = origen.claseUso,
        subClaseUso = origen.subClaseUso,
        areaComunTerreno = origen.areaComunTerreno,
        frontisPrincipal = origen.frontisPrincipal,
        areaComunConstruida = origen.areaComunConstruida,
        autoavaluoPorRevisar = origen.autoavaluoPorRevisar,
        autoavaluoMotivo = origen.autoavaluoMotivo,
        idAranc = origen.idAranc
    )

// #157: what the condóminos share of the predio, by its model names: delPredio's fields but the condominio's own
// (predio, año, secuencia) and what recalcular derives. the autoavalúo too while it is the user's (no catastro)
private fun compartido(
    d: Declaracion,
    autoavaluoManual: Boolean
): Map<String, Any?> =
    mapOf(
        "uso" to d.uso,
        "clasificacion" to d.clasificacion,
        "estado_construccion" to d.estadoConstruccion,
        "area_terreno" to d.areaTerreno,
        "numero_habitantes" to d.numeroHabitantes,
        "inhabitable_tipo_documento" to d.inhabitableTipoDocumento,
        "inhabitable_numero_resolucion" to d.inhabitableNumeroResolucion,
        "inhabitable_fecha_resolucion" to d.inhabitableFechaResolucion,
        "inhabitable_fecha_inicio" to d.inhabitableFechaInicio,
        "clase_uso" to d.claseUso,
        "sub_clase_uso" to d.subClaseUso,
        "area_comun_terreno" to d.areaComunTerreno,
        "frontis_principal" to d.frontisPrincipal,
        "id_aranc" to d.idAranc
    ) + if (autoavaluoManual) mapOf("valor_autoavaluo" to d.valorAutoavaluo) else emptyMap()

// what `otro` must change to declare the predio as `origen` does (compartido), compared by value: 200 and 200.00
// are the same area
fun delPredioCambiado(
    origen: Declaracion,
    otro: Declaracion,
    autoavaluoManual: Boolean
): Map<String, Any?> {
    val suyo = compartido(otro, autoavaluoManual)
    return compartido(origen, autoavaluoManual).filter { (campo, valor) -> porValor(valor) != porValor(suyo[campo]) }
}

private fun porValor(valor: Any?) = if (valor is BigDecimal) valor.stripTrailingZeros() else valor

// the valuation an external padrón (Ica) brings (model/model.json), which the dtos do not write (Declaracion carries
// its arancel read-only, #195): a condómino's copy takes it from the stored records, and
// GET /declaraciones/{id}/valuacion-origen reads it there (ValuacionOrigen)
val PADRON_EXTERNO_DECLARACION = listOf("arancel", "valor_terreno", "valor_construccion", OTRAS_INSTALACIONES)

// the external padrón's otras instalaciones (#208): an amount catastro's valuation adds as an obra, fixed: not a figure
// of its year (padronDeLaCopia)
const val OTRAS_INSTALACIONES = "valor_otras_instalaciones"
val PADRON_EXTERNO_NIVEL = listOf(CLASIFICACION, "valor_unitario", "incremento", "porcentaje_depreciacion", "valor_total")

// a nivel's clasificación de la construcción: an insumo of the construction, not a figure of its year (copiar)
const val CLASIFICACION = "clasificacion"

// #180: what makes a nivel carry an external padrón's valuation: its figures, not its clasificación (an insumo, which a
// copy to another year keeps). a nivel with only that has no valuación de origen
val VALUACION_PADRON_NIVEL = PADRON_EXTERNO_NIVEL - CLASIFICACION

// of a stored nivel, whether it carries an external padrón's valuation (VALUACION_PADRON_NIVEL)
fun conValuacionDelPadron(nivel: Map<*, *>) = conValor(nivel, VALUACION_PADRON_NIVEL).isNotEmpty()

// of a stored declaración, the external padrón's valuation it has (conValor): its arancel is no padrón's when it names
// its tramo de vía (id_aranc, #188): Normativa's, derived. #237: as a nivel's clasificación (VALUACION_PADRON_NIVEL),
// the otras instalaciones alone, which a copy to another year keeps, are no valuation: they come with its figures
fun padronExterno(declaracion: Map<*, *>): Map<String, Any> {
    val conTodo = conValor(declaracion, PADRON_EXTERNO_DECLARACION)
    val valores = if (declaracion["id_aranc"]?.toString().isNullOrBlank()) conTodo else conTodo - "arancel"
    return if (valores.keys.any { it != OTRAS_INSTALACIONES }) valores else emptyMap()
}

// the declaración's mark that it changed after that valuation was imported (DeclaracionService.marcarOrigenModificado)
const val ORIGEN_MODIFICADO = "origen_modificado"

// the predio's code in the padrón de origen (#207): distinct from codigo when the padrón shares a bare code between
// several predios, e.g. Ica's SAT. the brecha cruza por él
const val CODIGO_ORIGEN = "codigo_origen"

// the predio's codes in the source catastro and in the Formato Padrón Municipal Armonización: only read, never copied
val PADRON_EXTERNO_PREDIO = listOf("codigo_via", "codigo_unidad_urbana", "codigo_hu_armonizacion", "codigo_via_armonizacion", CODIGO_ORIGEN)

// of a stored record, the fields of `campos` that have a value, in that order: null and blank text are none
fun conValor(
    registro: Map<*, *>,
    campos: List<String>
): Map<String, Any> =
    campos
        .mapNotNull { campo -> registro[campo]?.takeUnless { it is String && it.isBlank() }?.let { campo to it } }
        .toMap()

private const val PORCENTAJE = "porcentaje_condominio"
