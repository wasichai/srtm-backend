package srtm.caja

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import srtm.Permisos

// #165: caja's buzón, POST {url}/pagos with url = https://<srtm>/api/srtm/caja. caja signs in as the organization's
// service account caja (role CAJA, docs/develop/alta-organizacion.md): the organization is its token's. the body is
// read as it came, and the answer is exact (PagosService): caja reads a_cuenta and motivo from a 202 and a 409.
// #166 its conciliación of a day (GET {url}/pagos/conciliacion?fecha=) and #168 its question of whether an orden still
// stands (POST {url}/ordenes/vigencia): both read, and a non-200 is for caja «no contesta»
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

    @GetMapping("/pagos/conciliacion")
    suspend fun conciliar(
        @RequestParam(required = false) fecha: String?
    ): ResponseEntity<String> {
        permisos.exigirLeer(ObjetosCaja.PAGO_CAJA, "conciliar los pagos de caja")
        val r = pagos.conciliar(Conciliacion.fecha(fecha))
        return ResponseEntity.status(r.estado).contentType(MediaType.APPLICATION_JSON).body(r.cuerpo)
    }

    @PostMapping("/ordenes/vigencia")
    suspend fun vigencia(
        @RequestBody(required = false) cuerpo: String?
    ): ResponseEntity<String> {
        permisos.exigirLeer(ObjetosCaja.ORDEN_CAJA, "preguntar la vigencia de las órdenes de caja")
        val r = pagos.vigencia(cuerpo)
        return ResponseEntity.status(r.estado).contentType(MediaType.APPLICATION_JSON).body(r.cuerpo)
    }
}
