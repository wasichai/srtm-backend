package srtm.brecha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import wasichai.core.common.ConflictException
import java.time.Duration
import java.util.UUID

// a catastro per organization: the map srtm.catastro.organizaciones (and the old single properties, an entry of it),
// bound from the environment, and the registry that gives each organization its own client
class CatastrosTest {
    private val ica = UUID.fromString("0f5c2b1e-6d2a-4c1e-9b7a-3e8d5f4a2c10")

    // a catastro that is only its name: which one an organization got
    private class Falso(
        val nombre: String
    ) : Catastro {
        override suspend fun valuaciones(
            anio: Int,
            porPagina: suspend () -> Unit
        ): Valuaciones = Valuaciones(anio, nombre, emptyList())
    }

    @Test
    fun `the old single properties are a map of one entry`() {
        val p = CatastroProperties(enabled = true, url = "http://perene", clientId = "cid", clientSecret = "s", organizacion = " perene ")
        assertEquals(
            mapOf(
                "perene" to
                    CuentaCatastro(organizacion = "perene", url = "http://perene", clientId = "cid", clientSecret = "s", timeout = Duration.ofSeconds(60))
            ),
            p.cuentas()
        )
        // the account without an organization is nobody's
        assertEquals(emptyMap<String, CuentaCatastro>(), p.copy(organizacion = "").cuentas())
    }

    @Test
    fun `a map entry replaces the old one of the same organization, and its organizacion replaces its key`() {
        val p =
            CatastroProperties(
                enabled = true,
                url = "http://viejo",
                clientId = "viejo",
                clientSecret = "s",
                organizacion = "perene",
                organizaciones =
                    mapOf(
                        "PERENE" to CuentaCatastro(url = "http://perene", clientId = "cid-perene", clientSecret = "s1"),
                        "ica" to
                            CuentaCatastro(
                                organizacion = ica.toString(),
                                url = "http://ica",
                                clientId = "cid-ica",
                                clientSecret = "s2",
                                timeout = Duration.ofSeconds(5)
                            )
                    )
            )
        val cuentas = p.cuentas()
        assertEquals(setOf("perene", ica.toString()), cuentas.keys)
        assertEquals("http://perene", cuentas.getValue("perene").url)
        // the install's timeout when the entry has none
        assertEquals(Duration.ofSeconds(60), cuentas.getValue("perene").timeout)
        assertEquals("http://ica", cuentas.getValue(ica.toString()).url)
        assertEquals(Duration.ofSeconds(5), cuentas.getValue(ica.toString()).timeout)
    }

    @Test
    fun `the map binds from environment variables`() {
        val variables =
            mapOf(
                "SRTM_CATASTRO_ENABLED" to "true",
                "SRTM_CATASTRO_ORGANIZACIONES_PERENE_URL" to "http://perene:8091",
                "SRTM_CATASTRO_ORGANIZACIONES_PERENE_CLIENT_ID" to "cid-perene",
                "SRTM_CATASTRO_ORGANIZACIONES_PERENE_CLIENT_SECRET" to "secreto-perene",
                "SRTM_CATASTRO_ORGANIZACIONES_ICA_ORGANIZACION" to ica.toString(),
                "SRTM_CATASTRO_ORGANIZACIONES_ICA_URL" to "http://ica:8091",
                "SRTM_CATASTRO_ORGANIZACIONES_ICA_CLIENT_ID" to "cid-ica",
                "SRTM_CATASTRO_ORGANIZACIONES_ICA_CLIENT_SECRET" to "secreto-ica",
                "SRTM_CATASTRO_ORGANIZACIONES_ICA_TIMEOUT" to "90s"
            )
        val fuente = SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)
        val p = Binder(ConfigurationPropertySources.from(fuente).filterNotNull()).bind("srtm.catastro", CatastroProperties::class.java).get()
        assertTrue(p.enabled)
        val cuentas = p.cuentas()
        assertEquals(
            CuentaCatastro(
                organizacion = "perene",
                url = "http://perene:8091",
                clientId = "cid-perene",
                clientSecret = "secreto-perene",
                timeout = Duration.ofSeconds(60)
            ),
            cuentas["perene"]
        )
        assertEquals(
            CuentaCatastro(
                organizacion = ica.toString(),
                url = "http://ica:8091",
                clientId = "cid-ica",
                clientSecret = "secreto-ica",
                timeout = Duration.ofSeconds(90)
            ),
            cuentas[ica.toString()]
        )
    }

    @Test
    fun `each organization its own catastro, by its id or by its slug`() {
        val perene = Falso("perene")
        val deIca = Falso("ica")
        val catastros = Catastros(mapOf("perene" to perene, ica.toString() to deIca))
        assertTrue(catastros.configurado)
        assertTrue(catastros.necesitaSlug)
        assertSame(perene, catastros.de(UUID.randomUUID(), "PERENE"))
        assertSame(deIca, catastros.de(ica, null))
        // an id key never matches a slug, nor a slug key without the slug
        assertSame(deIca, catastros.de(ica, "ica"))
        assertNull(catastros.de(UUID.randomUUID(), ica.toString()))
        assertNull(catastros.de(UUID.randomUUID(), null))
        assertNull(catastros.de(UUID.randomUUID(), "ica"))
        assertTrue(!Catastros(mapOf(ica.toString() to deIca)).necesitaSlug)
        assertTrue(!Catastros(emptyMap()).configurado)
    }

    @Test
    fun `an organization named twice, by its slug and by its id, is refused`() {
        val catastros = Catastros(mapOf("ica" to Falso("a"), ica.toString() to Falso("b")))
        val e = assertThrows(ConflictException::class.java) { catastros.de(ica, "ica") }
        assertEquals(dosCatastros(setOf("ica", ica.toString())), e.message)
    }

    @Test
    fun `an account without url, client-id or client-secret is no catastro, and off is none`() {
        val p =
            CatastroProperties(
                enabled = true,
                organizaciones =
                    mapOf(
                        "perene" to CuentaCatastro(url = "http://perene", clientId = "cid", clientSecret = "s"),
                        "ica" to CuentaCatastro(url = "http://ica", clientId = "cid")
                    )
            )
        val catastros = CatastroConfig().catastros(p)
        assertTrue(catastros.de(UUID.randomUUID(), "perene") is ClienteCatastro)
        assertNull(catastros.de(UUID.randomUUID(), "ica"))
        assertTrue(!CatastroConfig().catastros(p.copy(enabled = false)).configurado)
    }

    @Test
    fun `the secret is never printed`() {
        val p =
            CatastroProperties(
                clientSecret = "viejo-secreto",
                organizaciones = mapOf("ica" to CuentaCatastro(url = "http://ica", clientId = "cid", clientSecret = "nuevo-secreto"))
            )
        assertTrue(!p.toString().contains("secreto"), p.toString())
        assertTrue(!p.cuentas().toString().contains("secreto"))
    }
}
