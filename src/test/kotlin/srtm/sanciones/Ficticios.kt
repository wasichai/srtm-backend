package srtm.sanciones

import srtm.impuesto.ParametroTributario
import java.math.BigDecimal
import java.time.LocalDate

// what the pure rules of the sanciones read, built by hand. every figure is FICTITIOUS (decision 14): no UIT, CUIS
// alícuota, plazo or feriado of Perené, nor of any year's real UIT
object Ficticios {
    // a UIT no year has had
    const val UIT = "4321.00"

    fun d(iso: String): LocalDate = LocalDate.parse(iso)

    fun uit(
        anio: Int = 2026,
        valor: String = UIT
    ) = ParametroTributario(
        id = "uit-$anio",
        tipo = "UIT",
        vigenciaDesde = LocalDate.of(anio, 1, 1),
        vigenciaHasta = LocalDate.of(anio, 12, 31),
        valorNumerico = BigDecimal(valor)
    )

    fun plazo(
        clave: String,
        dias: String,
        desde: String = "2026-01-01",
        hasta: String? = null,
        unidad: String? = Llaves.DIAS_HABILES
    ) = ParametroTributario(
        id = "plazo-$clave-$desde",
        tipo = Llaves.PLAZO,
        clave = clave,
        vigenciaDesde = d(desde),
        vigenciaHasta = hasta?.let(::d),
        valorNumerico = BigDecimal(dias),
        texto = unidad
    )

    // a year's FERIADOS row: its movable feriados (none is a statement too)
    fun feriados(
        anio: Int,
        vararg fechas: String
    ) = ParametroTributario(
        id = "feriados-$anio",
        tipo = Llaves.FERIADOS,
        clave = Llaves.feriados(anio),
        vigenciaDesde = LocalDate.of(anio, 1, 1),
        vigenciaHasta = LocalDate.of(anio, 12, 31),
        texto = fechas.joinToString(",")
    )

    // a CUIS version: 10 % the first time, 15 % the second, 20 % the third or more
    fun codigo(
        codigo: String = "A-042",
        desde: String = "2026-01-01",
        hasta: String? = null,
        primera: String? = "10",
        segunda: String? = "15",
        tercera: String? = "20",
        id: String = "cuis-$codigo-$desde"
    ) = CodigoInfraccion(
        id = id,
        familia = ADMINISTRATIVA,
        codigo = codigo,
        descripcion = "No exhibir la licencia (ficticio)",
        porcentajeUit = primera?.let(::BigDecimal),
        porcentajeUitSegunda = segunda?.let(::BigDecimal),
        porcentajeUitTercera = tercera?.let(::BigDecimal),
        medidaComplementaria = "Clausura temporal",
        baseLegal = "Ordenanza ficticia 001",
        vigenciaDesde = d(desde),
        vigenciaHasta = hasta?.let(::d),
        observacion = "Carga de prueba del CUIS",
        clave = claveDeCodigo(ADMINISTRATIVA, codigo, d(desde)),
        claveVigente = if (hasta == null) claveVigenteDe(ADMINISTRATIVA, codigo) else null
    )

    fun notificacion(
        numero: String = "NP-0001",
        fecha: String = "2026-08-10",
        plazoDias: Int? = 5
    ) = NotificacionAdministrativa(
        id = "np-$numero",
        numero = numero,
        fecha = d(fecha),
        direccion = "JR. FICTICIO 123",
        motivo = "Letrero sin licencia",
        plazoDias = plazoDias,
        observacion = "Notificación de prueba",
        contribuyente = "c"
    )

    fun subsanacion(n: NotificacionAdministrativa) =
        SubsanacionNotificacion(id = "s-${n.id}", fecha = n.fecha, observacion = "Retiró el letrero", clave = n.id, notificacion = n.id)

    fun anulacion(acta: String = "acta") = AnulacionPapeleta(id = "an-$acta", fecha = d("2026-08-20"), motivo = "Error material", clave = acta, papeleta = acta)

    fun descargo(
        acta: String = "acta",
        id: String = "desc-1"
    ) = DescargoPapeleta(id = id, numeroExpediente = "EXP-$id", tipoRecurso = "DESCARGO", fecha = d("2026-08-12"), papeleta = acta)

    fun resolucion(
        tipo: String = RESOLUCION_ADMINISTRATIVA,
        efecto: String? = null,
        descargo: String? = null,
        acta: String = "acta",
        correlativo: Int = 1
    ) = ResolucionGerencia(
        id = "res-$tipo-$correlativo",
        tipo = tipo,
        anio = 2026,
        correlativo = correlativo,
        numero = numeroDeResolucion(tipo, 2026, correlativo),
        fecha = d("2026-08-25"),
        sentido = efecto?.let { "FUNDADO" },
        efecto = efecto,
        papeleta = acta,
        descargo = descargo
    )
}
