package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.emision.Cabecera
import srtm.emision.PdfRenderer
import srtm.emision.texto
import srtm.impuesto.ParametroTributario
import srtm.rentas.Contribuyente
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

// what the HLA prints of a contribuyente's year: only the months with a cuota, the backend's totals, each month's due
// date and the ordinance it comes from. FICTITIOUS figures
class HojaHlaTest {
    private val servicios =
        listOf(
            ServicioArbitrio(id = "s1", codigo = "LIMPIEZA", nombre = "Limpieza pública", orden = 1),
            ServicioArbitrio(id = "s2", codigo = "SERENAZGO", nombre = "Serenazgo", orden = 2)
        )

    private fun cuota(monto: String) = CuotaMes("c", BigDecimal(monto), "c1", LocalDate.of(2026, 3, 15), "TASA_ARBITRIO:X", emptyList())

    // january to may only: the contribuyente sold in may
    private val matriz =
        MatrizArbitrios(
            anio = 2026,
            predio = PredioResumen("p1", "01-01-0001", "JR. LIMA 123"),
            filas =
                listOf(
                    FilaServicio(servicios[0], List(12) { if (it < 5) cuota("8.50") else null }, BigDecimal("42.50")),
                    FilaServicio(servicios[1], List(12) { if (it < 5) cuota("4.25") else null }, BigDecimal("21.25"))
                ),
            titulares = emptyList(),
            totalesPorMes = List(12) { if (it < 5) BigDecimal("12.75") else BigDecimal("0.00") },
            total = BigDecimal("63.75"),
            fechaCalculo = LocalDate.of(2026, 3, 15),
            pendientes = 0,
            faltan = emptyList(),
            faltanDetalle = emptyList()
        )
    private val ordenanza =
        OrdenanzaArbitrio(
            anio = 2026,
            numero = "000-2025-MD (ficticia)",
            acuerdoRatificacion = "Acuerdo de Concejo 000 (ficticio)",
            municipalidadRatificante = "MUNICIPALIDAD PROVINCIAL DE PRUEBA",
            fechaRatificacion = LocalDate.of(2025, 12, 28)
        )

    private fun hoja() =
        hojaHla(
            Cabecera("MUNICIPALIDAD DE PRUEBA"),
            Contribuyente(id = "c1", codigo = "000001", nombreCompleto = "ANA"),
            ArbitriosContribuyente(2026, Persona("c1", "000001", "ANA"), listOf(matriz), BigDecimal("63.75"), LocalDate.of(2026, 3, 15)),
            ordenanza,
            (1..12).associateWith { LocalDate.of(2026, it, 28) },
            mapOf("p1" to (listOf("Z1") to listOf("CASA"))),
            LocalDateTime.of(2026, 4, 1, 10, 30)
        )

    @Test
    fun `only the months with a cuota, by servicio, with the backend's totals`() {
        val h = hoja()
        assertEquals(listOf("Limpieza pública", "Serenazgo"), h.servicios)
        val p = h.predios.single()
        assertEquals(listOf("Enero", "Febrero", "Marzo", "Abril", "Mayo"), p.meses.map { it.mes })
        assertEquals(listOf("S/ 8.50", "S/ 4.25"), p.meses[0].montos)
        assertEquals("S/ 12.75", p.meses[0].total)
        assertEquals(listOf("S/ 42.50", "S/ 21.25"), p.totales)
        assertEquals("S/ 63.75", p.total)
        assertEquals("Z1", p.zona)
        assertEquals("CASA", p.uso)
        assertEquals("S/ 63.75", h.total)
        assertEquals("15/03/2026", h.fechaCalculo)
        assertEquals("01/04/2026 10:30", h.emitido)
        // cuotas de antes del desglose (base null, Task 10): la HLA se ve como hoy, sin líneas de cálculo
        assertEquals(emptyList<String>(), p.calculos)
    }

    // Task 11: el cálculo legible de la HLA, por predio - una línea por (secuencia, servicio, fórmula) distinta,
    // con el rango de meses (ene-dic con guion en, un solo mes alone). cifra de global-constraints.md: Miraflores
    // 2026, Ord. 665/MM
    private fun desglose(
        secuencia: String,
        formula: String
    ) = DesgloseCuota(
        id = null,
        secuenciaUso = secuencia,
        monto = null,
        base = "AREA_CONSTRUIDA_M2",
        cantidadBase = null,
        tasaUnitaria = null,
        habitantes = null,
        promedioHabitantes = null,
        variacionHabitante = null,
        habitantesPresuntos = null,
        zona = null,
        usoArbitrio = null,
        influencia = null,
        afluencia = null,
        formula = formula
    )

