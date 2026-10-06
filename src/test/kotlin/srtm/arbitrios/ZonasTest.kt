package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.impuesto.ParametroTributario
import srtm.rentas.Predio
import java.time.LocalDate

// Zonas.de: the zona a servicio reads for a predio that day. zona_arbitrio_ubicacion (by via, tramo and manzana)
// is tried first, servicio-specific before general, manzana-specific before general; ARBITRIO_ZONA (by sector
// catastral) is the fallback, also servicio-specific before general. several rows of the same level agreeing on
// the zona win it; disagreeing ones are Ambigua
class ZonasTest {
    private val dia = LocalDate.of(2026, 3, 15)
    private val servicio = "LIMPIEZA"
    private val predio = Predio(id = "p", via = "AV. X", numero = "245", manzana = "M1", sectorCatastral = "S-01")

    private fun ubicacion(
        servicio: String? = null,
        via: String = "AV. X",
        tipoVia: String? = null,
        manzana: String? = null,
        ini: Int? = null,
        fin: Int? = null,
        paridad: String? = null,
        zona: String,
        desde: LocalDate = LocalDate.of(2026, 1, 1),
        hasta: LocalDate? = null
    ) = ZonaUbicacion(
        id = "u",
        servicio = servicio,
        tipoVia = tipoVia,
        via = via,
        manzana = manzana,
        numeroInicial = ini,
        numeroFinal = fin,
        paridad = paridad,
        zona = zona,
        vigenciaDesde = desde,
        vigenciaHasta = hasta,
        norma = "Ordenanza ficticia de prueba"
    )

    private fun param(
        tipo: String,
        clave: String,
        texto: String,
        desde: LocalDate = LocalDate.of(2026, 1, 1)
    ) = ParametroTributario(id = "p-$tipo-$clave", tipo = tipo, clave = clave, vigenciaDesde = desde, texto = texto, norma = "Ordenanza ficticia de prueba")

    @Test
    fun `numeroDe reads the leading digits, S-N has none`() {
        assertEquals(245, Zonas.numeroDe("245"))
        assertEquals(245, Zonas.numeroDe("245-A"))
        assertNull(Zonas.numeroDe("S/N"))
        assertNull(Zonas.numeroDe(null))
    }

    @Test
    fun `without any row, ninguna`() {
        assertEquals(ZonaResuelta.Ninguna, Zonas.de(emptyList(), emptyList(), servicio, predio, dia))
    }

    @Test
    fun `without an ubicacion, the sector catastral's ARBITRIO_ZONA wins, servicio-specific over general`() {
        val general = listOf(param(Llaves.ARBITRIO_ZONA, "S-01", texto = "Z6"))
        assertEquals(ZonaResuelta.Es("Z6", "ARBITRIO_ZONA:S-01"), Zonas.de(emptyList(), general, servicio, predio, dia))

        val porServicio = general + param(Llaves.ARBITRIO_ZONA, "LIMPIEZA:S-01", texto = "Z5")
        assertEquals(ZonaResuelta.Es("Z5", "ARBITRIO_ZONA:LIMPIEZA:S-01"), Zonas.de(emptyList(), porServicio, servicio, predio, dia))
    }

    @Test
    fun `the 6 levels, each more precise one winning over the next`() {
        val sector = listOf(param(Llaves.ARBITRIO_ZONA, "S-01", texto = "Z6"))
        assertEquals("Z6", (Zonas.de(emptyList(), sector, servicio, predio, dia) as ZonaResuelta.Es).zona)

        val conServicioSector = sector + param(Llaves.ARBITRIO_ZONA, "LIMPIEZA:S-01", texto = "Z5")
        assertEquals("Z5", (Zonas.de(emptyList(), conServicioSector, servicio, predio, dia) as ZonaResuelta.Es).zona)

        val sinServicioSinManzana = listOf(ubicacion(zona = "Z4"))
        assertEquals("Z4", (Zonas.de(sinServicioSinManzana, conServicioSector, servicio, predio, dia) as ZonaResuelta.Es).zona)

        val sinServicioConManzana = sinServicioSinManzana + ubicacion(manzana = "M1", zona = "Z3")
        assertEquals("Z3", (Zonas.de(sinServicioConManzana, conServicioSector, servicio, predio, dia) as ZonaResuelta.Es).zona)

        val conServicioSinManzana = sinServicioConManzana + ubicacion(servicio = "LIMPIEZA", zona = "Z2")
        assertEquals("Z2", (Zonas.de(conServicioSinManzana, conServicioSector, servicio, predio, dia) as ZonaResuelta.Es).zona)

        val conServicioConManzana = conServicioSinManzana + ubicacion(servicio = "LIMPIEZA", manzana = "M1", zona = "Z1")
        assertEquals("Z1", (Zonas.de(conServicioConManzana, conServicioSector, servicio, predio, dia) as ZonaResuelta.Es).zona)
    }

