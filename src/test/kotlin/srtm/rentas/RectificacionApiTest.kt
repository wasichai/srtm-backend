package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalDate

// #147: a rectificatoria is a new declaración, numbered, with what the one it rectifies declares and its lists; that
// one stays SUSTITUIDA, read-only and out of every count. another year is declared from the year before (desde). the
// external padrón's valuation of the declaración stays where it came: neither copy takes it. its niveles' (#180): the
// rectificatoria keeps it all, another year only the clasificación
class RectificacionApiTest : SrtmApiTest() {
    @Test
    fun `a rectificatoria is numbered, with the lists and titular's data of the one it replaces, not its padron valuation`() {
        val contribuyente = inscribir()
        val origen = declarar(contribuyente, predio())
        val id = origen["id"].asString()
        val nivel = post("/api/srtm/declaraciones/$id/niveles", NIVEL)
        post("/api/srtm/declaraciones/$id/obras", OBRA)
        post("/api/srtm/declaraciones/$id/frentes", FRENTE)
        val transferente = post("/api/srtm/declaraciones/$id/transferentes", TRANSFERENTE)
        importar(DECLARACION, id, DECLARACION_ICA)
        importar(NIVEL_CONSTRUCCION, nivel["id"].asString(), NIVEL_ICA)

        val ficha = rectificar(id, mapOf("medio_presentacion" to "VIRTUAL"))

        val r = ficha["declaracion"]
        val nueva = r["id"].asString()
        assertEquals(contribuyente, ficha["contribuyente"]["id"].asString())
        assertEquals(origen["predio"].asString(), ficha["predio"]["id"].asString())
        assertEquals("RECTIFICACION", r["motivo"].asString())
        assertEquals("VIGENTE", r["estado"].asString())
        assertEquals(id, r["sustituye"].asString())
        assertTrue(r["numero_declaracion"].asInt() > origen["numero_declaracion"].asInt(), r.toString())
        assertEquals("VIRTUAL", r["medio_presentacion"].asString())
        assertEquals(LocalDate.now().toString(), r["fecha_presentacion"].asString())
        assertEquals(2026, r["anio"].asInt())
        assertEquals("001", r["secuencia_uso"].asString())
        for (campo in listOf("tipo_adquisicion", "fecha_adquisicion", "documentos_sustento", "condicion_especial", "uso")) {
            assertEquals(origen[campo].asString(), r[campo].asString(), campo)
        }
        for (campo in listOf("valor_autoavaluo", "deduccion", "valor_afecto", "porcentaje_condominio")) assertDecimal(origen[campo], r[campo])
        assertEquals("PROPIETARIO UNICO", r["condicion_propiedad"].asString())

        // its lists, copied
        assertEquals(listOf(120.0), lista("/api/srtm/declaraciones/$nueva/niveles").map { it["area_construida"].asDouble() })
        assertEquals(listOf(100.0), lista("/api/srtm/declaraciones/$nueva/obras").map { it["total_metrado"].asDouble() })
        assertEquals(listOf("MARGINAL"), lista("/api/srtm/declaraciones/$nueva/frentes").map { it["via"].asString() })
        val copiado = lista("/api/srtm/declaraciones/$nueva/transferentes").single()
        assertEquals(transferente["codigo"].asString(), copiado["codigo"].asString())
        assertEquals("43434352", copiado["numero_documento"].asString())

        // the valuation of the padrón's declaración stays in the sustituida; its nivel's goes with the nivel (#180)
        val sinOrigen = valuacionOrigen(nueva)
        assertEquals(0, sinOrigen["declaracion"].size(), sinOrigen.toString())
        assertEquals(1, sinOrigen["niveles"].size(), sinOrigen.toString())
        assertEquals(DECLARACION_ICA.size, valuacionOrigen(id)["declaracion"].size())

        val sustituida = declaracion(id)
        assertEquals("SUSTITUIDA", sustituida["estado"].asString())
        assertEquals(LocalDate.now().toString(), sustituida["fecha_sustitucion"].asString())
        assertEquals(origen["motivo"].asString(), sustituida["motivo"].asString())
    }

