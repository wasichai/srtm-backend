package srtm.rentas

import srtm.brecha.ConstruccionValuacion
import srtm.brecha.InsumosValuacion
import srtm.brecha.ObraValuacion
import java.math.BigDecimal

// a nivel with its clasificación (PADRON_EXTERNO_NIVEL): read from the stored record, as before #241 the dto did not
// carry it (NivelConstruccion.clasificacion has it now)
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
            codigoMunicipal = predio?.codigo?.trim()?.ifEmpty { null },
            tipoPredio = tipoPredio(predio?.tipoPredio)
        )
    }

    // the predio's tipo_predio (its own only, #209: the declaración has none) as catastro's tipoPredio; anything
    // else, or none, is null: a predio without it is valued as urbano, as always
    fun tipoPredio(tipo: String?): String? =
        when (tipo?.trim()) {
            "PREDIO RUSTICO" -> "RUSTICO"
            "PREDIO URBANO" -> "URBANO"
            else -> null
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
    // it has narrow the catalog's rows of its uso; none or more than one left is no code (catastro's motivo). #235:
    // without uso (an Ica padrón's sub clase código) the code is the prefix its sub clase's rows share (XXYY00), else
    // its clase's (XX0000): catastro takes a code's longest prefix. none, or more than one prefix, is no code
    fun codigoDeUso(
        d: Declaracion,
        usos: List<UsoPredio>
    ): String? {
        val clase = d.claseUso.valor()
        val subClase = d.subClaseUso.valor()
        val suyos = usos.filter { (clase == null || it.clase == clase) && (subClase == null || it.subClase == subClase) }
        val uso = d.uso.valor()
        if (uso != null) return suyos.filter { it.uso == uso }.singleOrNull()?.codigo
        if (clase == null && subClase == null) return null
        val (digitos, relleno) = if (subClase != null) 4 to "00" else 2 to "0000"
        return suyos
            .mapNotNull {
                it.codigo
                    .valor()
                    ?.takeIf { c -> c.length == 6 }
                    ?.take(digitos)
            }.distinct()
            .singleOrNull()
            ?.plus(relleno)
    }

    private fun String?.valor(): String? = this?.trim()?.ifEmpty { null }
}