    @Test
    fun `the row of the matching paridad wins when the ranges overlap`() {
        val ubicaciones =
            listOf(
                ubicacion(ini = 200, fin = 298, paridad = "PAR", zona = "Z2"),
                ubicacion(ini = 201, fin = 299, paridad = "IMPAR", zona = "Z3")
            )
        assertEquals(
            ZonaResuelta.Es("Z3", "ZONA_ARBITRIO_UBICACION:||AV. X||201|299|IMPAR|2026-01-01"),
            Zonas.de(ubicaciones, emptyList(), servicio, predio, dia)
        )
    }

    @Test
    fun `245-A matches the same tramo as 245`() {
        val conLetra = predio.copy(numero = "245-A")
        val ubicaciones = listOf(ubicacion(ini = 201, fin = 299, paridad = "IMPAR", zona = "Z3"))
        assertEquals("Z3", (Zonas.de(ubicaciones, emptyList(), servicio, conLetra, dia) as ZonaResuelta.Es).zona)
    }

    @Test
    fun `S-N falls through to the sector, no ubicacion matches a numero range`() {
        val sinNumero = predio.copy(numero = "S/N")
        val ubicaciones = listOf(ubicacion(ini = 200, fin = 298, paridad = "PAR", zona = "Z2"))
        val sector = listOf(param(Llaves.ARBITRIO_ZONA, "S-01", texto = "Z6"))
        assertEquals(ZonaResuelta.Es("Z6", "ARBITRIO_ZONA:S-01"), Zonas.de(ubicaciones, sector, servicio, sinNumero, dia))
    }

    @Test
    fun `via is compared trimmed, case and accent insensitive`() {
        val conTilde = predio.copy(via = "AVENIDA PERU")
        val ubicaciones = listOf(ubicacion(via = " Avenida Perú ", zona = "Z4"))
        assertEquals("Z4", (Zonas.de(ubicaciones, emptyList(), servicio, conTilde, dia) as ZonaResuelta.Es).zona)
    }

    @Test
    fun `a different via does not match`() {
        val ubicaciones = listOf(ubicacion(via = "JR. OTRA", zona = "Z4"))
        assertEquals(ZonaResuelta.Ninguna, Zonas.de(ubicaciones, emptyList(), servicio, predio, dia))
    }

    @Test
    fun `a row of 2027 does not rule in 2026`() {
        val futura = listOf(ubicacion(zona = "Z4", desde = LocalDate.of(2027, 1, 1)))
        val sector = listOf(param(Llaves.ARBITRIO_ZONA, "S-01", texto = "Z6"))
        assertEquals(ZonaResuelta.Es("Z6", "ARBITRIO_ZONA:S-01"), Zonas.de(futura, sector, servicio, predio, dia))
    }

    @Test
    fun `two rows of the same level agreeing on the zona win it`() {
        val ubicaciones =
            listOf(
                ubicacion(zona = "Z4", desde = LocalDate.of(2026, 1, 1)),
                ubicacion(zona = "Z4", desde = LocalDate.of(2026, 2, 1))
            )
        assertEquals("Z4", (Zonas.de(ubicaciones, emptyList(), servicio, predio, dia) as ZonaResuelta.Es).zona)
    }

