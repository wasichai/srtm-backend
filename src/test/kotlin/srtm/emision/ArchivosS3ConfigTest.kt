package srtm.emision

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import wasichai.files.FileStore

// which store srtm.archivos.almacen makes for wasichai-files. no bucket is reached here: ArchivosS3Test starts the
// context against minio
class ArchivosS3ConfigTest {
    private val contexto = ApplicationContextRunner().withUserConfiguration(ArchivosConfig::class.java)

    @Test
    fun `with almacen s3 and no bucket the app does not start and says why`() {
        contexto.withPropertyValues("srtm.archivos.almacen=s3").run {
            val causas = generateSequence(it.startupFailure) { e -> e.cause }.map { e -> e.message }.toList()
            assertTrue("srtm.archivos.s3.bucket es obligatorio con srtm.archivos.almacen=s3" in causas, "$causas")
        }
    }

    @Test
    fun `by default srtm makes no store, the module's local one stays`() {
        contexto.run {
            assertTrue(it.getBeansOfType(FileStore::class.java).isEmpty())
        }
    }
}
