package srtm.emision

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.rentas.Contribuyente
import srtm.rentas.Declaracion
import srtm.rentas.NivelConstruccion
import srtm.rentas.ObraComplementaria
import srtm.rentas.Predio
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import javax.imageio.ImageIO

// what the PU shows of a predio, its titular and each of its usos, formatted; and the pu template rendering it
class HojaPuTest {
    private val ahora = LocalDateTime.of(2026, 3, 15, 9, 30)

    @Test
    fun `the titular is named by its full name and document`() {
        val hoja = hoja()
        assertEquals("000123", hoja.contribuyente.codigo)
        assertEquals("PEÑA ÑAUPARI, JOSÉ", hoja.contribuyente.nombre)
        assertEquals("DNI 45678912", hoja.contribuyente.documento)
        assertEquals("CONDOMINO", hoja.contribuyente.condicion)
        assertEquals("50.00 %", hoja.contribuyente.porcentaje)
    }

    @Test
    fun `a juridica is named by its razon social`() {
        val hoja =
            hoja(
                contribuyente =
                    CONTRIBUYENTE.copy(
                        nombreCompleto = null,
                        razonSocial = "INVERSIONES SAC",
                        tipoDocumento = "RUC",
                        numeroDocumento = "20123456789"
                    )
            )
        assertEquals("INVERSIONES SAC", hoja.contribuyente.nombre)
        assertEquals("RUC 20123456789", hoja.contribuyente.documento)
    }

    @Test
    fun `the predio's ubicacion joins its catastral parts`() {
        val ubicacion = hoja().predio
        assertEquals("01-02-0003", ubicacion.codigo)
        assertEquals("JR. LIMA 123", ubicacion.direccion)
        assertEquals("01 / 02 / 3", ubicacion.sectorManzanaLote)
        assertEquals("URB. LOS PINOS", ubicacion.habilitacion)
        assertEquals("120606 · JUNÍN / CHANCHAMAYO / PERENÉ", ubicacion.ubigeo)
    }

    @Test
    fun `each uso is a section, in secuencia order`() {
        val hoja = hoja(usos = listOf(uso("002", 12), uso("001", 11)))
        assertEquals(listOf("001", "002"), hoja.usos.map { it.secuencia })
        assertEquals("11, 12", hoja.declaraciones)
    }

    @Test
    fun `a section formats its uso, areas and values`() {
        val seccion = hoja().usos.single()
        assertEquals("RESIDENCIAL / UNIFAMILIAR / CASA HABITACIÓN", seccion.uso)
        assertEquals("250.50", seccion.areaTerreno)
        assertEquals("1,200.00", seccion.areaConstruida)
        assertEquals("S/ 123,456.78", seccion.valores.autoavaluo)
        assertEquals("S/ 61,728.39", seccion.valores.valorCondominio)
        assertEquals("S/ 0.00", seccion.valores.deduccion)
        assertEquals("", seccion.frente)
    }

    @Test
    fun `inactive niveles and obras are left out`() {
        val usos =
            listOf(
                uso("001", 11).copy(niveles = listOf(NIVEL, NIVEL.copy(numeroPiso = 2, estado = "INACTIVO")), obras = listOf(OBRA.copy(estado = "INACTIVO")))
            )
        val seccion = hoja(usos = usos).usos.single()
        assertEquals(listOf("1"), seccion.niveles.map { it.piso })
        assertTrue(seccion.obras.isEmpty())
    }

    @Test
    fun `a nivel shows its date, its seven categories and areas`() {
        val nivel =
            hoja()
                .usos
                .single()
                .niveles
                .single()
        assertEquals("03/2015", nivel.anioMes)
        assertEquals(listOf("C", "D", "E", "F", "G", "H", "I"), nivel.categorias)
        assertEquals("120.00", nivel.area)
    }

    @Test
    fun `the pu renders the predio, the titular, a nivel and the autoavaluo, a section per uso`() {
        val pdf = PdfRenderer().render("pu", mapOf("pu" to hoja(usos = listOf(uso("001", 11), uso("002", 12)))))
        val texto = texto(pdf)
        assertTrue("01-02-0003" in texto, texto)
        assertTrue("PEÑA ÑAUPARI, JOSÉ" in texto, texto)
        assertTrue("CONCRETO" in texto, texto)
        assertTrue("S/ 123,456.78" in texto, texto)
        assertTrue("DECLARACIÓN JURADA DEL IMPUESTO PREDIAL" in texto, texto)
        assertEquals(2, Regex("USO N\\.° 00[12]").findAll(texto).count(), texto)
        muestra(pdf)
    }

    @Test
    fun `the pu opens with the municipality's header, as its receipts`() {
        val texto = texto(PdfRenderer().render("pu", mapOf("pu" to hoja())))
        assertTrue("MUNICIPALIDAD DISTRITAL DE PERENÉ" in texto, texto)
        assertTrue("GERENCIA DE ADMINISTRACIÓN TRIBUTARIA" in texto, texto)
        assertTrue("RUC: 20195238961" in texto, texto)
        assertTrue("Fecha: 15/03/2026 09:30" in texto, texto)
        assertTrue("SUB GERENCIA DE RENTAS" in texto, texto)
        assertTrue("JR. LIMA 123 - PERENÉ" in texto, texto)
        assertTrue("PREDIO URBANO" in texto, texto)
    }

