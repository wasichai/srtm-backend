package srtm.normativa

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// #305: the tolerances of the brecha's comparison are Normativa's TOLERANCIA_BRECHA rows: its clave AREA or MONTO,
// its value 0 or more; anything else is a 400 that names the field. FICTITIOUS figures
class ToleranciasBrechaApiTest : ConNormativaApiTest() {
    private fun tolerancia(
        clave: String,
        valor: Any?
    ) = mapOf(
        "tipo" to "TOLERANCIA_BRECHA",
        "clave" to clave,
        "vigencia_desde" to "${anio()}-01-01",
        "valor_numerico" to valor,
        "norma" to "Norma ficticia de prueba",
        "fuente" to "ToleranciasBrechaApiTest"
    )

    @Test
    fun `a tolerance of 0 or more is registered, a negative one or another clave is a 400`() {
        assertEquals("POR_VERIFICAR", registrar(tolerancia("AREA", "0.75"))["estado"].asString())
        assertEquals("POR_VERIFICAR", registrar(tolerancia("MONTO", 0))["estado"].asString())
        rechazo("POST", PARAMETROS, tolerancia("AREA", "-1"), "valor_numerico")
        rechazo("POST", PARAMETROS, tolerancia("RELATIVA", "5"), "clave")
        rechazo("POST", PARAMETROS, tolerancia("MONTO", "uno"), "valor_numerico")
    }
}
