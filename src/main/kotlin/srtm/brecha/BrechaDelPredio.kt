package srtm.brecha

import srtm.rentas.UsoPredio
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant

// #303: GET /brecha/{anio}/predios/{predio}, the brecha of one predio as its corrida measured it (never catastro
// live): its row, its niveles, what differs between the two sides and the avisos. camelCase, like /predios
data class BrechaDelPredio(
    val corrida: CorridaDelPredio,
    val fila: PredioBrecha,
    val niveles: List<NivelDelPredio>,
    val diferencias: List<Diferencia>,
    val iguales: List<String>,
    val avisos: List<AvisoDelPredio>,
    val tolerancias: Tolerancias
)

data class CorridaDelPredio(
    val id: String,
    val anio: Int?,
    val terminado: String?,
    val versionCatastro: String?,
    val origen: String?
)

// a piso of the row: its areas (delta null when either is missing), its rentas niveles and catastro secciones
// (#304: null before them) and, for a PAR whose two sides have one nivel each, what differs between them
data class NivelDelPredio(
    val piso: Int?,
    val areaRentas: BigDecimal?,
    val areaCatastro: BigDecimal?,
    val estado: String?,
    val delta: BigDecimal?,
    val nivelesRentas: Int?,
    val detalleRentas: List<DetalleNivel>?,
    val detalleCatastro: List<DetalleNivel>?,
    val diferencias: List<Diferencia>
)

// a field that differs: each side's value and, for numbers, catastro - rentas
data class Diferencia(
    val grupo: String,
    val campo: String,
    val rentas: Any?,
    val catastro: Any?,
    val delta: BigDecimal?,
    val unidad: String
)

data class AvisoDelPredio(
    val codigo: String,
    val texto: String
)

// the tolerances the comparison used: an area or a frente differs when |Δ| >= area, an amount when |Δ| >= monto
// (#305 makes them parameters of the organization)
data class Tolerancias(
    val area: BigDecimal,
    val monto: BigDecimal
)

// what the service reads besides the row and its niveles: the uso catalog by código, the UIT of 1 de enero (null:
// none, then SUPERA_5_UIT is not told), the last save of the predio's vigente DJs and their niveles and obras, and
// whether another predio of the same codigo_origen crossed in the corrida (CODIGO_COMPARTIDO)
data class ContextoDelPredio(
    val usos: Map<String, UsoPredio> = emptyMap(),
    val uit: BigDecimal? = null,
    val guardados: List<Instant?> = emptyList(),
    val codigoCompartido: Boolean = false
)

// #303: the comparison of a row's two sides, pure: only what differs past the tolerances, in a fixed order by grupo
// (AREAS, NIVELES, VALORES, USO), and the fields compared that do not (iguales). a field missing on either side is
// neither: it is not available, not a difference. a text differs when it is not the same without case nor spaces
object ComparacionDelPredio {
    val TOLERANCIA_AREA = BigDecimal("0.50")
    val TOLERANCIA_MONTO = BigDecimal("1.00")
    val TOLERANCIAS = Tolerancias(TOLERANCIA_AREA, TOLERANCIA_MONTO)

    const val AREAS = "AREAS"
    const val NIVELES = "NIVELES"
    const val VALORES = "VALORES"
    const val USO = "USO"

    const val M2 = "M2"
    const val M = "M"
    const val SOLES = "SOLES"
    const val TEXTO = "TEXTO"
    const val NUMERO = "NUMERO"

    // the separator of the five acabados in one value (I·I·I·I·I)
    private const val PUNTO = "·"

    private val DETALLE = JsonMapper.builder().build()
    private val CINCO = BigDecimal(5)

    private class Comparador(
        private val tolerancias: Tolerancias
    ) {
        val diferencias = mutableListOf<Diferencia>()
        val iguales = mutableListOf<String>()

        fun numero(
            grupo: String,
            campo: String,
            rentas: BigDecimal?,
            catastro: BigDecimal?,
            unidad: String
        ) {
            if (rentas == null || catastro == null) return
            val delta = catastro - rentas
            val tolerancia = if (unidad == SOLES) tolerancias.monto else tolerancias.area
            if (unidad == NUMERO) {
                if (delta.signum() != 0) diferencias += Diferencia(grupo, campo, rentas, catastro, delta, unidad) else iguales += campo
                return
            }
            if (delta.abs() >= tolerancia) diferencias += Diferencia(grupo, campo, rentas, catastro, delta, unidad) else iguales += campo
        }

        fun texto(
            grupo: String,
            campo: String,
            rentas: String?,
            catastro: String?
        ) {
            if (rentas.isNullOrBlank() || catastro.isNullOrBlank()) return
            if (normal(rentas) != normal(catastro)) diferencias += Diferencia(grupo, campo, rentas, catastro, null, TEXTO) else iguales += campo
        }
    }

    private fun normal(texto: String) = texto.uppercase().filterNot(Char::isWhitespace)

