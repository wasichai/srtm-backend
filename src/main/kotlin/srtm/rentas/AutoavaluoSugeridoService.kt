package srtm.rentas

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import srtm.brecha.Catastro
import srtm.brecha.CatastroException
import srtm.brecha.Catastros
import srtm.brecha.SIN_CATASTRO_CONFIGURADO
import srtm.brecha.ValuacionDeInsumos
import srtm.brecha.delUsuario
import wasichai.core.common.ConflictException
import wasichai.core.common.WasichaiException
import wasichai.core.identity.CurrentUser
import wasichai.core.organization.OrganizationRepository
import java.util.UUID

// catastro-backend did not answer the valuación: a 502 with its reason (never a token nor the secret)
class CatastroNoResponde(
    mensaje: String
) : WasichaiException(HttpStatus.BAD_GATEWAY, mensaje)

// #149: the autoavalúo sugerido of a declaración, valued by its organization's catastro-backend (the brecha's client
// and account) from the declaración, its predio, its niveles and its obras (AutoavaluoSugerido). nothing is stored:
// the portal shows it and the user decides. the records are read as the caller (core checks each), the declaración
// first: another organization's is a 404 before the catastro's 409
@Service
class AutoavaluoSugeridoService(
    private val registros: Registros,
    private val listas: Listas,
    private val catastros: Catastros,
    private val currentUser: CurrentUser,
    private val organizaciones: OrganizationRepository
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun sugerido(id: UUID): ValuacionDeInsumos {
        val registro = registros.get(DECLARACION, Map::class.java, id) as Map<String, Any?>
        val catastro = catastro() ?: throw ConflictException(SIN_CATASTRO_CONFIGURADO)
        return valuar(id, registro, catastro)
    }

    // #156: the autoavalúo a write of the declaración leaves (DeclaracionService.recalcular). catastro failing is a
    // mark, never a refused write: catastro's motivo, or that it did not answer. none configured: the user's
    @Suppress("UNCHECKED_CAST")
    suspend fun deLaDeclaracion(id: UUID): Autoavaluo {
        val catastro =
            try {
                catastro() ?: return Autoavaluo.Manual
            } catch (e: ConflictException) {
                return Autoavaluo.PorRevisar(e.message ?: SIN_CATASTRO_CONFIGURADO)
            }
        return try {
            Derivados.autoavaluo(valuar(id, registros.get(DECLARACION, Map::class.java, id) as Map<String, Any?>, catastro))
        } catch (e: CatastroNoResponde) {
            Derivados.noResponde(e.message)
        }
    }

    // the organization has no catastro: its users write the autoavalúo. one configured twice has one (its writes are
    // marked with why)
    suspend fun manual(): Boolean =
        try {
            catastro() == null
        } catch (_: ConflictException) {
            false
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun valuar(
        id: UUID,
        registro: Map<String, Any?>,
        catastro: Catastro
    ): ValuacionDeInsumos {
        val declaracion = Records.read(Declaracion::class.java, id.toString(), registro)
        val predio = declaracion.predio?.let { registros.get(PREDIO, Predio::class.java, UUID.fromString(it)) }
        val niveles =
            listas.listar(NIVEL_CONSTRUCCION, Map::class.java, PARENT, id).map {
                val nivel = it as Map<String, Any?>
                NivelDeValuacion(Records.read(NivelConstruccion::class.java, nivel["id"].toString(), nivel), nivel[CLASIFICACION]?.toString())
            }
        val obras = listas.listar(OBRA_COMPLEMENTARIA, ObraComplementaria::class.java, PARENT, id)
        val usos = registros.all(USO_PREDIO, UsoPredio::class.java)
        val arancel = registro["arancel"]?.toString()?.trim()?.toBigDecimalOrNull()
        val insumos = AutoavaluoSugerido.insumos(declaracion, arancel, predio, niveles, obras, usos)
        val otrasInstalaciones = registro["valor_otras_instalaciones"]?.toString()?.trim()?.toBigDecimalOrNull()
        return try {
            Derivados.conObrasDelPadron(catastro.valuar(insumos), obras, otrasInstalaciones)
        } catch (e: CatastroException) {
            throw CatastroNoResponde(e.message ?: "catastro-backend no valuó la declaración")
        }
    }

    // the caller's organization's catastro (BrechaService's rule), or null: its slug is read only when some key is a
    // slug, and only when the install has any
    private suspend fun catastro(): Catastro? = catastros.delUsuario(currentUser.require(), organizaciones)

    private companion object {
        // the niveles' and obras' field that names their declaración (DeclaracionService's)
        const val PARENT = "declaracion"
    }
}

// read-only, next to RentasController's declaración routes
@RestController
@RequestMapping("/api/srtm")
class AutoavaluoSugeridoController(
    private val autoavaluos: AutoavaluoSugeridoService
) {
    @GetMapping("/declaraciones/{id}/autoavaluo-sugerido")
    suspend fun sugerido(
        @PathVariable id: UUID
    ) = autoavaluos.sugerido(id)
}
