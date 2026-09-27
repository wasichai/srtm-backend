package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus

// the portal api for a clerk whose role is not ADMIN: core's object and field permissions narrow what the
// catalogs answer and what a save sends, without failing the whole answer or the whole save. token is the admin's
class PermisosApiTest : SrtmApiTest() {
    @Test
    fun `a role that cannot read one catalog object still gets the other catalogs`() {
        // a permission grants, none denies: READ on every object of the model but via
        val funcionario = funcionario(objetosDelModelo().filter { it != VIA }.map { permiso(it, "READ") })
        client
            .get()
            .uri("/api/srtm/catalogos")
            .header(HttpHeaders.AUTHORIZATION, funcionario)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.contribuyente.tipo_documento")
            .isNotEmpty
            .jsonPath("$.predio.condicion")
            .isNotEmpty
            .jsonPath("$.via")
            .doesNotExist()
    }

    @Test
    fun `a role that cannot write a field still saves the record, and the field keeps its stored value`() {
        val funcionario =
            funcionario(
                listOf("READ", "CREATE", "UPDATE").map { permiso(null, it) },
                bloqueado = CONTRIBUYENTE to "observacion"
            )
        val inscrito = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()) + ("observacion" to "PRIMERA VISITA"))
        val id = inscrito["id"].asString()

        // the whole form, the locked field changed too: it is not sent, so core keeps what it has
        val body = fields(inscrito) + mapOf("apellido_paterno" to "RAMOS", "observacion" to "OTRA")
        val updated = put("/api/srtm/contribuyentes/$id", body, funcionario)
        assertEquals("RAMOS OTINIANO JUNIOR", updated["nombre_completo"].asString())
        assertEquals("PRIMERA VISITA", updated["observacion"].asString())

        // a new one is saved too, without the field the role may not write
        val nuevo = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()) + ("observacion" to "NO VA"), funcionario)
        assertTrue(nuevo["observacion"] == null || nuevo["observacion"].isNull)
    }

    // a clerk with a role of their own: what it may do, and the one field it may read but not write
    private fun funcionario(
        permisos: List<Map<String, Any?>>,
        bloqueado: Pair<String, String>? = null
    ): String {
        val rol = uniqueName("ROL").uppercase()
        send("POST", "/api/roles", mapOf("name" to rol, "label" to rol), HttpStatus.CREATED)
        send("PUT", "/api/roles/$rol/permissions", mapOf("permissions" to permisos), HttpStatus.OK)
        if (bloqueado != null) {
            val (objeto, campo) = bloqueado
            send(
                "PUT",
                "/api/roles/$rol/field-permissions",
                mapOf("fields" to listOf(mapOf("objectName" to objeto, "fieldName" to campo, "read" to true, "write" to false))),
                HttpStatus.OK
            )
        }
        val email = "${rol.lowercase()}@srtm.test"
        send("POST", "/api/users", mapOf("email" to email, "displayName" to rol, "password" to CLAVE, "roles" to listOf(rol)), HttpStatus.CREATED)
        return bearer(email, CLAVE)
    }

    // objectName null: every object of the organization
    private fun permiso(
        objeto: String?,
        accion: String
    ) = mapOf("objectName" to objeto, "action" to accion)

    private fun objetosDelModelo(): List<String> =
        modelo()["objects"]
            .iterator()
            .asSequence()
            .map { it["name"].asString() }
            .toList()

    private companion object {
        // Core wants at least 8 characters
        const val CLAVE = "clave-del-funcionario"
    }
}
