package srtm.rentas

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException
import wasichai.core.data.RecordService
import wasichai.core.metadata.MetadataService
import java.util.UUID

// the code of a predio registered in the portal, against an in-memory registry: codigo and numero_registro are
// unique there as in the database, and a rival clerk can take a code between our read and our insert
class CodigoPredioTest {
    private val registros = RegistrosEnMemoria()
    private val service = DeclaracionService(registros, Listas(registros), ContribuyenteService(registros, Listas(registros)))

    private fun registrar(predio: Predio) = runBlocking { service.registrarPredio(predio.copy(direccion = "S/N")) }

    @Test
    fun `a code taken meanwhile is computed again on the retry`() {
        registros.rival = Predio(codigo = "01-02-0001", numeroRegistro = 1)
        val predio = registrar(Predio(sectorCatastral = "1", manzanaCatastral = "2"))
        assertEquals("01-02-0002", predio.codigo)
        assertEquals(listOf("01-02-0001", "01-02-0002"), registros.intentos)
    }

    @Test
    fun `without sector or manzana a predio takes the next code of the portal's own series`() {
        registros.predios += Predio(codigo = "01-01-0009", numeroRegistro = 1)
        assertEquals("P-000001", registrar(Predio()).codigo)
        assertEquals("P-000002", registrar(Predio()).codigo)
    }

    @Test
    fun `a sector without its manzana, or blanks, are no sector-manzana code`() {
        assertEquals("P-000001", registrar(Predio(sectorCatastral = "01")).codigo)
        assertEquals("P-000002", registrar(Predio(manzanaCatastral = "02")).codigo)
        assertEquals("P-000003", registrar(Predio(sectorCatastral = " ", manzanaCatastral = "")).codigo)
    }

    @Test
    fun `the lote's municipal code, when no predio has it yet`() {
        registros.lotes += CatastroFiscal(codigoCpu = "54102166-0001-2", codigoPredioMunicipal = "5243")
        assertEquals("5243", registrar(Predio(codigoCpu = "54102166-0001-2")).codigo)
        // the same lote again: its code is taken
        assertEquals("P-000001", registrar(Predio(codigoCpu = "54102166-0001-2")).codigo)
    }

    @Test
    fun `a lote without a municipal code, or a CPU with no lote, falls to the own series`() {
        registros.lotes += CatastroFiscal(codigoCpu = "54102167-0001-7", codigoPredioMunicipal = " ")
        assertEquals("P-000001", registrar(Predio(codigoCpu = "54102167-0001-7")).codigo)
        assertEquals("P-000002", registrar(Predio(codigoCpu = "NO-EXISTE")).codigo)
    }

    @Test
    fun `sector and manzana come before the lote's code`() {
        registros.lotes += CatastroFiscal(codigoCpu = "54102166-0001-2", codigoPredioMunicipal = "5243")
        assertEquals("01-02-0001", registrar(Predio(sectorCatastral = "01", manzanaCatastral = "02", codigoCpu = "54102166-0001-2")).codigo)
    }

    @Test
    fun `a lote code taken meanwhile falls to the own series on the retry`() {
        registros.lotes += CatastroFiscal(codigoCpu = "54102166-0001-2", codigoPredioMunicipal = "5243")
        registros.rival = Predio(codigo = "5243", numeroRegistro = 1)
        assertEquals("P-000001", registrar(Predio(codigoCpu = "54102166-0001-2")).codigo)
        assertEquals(listOf("5243", "P-000001"), registros.intentos)
    }

    private class RegistrosEnMemoria : Registros(Mockito.mock(RecordService::class.java), Mockito.mock(MetadataService::class.java)) {
        val predios = mutableListOf<Predio>()
        val lotes = mutableListOf<CatastroFiscal>()

        // a predio another clerk registers between our read and our insert, once
        var rival: Predio? = null

        // the codes each insert tried
        val intentos = mutableListOf<String?>()

        override suspend fun highest(
            objectName: String,
            field: String,
            prefix: String?
        ): String? =
            when (field) {
                "codigo" -> predios.mapNotNull { it.codigo }.filter { it.startsWith(prefix.orEmpty()) }.maxOrNull()
                "numero_registro" -> predios.mapNotNull { it.numeroRegistro }.maxOrNull()?.toString()
                else -> error("highest $objectName.$field")
            }

        override suspend fun <T : Any> create(
            objectName: String,
            type: Class<T>,
            attributes: Map<String, Any?>
        ): T {
            check(objectName == PREDIO)
            rival?.let {
                predios += it
                rival = null
            }
            val predio = Records.read(Predio::class.java, UUID.randomUUID().toString(), attributes)
            intentos += predio.codigo
            if (predios.any { it.codigo == predio.codigo || it.numeroRegistro == predio.numeroRegistro }) {
                throw DataIntegrityViolationException("duplicate key value violates unique constraint")
            }
            predios += predio
            return Records.read(type, predio.id!!, attributes)
        }

        override suspend fun <T : Any> all(
            objectName: String,
            type: Class<T>,
            filters: Map<String, String>,
            sort: String?,
            descending: Boolean
        ): List<T> {
            val rows: List<Any> =
                when (objectName) {
                    PREDIO -> predios
                    CATASTRO_FISCAL -> lotes
                    else -> error("all $objectName")
                }
            return rows.filter { row -> filters.all { (field, value) -> Records.attributes(row)[field]?.toString() == value } }.map(type::cast)
        }
    }
}
