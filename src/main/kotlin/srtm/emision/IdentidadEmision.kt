package srtm.emision

import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import wasichai.core.identity.JwtService
import wasichai.core.identity.RoleQueries
import wasichai.core.platform.WasichaiSchemas
import java.time.Duration
import java.time.Instant
import java.util.UUID

// who a lote is generated as (wasichai/srtm-backend#53): the user who created it, the same who launched the emission,
// so core checks that user's permissions on every read, as a request of theirs would. wasichai 0.2.0 has no way to
// rebuild a user's Authentication, nor a system context for RecordService, and CurrentUser reads only a Jwt principal
// (sub and the org, email and roles claims): this builds the one core's jwt filter would, from the user's row and
// roles as they are now. it is never signed and never leaves the process
@Component
class IdentidadEmision(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val roles: RoleQueries
) {
    // null if the user no longer exists or is disabled
    suspend fun de(usuario: UUID): Authentication? {
        val (organizacion, email) =
            db
                .sql("SELECT organization_id, email FROM ${schemas.metadata}.users WHERE id = :id AND enabled")
                .bind("id", usuario)
                .map { row, _ -> row.get("organization_id", UUID::class.java)!! to row.get("email", String::class.java)!! }
                .one()
                .awaitFirstOrNull()
                ?: return null
        val ahora = Instant.now()
        val jwt =
            Jwt
                .withTokenValue(TOKEN)
                .header("alg", "none")
                .subject(usuario.toString())
                .claim(JwtService.CLAIM_ORGANIZATION, organizacion.toString())
                .claim(JwtService.CLAIM_EMAIL, email)
                .claim(JwtService.CLAIM_ROLES, roles.roleNamesOf(usuario))
                .issuedAt(ahora)
                .expiresAt(ahora.plus(VIGENCIA))
                .build()
        return JwtAuthenticationToken(jwt)
    }

    private companion object {
        // no token travels: the value only says where the jwt came from
        const val TOKEN = "emision-masiva"

        // nothing checks it again (as with a request's jwt, its expiry does not stop a lote already started)
        val VIGENCIA: Duration = Duration.ofHours(1)
    }
}