    @Test
    fun `the sustituida is read-only and stays, its rectificatoria is edited and keeps its place`() {
        val id = declarar(inscribir(), predio())["id"].asString()
        val frente = post("/api/srtm/declaraciones/$id/frentes", FRENTE)
        val r = rectificar(id)["declaracion"]
        val nueva = r["id"].asString()
        val sustituida = declaracion(id)

        val problema = rejected("PUT", "/api/srtm/declaraciones/$id", fields(sustituida) + ("uso" to "EDIFICIO"), "estado")
        assertEquals("La declaración fue sustituida por una rectificatoria", problema["detail"].asString(), problema.toString())
        rejected("POST", "/api/srtm/declaraciones/$id/niveles", NIVEL, "estado")
        rejected("PUT", "/api/srtm/frentes/${frente["id"].asString()}", fields(frente) + ("frontis" to 9), "estado")
        rejected("POST", "/api/srtm/declaraciones/$id/anular", mapOf("motivo_anulacion" to "Otra vez"), "estado")
        rejected("POST", "/api/srtm/declaraciones/$id/rectificar", emptyMap(), "estado")
        assertEquals("La declaración fue sustituida por una rectificatoria: no se puede eliminar", conflict("/api/srtm/declaraciones/$id"))

        // edited, it is still a rectificatoria, of the same year
        val editada = put("/api/srtm/declaraciones/$nueva", fields(r) + mapOf("uso" to "EDIFICIO", "sustituye" to null))
        assertEquals("RECTIFICACION", editada["motivo"].asString())
        assertEquals(id, editada["sustituye"].asString())
        rejected("PUT", "/api/srtm/declaraciones/$nueva", fields(editada) + ("anio" to 2027), "anio")
        assertEquals("SUSTITUIDA", declaracion(id)["estado"].asString())

        // without lists it would go, and leave the year without a declaración
        val vacia = declarar(inscribir(), predio())["id"].asString()
        val sinListas = rectificar(vacia)["declaracion"]["id"].asString()
        assertEquals("La declaración rectifica otra: anúlela en lugar de eliminarla", conflict("/api/srtm/declaraciones/$sinListas"))
    }

    @Test
    fun `the fichas count the rectificatoria alone, and the history lists both`() {
        val contribuyente = inscribir()
        val predio = predio()
        val id = declarar(contribuyente, predio)["id"].asString()
        val r = rectificar(id)["declaracion"]
        put("/api/srtm/declaraciones/${r["id"].asString()}", fields(r) + ("valor_autoavaluo" to 20000))

        val fichaContribuyente = tree(send("GET", "/api/srtm/contribuyentes/$contribuyente?anio=2026", null, HttpStatus.OK))
        assertEquals(1, fichaContribuyente["predios"].asInt())
        assertEquals(1, fichaContribuyente["totales"]["declaraciones"].asInt())
        assertDecimal("20000", fichaContribuyente["totales"]["autoavaluo"])
        assertDecimal("19000", fichaContribuyente["totales"]["valor_afecto"])
        val fichaPredio = tree(send("GET", "/api/srtm/predios/$predio?anio=2026", null, HttpStatus.OK))
        assertEquals(1, fichaPredio["titulares"].asInt())
        assertDecimal("20000", fichaPredio["totales"]["autoavaluo"])

        val historia = lista("/api/srtm/predios/$predio/declaraciones?anio=2026").map { it["declaracion"]["estado"].asString() }
        assertEquals(listOf("SUSTITUIDA", "VIGENTE"), historia.sorted())
    }

