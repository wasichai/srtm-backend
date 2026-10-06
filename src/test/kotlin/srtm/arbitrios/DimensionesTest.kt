package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import srtm.impuesto.ParametroTributario
import srtm.rentas.Predio
import java.time.LocalDate

// Dimensiones' two readers (influencia, afluencia) and valores, the way Arbitrios.determinar turns a servicio's
// dimensiones (Servicios.dimensiones) into its tasa key, together with zona and uso
class DimensionesTest {
    private val anio = 2026
    private val dia = LocalDate.of(2026, 3, 15)

    private fun param(
        tipo: String,
        clave: String,
        texto: String? = null,
        desde: LocalDate = LocalDate.of(2026, 1, 1),
        hasta: LocalDate? = null
    ) = ParametroTributario(
        id = "p-$tipo-$clave",
        tipo = tipo,
        clave = clave,
        vigenciaDesde = desde,
        vigenciaHasta = hasta,
        texto = texto,
        norma = "Ordenanza ficticia de prueba"
    )

    @Test
    fun `influencia without ubicacion de area verde is faltan, naming the predio`() {
        val predio = Predio(id = "p-1", codigo = "01-01-0001", ubicacionAreaVerde = null)
        assertEquals(
            ValorDimension.Falta("Ubicación respecto del área verde del predio 01-01-0001"),
            Dimensiones.influencia(emptyList(), predio, "01-01-0001", anio, dia)
        )
    }

    @Test
    fun `influencia without its mapping is faltan, naming the ubicacion`() {
        val predio = Predio(id = "p-1", codigo = "01-01-0001", ubicacionAreaVerde = "FRENTE A PARQUE")
        assertEquals(
            ValorDimension.Falta("${Llaves.ARBITRIO_INFLUENCIA} FRENTE A PARQUE 2026"),
            Dimensiones.influencia(emptyList(), predio, "01-01-0001", anio, dia)
        )
    }

    @Test
    fun `influencia is the texto of its mapping, in force that day`() {
        val predio = Predio(id = "p-1", codigo = "01-01-0001", ubicacionAreaVerde = "FRENTE A PARQUE")
        val fila = param(Llaves.ARBITRIO_INFLUENCIA, "FRENTE A PARQUE", texto = "Frente a Parque y/o Área Verde")
        assertEquals(
            ValorDimension.Es("Frente a Parque y/o Área Verde"),
            Dimensiones.influencia(listOf(fila), predio, "01-01-0001", anio, dia)
        )
    }

    @Test
    fun `afluencia without any matching row is faltan, naming the codigo de uso`() {
        assertEquals(
            ValorDimension.Falta("${Llaves.ARBITRIO_AFLUENCIA} 010101 2026"),
            Dimensiones.afluencia(emptyList(), "PARQUES", "010101", anio, dia)
        )
    }

    @Test
    fun `afluencia reads the longest matching prefijo, like ARBITRIO_USO`() {
        val corto = param(Llaves.ARBITRIO_AFLUENCIA, "01", texto = "Baja")
        val largo = param(Llaves.ARBITRIO_AFLUENCIA, "0101", texto = "Alta")
        assertEquals(ValorDimension.Es("Alta"), Dimensiones.afluencia(listOf(corto, largo), "PARQUES", "010101", anio, dia))
    }

    @Test
    fun `afluencia prefers a servicio-specific row over a bare prefijo, even when the bare one is longer`() {
        val general = param(Llaves.ARBITRIO_AFLUENCIA, "0101", texto = "Alta")
        val deServicio = param(Llaves.ARBITRIO_AFLUENCIA, "PARQUES:01", texto = "Baja")
        assertEquals(ValorDimension.Es("Baja"), Dimensiones.afluencia(listOf(general, deServicio), "PARQUES", "010101", anio, dia))
    }

    @Test
    fun `afluencia only in force that day counts`() {
        val vencida = param(Llaves.ARBITRIO_AFLUENCIA, "01", texto = "Baja", hasta = LocalDate.of(2025, 12, 31))
        assertEquals(
            ValorDimension.Falta("${Llaves.ARBITRIO_AFLUENCIA} 010101 2026"),
            Dimensiones.afluencia(listOf(vencida), "PARQUES", "010101", anio, dia)
        )
    }

    @Test
    fun `valores resolves zona and uso directly, in the order of las dimensiones`() {
        val (valores, faltan) =
            Dimensiones.valores(
                listOf(Dimension.USO, Dimension.ZONA),
                "A",
                "CASA",
                { error("no debería llamarse") },
                { error("no debería llamarse") }
            )
        assertEquals(listOf("CASA", "A"), valores)
        assertEquals(emptyList<String>(), faltan)
    }

    @Test
    fun `valores calls influencia and afluencia only when listed, and keeps the order`() {
        val (valores, faltan) =
            Dimensiones.valores(
                listOf(Dimension.ZONA, Dimension.INFLUENCIA, Dimension.AFLUENCIA),
                "A",
                "CASA",
                { ValorDimension.Es("Frente a Parque y/o Área Verde") },
                { ValorDimension.Es("Baja") }
            )
        assertEquals(listOf("A", "Frente a Parque y/o Área Verde", "Baja"), valores)
        assertEquals(emptyList<String>(), faltan)
    }

    @Test
    fun `valores collects every faltan, without stopping at the first`() {
        val (valores, faltan) =
            Dimensiones.valores(
                listOf(Dimension.ZONA, Dimension.INFLUENCIA, Dimension.AFLUENCIA),
                "A",
                "CASA",
                { ValorDimension.Falta("falta influencia") },
                { ValorDimension.Falta("falta afluencia") }
            )
        assertEquals(listOf("A"), valores) // ZONA still resolves; the caller discards it anyway since faltan is not empty
        assertEquals(listOf("falta influencia", "falta afluencia"), faltan)
    }
}
