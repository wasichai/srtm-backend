package srtm.pide

import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import srtm.rentas.errorDocumento
import wasichai.core.common.NotFoundException

// what the portal asks leaving the "N° documento" of a contribuyente, a relacionado or a transferente (pages 3, 8
// and 15). under /api like the rest of the portal's api: core's jwt chain protects it
@RestController
@RequestMapping("/api/srtm/documentos")
class DocumentosController(
    private val documentos: DocumentoService
) {
    // 200 with RENIEC's names, 404 when there are none (or no convenio): the clerk types them
    @GetMapping("/{tipo}/{numero}")
    suspend fun consultar(
        @PathVariable tipo: String,
        @PathVariable numero: String
    ) = documentos.consultar(tipo, numero)
}

@Service
class DocumentoService(
    private val consulta: ConsultaDocumento,
    private val consultas: ConsultasReniec
) {
    // only a DNI of eight digits goes out: every consulta costs the municipality. a found one is remembered, so a
    // save with fuente PIDE RENIEC can be checked against it
    suspend fun consultar(
        tipo: String,
        numero: String
    ): DatosPersona {
        val dni = numero.trim()
        if (tipo != DNI || errorDocumento(tipo, dni) != null) throw NotFoundException("Solo se consulta un DNI de 8 dígitos")
        val datos =
            consulta.consultar(tipo, dni)?.copy(tipoDocumento = DNI, numeroDocumento = dni, fuenteInformacion = PIDE_RENIEC)
                ?: throw NotFoundException("RENIEC no devolvió datos del DNI: ingrese los datos a mano")
        consultas.registrar(datos)
        return datos
    }
}
