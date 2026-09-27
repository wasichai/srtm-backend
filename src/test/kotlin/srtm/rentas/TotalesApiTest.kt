package srtm.rentas

import org.junit.jupiter.api.Test

// the fichas' totals with two condóminos of one predio: each contribuyente counts its part, the predio its
// autoavalúo once
class TotalesApiTest : SrtmApiTest() {
    @Test
    fun `two condominos of a predio, each with its part, and the predio counted once`() {
        val a = inscribir()
        val b = inscribir()
        val compartido = predio()
        val propio = predio()
        // a predio of 10000.50, 40 % b's: the backend derives the parts, 6000.30 a's and 4000.20 b's
        declarar(a, compartido, autoavaluo = 10000.50, porcentaje = null)
        declarar(b, compartido, autoavaluo = 10000.50, porcentaje = 40)
        // a's own predio: no condominio, the whole autoavalúo is a's
        declarar(a, propio, autoavaluo = 5000.50, porcentaje = null)

        get("/api/srtm/contribuyentes/$a?anio=2026")
            .expectBody()
            .jsonPath("$.predios")
            .isEqualTo(2)
            .jsonPath("$.totales.declaraciones")
            .isEqualTo(2)
            .jsonPath("$.totales.autoavaluo")
            .isEqualTo(11000.80)
            .jsonPath("$.totales.valor_afecto")
            .isEqualTo(11000.80)
        get("/api/srtm/contribuyentes/$b?anio=2026")
            .expectBody()
            .jsonPath("$.totales.autoavaluo")
            .isEqualTo(4000.20)
            .jsonPath("$.totales.valor_afecto")
            .isEqualTo(4000.20)
        get("/api/srtm/predios/$compartido?anio=2026")
            .expectBody()
            .jsonPath("$.titulares")
            .isEqualTo(2)
            .jsonPath("$.totales.declaraciones")
            .isEqualTo(2)
            .jsonPath("$.totales.autoavaluo")
            .isEqualTo(10000.50)
            .jsonPath("$.totales.valor_afecto")
            .isEqualTo(10000.50)
    }

    private fun declarar(
        contribuyente: String,
        predio: String,
        autoavaluo: Double,
        porcentaje: Int?
    ) {
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to contribuyente,
                "predio" to predio,
                "anio" to 2026,
                "secuencia_uso" to "1",
                "porcentaje_condominio" to porcentaje,
                "valor_autoavaluo" to autoavaluo
            )
        )
    }
}
