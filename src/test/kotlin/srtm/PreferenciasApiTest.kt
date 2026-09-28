package srtm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import srtm.rentas.SrtmApiTest

// the theme and locale the portal (@wasichai/core 0.2.1) stores for its user: core 0.2.0's endpoint, with its
// migration applied to srtm's database, answering the seeded admin
class PreferenciasApiTest : SrtmApiTest() {
    // shared db: the admin's preferences go back to the defaults
    @AfterEach
    fun defaults() {
        put(PATH, mapOf("theme" to "system", "locale" to null))
    }

    @Test
    fun `the admin reads the defaults, stores a theme and a locale and reads them back`() {
        val inicial = tree(send("GET", PATH, null, HttpStatus.OK))
        assertEquals("system", inicial["theme"].asString())
        assertTrue(inicial["locale"].isNull, inicial.toString())

        val guardadas = put(PATH, mapOf("theme" to "dark", "locale" to "es-PE"))
        assertEquals("dark", guardadas["theme"].asString())
        assertEquals("es-PE", guardadas["locale"].asString())

        val leidas = tree(send("GET", PATH, null, HttpStatus.OK))
        assertEquals("dark", leidas["theme"].asString())
        assertEquals("es-PE", leidas["locale"].asString())
    }

    @Test
    fun `an invalid theme is a 400 and no token is a 401`() {
        rejected("PUT", PATH, mapOf("theme" to "Modo Oscuro"), "theme")
        client
            .get()
            .uri(PATH)
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    private companion object {
        const val PATH = "/api/auth/me/preferences"
    }
}
