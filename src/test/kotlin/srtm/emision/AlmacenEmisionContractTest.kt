package srtm.emision

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

// what every AlmacenEmision must do, whatever it keeps the files in: a new implementation extends this and gives its
// empty store in `nuevo`
abstract class AlmacenEmisionContractTest {
    // where the tests keep the files they hand to the store and the ones they bring back
    @TempDir
    lateinit var local: Path

    // an empty store: one per test
    protected abstract fun nuevo(): AlmacenEmision

    private fun archivo(
        contenido: ByteArray,
        nombre: String = "entrada.bin"
    ): Path = Files.write(local.resolve(nombre), contenido)

    @Test
    fun `a saved file opens, copies and measures as it was saved`() =
        runBlocking<Unit> {
            val almacen = nuevo()
            val clave = "emision-a/emision-2026-a.pdf"
            val bytes = Random(7).nextBytes(300_000)

            almacen.guardar(clave, archivo(bytes))

            assertTrue(almacen.existe(clave))
            assertArrayEquals(bytes, almacen.abrir(clave).inputStream.use { it.readAllBytes() })
            val copia = local.resolve("copia.bin")
            almacen.traer(clave, copia)
            assertArrayEquals(bytes, Files.readAllBytes(copia))
            assertEquals(bytes.size.toLong(), almacen.tamano(clave))
            assertEquals(bytes.size.toLong(), almacen.abrir(clave).contentLength())
        }

    @Test
    fun `saving consumes the local file`() =
        runBlocking<Unit> {
            val almacen = nuevo()
            val entrada = archivo("pdf".toByteArray())

            almacen.guardar("emision-a/x.pdf", entrada)

            assertFalse(Files.exists(entrada))
            assertTrue(almacen.existe("emision-a/x.pdf"))
        }

    @Test
    fun `saving again replaces the content`() =
        runBlocking<Unit> {
            val almacen = nuevo()
            val clave = "emision-a/x.pdf"
            almacen.guardar(clave, archivo("primero".toByteArray(), "uno.bin"))

            almacen.guardar(clave, archivo("segundo, y más largo".toByteArray(), "dos.bin"))

            assertEquals("segundo, y más largo", almacen.abrir(clave).inputStream.use { String(it.readAllBytes()) })
            assertEquals("segundo, y más largo".toByteArray().size.toLong(), almacen.tamano(clave))
        }

    @Test
    fun `copying replaces a local file that is already there`() =
        runBlocking<Unit> {
            val almacen = nuevo()
            almacen.guardar("emision-a/x.pdf", archivo("nuevo".toByteArray()))
            val destino = Files.writeString(local.resolve("viejo.bin"), "contenido anterior, más largo")

            almacen.traer("emision-a/x.pdf", destino)

            assertEquals("nuevo", Files.readString(destino))
        }

    @Test
    fun `a deleted key no longer exists and deleting it again is fine`() =
        runBlocking<Unit> {
            val almacen = nuevo()
            val clave = "emision-a/x.pdf"
            almacen.guardar(clave, archivo("pdf".toByteArray()))

            almacen.borrar(clave)

            assertFalse(almacen.existe(clave))
            almacen.borrar(clave)
            almacen.borrar("emision-a/nunca-estuvo.pdf")
        }

    @Test
    fun `a missing key does not exist and cannot be opened, copied or measured`() =
        runBlocking<Unit> {
            val almacen = nuevo()
            val clave = "emision-a/falta.pdf"
            val destino = local.resolve("destino.bin")

            assertFalse(almacen.existe(clave))
            assertThrows(ClaveInexistenteException::class.java) { runBlocking { almacen.abrir(clave) } }
            assertThrows(ClaveInexistenteException::class.java) { runBlocking { almacen.traer(clave, destino) } }
            assertThrows(ClaveInexistenteException::class.java) { runBlocking { almacen.tamano(clave) } }
            assertFalse(Files.exists(destino))
        }

    @Test
    fun `listing a prefix gives its keys in order and nothing else`() =
        runBlocking<Unit> {
            val almacen = nuevo()
            almacen.guardar("emision-a/parte-00002.pdf", archivo("2".toByteArray(), "dos.bin"))
            almacen.guardar("emision-a/parte-00001.pdf", archivo("1".toByteArray(), "uno.bin"))
            almacen.guardar("emision-b/x.pdf", archivo("x".toByteArray(), "x.bin"))

            assertEquals(listOf("emision-a/parte-00001.pdf", "emision-a/parte-00002.pdf"), almacen.listar("emision-a/"))
            assertEquals(listOf("emision-b/x.pdf"), almacen.listar("emision-b/"))
            assertEquals(listOf("emision-a/parte-00002.pdf"), almacen.listar("emision-a/parte-00002"))
            assertEquals(emptyList<String>(), almacen.listar("emision-c/"))
        }

    @Test
    fun `keys that escape the store are refused`() =
        runBlocking<Unit> {
            val almacen = nuevo()
            val entrada = archivo("pdf".toByteArray())
            val destino = local.resolve("destino.bin")

            listOf("../x", "/x", "a/../../x", "", "a/./b", "a//b", "a/", "..", ".", "a\\b", "a b", "ñ").forEach { clave ->
                assertThrows(IllegalArgumentException::class.java, { runBlocking { almacen.guardar(clave, entrada) } }, "guardar '$clave'")
                assertThrows(IllegalArgumentException::class.java, { runBlocking { almacen.abrir(clave) } }, "abrir '$clave'")
                assertThrows(IllegalArgumentException::class.java, { runBlocking { almacen.traer(clave, destino) } }, "traer '$clave'")
                assertThrows(IllegalArgumentException::class.java, { runBlocking { almacen.borrar(clave) } }, "borrar '$clave'")
                assertThrows(IllegalArgumentException::class.java, { runBlocking { almacen.existe(clave) } }, "existe '$clave'")
                assertThrows(IllegalArgumentException::class.java, { runBlocking { almacen.tamano(clave) } }, "tamano '$clave'")
            }
            listOf("../", "/", "", "a/../", "../x").forEach { prefijo ->
                assertThrows(IllegalArgumentException::class.java, { runBlocking { almacen.listar(prefijo) } }, "listar '$prefijo'")
            }
            // a refused save leaves the local file where it was
            assertTrue(Files.exists(entrada))
            assertFalse(Files.exists(destino))
        }
}