    @Test
    fun `a header with only its name prints no empty lines`() {
        val texto = texto(PdfRenderer().render("pu", mapOf("pu" to hoja(cabecera = Cabecera("MUNICIPALIDAD DISTRITAL DE PERENÉ")))))
        assertTrue("MUNICIPALIDAD DISTRITAL DE PERENÉ" in texto, texto)
        assertFalse("RUC:" in texto, texto)
        assertTrue("Fecha: 15/03/2026 09:30" in texto, texto)
    }

    @Test
    fun `the font is liberation sans, arial's metrics`() {
        val nombres = fuentes(PdfRenderer().render("pu", mapOf("pu" to hoja())))
        assertTrue(nombres.any { "LiberationSans" in it }, nombres.toString())
        assertTrue(nombres.any { "LiberationSans-Bold" in it }, nombres.toString())
    }

    @Test
    fun `the escudo prints only when configured`() {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(40, 50, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val conEscudo = hoja(cabecera = CABECERA.copy(escudo = escudoDe(png, "escudo.png")))
        assertEquals(1, imagenes(PdfRenderer().render("pu", mapOf("pu" to conEscudo))))
        assertEquals(0, imagenes(PdfRenderer().render("pu", mapOf("pu" to hoja()))))
    }

    @Test
    fun `a hundred pus in a row`() {
        val renderer = PdfRenderer()
        val hoja = hoja(usos = listOf(uso("001", 11), uso("002", 12)))
        // warm-up: the first one parses the template and loads the fonts
        renderer.render("pu", mapOf("pu" to hoja))
        val inicio = System.nanoTime()
        repeat(100) { renderer.render("pu", mapOf("pu" to hoja)) }
        val ms = (System.nanoTime() - inicio) / 1_000_000.0
        System.err.println("PU: 100 en %.0f ms, %.1f ms/PU (solo el pdf, sin leer Core)".format(ms, ms / 100))
        // the issue's goal is < 200 ms/PU; this is only the rendering, the whole pu is measured in PuApiTest
        assertTrue(ms / 100 < 200, "%.1f ms/PU".format(ms / 100))
    }

    // with -Dmuestra.dir the rendered pu and its first page as png land there, to look at
    private fun muestra(pdf: ByteArray) {
        val dir = System.getProperty("muestra.dir") ?: return
        File(dir).mkdirs()
        File(dir, "pu.pdf").writeBytes(pdf)
        Loader.loadPDF(pdf).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                ImageIO.write(PDFRenderer(doc).renderImageWithDPI(i, 110f), "png", File(dir, "pu-${i + 1}.png"))
            }
        }
    }

    private fun hoja(
        contribuyente: Contribuyente = CONTRIBUYENTE,
        usos: List<UsoDeclarado> = listOf(uso("001", 11)),
        cabecera: Cabecera = CABECERA
    ) = hojaPu(cabecera, 2026, PREDIO, contribuyente, usos, ahora)

    private fun uso(
        secuencia: String,
        numero: Int
    ) = UsoDeclarado(
        Declaracion(
            id = "d$numero",
            anio = 2026,
            secuenciaUso = secuencia,
            numeroDeclaracion = numero,
            condicionPropiedad = "CONDOMINO",
            porcentajeCondominio = BigDecimal("50"),
            claseUso = "RESIDENCIAL",
            subClaseUso = "UNIFAMILIAR",
            uso = "CASA HABITACIÓN",
            clasificacion = "CASA HABITACION",
            estadoConstruccion = "TERMINADO",
            areaTerreno = BigDecimal("250.5"),
            areaConstruida = BigDecimal("1200"),
            areaComunTerreno = BigDecimal("10"),
            valorAutoavaluo = BigDecimal("123456.78"),
            valorCondominio = BigDecimal("61728.39"),
            deduccion = BigDecimal.ZERO,
            valorAfecto = BigDecimal("61728.39")
        ),
        niveles = listOf(NIVEL),
        obras = listOf(OBRA)
    )

    private companion object {
        val CONTRIBUYENTE =
            Contribuyente(
                id = "c1",
                codigo = "000123",
                nombreCompleto = "PEÑA ÑAUPARI, JOSÉ",
                tipoDocumento = "DNI",
                numeroDocumento = "45678912",
                domicilioFiscal = "AV. PERÚ 456"
            )
        val PREDIO =
            Predio(
                id = "p1",
                codigo = "01-02-0003",
                direccion = "JR. LIMA 123",
                sectorCatastral = "01",
                manzanaCatastral = "02",
                lote = "3",
                habilitacionUrbana = "URB. LOS PINOS",
                ubigeo = "120606",
                departamento = "JUNÍN",
                provincia = "CHANCHAMAYO",
                distrito = "PERENÉ"
            )
        val NIVEL =
            NivelConstruccion(
                tipoNivel = "PISO",
                numeroPiso = 1,
                anioConstruccion = 2015,
                mesConstruccion = 3,
                material = "CONCRETO",
                estadoConservacion = "BUENO",
                murosColumnas = "C",
                techos = "D",
                pisos = "E",
                puertasVentanas = "F",
                revestimientos = "G",
                banos = "H",
                instalaciones = "I",
                areaConstruida = BigDecimal("120"),
                areaComun = BigDecimal("5.5"),
                estado = "ACTIVO"
            )
        val OBRA =
            ObraComplementaria(
                tipoObra = "MUROS PERIMETRICOS O CERCOS",
                categoria = "MURO DE LADRILLO",
                material = "LADRILLO",
                estadoConservacion = "BUENO",
                anioConstruccion = 2020,
                mesConstruccion = 1,
                cantidad = BigDecimal("2"),
                metrado = BigDecimal("50"),
                unidadMedida = "ML",
                totalMetrado = BigDecimal("100"),
                estado = "ACTIVO"
            )
    }
}
