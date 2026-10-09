package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.emision.Cabecera
import srtm.emision.PdfRenderer
import srtm.emision.texto
import srtm.rentas.Contribuyente
import srtm.sanciones.Ficticios.d
import java.math.BigDecimal

// what the paper of a resolución prints (rentas' ModeloDeLaResolucionDeGerenciaTest): the acta with its frozen multa,
// the CUIS version it used, the obligado, the recurso it resolves with its plazo, the fallo, the plazo for impugnar
// copied, the considerandos left to the municipality and the signatures without a digital one. FICTITIOUS figures
class HojaResolucionTest {
    private val cabecera = Cabecera("MUNICIPALIDAD DE PRUEBA", gerencia = "GERENCIA DE PRUEBA")

    private val codigo = Ficticios.codigo("A-042", desde = "1991-01-01").copy(baseLegal = "Ordenanza ficticia 001, art. 5")

    private val acta =
        Papeleta(
            id = "acta-1",
            familia = ADMINISTRATIVA,
            numero = "AC-0001",
            fechaInfraccion = d("1991-03-04"),
            horaInfraccion = "10:30",
            lugar = "JR. LIMA 123",
            reincidencia = SEGUNDA,
            medidaComplementaria = "Clausura temporal",
            baseImponible = BigDecimal("4321.00"),
            porcentajeInfraccion = BigDecimal("10"),
            importeInfraccion = BigDecimal("432.10"),
            porcentajeACobrar = BigDecimal("15"),
            // not base × %: the paper prints what the acta froze, it never computes it again
            importeAPagar = BigDecimal("999.99"),
            fechaCalculo = d("1991-03-05"),
            codigoInfraccion = codigo.id,
            obligado = "c1"
        )

    private val obligado =
        Contribuyente(
            id = "c1",
            tipoDocumento = "DNI",
            numeroDocumento = "12345678",
            nombreCompleto = "FLORES OTINIANO JUNIOR",
            domicilioFiscal = "AV. MARGINAL N° 234, JUNIN-CHANCHAMAYO-PERENE"
        )

    private val descargo =
        DescargoPapeleta(
            id = "d1",
            numeroExpediente = "EXP-0001",
            tipoRecurso = "RECONSIDERACION",
            fecha = d("1991-03-13"),
            presentadoHasta = d("1991-03-12"),
            enPlazo = false,
            papeleta = "acta-1"
        )

    private fun ris(sancion: String? = "Clausura por 7 días") =
        ResolucionGerencia(
            id = "r1",
            tipo = RESOLUCION_ADMINISTRATIVA,
            anio = 1991,
            correlativo = 7,
            numero = "RIS-1991-000007",
            fecha = d("1991-03-20"),
            sancionAccesoria = sancion,
            sustento = "Se constató la infracción",
            plazoTexto = "15 DIAS_HABILES",
            papeleta = "acta-1"
        )

    private val rgr =
        ResolucionGerencia(
            id = "r2",
            tipo = RESOLUCION_RECURSO,
            anio = 1991,
            correlativo = 2,
            numero = "RGR-1991-000002",
            fecha = d("1991-04-20"),
            sentido = "IMPROCEDENTE",
            efecto = SE_MANTIENE,
            sustento = "Presentado fuera de plazo",
            plazoTexto = "15 DIAS_HABILES",
            papeleta = "acta-1",
            descargo = "d1"
        )

