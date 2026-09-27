package srtm.pide

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// PIDE RENIEC when the convenio's credentials are configured, no consulta otherwise (the clerk types the names)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PideReniecProperties::class)
class PideConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun consultaDocumento(properties: PideReniecProperties): ConsultaDocumento {
        if (!properties.enabled) return SinConsulta()
        if (!properties.completa) {
            log.warn("srtm.pide.reniec.enabled sin url, usuario, RUC o password: no se consulta RENIEC")
            return SinConsulta()
        }
        return PideReniec(properties)
    }

    @Bean
    fun consultasReniec(properties: PideReniecProperties) = ConsultasReniec(properties.vigencia)
}
