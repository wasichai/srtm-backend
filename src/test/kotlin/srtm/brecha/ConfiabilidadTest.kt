package srtm.brecha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import srtm.rentas.Contribuyente
import srtm.rentas.Predio

// #204: how sure a row's cruce is: the ficha's levantamiento, the vínculo catastro made, and the titulares of the
// ficha against the DJ's. every documento and nombre is fictitious
class ConfiabilidadTest {
    private val predio = Predio(id = "p1", codigo = "01-01-0001")

    private fun item(
        metodo: String? = null,
        revisado: Boolean? = null,
        estado: String? = null,
        entrega: String? = null
    ) = ValuacionCatastral(
        codigoMunicipal = "01-01-0001",
        estadoLevantamiento = estado,
        entrega = entrega,
        vinculoMetodo = metodo,
        vinculoRevisado = revisado
    )

    private fun ficha(
        documento: String? = null,
        nombre: String? = null
    ) = TitularCatastral("01-01-0001", "DNI", documento, nombre, null, "PROPIETARIO")

    private val dj = Contribuyente(id = "a", tipoDocumento = "DNI", numeroDocumento = "40000001", nombreCompleto = "JUAN PEREZ GOMEZ")

    @Test
    fun `a documento without leading zeros nor separators, a padded one is none`() {
        assertEquals("12345678", Confiabilidad.documento("00012345678"))
        assertEquals("40000001", Confiabilidad.documento(" 4000-0001 "))
        assertEquals("20123456781", Confiabilidad.documento("20123456781"))
        assertNull(Confiabilidad.documento("000000000"))
        assertNull(Confiabilidad.documento("  "))
        assertNull(Confiabilidad.documento(null))
    }

    @Test
    fun `a nombre with its words sorted, without accents nor case`() {
        assertEquals("GOMEZ JUAN PEREZ", Confiabilidad.nombre("Pérez Gómez, Juan"))
        assertEquals(Confiabilidad.nombre("JUAN PEREZ GOMEZ"), Confiabilidad.nombre("pérez  gómez juan"))
        assertEquals("ANA NUNEZ", Confiabilidad.nombre("Núñez, Ana"))
        assertNull(Confiabilidad.nombre(" , "))
        assertNull(Confiabilidad.nombre(null))
    }

    @Test
    fun `a ficha sin levantar is SIN_LEVANTAR whatever its vinculo`() {
        assertEquals(
            ConfianzaDeFila(Confianza.SIN_LEVANTAR, "Ficha sin levantar (entrega E-2026-01)"),
            Confiabilidad.de(item(CODIGO_RENTAS, estado = SIN_LEVANTAR, entrega = "E-2026-01"), predio, listOf(ficha("40000001")), listOf(dj))
        )
        assertEquals(
            ConfianzaDeFila(Confianza.SIN_LEVANTAR, "Ficha sin levantar"),
            Confiabilidad.de(item(estado = SIN_LEVANTAR), null, emptyList(), emptyList())
        )
    }

    @Test
    fun `a vinculo by codigo de rentas, or manual and revisado, is CONFIABLE`() {
        assertEquals(
            ConfianzaDeFila(Confianza.CONFIABLE, "Vínculo por código de rentas"),
            Confiabilidad.de(item(CODIGO_RENTAS, estado = "COMPLETO"), predio, emptyList(), emptyList())
        )
        assertEquals(
            ConfianzaDeFila(Confianza.CONFIABLE, "Vínculo manual revisado"),
            Confiabilidad.de(item(MANUAL, revisado = true), predio, emptyList(), emptyList())
        )
    }

    @Test
    fun `a manual vinculo not revisado, or a geometric one, without a titular in common is DUDOSO`() {
        assertEquals(
            ConfianzaDeFila(Confianza.DUDOSO, "Vínculo MANUAL, sin titular en común"),
            Confiabilidad.de(item(MANUAL, revisado = false), predio, listOf(ficha("40000002", "MARIA LOPEZ")), listOf(dj))
        )
        assertEquals(
            ConfianzaDeFila(Confianza.DUDOSO, "Vínculo GEOMETRICO, sin titular en común"),
            Confiabilidad.de(item("GEOMETRICO"), predio, emptyList(), listOf(dj))
        )
        assertEquals(
            ConfianzaDeFila(Confianza.DUDOSO, "Vínculo sin método, sin titular en común"),
            Confiabilidad.de(item(), predio, emptyList(), emptyList())
        )
    }

    @Test
    fun `a titular in common by documento or by nombre is CONFIABLE`() {
        assertEquals(
            ConfianzaDeFila(Confianza.CONFIABLE, "Un titular de la ficha coincide por documento"),
            Confiabilidad.de(item("GEOMETRICO"), predio, listOf(ficha("00040000001", "OTRO NOMBRE")), listOf(dj))
        )
        assertEquals(
            ConfianzaDeFila(Confianza.CONFIABLE, "Un titular de la ficha coincide por nombre"),
            Confiabilidad.de(item("GEOMETRICO"), predio, listOf(ficha(null, "PEREZ GOMEZ JUAN")), listOf(dj))
        )
        // the DJ's nombre as nombreDe reads it: its apellidos and nombres when there is no nombre completo
        val porPartes = Contribuyente(id = "b", apellidoPaterno = "Pérez", apellidoMaterno = "Gómez", nombres = "Juan")
        assertEquals(
            Confianza.CONFIABLE,
            Confiabilidad.de(item("GEOMETRICO"), predio, listOf(ficha("000000000", "PEREZ GOMEZ, JUAN")), listOf(porPartes)).confianza
        )
        // two padded documentos are no match
        val sinDocumento = Contribuyente(id = "c", numeroDocumento = "00000000", nombreCompleto = "ANA RUIZ")
        assertEquals(
            Confianza.DUDOSO,
            Confiabilidad.de(item("GEOMETRICO"), predio, listOf(ficha("000000000", "LUIS DIAZ")), listOf(sinDocumento)).confianza
        )
    }

    @Test
    fun `without a predio no vinculo makes it CONFIABLE`() {
        assertEquals(
            ConfianzaDeFila(Confianza.DUDOSO, "Sin predio en el padrón"),
            Confiabilidad.de(item(CODIGO_RENTAS), null, listOf(ficha("40000001")), listOf(dj))
        )
        assertEquals(
            ConfianzaDeFila(Confianza.DUDOSO, "Sin ficha del catastro"),
            Confiabilidad.de(null, predio, emptyList(), listOf(dj))
        )
    }
}
