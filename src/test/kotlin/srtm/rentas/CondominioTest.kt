package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate

// the titulares of one predio, año and secuencia de uso: condición, % and values follow from the whole group
class CondominioTest {
    @Test
    fun `a sole titular is propietario unico at 100 percent, whatever it declared`() {
        val sole = condominio(listOf(titular("A", porcentaje = "40", condicion = "CONDOMINO", deduccion = "1000"))).single()
        assertEquals(PROPIETARIO_UNICO, sole.condicionPropiedad)
        assertEquals(BigDecimal("100"), sole.porcentajeCondominio)
        assertEquals(BigDecimal("10000.50"), sole.valorCondominio)
        assertEquals(BigDecimal("9000.50"), sole.valorAfecto)
    }

    @Test
    fun `a sole titular keeps sociedad conyugal or poseedor, which hold the predio alone too`() {
        assertEquals("SOCIEDAD CONYUGAL", condominio(listOf(titular("A", condicion = "SOCIEDAD CONYUGAL"))).single().condicionPropiedad)
        assertEquals("POSEEDOR", condominio(listOf(titular("A", condicion = "POSEEDOR"))).single().condicionPropiedad)
    }

    @Test
    fun `two or more are condominos, each with its part of the autoavaluo`() {
        val (a, b) = condominio(listOf(titular("A", porcentaje = "60", condicion = PROPIETARIO_UNICO, deduccion = "1000"), titular("B", porcentaje = "40")))
        assertEquals(CONDOMINO, a.condicionPropiedad)
        assertEquals(CONDOMINO, b.condicionPropiedad)
        assertEquals(BigDecimal("60"), a.porcentajeCondominio)
        // 10000.50 x 60 %, less the deducción
        assertEquals(BigDecimal("6000.30"), a.valorCondominio)
        assertEquals(BigDecimal("5000.30"), a.valorAfecto)
        assertEquals(BigDecimal("4000.20"), b.valorCondominio)
        assertEquals(BigDecimal("4000.20"), b.valorAfecto)
    }

    @Test
    fun `a part is rounded half up to cents, and a deduccion larger than it leaves nothing afecto`() {
        val (a, b) = condominio(listOf(titular("A", autoavaluo = "1000", porcentaje = "33.3335"), titular("B", porcentaje = "10", deduccion = "5000")))
        assertEquals(BigDecimal("333.34"), a.valorCondominio)
        assertEquals(BigDecimal("0.00"), b.valorAfecto)
    }

    @Test
    fun `without autoavaluo or percentage there are no values to derive`() {
        val (a, b) = condominio(listOf(titular("A", autoavaluo = null, porcentaje = "50"), titular("B", porcentaje = null)))
        assertNull(a.valorCondominio)
        assertNull(a.valorAfecto)
        assertNull(b.porcentajeCondominio)
        assertNull(b.valorCondominio)
        assertNull(b.valorAfecto)
    }

    @Test
    fun `the first declaration of a predio is its sole titular's`() {
        val (d) = condominioCon(titular("A", porcentaje = null), emptyList(), seUne = true)
        assertEquals(PROPIETARIO_UNICO, d.condicionPropiedad)
        assertEquals(BigDecimal("100"), d.porcentajeCondominio)
    }

    @Test
    fun `a newcomer to a predio held by a sole titular takes its share out of that titular's 100 percent`() {
        val (b, a) = condominioCon(titular("B", porcentaje = "40"), listOf(titular("A", porcentaje = "100", condicion = PROPIETARIO_UNICO)), seUne = true)
        assertEquals(CONDOMINO, a.condicionPropiedad)
        assertEquals(BigDecimal("60"), a.porcentajeCondominio)
        assertEquals(BigDecimal("6000.30"), a.valorCondominio)
        assertEquals(CONDOMINO, b.condicionPropiedad)
        assertEquals(BigDecimal("40"), b.porcentajeCondominio)
    }

    @Test
    fun `a newcomer to a sole titular's predio needs a share between 0 and 100`() {
        val sole = listOf(titular("A", porcentaje = "100"))
        for (porcentaje in listOf("100", "0", null)) {
            val e = assertThrows<ValidationException> { condominioCon(titular("B", porcentaje = porcentaje), sole, seUne = true) }
            assertEquals("porcentaje_condominio", e.violations.single().field)
        }
    }