    private fun cuotaDesglose(vararg d: DesgloseCuota) = CuotaMes("c", BigDecimal("10.28"), "c1", LocalDate.of(2026, 3, 15), "TASA_ARBITRIO:X", d.toList())

    private fun matrizDe(vararg filas: FilaServicio) =
        MatrizArbitrios(
            anio = 2026,
            predio = PredioResumen("p1", "01-01-0001", "JR. LIMA 123"),
            filas = filas.toList(),
            titulares = emptyList(),
            totalesPorMes = List(12) { BigDecimal.ZERO },
            total = BigDecimal.ZERO,
            fechaCalculo = LocalDate.of(2026, 3, 15),
            pendientes = 0,
            faltan = emptyList(),
            faltanDetalle = emptyList()
        )

    private fun calculosDe(matriz: MatrizArbitrios) =
        hojaHla(
            Cabecera("MUNICIPALIDAD DE PRUEBA"),
            Contribuyente(id = "c1", codigo = "000001", nombreCompleto = "ANA"),
            ArbitriosContribuyente(2026, Persona("c1", "000001", "ANA"), listOf(matriz), BigDecimal.ZERO, LocalDate.of(2026, 3, 15)),
            ordenanza,
            (1..12).associateWith { LocalDate.of(2026, it, 28) },
            mapOf("p1" to (listOf("Z1") to listOf("CASA"))),
            LocalDateTime.of(2026, 4, 1, 10, 30)
        ).predios.single().calculos

    @Test
    fun `una formula constante los doce meses da una sola linea, ene a dic - Miraflores Ord 665-MM 2026`() {
        val formula = "0.0514 S/ por m² × 100.00 m² × (1 + (4 − 2) × 0.50) = 10.28"
        val servicioRecoleccion = ServicioArbitrio(id = "s1", codigo = "RECOLECCION", nombre = "Recolección", orden = 1)
        val fila = FilaServicio(servicioRecoleccion, List(12) { cuotaDesglose(desglose("001", formula)) }, BigDecimal.ZERO)
        assertEquals(listOf("Recolección (uso 001), ene–dic: $formula"), calculosDe(matrizDe(fila)))
    }

    @Test
    fun `un solo mes con cuota imprime el mes solo, sin rango`() {
        val formula = "8.50 S/ por predio"
        val fila = FilaServicio(servicios[0], List(12) { if (it == 2) cuotaDesglose(desglose("001", formula)) else null }, BigDecimal.ZERO)
        assertEquals(listOf("Limpieza pública (uso 001), mar: $formula"), calculosDe(matrizDe(fila)))
    }

    @Test
    fun `la misma formula no contigua (con un hueco) da una linea por tramo contiguo`() {
        val formula = "8.50 S/ por predio"
        val meses =
            List(12) { i ->
                val mes = i + 1
                if (mes in 1..3 || mes in 7..8) cuotaDesglose(desglose("001", formula)) else null
            }
        val fila = FilaServicio(servicios[0], meses, BigDecimal.ZERO)
        assertEquals(
            listOf("Limpieza pública (uso 001), ene–mar: $formula", "Limpieza pública (uso 001), jul–ago: $formula"),
            calculosDe(matrizDe(fila))
        )
    }

    @Test
    fun `una tasa que cambia a mitad de año da dos lineas, una por formula`() {
        val antes = "8.50 S/ por predio"
        val despues = "9.00 S/ por predio"
        val meses = List(12) { i -> cuotaDesglose(desglose("001", if (i < 6) antes else despues)) }
        val fila = FilaServicio(servicios[0], meses, BigDecimal.ZERO)
        assertEquals(
            listOf("Limpieza pública (uso 001), ene–jun: $antes", "Limpieza pública (uso 001), jul–dic: $despues"),
            calculosDe(matrizDe(fila))
        )
    }

    @Test
    fun `dos secuencias del mismo mes y servicio imprimen una linea cada una`() {
        val formula1 = "8.50 S/ por predio"
        val formula2 = "4.25 S/ por predio"
        val meses = List(12) { cuotaDesglose(desglose("001", formula1), desglose("002", formula2)) }
        val fila = FilaServicio(servicios[0], meses, BigDecimal.ZERO)
        assertEquals(
            listOf("Limpieza pública (uso 001), ene–dic: $formula1", "Limpieza pública (uso 002), ene–dic: $formula2"),
            calculosDe(matrizDe(fila))
        )
    }