    @Test
    fun `a condomino's rectificatoria takes its place in the condominio, with its percentage`() {
        val predio = predio()
        val a = declarar(inscribir(), predio)["id"].asString()
        val b = declarar(inscribir(), predio, "porcentaje_condominio" to 40)["id"].asString()

        val deB = rectificar(b)["declaracion"]
        assertEquals("CONDOMINO", deB["condicion_propiedad"].asString())
        assertDecimal("40", deB["porcentaje_condominio"])
        assertDecimal("4000.20", deB["valor_condominio"])
        val deA = declaracion(a)
        assertEquals("CONDOMINO", deA["condicion_propiedad"].asString())
        assertDecimal("60", deA["porcentaje_condominio"])

        // and the other condómino's, against the first's rectificatoria
        val otraDeA = rectificar(a)["declaracion"]
        assertDecimal("60", otraDeA["porcentaje_condominio"])
        assertEquals("CONDOMINO", otraDeA["condicion_propiedad"].asString())
        assertDecimal("40", declaracion(deB["id"].asString())["porcentaje_condominio"])
        val fichaPredio = tree(send("GET", "/api/srtm/predios/$predio?anio=2026", null, HttpStatus.OK))
        assertEquals(2, fichaPredio["titulares"].asInt())
        assertDecimal("10000.50", fichaPredio["totales"]["autoavaluo"])
    }

    @Test
    fun `another year is declared from the year before, its lists and autoavaluo copied, its padron valuation not`() {
        val contribuyente = inscribir()
        val predio = predio()
        val origen = declarar(contribuyente, predio, "anio" to 2025)
        val id = origen["id"].asString()
        val nivel = post("/api/srtm/declaraciones/$id/niveles", NIVEL)
        post("/api/srtm/declaraciones/$id/frentes", FRENTE)
        importar(DECLARACION, id, DECLARACION_ICA)
        importar(NIVEL_CONSTRUCCION, nivel["id"].asString(), NIVEL_ICA)

        val ficha =
            post(
                "/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas",
                mapOf("predio_id" to predio, "desde" to id, "declaracion" to mapOf("anio" to 2026, "medio_presentacion" to "VIRTUAL"))
            )

        val d = ficha["declaracion"]
        val nueva = d["id"].asString()
        assertEquals(2026, d["anio"].asInt())
        assertEquals("INSCRIPCION", d["motivo"].asString())
        assertEquals("VIGENTE", d["estado"].asString())
        assertEquals("VIRTUAL", d["medio_presentacion"].asString())
        assertTrue(d["sustituye"] == null || d["sustituye"].isNull, d.toString())
        assertDecimal("10000.50", d["valor_autoavaluo"])
        assertEquals(origen["tipo_adquisicion"].asString(), d["tipo_adquisicion"].asString())
        assertEquals(1, lista("/api/srtm/declaraciones/$nueva/niveles").size)
        assertEquals(1, lista("/api/srtm/declaraciones/$nueva/frentes").size)
        assertEquals(0, valuacionOrigen(nueva)["declaracion"].size())
        assertEquals(0, valuacionOrigen(nueva)["niveles"].size())
        // the year before stays as it was
        assertEquals("VIGENTE", declaracion(id)["estado"].asString())
        assertEquals(DECLARACION_ICA.size, valuacionOrigen(id)["declaracion"].size())
    }

    @Test
    fun `another year is not declared from the same year, another predio or another contribuyente`() {
        val contribuyente = inscribir()
        val predio = predio()
        val id = declarar(contribuyente, predio, "anio" to 2025)["id"].asString()
        val presentar = "/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas"
        val antes = lista("/api/srtm/predios/$predio/declaraciones").size

        val mismoAnio =
            rejected("POST", presentar, mapOf("predio_id" to predio, "desde" to id, "declaracion" to mapOf("anio" to 2025)), "anio")
        assertEquals("En el mismo año corresponde rectificar la declaración", mismoAnio["detail"].asString(), mismoAnio.toString())
        rejected("POST", presentar, mapOf("predio_id" to predio(), "desde" to id, "declaracion" to mapOf("anio" to 2026)), "predio_id")
        rejected(
            "POST",
            "/api/srtm/contribuyentes/${inscribir()}/declaraciones-juradas",
            mapOf("predio_id" to predio, "desde" to id, "declaracion" to mapOf("anio" to 2026)),
            "desde"
        )
        assertEquals(antes, lista("/api/srtm/predios/$predio/declaraciones").size)
    }