    @Test
    fun `the parts of two or more may not add up to more than 100`() {
        val grupo = listOf(titular("A", porcentaje = "60"), titular("B", porcentaje = "30"))
        val e = assertThrows<ValidationException> { condominioCon(titular("C", porcentaje = "20"), grupo, seUne = true) }
        assertEquals("porcentaje_condominio", e.violations.single().field)
        assertEquals("Los % de propiedad del predio sumarían 110 % (máximo 100 %)", e.message)
        // up to 100 is fine, and nobody else's part changes
        val (c, a, b) = condominioCon(titular("C", porcentaje = "10"), grupo, seUne = true)
        assertEquals(BigDecimal("10"), c.porcentajeCondominio)
        assertEquals(BigDecimal("60"), a.porcentajeCondominio)
        assertEquals(BigDecimal("30"), b.porcentajeCondominio)
    }

    @Test
    fun `a condomino's new part is checked against the others', who keep theirs`() {
        val otro = listOf(titular("B", porcentaje = "40"))
        val e = assertThrows<ValidationException> { condominioCon(titular("A", porcentaje = "70"), otro, seUne = false) }
        assertEquals("porcentaje_condominio", e.violations.single().field)
        val (a, b) = condominioCon(titular("A", porcentaje = "50"), otro, seUne = false)
        assertEquals(BigDecimal("50"), a.porcentajeCondominio)
        assertEquals(BigDecimal("40"), b.porcentajeCondominio)
        // a condómino with no part declared is a 400 too
        assertThrows<ValidationException> { condominioCon(titular("A", porcentaje = null), otro, seUne = false) }
    }

    @Test
    fun `a contribuyente declares a predio, year and secuencia once`() {
        val e = assertThrows<ValidationException> { condominioCon(titular("A", porcentaje = "10"), listOf(titular("A", porcentaje = "100")), seUne = true) }
        assertEquals("contribuyente", e.violations.single().field)
    }

    @Test
    fun `a new condomino takes what the declaration says of the predio, not of its titular`() {
        val origen =
            titular("A", porcentaje = "100", deduccion = "1000").copy(
                id = "d1",
                numeroDeclaracion = 7,
                uso = "COMERCIAL",
                areaTerreno = BigDecimal("200"),
                areaConstruida = BigDecimal("150"),
                claseUso = "RESIDENCIAL",
                inhabitableTipoDocumento = "RESOLUCION",
                tipoAdquisicion = "COMPRA",
                documentosSustento = "MINUTA",
                folios = 2,
                condicionEspecial = "PENSIONISTA",
                fechaPresentacion = LocalDate.of(2025, 1, 2),
                otrosDatos = "LINDA CON EL RIO"
            )
        val nuevo = delPredio(origen)
        assertEquals(
            Declaracion(),
            nuevo.copy(
                predio = null,
                anio = null,
                secuenciaUso = null,
                valorAutoavaluo = null,
                uso = null,
                areaTerreno = null,
                areaConstruida = null,
                claseUso = null,
                inhabitableTipoDocumento = null
            )
        )
        assertEquals("P", nuevo.predio)
        assertEquals(2026, nuevo.anio)
        assertEquals("1", nuevo.secuenciaUso)
        assertEquals(BigDecimal("10000.50"), nuevo.valorAutoavaluo)
        assertEquals("COMERCIAL", nuevo.uso)
        assertEquals(BigDecimal("150"), nuevo.areaConstruida)
        assertEquals("RESOLUCION", nuevo.inhabitableTipoDocumento)
    }

    private fun titular(
        contribuyente: String,
        autoavaluo: String? = "10000.50",
        porcentaje: String? = null,
        condicion: String? = null,
        deduccion: String? = null
    ) = Declaracion(
        contribuyente = contribuyente,
        predio = "P",
        anio = 2026,
        secuenciaUso = "1",
        condicionPropiedad = condicion,
        porcentajeCondominio = porcentaje?.let(::BigDecimal),
        valorAutoavaluo = autoavaluo?.let(::BigDecimal),
        deduccion = deduccion?.let(::BigDecimal)
    )
}