    fun comparar(
        corrida: RegistroCorrida,
        fila: FilaBrecha,
        niveles: List<NivelBrecha>,
        contexto: ContextoDelPredio,
        tolerancias: Tolerancias = TOLERANCIAS
    ): BrechaDelPredio {
        val c = Comparador(tolerancias)
        c.numero(AREAS, "areaTerreno", fila.areaTerrenoDeclarada, fila.areaTerrenoCatastro, M2)
        c.numero(AREAS, "areaConstruida", fila.areaConstruidaDeclarada, fila.areaConstruidaCatastro, M2)
        if (niveles.isNotEmpty()) {
            c.numero(
                NIVELES,
                "pisosConConstruccion",
                BigDecimal(niveles.count { (it.areaRentas ?: BigDecimal.ZERO).signum() > 0 }),
                BigDecimal(niveles.count { (it.areaCatastro ?: BigDecimal.ZERO).signum() > 0 }),
                NUMERO
            )
        }
        c.numero(VALORES, "terreno", fila.terrenoDeclarado, fila.terrenoCatastro, SOLES)
        c.numero(VALORES, "construccion", fila.construccionDeclarada, fila.construccionCatastro, SOLES)
        c.numero(VALORES, "obras", fila.obrasDeclaradas, fila.obrasCatastro, SOLES)
        c.numero(VALORES, "arancel", fila.arancelDeclarado, fila.arancelCatastro, SOLES)
        c.numero(VALORES, "autoavaluo", fila.autoavaluoDeclarado, fila.autoavaluoCatastro, SOLES)
        c.numero(VALORES, "impuesto", fila.impuestoDeclarado, fila.impuestoCatastro, SOLES)
        // Δ arbitrios has no sides, only its difference
        fila.deltaArbitrios?.let { delta ->
            if (delta.abs() >=
                tolerancias.monto
            ) {
                c.diferencias += Diferencia(VALORES, "deltaArbitrios", null, null, delta, SOLES)
            } else {
                c.iguales += "deltaArbitrios"
            }
        }
        uso(c, fila.usoDeclarado, fila.uso, contexto.usos)
        c.numero(USO, "frente", fila.frenteDeclarado, fila.frenteCatastro, M)
        return BrechaDelPredio(
            corrida = CorridaDelPredio(corrida.id!!, corrida.anio, corrida.terminado, corrida.versionCatastro, corrida.origen),
            fila = predioBrechaDe(fila),
            niveles = ordenados(niveles).map { nivel(it, tolerancias) },
            diferencias = c.diferencias,
            iguales = c.iguales,
            avisos = avisos(corrida, fila, contexto),
            tolerancias = tolerancias
        )
    }

    // the uso by its clase and subclase of uso_predio when both códigos are in the catalog; else as texts
    private fun uso(
        c: Comparador,
        declarado: String?,
        catastro: String?,
        usos: Map<String, UsoPredio>
    ) {
        val r = declarado?.trim()?.let(usos::get)
        val k = catastro?.trim()?.let(usos::get)
        if (r != null && k != null) {
            c.texto(USO, "usoClase", r.clase, k.clase)
            c.texto(USO, "usoSubclase", r.subClase, k.subClase)
        } else {
            c.texto(USO, "uso", declarado, catastro)
        }
    }

    // by piso, those without one last
    private fun ordenados(niveles: List<NivelBrecha>) = niveles.sortedWith(compareBy(nullsLast()) { it.piso })

    private fun nivel(
        n: NivelBrecha,
        tolerancias: Tolerancias
    ): NivelDelPredio {
        val rentas = detalle(n.detalleRentas)
        val catastro = detalle(n.detalleCatastro)
        val diferencias =
            if (n.estado == EstadoNivel.PAR.name && rentas?.size == 1 && catastro?.size == 1) {
                caracteristicas(rentas.single(), catastro.single(), tolerancias)
            } else {
                emptyList()
            }
        return NivelDelPredio(
            piso = n.piso,
            areaRentas = n.areaRentas,
            areaCatastro = n.areaCatastro,
            estado = n.estado,
            delta = if (n.areaRentas == null || n.areaCatastro == null) null else n.areaCatastro - n.areaRentas,
            nivelesRentas = n.nivelesRentas,
            detalleRentas = rentas,
            detalleCatastro = catastro,
            diferencias = diferencias
        )
    }

    fun detalle(texto: String?): List<DetalleNivel>? =
        texto?.takeIf { it.isNotBlank() }?.let {
            DETALLE.readValue(
                it,
                DETALLE.typeFactory.constructCollectionType(List::class.java, DetalleNivel::class.java)
            )
        }

    // #304: a PAR piso's características, each apart, and the five acabados as one: the user counts them as a
    // difference like any other (decision of 14-11-2026), grouped so they do not hide the rest
    fun caracteristicas(
        r: DetalleNivel,
        k: DetalleNivel,
        tolerancias: Tolerancias = TOLERANCIAS
    ): List<Diferencia> {
        val c = Comparador(tolerancias)
        c.texto(NIVELES, "material", r.material, k.material)
        c.texto(NIVELES, "estadoConservacion", r.estadoConservacion, k.estadoConservacion)
        c.numero(NIVELES, "anioConstruccion", r.anioConstruccion?.let(::BigDecimal), k.anioConstruccion?.let(::BigDecimal), NUMERO)
        c.texto(NIVELES, "murosColumnas", r.murosColumnas, k.murosColumnas)
        c.texto(NIVELES, "techos", r.techos, k.techos)
        c.texto(NIVELES, "acabados", acabados(r), acabados(k))
        c.texto(NIVELES, "clasificacion", r.clasificacion, k.clasificacion)
        return c.diferencias
    }

