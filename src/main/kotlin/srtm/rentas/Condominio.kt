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
// autoavalúo, inhabitabilidad), not what belongs to its titular (adquisición, documentos, condición especial,
// deducción, otros datos)
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
        areaComunTerreno = origen.areaComunTerreno
    )

private const val PORCENTAJE = "porcentaje_condominio"
