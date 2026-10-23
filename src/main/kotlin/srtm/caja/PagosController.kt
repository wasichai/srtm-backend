package srtm.caja

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import srtm.Permisos

// #165: caja's buzón, POST {url}/pagos with url = https://<srtm>/api/srtm/caja. caja signs in as the organization's
// service account caja (role CAJA, docs/develop/alta-organizacion.md): the organization is its token's. the body is
// read as it came, and the answer is exact (PagosService): caja reads a_cuenta and motivo from a 202 and a 409
@RestController
@RequestMapping("/api/srtm/caja")
class PagosController(
    private val pagos: PagosService,
    private val permisos: Permisos
) {
    @PostMapping("/pagos")
    suspend fun recibir(
        @RequestBody(required = false) cuerpo: String?
    ): ResponseEntity<String> {
        permisos.exigirCrear(ObjetosCaja.PAGO_CAJA, "registrar un pago de caja")
        val r = pagos.recibir(cuerpo)
        return ResponseEntity.status(r.estado).contentType(MediaType.APPLICATION_JSON).body(r.cuerpo)
    }
}