    // pisos, puertas y ventanas, revestimientos, baños e instalaciones; null when none is there
    private fun acabados(d: DetalleNivel): String? {
        val letras = listOf(d.pisos, d.puertasVentanas, d.revestimientos, d.banos, d.instalaciones)
        if (letras.all { it.isNullOrBlank() }) return null
        return letras.joinToString(PUNTO) { it?.trim().orEmpty().ifEmpty { "-" } }
    }

    private fun avisos(
        corrida: RegistroCorrida,
        fila: FilaBrecha,
        contexto: ContextoDelPredio
    ): List<AvisoDelPredio> {
        val avisos = mutableListOf<AvisoDelPredio>()
        val declarada = fila.areaConstruidaDeclarada ?: BigDecimal.ZERO
        val catastral = fila.areaConstruidaCatastro ?: BigDecimal.ZERO
        val conCatastro = fila.autoavaluoCatastro != null || fila.areaTerrenoCatastro != null
        val conRentas = fila.autoavaluoDeclarado != null
        if (conCatastro && declarada.signum() > 0 && catastral.signum() == 0) {
            avisos += AvisoDelPredio(CATASTRO_SIN_CONSTRUCCION, "Rentas declara área construida y el catastro no tiene construcciones")
        }
        if (conRentas && catastral.signum() > 0 && declarada.signum() == 0) {
            avisos += AvisoDelPredio(RENTAS_SIN_CONSTRUCCION, "El catastro tiene construcciones y rentas no declara área construida")
        }
        val delta = fila.deltaAutoavaluo
        if (contexto.uit != null && delta != null && delta.abs() > contexto.uit.multiply(CINCO)) {
            avisos += AvisoDelPredio(Seguimiento.SUPERA_5_UIT, "El Δ autoavalúo supera 5 UIT del 1 de enero de ${corrida.anio}")
        }
        if ((fila.obrasDj ?: BigDecimal.ZERO).signum() > 0) {
            avisos += AvisoDelPredio(OBRAS_DE_LA_DJ, "El lado del catastro sumó las obras de la DJ: la ficha no levanta obras")
        }
        if (fila.categoria in COMPARADAS && fila.deltaArbitrios == null) {
            avisos += AvisoDelPredio(SIN_DELTA_ARBITRIOS, "La corrida no calculó el Δ arbitrios de este predio")
        }
        val terminado = corrida.terminado?.let(::instante)
        if (terminado != null && contexto.guardados.any { it != null && it.isAfter(terminado) }) {
            avisos += AvisoDelPredio(DJ_POSTERIOR_A_LA_CORRIDA, "La DJ del predio cambió después de la corrida: estas cifras son de antes")
        }
        if (contexto.codigoCompartido) {
            avisos +=
                AvisoDelPredio(
                    CODIGO_COMPARTIDO,
                    "El código del SAT de este predio se partió en varios predios y la ficha cruzó con otro de ellos"
                )
        }
        // #304: the external padrón's components kept as they came, which do not always add up (Ica: ~3 %)
        val terreno = fila.terrenoDeclarado
        val construccion = fila.construccionDeclarada
        val autoavaluo = fila.autoavaluoDeclarado
        if (terreno != null && construccion != null && autoavaluo != null) {
            val suma = terreno + construccion + (fila.obrasDeclaradas ?: BigDecimal.ZERO)
            if ((suma - autoavaluo).abs() >= TOLERANCIA_MONTO) {
                avisos += AvisoDelPredio(COMPONENTES_NO_SUMAN, "Los componentes declarados no suman el autoavalúo declarado")
            }
        }
        return avisos
    }

    // the categorías whose two sides were compared (Brecha.COMPARADAS without OMISO, which has no declared side)
    private val COMPARADAS = setOf(Categoria.SUBVALUADO.name, Categoria.SOBREVALUADO.name, Categoria.COINCIDE.name)

    const val CATASTRO_SIN_CONSTRUCCION = "CATASTRO_SIN_CONSTRUCCION"
    const val RENTAS_SIN_CONSTRUCCION = "RENTAS_SIN_CONSTRUCCION"
    const val OBRAS_DE_LA_DJ = "OBRAS_DE_LA_DJ"
    const val SIN_DELTA_ARBITRIOS = "SIN_DELTA_ARBITRIOS"
    const val DJ_POSTERIOR_A_LA_CORRIDA = "DJ_POSTERIOR_A_LA_CORRIDA"
    const val CODIGO_COMPARTIDO = "CODIGO_COMPARTIDO"
    const val COMPONENTES_NO_SUMAN = "COMPONENTES_NO_SUMAN"
}