    @Test
    fun `two rows of the same level disagreeing on the zona are ambigua`() {
        val ubicaciones =
            listOf(
                ubicacion(zona = "Z4"),
                ubicacion(zona = "Z4-bis", desde = LocalDate.of(2026, 2, 1))
            )
        assertEquals(ZonaResuelta.Ambigua(listOf("Z4", "Z4-bis")), Zonas.de(ubicaciones, emptyList(), servicio, predio, dia))
    }

    @Test
    fun `tipoVia narrows the match only when the row sets it`() {
        val conTipo = predio.copy(tipoVia = "AV")
        val sinTipo = listOf(ubicacion(zona = "Z4"))
        assertEquals("Z4", (Zonas.de(sinTipo, emptyList(), servicio, conTipo, dia) as ZonaResuelta.Es).zona)

        val otroTipo = listOf(ubicacion(tipoVia = "JR", zona = "Z4"))
        assertEquals(ZonaResuelta.Ninguna, Zonas.de(otroTipo, emptyList(), servicio, conTipo, dia))

        val mismoTipo = listOf(ubicacion(tipoVia = "AV", zona = "Z4"))
        assertEquals("Z4", (Zonas.de(mismoTipo, emptyList(), servicio, conTipo, dia) as ZonaResuelta.Es).zona)
    }

    // the contexto's index (#82): the rows grouped by their normalized via once, and a predio's narrowed once per
    // determination to those of its via, tipo_via, tramo and paridad. what Zonas.de decides from them is the same
    @Test
    fun `a predio's rows are those of its via, tipo_via, tramo and paridad - case and accent insensitive`() {
        val suyas =
            listOf(
                ubicacion(via = " Avenida Perú ", tipoVia = "Av", ini = 201, fin = 299, paridad = "IMPAR", zona = "Z3"),
                ubicacion(via = "AVENIDA PERU", zona = "Z4", desde = LocalDate.of(2027, 1, 1)) // a day decides its vigencia
            )
        val ajenas =
            listOf(
                ubicacion(via = "AVENIDA PERUANA", zona = "Z5"),
                ubicacion(via = "avenida perú", tipoVia = "JR", zona = "Z5"),
                ubicacion(via = "avenida perú", ini = 300, fin = 399, zona = "Z5"),
                ubicacion(via = "avenida perú", paridad = "PAR", zona = "Z5")
            )
        val porVia = UbicacionesPorVia(ajenas + suyas)
        val enPeru = predio.copy(via = "avenida peru", tipoVia = "AV", numero = "245")
        val delPredio = porVia.delPredio(enPeru)
        assertEquals(suyas, delPredio.filas)
        val esperada = ZonaResuelta.Es("Z3", "ZONA_ARBITRIO_UBICACION:|Av|Avenida Perú||201|299|IMPAR|2026-01-01")
        assertEquals(esperada, Zonas.de(delPredio, emptyList(), servicio, enPeru, dia))
        assertEquals(Zonas.de(ajenas + suyas, emptyList(), servicio, enPeru, dia), Zonas.de(delPredio, emptyList(), servicio, enPeru, dia))
        // the contexto builds it once, lazily, and keeps it
        val c = ContextoArbitrios(2026, null, emptyList(), emptyList(), emptyList(), ajenas + suyas)
        assertEquals(suyas, c.porVia.delPredio(enPeru).filas)
        assertTrue(c.porVia === c.porVia)
    }

    // the same normalization as the via's (import_zonas_arbitrio.py checks overlaps with it too)
    @Test
    fun `tipoVia is compared trimmed, case and accent insensitive, like the via`() {
        val jiron = predio.copy(tipoVia = "JIRON")
        val conTilde = listOf(ubicacion(tipoVia = " Jirón ", zona = "Z4"))
        assertEquals("Z4", (Zonas.de(conTilde, emptyList(), servicio, jiron, dia) as ZonaResuelta.Es).zona)

        val av = predio.copy(tipoVia = "Av")
        val mayusculas = listOf(ubicacion(tipoVia = "AV", zona = "Z4"))
        assertEquals("Z4", (Zonas.de(mayusculas, emptyList(), servicio, av, dia) as ZonaResuelta.Es).zona)
    }
}