    @Test
    fun `another year is not declared from a sustituida or an annulled declaration`() {
        val contribuyente = inscribir()
        val predio = predio()
        val presentar = "/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas"
        val sustituida = declarar(contribuyente, predio, "anio" to 2025)["id"].asString()
        rectificar(sustituida)
        val anulada = declarar(contribuyente, predio, "anio" to 2024)["id"].asString()
        send("POST", "/api/srtm/declaraciones/$anulada/anular", mapOf("motivo_anulacion" to "Vendido"), HttpStatus.OK)
        val antes = lista("/api/srtm/predios/$predio/declaraciones").size

        val deSustituida =
            rejected("POST", presentar, mapOf("predio_id" to predio, "desde" to sustituida, "declaracion" to mapOf("anio" to 2026)), "desde")
        assertEquals("La declaración fue sustituida: declara desde su rectificatoria", deSustituida["detail"].asString(), deSustituida.toString())
        val deAnulada =
            rejected("POST", presentar, mapOf("predio_id" to predio, "desde" to anulada, "declaracion" to mapOf("anio" to 2026)), "desde")
        assertEquals("La declaración está anulada: no se puede declarar desde ella", deAnulada["detail"].asString(), deAnulada.toString())
        assertEquals(antes, lista("/api/srtm/predios/$predio/declaraciones").size)
    }

    // #180: what the padrón brought of a nivel, which its dto does not carry, is the same nivel's in the same year
    @Test
    fun `a rectificatoria keeps every padron field of its niveles and their valuation, a nivel without them as before`() {
        val id = declarar(inscribir(), predio())["id"].asString()
        val conPadron = post("/api/srtm/declaraciones/$id/niveles", NIVEL)
        post("/api/srtm/declaraciones/$id/niveles", NIVEL + ("numero_piso" to 2))
        importar(NIVEL_CONSTRUCCION, conPadron["id"].asString(), NIVEL_COMPLETO)

        val nueva = rectificar(id)["declaracion"]["id"].asString()

        val niveles = lista("/api/srtm/declaraciones/$nueva/niveles").associateBy { it["numero_piso"].asInt() }
        assertEquals(setOf(1, 2), niveles.keys, niveles.toString())
        assertPadron(NIVEL_COMPLETO, niveles.getValue(1)["id"].asString())
        assertPadron(emptyMap(), niveles.getValue(2)["id"].asString())
        // the source keeps its own
        assertPadron(NIVEL_COMPLETO, conPadron["id"].asString())

        // and the copy carries that valuation as the source does: shown, marked on an edit, its year kept
        val origen = valuacionOrigen(nueva)
        assertEquals(1, origen["niveles"].size(), origen.toString())
        assertEquals(NIVEL_COMPLETO.size, origen["niveles"][niveles.getValue(1)["id"].asString()].size(), origen.toString())
        assertFalse(origen["modificada"].asBoolean(), origen.toString())
        val otro = niveles.getValue(2)
        put("/api/srtm/niveles/${otro["id"].asString()}", fields(otro) + ("area_construida" to 150))
        assertTrue(valuacionOrigen(nueva)["modificada"].asBoolean())
        rejected("PUT", "/api/srtm/declaraciones/$nueva", fields(declaracion(nueva)) + ("anio" to 2027), "anio")
    }

