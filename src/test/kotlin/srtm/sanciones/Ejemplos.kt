package srtm.sanciones

// records of the sanciones as srtm's services write them, attribute by attribute, the way the generic api would send
// them. FICTITIOUS values: no CUIS, UIT or plazo of Perené is used. a relation is any id the caller gives
object Ejemplos {
    fun codigo(
        codigo: String = "A-042",
        desde: String = "2026-01-01"
    ): Map<String, Any?> =
        mapOf(
            "familia" to "ADMINISTRATIVA",
            "codigo" to codigo,
            "descripcion" to "No exhibir la licencia (ficticio)",
            "materia" to "COMERCIO",
            "porcentaje_uit" to "10",
            "porcentaje_uit_segunda" to "15",
            "porcentaje_uit_tercera" to "20",
            "medida_complementaria" to "Clausura temporal",
            "base_legal" to "Ordenanza ficticia 001",
            "vigencia_desde" to desde,
            "vigencia_hasta" to null,
            "observacion" to "Carga de prueba del CUIS",
            "clave" to "ADMINISTRATIVA|$codigo|$desde",
            "clave_vigente" to "ADMINISTRATIVA|$codigo"
        )

    fun notificacion(
        numero: String = "NP-0001",
        contribuyente: String? = "c"
    ): Map<String, Any?> =
        mapOf(
            "numero" to numero,
            "fecha" to "2026-03-02",
            "direccion" to "JR. LIMA 123",
            "motivo" to "Letrero sin licencia",
            "plazo_dias" to 5,
            "observacion" to "Notificación de prueba",
            "contribuyente" to contribuyente,
            "predio" to null
        )

    fun subsanacion(notificacion: String = "n"): Map<String, Any?> =
        mapOf("fecha" to "2026-03-04", "observacion" to "Retiró el letrero", "clave" to notificacion, "notificacion" to notificacion)

    fun papeleta(
        numero: String = "AC-0001",
        codigo: String = "k",
        uit: String = "u",
        obligado: String = "o",
        contribuyente: String? = obligado
    ): Map<String, Any?> =
        mapOf(
            "familia" to "ADMINISTRATIVA",
            "numero" to numero,
            "clave" to "ADMINISTRATIVA|$numero",
            "fecha_infraccion" to "2026-03-04",
            "hora_infraccion" to "10:30",
            "lugar" to "JR. LIMA 123",
            "expediente" to "EXP-0001",
            "inspector" to "INSPECTOR DE PRUEBA",
            "descripcion_hecho" to "Letrero sin licencia",
            "reincidencia" to "PRIMERA",
            "medida_complementaria" to "Clausura temporal",
            "base_imponible" to "5500.00",
            "porcentaje_infraccion" to "10",
            "importe_infraccion" to "550.00",
            "porcentaje_a_cobrar" to "10",
            "importe_a_pagar" to "550.00",
            "importe_con_beneficio" to null,
            "fecha_calculo" to "2026-03-04",
            "observacion" to "Acta de prueba",
            "codigo_infraccion" to codigo,
            "uit" to uit,
            "obligado" to obligado,
            "contribuyente" to contribuyente,
            "predio" to null,
            "notificacion_previa" to null
        )

    fun anulacion(papeleta: String = "p"): Map<String, Any?> =
        mapOf("fecha" to "2026-03-05", "motivo" to "Error en el número", "observacion" to "Se anula", "clave" to papeleta, "papeleta" to papeleta)

    fun descargo(
        papeleta: String = "p",
        plazo: String = "t",
        fecha: String = "2026-03-10",
        presentadoHasta: String = "2026-03-12",
        enPlazo: Boolean = true
    ): Map<String, Any?> =
        mapOf(
            "numero_expediente" to "EXP-0002",
            "tipo_recurso" to "DESCARGO",
            "fecha" to fecha,
            "presentado_hasta" to presentadoHasta,
            "en_plazo" to enPlazo,
            "plazo_texto" to "5 DIAS_HABILES",
            "sustento" to "Tenía la licencia en trámite",
            "observacion" to "Descargo de prueba",
            "papeleta" to papeleta,
            "plazo" to plazo
        )

    // a RIS by default; a RECURSO with its descargo, sentido and efecto
    fun resolucion(
        papeleta: String = "p",
        tipo: String = "ADMINISTRATIVA",
        descargo: String? = null
    ): Map<String, Any?> =
        mapOf(
            "tipo" to tipo,
            "anio" to 2026,
            "correlativo" to 7,
            "numero" to (if (tipo == "RECURSO") "RGR" else "RIS") + "-2026-000007",
            "fecha" to "2026-03-20",
            "sentido" to descargo?.let { "INFUNDADO" },
            "efecto" to descargo?.let { "SE_MANTIENE" },
            "sancion_accesoria" to null,
            "sustento" to "Se constató la infracción",
            "plazo_texto" to "15 DIAS_HABILES",
            "clave_ris" to papeleta.takeIf { tipo == "ADMINISTRATIVA" },
            "clave_descargo" to descargo,
            "observacion" to "Resolución de prueba",
            "papeleta" to papeleta,
            "descargo" to descargo,
            "plazo" to "t"
        )

    fun notificacionResolucion(
        resolucion: String = "r",
        resultado: String = "NOTIFICADO"
    ): Map<String, Any?> {
        val surte = resultado != "NO_UBICADO"
        return mapOf(
            "intento" to 1,
            "clave" to "$resolucion|1",
            "fecha_diligencia" to "2026-03-23",
            "modalidad" to "PERSONAL",
            "resultado" to resultado,
            "notificador" to "NOTIFICADOR DE PRUEBA",
            "direccion" to "JR. LIMA 123",
            "receptor" to "JUNIOR FLORES",
            "documento_receptor" to "12345678",
            "vinculo" to "TITULAR",
            "acuse" to "Firmó",
            "exigible_desde" to (if (surte) "2026-04-14" else null),
            "plazo_texto" to (if (surte) "15 DIAS_HABILES" else null),
            "observacion" to "Notificación de prueba",
            "resolucion" to resolucion,
            "plazo" to (if (surte) "t" else null)
        )
    }
}
