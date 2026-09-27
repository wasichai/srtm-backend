package srtm.emision

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.impuesto.ImpuestoPredial
import srtm.impuesto.Parametros
import srtm.rentas.Contribuyente
import srtm.rentas.Declaracion
import srtm.rentas.Predio
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import javax.imageio.ImageIO

// what the HR shows of a contribuyente, its predios and the liquidación, formatted; and the hr template rendering it
class HojaHrTest {
    private val hoy = LocalDate.of(2026, 3, 15)

    @Test
    fun `the contribuyente shows its full domicilio fiscal and its condicion especial`() {
        val c = hoja().contribuyente
        assertEquals("000123", c.codigo)
        assertEquals("PEÑA ÑAUPARI, JOSÉ", c.nombre)
        assertEquals("DNI 45678912", c.documento)
        assertEquals("AV. PERÚ 456 — PERENÉ / CHANCHAMAYO / JUNÍN", c.domicilioFiscal)
        assertEquals("PENSIONISTA", c.condicionEspecial)
    }

    @Test
    fun `without a condicion especial it is empty`() {
        val hoja = hoja(declaraciones = listOf(PROPIA.copy(condicionEspecial = null), CONDOMINIO))
        assertEquals("", hoja.contribuyente.condicionEspecial)
    }

    @Test
    fun `a row per declaracion, in predio code order, with the totals`() {
        val hoja = hoja()
        assertEquals(listOf("01-01-0001", "01-02-0003"), hoja.predios.map { it.codigo })
        val condominio = hoja.predios[1]
        assertEquals("JR. LIMA 123", condominio.direccion)
        assertEquals("RESIDENCIAL / CASA HABITACIÓN", condominio.uso)
        assertEquals("S/ 100,000.00", condominio.autoavaluo)
        assertEquals("50.00 %", condominio.porcentaje)
        assertEquals("S/ 50,000.00", condominio.valorAfecto)
        assertEquals("S/ 160,000.00", hoja.totales.autoavaluo)
        assertEquals("S/ 110,000.00", hoja.totales.valorAfecto)
    }

    @Test
    fun `the determinacion shows the uit, the base and each tramo`() {
        val i = hoja().impuesto
        val liquidacion = liquidar("110000.00")
        assertEquals(soles(liquidacion.uit!!), i.uit)
        assertEquals("S/ 110,000.00", i.base)
        assertEquals(3, i.tramos.size)
        val primero = i.tramos.first()
        assertEquals("1", primero.tramo)
        assertEquals("S/ 0.00", primero.desde)
        assertEquals(soles(liquidacion.tramos[0].hasta!!), primero.hasta)
        assertEquals("${liquidacion.tramos[0].alicuota.stripTrailingZeros().toPlainString()} %", primero.alicuota)
        assertEquals(soles(liquidacion.tramos[0].impuesto), primero.impuesto)
        assertEquals("en adelante", i.tramos.last().hasta)
        assertEquals(soles(liquidacion.impuestoAnual!!), i.anual)
        assertEquals(soles(liquidacion.impuestoCalculado!!), i.calculado)
        assertEquals(soles(liquidacion.minimo!!), i.minimo)
        assertFalse(i.minimoAplicado)
    }

    @Test
    fun `four cuotas with their vencimiento and the pago al contado`() {
        val hoja = hoja()
        val liquidacion = liquidar("110000.00")
        assertEquals(listOf("1", "2", "3", "4"), hoja.cuotas.map { it.numero })
        assertEquals(listOf("27/02/2026", "29/05/2026", "31/08/2026", "30/11/2026"), hoja.cuotas.map { it.vencimiento })
        assertEquals(liquidacion.cuotas.map { soles(it.monto) }, hoja.cuotas.map { it.monto })
        assertEquals("Al contado: ${soles(liquidacion.impuestoAnual!!)} hasta el 27/02/2026", hoja.contado)
    }