    // a copy to another year keeps the clasificación, an insumo of the construction; the valuation figures are of the
    // source's year
    @Test
    fun `another year declared from a DJ keeps its niveles' clasificacion, not the padron's figures nor a valuation`() {
        val contribuyente = inscribir()
        val predio = predio()
        val id = declarar(contribuyente, predio, "anio" to 2025)["id"].asString()
        val conPadron = post("/api/srtm/declaraciones/$id/niveles", NIVEL)
        post("/api/srtm/declaraciones/$id/niveles", NIVEL + ("numero_piso" to 2))
        importar(NIVEL_CONSTRUCCION, conPadron["id"].asString(), NIVEL_COMPLETO)

        val nueva =
            post(
                "/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas",
                mapOf("predio_id" to predio, "desde" to id, "declaracion" to mapOf("anio" to 2026))
            )["declaracion"]["id"].asString()

        val niveles = lista("/api/srtm/declaraciones/$nueva/niveles").associateBy { it["numero_piso"].asInt() }
        assertEquals(setOf(1, 2), niveles.keys, niveles.toString())
        assertPadron(mapOf("clasificacion" to NIVEL_COMPLETO.getValue("clasificacion")), niveles.getValue(1)["id"].asString())
        assertPadron(emptyMap(), niveles.getValue(2)["id"].asString())
        assertPadron(NIVEL_COMPLETO, conPadron["id"].asString())

        // a clasificación alone is no valuation: none shown, an edit does not mark it, its year changes
        val origen = valuacionOrigen(nueva)
        assertEquals(0, origen["niveles"].size(), origen.toString())
        assertEquals(0, origen["declaracion"].size(), origen.toString())
        val clasificado = niveles.getValue(1)
        put("/api/srtm/niveles/${clasificado["id"].asString()}", fields(clasificado) + ("area_construida" to 150))
        assertFalse(valuacionOrigen(nueva)["modificada"].asBoolean())
        assertEquals(2027, put("/api/srtm/declaraciones/$nueva", fields(declaracion(nueva)) + ("anio" to 2027))["anio"].asInt())
        assertPadron(mapOf("clasificacion" to NIVEL_COMPLETO.getValue("clasificacion")), clasificado["id"].asString())
    }

    @Test
    fun `a declaration with an external padron's valuation keeps its year`() {
        val conValuacion = declarar(inscribir(), predio())
        importar(DECLARACION, conValuacion["id"].asString(), DECLARACION_ICA)
        val conNivel = declarar(inscribir(), predio())
        val nivel = post("/api/srtm/declaraciones/${conNivel["id"].asString()}/niveles", NIVEL)
        importar(NIVEL_CONSTRUCCION, nivel["id"].asString(), NIVEL_ICA)

        for (d in listOf(conValuacion, conNivel)) {
            val actual = declaracion(d["id"].asString())
            val problema = rejected("PUT", "/api/srtm/declaraciones/${d["id"].asString()}", fields(actual) + ("anio" to 2027), "anio")
            assertEquals("no cambia de año: declara el año nuevo desde esta DJ", problema["errors"][0]["message"].asString(), problema.toString())
            // the rest of it is edited
            put("/api/srtm/declaraciones/${d["id"].asString()}", fields(actual) + ("uso" to "EDIFICIO"))
        }
        val propia = declarar(inscribir(), predio())
        assertEquals(2027, put("/api/srtm/declaraciones/${propia["id"].asString()}", fields(propia) + ("anio" to 2027))["anio"].asInt())
    }

    private fun rectificar(
        id: String,
        body: Map<String, Any?>? = null
    ): JsonNode = tree(send("POST", "/api/srtm/declaraciones/$id/rectificar", body, HttpStatus.CREATED))

