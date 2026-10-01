package srtm.emision

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

// the masiva's settings (application.yml, README "Emisión masiva"). AlmacenLocal reads `dir` on its own: it is the
// only class that knows where the local almacén is
@ConfigurationProperties("srtm.emision")
data class EmisionProperties(
    // which AlmacenEmision: local (the server's disk) or s3
    val almacen: String = "local",
    val dir: String = "./data/emisiones",
    // this instance's work files: local and disposable
    val temporales: String = System.getProperty("java.io.tmpdir") + "/srtm-emision",
    // contribuyentes per lote
    val lote: Int = 100,
    // workers of this instance: auto (by cores and memory) or a number; 0 runs none
    val trabajadores: String = "auto",
    // how long a worker with nothing to do waits before looking again (a local POST wakes it before)
    val espera: Duration = Duration.ofSeconds(5),
    // how long a lote or an assembly whose instance stopped beating is kept before another one takes it
    val lease: Duration = Duration.ofMinutes(2),
    // takes of a lote before it is FALLIDO
    val intentos: Int = 3,
    // the retention: the last n files of each year, none older than the days (0 turns a rule off)
    val conservar: Int = 5,
    val dias: Int = 0,
    val s3: S3 = S3()
) {
    init {
        require(lote >= 1) { "srtm.emision.lote debe ser al menos 1: $lote" }
        require(intentos >= 1) { "srtm.emision.intentos debe ser al menos 1: $intentos" }
        require(!lease.isNegative && !lease.isZero) { "srtm.emision.lease debe ser positivo: $lease" }
        require(!espera.isNegative && !espera.isZero) { "srtm.emision.espera debe ser positiva: $espera" }
    }

    // the object store of srtm.emision.almacen=s3 (AlmacenS3), shared by every instance: required then
    data class S3(
        val bucket: String = "",
        // none: the sdk's chain (AWS_REGION), or us-east-1 against another endpoint
        val region: String = "",
        // another endpoint than aws' (minio, say), reached path-style; none: aws'
        val endpoint: String? = null,
        // the keys go under it, if any
        val prefijo: String = ""
    )
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmisionProperties::class)
class EmisionConfig
