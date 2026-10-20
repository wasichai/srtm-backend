package srtm.rentas

import srtm.brecha.ConstruccionValuacion
import srtm.brecha.InsumosValuacion
import srtm.brecha.ObraValuacion
import java.math.BigDecimal

// a nivel with the clasificación an external padrón brought (PADRON_EXTERNO_NIVEL), which the dto does not carry
data class NivelDeValuacion(
    val nivel: NivelConstruccion,
    val clasificacion: String? = null
)

// #149: a declaración as the insumos of catastro's engine (POST /api/catastro/valuacion). pure: the service reads the
// records and catastro values them. nothing of it is stored
object AutoavaluoSugerido {
    private const val INACTIVO = "INACTIVO"
    private const val PISO = "PISO"

    // a categoría is the instructivo's partida as the portal stores it ("3. MURO DE LADRILLO DE ARCILLA") or its number
    private val NUMERO_DE_PARTIDA = Regex("^\\s*(\\d+)\\s*(\\..*)?$")

    // arancel: the declaración's own (an external padrón's, PADRON_EXTERNO_DECLARACION); null lets catastro take its
    // ficha's by codigoMunicipal, the predio's código (the brecha's key too). esEdificio: the predio has its
    // edificación, or a floor above the first is declared (a nivel on floor n stands on n - 1 others); it only matters
    // from the cuadro's 5th floor. obras: cantidad is the total metrado (cantidad x metrado), the partida's unit
    fun insumos(
        declaracion: Declaracion,
        arancel: BigDecimal?,
        predio: Predio?,
        niveles: List<NivelDeValuacion>,
        obras: List<ObraComplementaria>,
        usos: List<UsoPredio>
    ): InsumosValuacion {
        val activos = niveles.filter { it.nivel.estado != INACTIVO }
        val pisos = activos.filter { (it.nivel.tipoNivel ?: PISO) == PISO }.mapNotNull { it.nivel.numeroPiso }
        return InsumosValuacion(
            anio = declaracion.anio ?: throw IllegalArgumentException("La declaración ${declaracion.id} no tiene año"),
            areaTerreno = declaracion.areaTerreno,
            arancelM2 = arancel,
            uso = codigoDeUso(declaracion, usos),
            esEdificio = !predio?.edificacion.isNullOrBlank() || pisos.any { it > 1 },
            construcciones = activos.map { construccion(it, declaracion.clasificacion) },
            obras = obras.filter { it.estado != INACTIVO }.map(::obra),
            codigoMunicipal = predio?.codigo?.trim()?.ifEmpty { null }
        )
    }

    // the clasificación of the nivel, else the declaración's (both an external padrón's): the RNT tabla it depreciates
    // with. none: catastro takes the uso's
    private fun construccion(
        n: NivelDeValuacion,
        deLaDeclaracion: String?
    ): ConstruccionValuacion {
        val nivel = n.nivel
        return ConstruccionValuacion(
            piso = piso(nivel.tipoNivel, nivel.numeroPiso),
            areaConstruida = nivel.areaConstruida,
            anioConstruccion = nivel.anioConstruccion,
            material = nivel.material.valor(),
            estado = nivel.estadoConservacion.valor(),
            muros = nivel.murosColumnas.valor(),
            techos = nivel.techos.valor(),
            puertas = nivel.puertasVentanas.valor(),
            clasificacion = n.clasificacion.valor() ?: deLaDeclaracion.valor()
        )
    }

    // catastro's piso: the floor's number, S1 a sótano, M1 a mezanine, AZ the azotea
    fun piso(
        tipoNivel: String?,
        numeroPiso: Int?
    ): String {
        val n = numeroPiso ?: 1
        return when (tipoNivel) {
            "SOTANO" -> "S$n"
            "MEZANINE" -> "M$n"
            "AZOTEA" -> "AZ"
            else -> "$n"
        }
    }

    private fun obra(o: ObraComplementaria) =
        ObraValuacion(
            codigoVu = codigoVu(o),
            cantidad = o.totalMetrado ?: totalMetrado(o),
            anioConstruccion = o.anioConstruccion,
            material = o.material.valor(),
            estado = o.estadoConservacion.valor()
        )

    // "tipo|n", the key of catastro's cuadro. a categoría without its partida's number goes as declared (none: the tipo
    // alone): no partida has that key, and catastro's OBRA_DESCONOCIDA motivo names the obra it cannot value
    fun codigoVu(o: ObraComplementaria): String? {
        val tipo = o.tipoObra.valor() ?: return null
        val categoria = o.categoria.valor() ?: return tipo
        val numero =
            NUMERO_DE_PARTIDA
                .matchEntire(categoria)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        return "$tipo|${numero ?: categoria}"
    }

    // the declaración stores the names of its clase, sub clase and uso; catastro wants the uso_predio's code. the names
    // it has narrow the catalog's rows of its uso; none or more than one left is no code (catastro's motivo)
    fun codigoDeUso(
        d: Declaracion,
        usos: List<UsoPredio>
    ): String? {
        val uso = d.uso.valor() ?: return null
        val suyos =
            usos.filter {
                it.uso == uso &&
                    (d.claseUso.valor() == null || it.clase == d.claseUso) &&
                    (d.subClaseUso.valor() == null || it.subClase == d.subClaseUso)
            }
        return suyos.singleOrNull()?.codigo
    }

    private fun String?.valor(): String? = this?.trim()?.ifEmpty { null }
}