    @Test
    fun `a RIS prints the acta with its frozen multa, the version used, the obligado and the plazo copied`() {
        val h = hojaResolucion(cabecera, ris(), acta, codigo, obligado, null)

        assertEquals("RESOLUCIÓN DE SANCIÓN — RIS", h.titulo)
        assertEquals("RIS-1991-000007", h.numero)
        assertEquals("20/03/1991", h.fecha)
        assertEquals("AC-0001", h.acta.numero)
        assertEquals("PAPELETA-acta-1", h.acta.referencia)
        assertEquals("04/03/1991", h.acta.fecha)
        assertEquals("10:30", h.acta.hora)
        assertEquals("A-042", h.acta.codigo)
        assertEquals("No exhibir la licencia (ficticio)", h.acta.descripcion)
        assertEquals("Ordenanza ficticia 001, art. 5", h.acta.baseLegal)
        assertEquals("Segunda vez", h.acta.reincidencia)
        assertEquals(
            listOf(
                "Base imponible (UIT)" to "S/ 4,321.00",
                "Porcentaje de la infracción" to "10.00 %",
                "Importe de la infracción" to "S/ 432.10",
                "Porcentaje a cobrar por la reincidencia" to "15.00 %",
                "Importe a pagar" to "S/ 999.99"
            ),
            h.acta.desglose.map { it.concepto to it.valor }
        )
        assertTrue(h.acta.calculo.startsWith("Multa calculada el 05/03/1991"), h.acta.calculo)
        assertEquals("FLORES OTINIANO JUNIOR", h.obligado.nombre)
        assertEquals("DNI 12345678", h.obligado.documento)
        assertEquals("AV. MARGINAL N° 234, JUNIN-CHANCHAMAYO-PERENE", h.obligado.domicilio)
        assertNull(h.recurso)
        assertNull(h.sentido)
        assertNull(h.efecto)
        assertEquals("Clausura por 7 días", h.sancionAccesoria)
        assertEquals("Se constató la infracción", h.sustento)
        assertEquals("Plazo para impugnar: 15 DIAS_HABILES", h.plazo)
        assertTrue("art. 218.2 del TUO de la Ley 27444" in h.basePlazo, h.basePlazo)
        assertEquals(CONSIDERANDOS, h.considerandos)
        assertEquals(listOf("Gerente", "Secretario"), h.firmas)
        assertTrue("sin firma digital" in h.sinFirma, h.sinFirma)
    }

    @Test
    fun `a RGR prints the recurso it resolves, whether it came in plazo, and its fallo`() {
        val h = hojaResolucion(cabecera, rgr, acta, codigo, obligado, descargo)

        assertEquals("RESOLUCIÓN DEL RECURSO — RGR", h.titulo)
        val r = h.recurso!!
        assertEquals("EXP-0001", r.expediente)
        assertEquals("Recurso de reconsideración", r.tipo)
        assertEquals("13/03/1991", r.presentado)
        assertEquals("NO, el plazo venció el 12/03/1991", r.enPlazo)
        assertEquals(
            "SÍ, el plazo vencía el 12/03/1991",
            hojaResolucion(cabecera, rgr, acta, codigo, obligado, descargo.copy(enPlazo = true)).recurso!!.enPlazo
        )
        assertEquals("Improcedente", h.sentido)
        assertEquals("Se mantiene la multa", h.efecto)
        assertNull(h.sancionAccesoria)
        assertEquals("Se deja sin efecto la multa", hojaResolucion(cabecera, rgr.copy(efecto = SE_DEJA_SIN_EFECTO), acta, codigo, obligado, descargo).efecto)
    }

    @Test
    fun `an obligado without a domicilio fiscal says so, and a blank sanción accesoria is none`() {
        val h = hojaResolucion(cabecera, ris(sancion = " "), acta, codigo, obligado.copy(domicilioFiscal = " "), null)
        assertEquals("Sin domicilio fiscal registrado", h.obligado.domicilio)
        assertNull(h.sancionAccesoria)
        assertNull(domicilioFiscalDe(obligado.copy(domicilioFiscal = null)))
    }

    @Test
    fun `the template draws it, accents included`() {
        val texto = texto(PdfRenderer().render("resolucion", mapOf("r" to hojaResolucion(cabecera, rgr, acta, codigo, obligado, descargo))))
        for (esperado in listOf(
            "MUNICIPALIDAD DE PRUEBA",
            "RESOLUCIÓN DEL RECURSO — RGR",
            "RGR-1991-000002",
            "Fecha: 20/04/1991",
            "AC-0001",
            "A-042",
            "Ordenanza ficticia 001, art. 5",
            "S/ 999.99",
            "FLORES OTINIANO JUNIOR",
            "DNI 12345678",
            "EXP-0001",
            "Presentado dentro del plazo: NO, el plazo venció el 12/03/1991",
            "Improcedente",
            "Plazo para impugnar: 15 DIAS_HABILES",
            CONSIDERANDOS,
            "Gerente",
            "Secretario"
        )) {
            assertTrue(esperado in texto, "$esperado: $texto")
        }
        val ris = texto(PdfRenderer().render("resolucion", mapOf("r" to hojaResolucion(cabecera, ris(), acta, codigo, obligado, null))))
        assertTrue("RESOLUCIÓN DE SANCIÓN — RIS" in ris, ris)
        assertTrue("Clausura por 7 días" in ris, ris)
        assertFalse("Presentado dentro del plazo" in ris, ris)
    }
}