    // un PU por uso (#84): a predio of two secuencias has one zona (the predio's) but two usos, one per secuencia -
    // DocumentosArbitrios.zonasYUsos collects them from every cuota, not one per servicio and mes
    @Test
    fun `a predio of two usos prints both, joined`() {
        val h =
            hojaHla(
                Cabecera("MUNICIPALIDAD DE PRUEBA"),
                Contribuyente(id = "c1", codigo = "000001", nombreCompleto = "ANA"),
                ArbitriosContribuyente(2026, Persona("c1", "000001", "ANA"), listOf(matriz), BigDecimal("63.75"), LocalDate.of(2026, 3, 15)),
                ordenanza,
                (1..12).associateWith { LocalDate.of(2026, it, 28) },
                mapOf("p1" to (listOf("Z1") to listOf("CASA", "COMERCIO"))),
                LocalDateTime.of(2026, 4, 1, 10, 30)
            )
        val p = h.predios.single()
        assertEquals("Z1", p.zona)
        assertEquals("CASA / COMERCIO", p.uso)
    }

    // the template (#85): a predio with calculos prints its «Cálculo» block and its lines; one without (cuotas of
    // before the desglose) prints none, as the HLA was before
    @Test
    fun `the HLA prints the Calculo block of a predio with calculos, and none for one without`() {
        val formula = "0.0514 S/ por m² × 100.00 m² × (1 + (4 − 2) × 0.50) = 10.28"
        val conCalculo =
            matrizDe(FilaServicio(servicios[0], List(12) { if (it < 5) cuotaDesglose(desglose("001", formula)) else null }, BigDecimal.ZERO))
                .copy(predio = PredioResumen("p2", "01-01-0002", "JR. CUZCO 456"))

        fun pdf(matrices: List<MatrizArbitrios>) =
            texto(
                PdfRenderer().render(
                    "hla",
                    mapOf(
                        "hla" to
                            hojaHla(
                                Cabecera("MUNICIPALIDAD DE PRUEBA"),
                                Contribuyente(id = "c1", codigo = "000001", nombreCompleto = "ANA"),
                                ArbitriosContribuyente(2026, Persona("c1", "000001", "ANA"), matrices, BigDecimal("63.75"), LocalDate.of(2026, 3, 15)),
                                ordenanza,
                                (1..12).associateWith { LocalDate.of(2026, it, 28) },
                                mapOf("p1" to (listOf("Z1") to listOf("CASA")), "p2" to (listOf("Z1") to listOf("CASA"))),
                                LocalDateTime.of(2026, 4, 1, 10, 30)
                            )
                    )
                )
            ).replace(Regex("\\s+"), " ")

        val sinCalculo = pdf(listOf(matriz))
        assertFalse("Cálculo" in sinCalculo, sinCalculo)
        assertFalse("S/ por" in sinCalculo, sinCalculo)

        val ambos = pdf(listOf(matriz, conCalculo))
        assertEquals(1, Regex("Cálculo").findAll(ambos).count(), ambos) // the second predio's, not the first's
        assertTrue("Limpieza pública (uso 001), ene–may: $formula" in ambos, ambos)
        assertTrue(ambos.indexOf("01-01-0002") < ambos.indexOf("Cálculo"), ambos)
    }

    @Test
    fun `each month's cuota with its due date`() {
        val cuotas = hoja().cuotas
        assertEquals(5, cuotas.size)
        assertEquals(FilaCuotaHla("Enero", "S/ 12.75", "28/01/2026"), cuotas.first())
        assertEquals("28/05/2026", cuotas.last().vencimiento)
    }

    @Test
    fun `the ordinance and its ratification, as the HLA cites them`() {
        assertEquals(
            "Ordenanza N.° 000-2025-MD (ficticia), ratificada por Acuerdo de Concejo 000 (ficticio) de la MUNICIPALIDAD PROVINCIAL DE PRUEBA del 28/12/2025",
            hoja().ordenanza
        )
    }

    @Test
    fun `a month's due date is the one in force on its day 1, and must be a date`() {
        fun fila(
            clave: String,
            texto: String,
            desde: String
        ) = ParametroTributario(id = clave, tipo = Llaves.ARBITRIO_VENCIMIENTO, clave = clave, vigenciaDesde = LocalDate.parse(desde), texto = texto)
        val parametros =
            listOf(fila("3", "2026-03-31", "2026-01-01"), fila("3", "2026-04-15", "2026-02-01"), fila("4", "fin de abril", "2026-01-01"))
        assertEquals(LocalDate.of(2026, 4, 15), vencimiento(parametros, 2026, 3))
        assertNull(vencimiento(parametros, 2026, 4))
        assertNull(vencimiento(parametros, 2026, 5))
    }
}