    // a predio of 10000.50, with a deducción of 1000, its titular's acquisition and condición especial
    private fun declarar(
        contribuyente: String,
        predio: String,
        vararg extra: Pair<String, Any?>
    ): JsonNode =
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to contribuyente,
                "predio" to predio,
                "anio" to 2026,
                "secuencia_uso" to "1",
                "uso" to "CASA HABITACIÓN",
                "valor_autoavaluo" to 10000.50,
                "deduccion" to 1000,
                "tipo_adquisicion" to "COMPRA",
                "fecha_adquisicion" to "2020-05-04",
                "documentos_sustento" to "MINUTA",
                "condicion_especial" to "PENSIONISTA"
            ) + extra
        )

    private fun declaracion(id: String): JsonNode = tree(send("GET", "/api/srtm/declaraciones/$id", null, HttpStatus.OK))["declaracion"]

    private fun valuacionOrigen(id: String): JsonNode = tree(send("GET", "/api/srtm/declaraciones/$id/valuacion-origen", null, HttpStatus.OK))

    private fun lista(path: String): List<JsonNode> = tree(send("GET", path, null, HttpStatus.OK)).iterator().asSequence().toList()

    private fun conflict(path: String): String = tree(send("DELETE", path, null, HttpStatus.CONFLICT))["detail"].asString()

    // what the importer does: core's record api, whose update replaces every field (so it sends them all)
    private fun importar(
        objeto: String,
        id: String,
        ica: Map<String, Any>
    ) {
        val registro = tree(send("GET", "/api/objects/$objeto/records/$id", null, HttpStatus.OK))["attributes"]
        send("PUT", "/api/objects/$objeto/records/$id", mapOf("attributes" to fields(registro) + ica), HttpStatus.OK)
    }

    // the stored nivel's PADRON_EXTERNO_NIVEL fields with a value are exactly `esperado`'s (decimals by value)
    private fun assertPadron(
        esperado: Map<String, Any>,
        nivel: String
    ) {
        val registro = tree(send("GET", "/api/objects/$NIVEL_CONSTRUCCION/records/$nivel", null, HttpStatus.OK))["attributes"]
        val presentes = PADRON_EXTERNO_NIVEL.filter { registro[it] != null && !registro[it].isNull && registro[it].asString().isNotBlank() }
        assertEquals(esperado.keys, presentes.toSet(), registro.toString())
        for ((campo, valor) in esperado) {
            if (valor is BigDecimal) assertDecimal(valor.toPlainString(), registro[campo]) else assertEquals(valor, registro[campo].asString(), campo)
        }
    }

    private fun assertDecimal(
        expected: String,
        actual: JsonNode?
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual!!.decimalValue()), "expected $expected, got $actual")

    private fun assertDecimal(
        expected: JsonNode?,
        actual: JsonNode?
    ) = assertEquals(0, expected!!.decimalValue().compareTo(actual!!.decimalValue()), "expected $expected, got $actual")

    private companion object {
        val NIVEL =
            mapOf(
                "tipo_nivel" to "PISO",
                "numero_piso" to 1,
                "anio_construccion" to 2015,
                "mes_construccion" to 3,
                "material" to "LADRILLO",
                "estado_conservacion" to "BUENO",
                "area_construida" to 120
            )
        val OBRA =
            mapOf(
                "ingreso" to "POR CATEGORIAS",
                "material" to "LADRILLO",
                "tipo_obra" to "MUROS PERIMETRICOS O CERCOS",
                "estado_conservacion" to "BUENO",
                "anio_construccion" to 2024,
                "mes_construccion" to 2,
                "numero_piso" to 1,
                "cantidad" to 2,
                "metrado" to 50
            )
        val FRENTE = mapOf("tipo_via" to "AVENIDA", "via" to "MARGINAL", "frontis" to 7)
        val TRANSFERENTE =
            mapOf(
                "porcentaje_transferido" to 50,
                "tipo_documento" to "DNI",
                "numero_documento" to "43434352",
                "apellido_paterno" to "NEIRA",
                "nombres" to "DUBERLI",
                "departamento" to "JUNIN",
                "provincia" to "CHANCHAMAYO",
                "distrito" to "PERENE",
                "descripcion_domicilio" to "JR. LIMA 123"
            )
        val DECLARACION_ICA = mapOf("arancel" to BigDecimal("35.50"), "valor_terreno" to BigDecimal("7100"))
        val NIVEL_ICA = mapOf("valor_unitario" to BigDecimal("402.08"), "valor_total" to BigDecimal("48250.75"))

        // every PADRON_EXTERNO_NIVEL field, fictitious
        val NIVEL_COMPLETO =
            mapOf(
                "clasificacion" to "CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA",
                "valor_unitario" to BigDecimal("402.08"),
                "incremento" to BigDecimal("20.10"),
                "porcentaje_depreciacion" to BigDecimal("12"),
                "valor_total" to BigDecimal("48250.75")
            )
    }
}