    @Test
    fun `the hr renders the predios, the tramos, the cuotas and the ipm note`() {
        val pdf = PdfRenderer().render("hr", mapOf("hr" to hoja()))
        val texto = texto(pdf)
        assertTrue("HOJA DE RESUMEN" in texto, texto)
        assertTrue("01-01-0001" in texto && "01-02-0003" in texto, texto)
        assertTrue("S/ 110,000.00" in texto, texto)
        assertTrue(soles(liquidar("110000.00").impuestoAnual!!) in texto, texto)
        assertTrue("30/11/2026" in texto, texto)
        assertTrue("Las cuotas 2 a 4 se reajustan por IPM (TUO LTM art. 15)" in texto, texto)
        assertFalse("Se aplica el mínimo" in texto, texto)
        muestra(pdf)
    }

    @Test
    fun `a small base prints that the minimo applies`() {
        val chica = PROPIA.copy(valorAutoavaluo = BigDecimal("1000.00"), valorCondominio = BigDecimal("1000.00"), valorAfecto = BigDecimal("1000.00"))
        val hoja = hoja(declaraciones = listOf(chica), liquidacion = liquidar("1000.00"))
        assertTrue(hoja.impuesto.minimoAplicado)
        val texto = texto(PdfRenderer().render("hr", mapOf("hr" to hoja)))
        assertTrue("Se aplica el mínimo" in texto, texto)
    }

    // with -Dmuestra.dir the rendered hr and its pages as png land there, to look at
    private fun muestra(pdf: ByteArray) {
        val dir = System.getProperty("muestra.dir") ?: return
        File(dir).mkdirs()
        File(dir, "hr.pdf").writeBytes(pdf)
        Loader.loadPDF(pdf).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                ImageIO.write(PDFRenderer(doc).renderImageWithDPI(i, 110f), "png", File(dir, "hr-${i + 1}.png"))
            }
        }
    }

    private fun liquidar(base: String) = ImpuestoPredial.liquidar(2026, BigDecimal(base), Parametros.predial)

    private fun soles(valor: BigDecimal) = "S/ " + java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(java.util.Locale.US)).format(valor)

    private fun hoja(
        declaraciones: List<Declaracion> = listOf(CONDOMINIO, PROPIA),
        liquidacion: srtm.impuesto.Liquidacion = liquidar("110000.00")
    ) = hojaHr("MUNICIPALIDAD DISTRITAL DE PERENÉ", 2026, CONTRIBUYENTE, PREDIOS, declaraciones, liquidacion, hoy)

    private companion object {
        val CONTRIBUYENTE =
            Contribuyente(
                id = "c1",
                codigo = "000123",
                nombreCompleto = "PEÑA ÑAUPARI, JOSÉ",
                tipoDocumento = "DNI",
                numeroDocumento = "45678912",
                domicilioFiscal = "AV. PERÚ 456",
                domicilioDistrito = "PERENÉ",
                domicilioProvincia = "CHANCHAMAYO",
                domicilioDepartamento = "JUNÍN"
            )
        val PREDIOS =
            mapOf(
                "p1" to Predio(id = "p1", codigo = "01-01-0001", direccion = "AV. MARGINAL 10"),
                "p2" to Predio(id = "p2", codigo = "01-02-0003", direccion = "JR. LIMA 123")
            )
        val PROPIA =
            Declaracion(
                id = "d1",
                predio = "p1",
                anio = 2026,
                secuenciaUso = "001",
                condicionPropiedad = "PROPIETARIO UNICO",
                porcentajeCondominio = BigDecimal("100"),
                claseUso = "COMERCIAL",
                uso = "TIENDA",
                valorAutoavaluo = BigDecimal("60000.00"),
                valorCondominio = BigDecimal("60000.00"),
                deduccion = BigDecimal.ZERO,
                valorAfecto = BigDecimal("60000.00"),
                condicionEspecial = "PENSIONISTA"
            )
        val CONDOMINIO =
            Declaracion(
                id = "d2",
                predio = "p2",
                anio = 2026,
                secuenciaUso = "001",
                condicionPropiedad = "CONDOMINO",
                porcentajeCondominio = BigDecimal("50"),
                claseUso = "RESIDENCIAL",
                uso = "CASA HABITACIÓN",
                valorAutoavaluo = BigDecimal("100000.00"),
                valorCondominio = BigDecimal("50000.00"),
                deduccion = BigDecimal.ZERO,
                valorAfecto = BigDecimal("50000.00")
            )
    }
}
