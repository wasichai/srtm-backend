package srtm.rentas

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// the end-user portal's api. under /api like core's routes, so core's jwt chain protects it and
// its error handler answers problem+json. handlers are suspend: the caller's security context
// lives in the request's coroutine, which RecordService reads
@RestController
@RequestMapping("/api/srtm")
class RentasController(
    private val rentas: RentasService,
    private val contribuyentes: ContribuyenteService,
    private val declaraciones: DeclaracionService,
    private val predios: PredioService,
    private val catalogo: CatalogoService
) {
    @GetMapping("/resumen")
    suspend fun resumen() = rentas.resumen()

    // catalogs

    @GetMapping("/catalogos")
    suspend fun catalogos() = catalogo.opciones()

    @GetMapping("/ubigeos")
    suspend fun ubigeos() = catalogo.ubigeos()

    @GetMapping("/categorias-valor")
    suspend fun categoriasValor() = catalogo.categoriasValor()

    @GetMapping("/obras-categorias")
    suspend fun obrasCategorias(
        @RequestParam(name = "tipo_obra", required = false) tipoObra: String?
    ) = predios.obrasCategorias(tipoObra)

    // "buscar predios" (page 13): in the padrón (tributario) and in the catastro fiscal, same filters

    @GetMapping("/predios/buscar")
    suspend fun buscarPredios(
        @RequestParam params: Map<String, String>
    ) = predios.buscarPredios(FiltrosPredio.of(params), params["page"]?.toIntOrNull(), params["size"]?.toIntOrNull())

    @GetMapping("/catastro")
    suspend fun buscarCatastro(
        @RequestParam params: Map<String, String>
    ) = predios.buscarCatastro(FiltrosPredio.of(params), params["page"]?.toIntOrNull(), params["size"]?.toIntOrNull())

    @PostMapping("/catastro")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun crearLote(
        @RequestBody body: CatastroFiscal
    ) = predios.crearLote(body)

    @GetMapping("/catastro/{id}")
    suspend fun lote(
        @PathVariable id: UUID
    ) = predios.lote(id)

    @PutMapping("/catastro/{id}")
    suspend fun actualizarLote(
        @PathVariable id: UUID,
        @RequestBody body: CatastroFiscal
    ) = predios.actualizarLote(id, body)

    @GetMapping("/vias")
    suspend fun vias(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) tipo: String?,
        @RequestParam(required = false) ubigeo: String?,
        @RequestParam(required = false) size: Int?
    ) = catalogo.vias(q, tipo, ubigeo, size)

    @GetMapping("/unidades-urbanas")
    suspend fun unidadesUrbanas(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) tipo: String?,
        @RequestParam(required = false) ubigeo: String?,
        @RequestParam(required = false) size: Int?
    ) = catalogo.unidadesUrbanas(q, tipo, ubigeo, size)

    // contribuyentes

    @GetMapping("/contribuyentes")
    suspend fun contribuyentes(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ) = contribuyentes.buscar(q, page, size)

    @PostMapping("/contribuyentes")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun inscribir(
        @RequestBody body: Contribuyente
    ) = contribuyentes.inscribir(body)

    @GetMapping("/contribuyentes/{id}")
    suspend fun contribuyente(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?
    ) = rentas.fichaContribuyente(id, anio)

    @PutMapping("/contribuyentes/{id}")
    suspend fun actualizarContribuyente(
        @PathVariable id: UUID,
        @RequestBody body: Contribuyente
    ) = contribuyentes.actualizar(id, body)

    @GetMapping("/contribuyentes/{id}/declaraciones")
    suspend fun declaracionesDeContribuyente(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?
    ) = rentas.declaracionesDeContribuyente(id, anio)

    // the contribuyente's lists: listed and added under it, changed and removed by their own id

    @GetMapping("/contribuyentes/{id}/domicilios")
    suspend fun domicilios(
        @PathVariable id: UUID
    ) = contribuyentes.domicilios(id)

    @PostMapping("/contribuyentes/{id}/domicilios")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun agregarDomicilio(
        @PathVariable id: UUID,
        @RequestBody body: Domicilio
    ) = contribuyentes.agregarDomicilio(id, body)

    @PutMapping("/domicilios/{id}")
    suspend fun actualizarDomicilio(
        @PathVariable id: UUID,
        @RequestBody body: Domicilio
    ) = contribuyentes.actualizarDomicilio(id, body)

    @DeleteMapping("/domicilios/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun borrarDomicilio(
        @PathVariable id: UUID
    ) = contribuyentes.borrarDomicilio(id)

    @GetMapping("/contribuyentes/{id}/relacionados")
    suspend fun relacionados(
        @PathVariable id: UUID
    ) = contribuyentes.relacionados(id)

    @PostMapping("/contribuyentes/{id}/relacionados")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun agregarRelacionado(
        @PathVariable id: UUID,
        @RequestBody body: Relacionado
    ) = contribuyentes.agregarRelacionado(id, body)

    @PutMapping("/relacionados/{id}")
    suspend fun actualizarRelacionado(
        @PathVariable id: UUID,
        @RequestBody body: Relacionado
    ) = contribuyentes.actualizarRelacionado(id, body)

    @DeleteMapping("/relacionados/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun borrarRelacionado(
        @PathVariable id: UUID
    ) = contribuyentes.borrar(RELACIONADO, id)

    @GetMapping("/contribuyentes/{id}/medios-contacto")
    suspend fun mediosContacto(
        @PathVariable id: UUID
    ) = contribuyentes.mediosContacto(id)

    @PostMapping("/contribuyentes/{id}/medios-contacto")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun agregarMedioContacto(
        @PathVariable id: UUID,
        @RequestBody body: MedioContacto
    ) = contribuyentes.agregarMedioContacto(id, body)

    @PutMapping("/medios-contacto/{id}")
    suspend fun actualizarMedioContacto(
        @PathVariable id: UUID,
        @RequestBody body: MedioContacto
    ) = contribuyentes.actualizarMedioContacto(id, body)

    @DeleteMapping("/medios-contacto/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun borrarMedioContacto(
        @PathVariable id: UUID
    ) = contribuyentes.borrar(MEDIO_CONTACTO, id)

    @GetMapping("/contribuyentes/{id}/sustentos")
    suspend fun sustentos(
        @PathVariable id: UUID
    ) = contribuyentes.sustentos(id)

    @PostMapping("/contribuyentes/{id}/sustentos")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun agregarSustento(
        @PathVariable id: UUID,
        @RequestBody body: Sustento
    ) = contribuyentes.agregarSustento(id, body)

    @PutMapping("/sustentos/{id}")
    suspend fun actualizarSustento(
        @PathVariable id: UUID,
        @RequestBody body: Sustento
    ) = contribuyentes.actualizarSustento(id, body)

    @DeleteMapping("/sustentos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun borrarSustento(
        @PathVariable id: UUID
    ) = contribuyentes.borrar(SUSTENTO, id)

    // predios and declaraciones

    @GetMapping("/predios")
    suspend fun predios(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ) = rentas.predios(q, page, size)

    @PostMapping("/predios")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun crearPredio(
        @RequestBody body: Predio
    ) = declaraciones.registrarPredio(body)

    @GetMapping("/predios/{id}")
    suspend fun predio(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?
    ) = rentas.fichaPredio(id, anio)

    @PutMapping("/predios/{id}")
    suspend fun actualizarPredio(
        @PathVariable id: UUID,
        @RequestBody body: Predio
    ) = declaraciones.actualizarPredio(id, body)

    @GetMapping("/predios/{id}/declaraciones")
    suspend fun declaracionesDePredio(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?
    ) = rentas.declaracionesDePredio(id, anio)

    @PostMapping("/declaraciones")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun crearDeclaracion(
        @RequestBody body: Declaracion
    ) = declaraciones.crear(body)

    @PutMapping("/declaraciones/{id}")
    suspend fun actualizarDeclaracion(
        @PathVariable id: UUID,
        @RequestBody body: Declaracion
    ) = declaraciones.actualizar(id, body)

    @DeleteMapping("/declaraciones/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun borrarDeclaracion(
        @PathVariable id: UUID
    ) = declaraciones.borrarDeclaracion(id)

    // "datos de los condóminos": another titular of the declaración's predio, year and secuencia
    @PostMapping("/declaraciones/{id}/condominos")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun agregarCondomino(
        @PathVariable id: UUID,
        @RequestBody body: NuevoCondomino
    ) = declaraciones.agregarCondomino(id, body)

    // the declaración jurada predial: presented from its contribuyente, then its ficha and its lists

    @PostMapping("/contribuyentes/{id}/declaraciones-juradas")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun presentar(
        @PathVariable id: UUID,
        @RequestBody body: NuevaDeclaracion
    ) = declaraciones.presentar(id, body)

    @GetMapping("/declaraciones/{id}")
    suspend fun declaracion(
        @PathVariable id: UUID
    ) = declaraciones.ficha(id)

    @GetMapping("/declaraciones/{id}/transferentes")
    suspend fun transferentes(
        @PathVariable id: UUID
    ) = declaraciones.transferentes(id)

    @PostMapping("/declaraciones/{id}/transferentes")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun agregarTransferente(
        @PathVariable id: UUID,
        @RequestBody body: Transferente
    ) = declaraciones.agregarTransferente(id, body)

    @PutMapping("/transferentes/{id}")
    suspend fun actualizarTransferente(
        @PathVariable id: UUID,
        @RequestBody body: Transferente
    ) = declaraciones.actualizarTransferente(id, body)

    @DeleteMapping("/transferentes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun borrarTransferente(
        @PathVariable id: UUID
    ) = declaraciones.borrar(TRANSFERENTE, id)

    @GetMapping("/declaraciones/{id}/niveles")
    suspend fun niveles(
        @PathVariable id: UUID
    ) = declaraciones.niveles(id)

    @PostMapping("/declaraciones/{id}/niveles")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun agregarNivel(
        @PathVariable id: UUID,
        @RequestBody body: NivelConstruccion
    ) = declaraciones.agregarNivel(id, body)

    @PutMapping("/niveles/{id}")
    suspend fun actualizarNivel(
        @PathVariable id: UUID,
        @RequestBody body: NivelConstruccion
    ) = declaraciones.actualizarNivel(id, body)

    @DeleteMapping("/niveles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun borrarNivel(
        @PathVariable id: UUID
    ) = declaraciones.borrar(NIVEL_CONSTRUCCION, id)

    @GetMapping("/declaraciones/{id}/obras")
    suspend fun obras(
        @PathVariable id: UUID
    ) = declaraciones.obras(id)

    @PostMapping("/declaraciones/{id}/obras")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun agregarObra(
        @PathVariable id: UUID,
        @RequestBody body: ObraComplementaria
    ) = declaraciones.agregarObra(id, body)

    @PutMapping("/obras/{id}")
    suspend fun actualizarObra(
        @PathVariable id: UUID,
        @RequestBody body: ObraComplementaria
    ) = declaraciones.actualizarObra(id, body)

    @DeleteMapping("/obras/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun borrarObra(
        @PathVariable id: UUID
    ) = declaraciones.borrar(OBRA_COMPLEMENTARIA, id)

    @GetMapping("/declaraciones/{id}/frentes")
    suspend fun frentes(
        @PathVariable id: UUID
    ) = declaraciones.frentes(id)

    @PostMapping("/declaraciones/{id}/frentes")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun agregarFrente(
        @PathVariable id: UUID,
        @RequestBody body: OtroFrente
    ) = declaraciones.agregarFrente(id, body)

    @PutMapping("/frentes/{id}")
    suspend fun actualizarFrente(
        @PathVariable id: UUID,
        @RequestBody body: OtroFrente
    ) = declaraciones.actualizarFrente(id, body)

    @DeleteMapping("/frentes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun borrarFrente(
        @PathVariable id: UUID
    ) = declaraciones.borrar(OTRO_FRENTE, id)
}
