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

    // the same cases are in srtm-ui (src/portal/direccion.test.tsx): both sides must print the same line
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
            "CA. ADELA DELGADO DE VELEZMORO, N° 234 A, MZ. C, LT. 19, AA.HH. SANTO TORIBIO DE MOGROVEJO, JUNIN-CHANCHAMAYO-PERENE",
            describir(domicilio)
        )
        // OTROS is not a word of the address, blanks are skipped
        assertEquals(
            "SECTOR IPANEMA, KM. 12, JUNIN",
            describir(Domicilio(tipoVia = "OTROS", via = "SECTOR IPANEMA", kilometro = "12", numero = " ", departamento = "JUNIN"))
        )
    }

    @Test
    fun `the srtm abbreviates the common types of via and unidad urbana, the rest go whole`() {
        assertEquals(
            listOf("AV. A", "CA. A", "JR. A", "PSJE. A", "PROL. A", "CARR. A", "CARROZABLE A", "MALECON A", "A"),
            listOf("AVENIDA", "CALLE", "JIRON", "PASAJE", "PROLONGACION", "CARRETERA", "CARROZABLE", "MALECON", "OTROS").map {
                describir(Domicilio(tipoVia = it, via = "A"))
            }
        )
        assertEquals(
            listOf("AA.HH. B", "AA.VV. B", "C.P. B", "URB. B", "CERCADO B", "B"),
            listOf("ASENTAMIENTO HUMANO", "ASOCIACION DE VIVIENDA", "CENTRO POBLADO", "URBANIZACION", "CERCADO", "OTROS").map {
                describir(Domicilio(tipoUnidadUrbana = it, unidadUrbana = "B"))
            }
        )
    }

    @Test
    fun `a via or zona that already starts with its type does not get it twice`() {
        assertEquals("JR. LIMA", describir(Domicilio(tipoVia = "JIRON", via = "JR. LIMA")))
        assertEquals("JIRON LIMA", describir(Domicilio(tipoVia = "JIRON", via = "JIRON LIMA")))
        assertEquals("URB. LOS PINOS", describir(Domicilio(tipoUnidadUrbana = "URBANIZACION", unidadUrbana = "URB. LOS PINOS")))
        // a word that only starts like the type is the name's
        assertEquals("CA. CALLEJON OSCURO", describir(Domicilio(tipoVia = "CALLE", via = "CALLEJON OSCURO")))
        // a type alone, with no name, is still written
        assertEquals("AV.", describir(Domicilio(tipoVia = "AVENIDA")))
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
        assertEquals("AV. ANDRES AVELINO CACERES, MZ. C, LT. 19, URB. SOL DE LA ALAMEDA, JUNIN-CHANCHAMAYO-PERENE", describirUbicacion(ubicacion))
        assertEquals("JR. LIMA Nro.: 12", describirUbicacion(Predio(direccion = "JR. LIMA Nro.: 12", via = "JR. LIMA")))
    }

    @Test
    fun `a predio of the padron, normalized, reads as the srtm writes it`() {
        // what model/normalizar_padron.py leaves of "JIRON LIMA Nro.: 12 Mz.: A Lt.: 5 Km.: 1 CERCADO II MESETA"
        val padron =
            Predio(
                direccion = "JIRON LIMA Nro.: 12 Mz.: A Lt.: 5 Km.: 1 CERCADO II MESETA",
                tipoVia = "JIRON",
                via = "LIMA",
                numero = "12",
                manzana = "A",
                lote = "5",
                kilometro = "1",
                tipoZona = "CERCADO",
                habilitacionUrbana = "II MESETA",
                departamento = "JUNIN",
                provincia = "CHANCHAMAYO",
                distrito = "PERENE"
            )
        assertEquals("JR. LIMA, N° 12, MZ. A, LT. 5, KM. 1, CERCADO II MESETA, JUNIN-CHANCHAMAYO-PERENE", describirUbicacion(padron))
    }

    @Test
    fun `the secuencia de uso has the padron's three digits`() {
        assertEquals("001", secuenciaUso(null))
        assertEquals("001", secuenciaUso(" "))
        assertEquals("001", secuenciaUso("1"))
        assertEquals("012", secuenciaUso(" 12 "))
        assertEquals("002", secuenciaUso("002"))
        assertEquals("0001", secuenciaUso("0001"))
        // not a number: kept as written
        assertEquals("A", secuenciaUso("A"))
    }

    @Test
    fun `a secuencia stored before the padding is the same condominio and the same autoavaluo`() {
        val antes = Declaracion(predio = "P", anio = 2026, secuenciaUso = "1", valorAutoavaluo = java.math.BigDecimal("1000"))
        val despues = antes.copy(secuenciaUso = "001")
        assertEquals(grupoDe(antes), grupoDe(despues))
        assertEquals(java.math.BigDecimal("1000"), totalesDePredio(listOf(antes, despues)).autoavaluo)
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
