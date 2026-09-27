package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReglasTest {
    @Test
    fun `tipo_persona follows the srtm's tipo de contribuyente`() {
        assertEquals("NATURAL", tipoPersona("PERSONA NATURAL"))
        assertEquals("NATURAL", tipoPersona("SOCIEDAD CONYUGAL"))
        assertEquals("SUCESION", tipoPersona("SUCESION INDIVISA"))
        assertEquals("JURIDICA", tipoPersona("PERSONA JURIDICA"))
        assertEquals("JURIDICA", tipoPersona("OTROS PATRIMONIOS AUTONOMOS"))
        assertEquals(null, tipoPersona(null))
    }

    @Test
    fun `nombre_completo is surnames then names for a person, the razon social otherwise`() {
        val persona = Contribuyente(tipoContribuyente = "PERSONA NATURAL", apellidoPaterno = "FLORES", apellidoMaterno = "OTINIANO", nombres = "JUNIOR PAOLO")
        assertEquals("FLORES OTINIANO JUNIOR PAOLO", nombreCompleto(persona))
        val empresa = Contribuyente(tipoContribuyente = "PERSONA JURIDICA", razonSocial = " ASOCIACION AGRARIA PERENE ")
        assertEquals("ASOCIACION AGRARIA PERENE", nombreCompleto(empresa))
        // nothing to build it from: what was sent stays
        assertEquals("X", nombreCompleto(Contribuyente(nombreCompleto = "X")))
    }

    // the same case is in srtm-ui (describirDomicilio): both sides must print the same line
    @Test
    fun `a domicilio's one-line description`() {
        val domicilio =
            Domicilio(
                tipoVia = "CALLE",
                via = "ADELA DELGADO DE VELEZMORO",
                numero = "234",
                letra1 = "A",
                manzana = "C",
                lote = "19",
                tipoUnidadUrbana = "ASENTAMIENTO HUMANO",
                unidadUrbana = "SANTO TORIBIO DE MOGROVEJO",
                departamento = "JUNIN",
                provincia = "CHANCHAMAYO",
                distrito = "PERENE"
            )
        assertEquals(
            "CALLE ADELA DELGADO DE VELEZMORO, N° 234 A, MZ. C, LT. 19, ASENTAMIENTO HUMANO SANTO TORIBIO DE MOGROVEJO, JUNIN-CHANCHAMAYO-PERENE",
            describir(domicilio)
        )
        // OTROS is not a word of the address, blanks are skipped
        assertEquals(
            "SECTOR IPANEMA, KM. 12, JUNIN",
            describir(Domicilio(tipoVia = "OTROS", via = "SECTOR IPANEMA", kilometro = "12", numero = " ", departamento = "JUNIN"))
        )
    }

    @Test
    fun `only an active fiscal domicilio counts`() {
        assertTrue(esFiscalActivo(Domicilio(tipoDomicilio = "FISCAL", estado = "ACTIVO")))
        assertTrue(esFiscalActivo(Domicilio(tipoDomicilio = "FISCAL")))
        assertFalse(esFiscalActivo(Domicilio(tipoDomicilio = "FISCAL", estado = "INACTIVO")))
        assertFalse(esFiscalActivo(Domicilio(tipoDomicilio = "REAL")))
    }

    @Test
    fun `codes count up with a fixed width`() {
        assertEquals("000001", siguienteCodigo(null))
        assertEquals("000013", siguienteCodigo("000012"))
        assertEquals("000001", siguienteCodigo("ABC"))
    }

    @Test
    fun `a predio's direccion comes from its srtm ubicacion, an imported one keeps the padron's`() {
        val ubicacion =
            Predio(
                tipoVia = "AVENIDA",
                via = "ANDRES AVELINO CACERES",
                manzana = "C",
                lote = "19",
                tipoZona = "URBANIZACION",
                habilitacionUrbana = "SOL DE LA ALAMEDA",
                departamento = "JUNIN",
                provincia = "CHANCHAMAYO",
                distrito = "PERENE",
                direccion = "lo que diga el cliente"
            )
        assertEquals("AVENIDA ANDRES AVELINO CACERES, MZ. C, LT. 19, URBANIZACION SOL DE LA ALAMEDA, JUNIN-CHANCHAMAYO-PERENE", describirUbicacion(ubicacion))
        assertEquals("JR. LIMA Nro.: 12", describirUbicacion(Predio(direccion = "JR. LIMA Nro.: 12", via = "JR. LIMA")))
    }

    @Test
    fun `a predio's code is the next of its sector and manzana`() {
        assertEquals("01-02-", prefijoPredio("1", "2"))
        assertEquals("01-02-0001", siguienteCodigoPredio("01-02-", null))
        assertEquals("01-02-0013", siguienteCodigoPredio("01-02-", "01-02-0012"))
    }

    @Test
    fun `an obra's total metrado is cantidad times metrado`() {
        assertEquals(java.math.BigDecimal("100"), totalMetrado(ObraComplementaria(cantidad = java.math.BigDecimal("2"), metrado = java.math.BigDecimal("50"))))
        assertEquals(null, totalMetrado(ObraComplementaria(cantidad = java.math.BigDecimal("2"))))
    }
}
