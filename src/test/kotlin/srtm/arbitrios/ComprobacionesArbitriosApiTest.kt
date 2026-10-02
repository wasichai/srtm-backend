package srtm.arbitrios

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.emision.IdentidadEmision
import srtm.rentas.SrtmApiTest
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.platform.WasichaiSchemas
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

// spike (plan §3, sección 9 del prompt): what wasichai 0.2.0 does with the cuotas de arbitrio before designing them.
// a throwaway object shaped like cuota_arbitrio; what each test prints is the evidence. not merged
class ComprobacionesArbitriosApiTest : SrtmApiTest() {
    @Autowired
    lateinit var records: RecordService

    @Autowired
    lateinit var identidad: IdentidadEmision

    @Autowired
    lateinit var db: DatabaseClient

    @Autowired
    lateinit var schemas: WasichaiSchemas

    @Autowired
    lateinit var transacciones: ReactiveTransactionManager

    @Test
    fun `9_5 volumen - crear y listar cuotas en proceso`() {
        val objeto = objetoCuota()
        val predio = predio()
        val contribuyente = inscribir()
        val secuencial = 1000
        val concurrentes = 4000
        conAdmin {
            val t0 = System.nanoTime()
            repeat(secuencial) { crear(objeto, predio, contribuyente, it) }
            val t1 = System.nanoTime()
            (0 until 4)
                .map { w ->
                    async {
                        for (i in 0 until concurrentes / 4) crear(objeto, predio, contribuyente, secuencial + w * (concurrentes / 4) + i)
                    }
                }.awaitAll()
            val t2 = System.nanoTime()
            reporte("crear $secuencial secuencial: %.0f cuotas/s".format(secuencial / ((t1 - t0) / 1e9)))
            reporte("crear $concurrentes con 4 corrutinas: %.0f cuotas/s".format(concurrentes / ((t2 - t1) / 1e9)))

            val (tabla, columnas) = tablaFisica(objeto, listOf("anio", "predio_codigo", "periodo", "clave", "zona"))
            // the year of the padrón: ~8 300 predios x 5 servicios x 12 meses, written straight in sql (no bulk path in core)
            val org = db.sql("SELECT organization_id FROM $tabla LIMIT 1").map { r -> r.get(0, UUID::class.java)!! }.awaitSingle()
            val t3 = System.nanoTime()
            db
                .sql(
                    "INSERT INTO $tabla (organization_id, ${columnas["anio"]}, ${columnas["predio_codigo"]}, ${columnas["periodo"]}, " +
                        "${columnas["clave"]}, ${columnas["zona"]}) SELECT :org, 2026, 'B-' || (g / 60), (g % 12) + 1, 'b-' || g, 'Z1' " +
                        "FROM generate_series(1, $FILAS) g"
                ).bind("org", org)
                .await()
            db.sql("ANALYZE $tabla").await()
            reporte("relleno de $FILAS filas por sql: %.1f s".format((System.nanoTime() - t3) / 1e9))
            val sinIndice = listar(objeto)
            db.sql("CREATE INDEX ON $tabla (${columnas["anio"]}, ${columnas["predio_codigo"]})").await()
            db.sql("ANALYZE $tabla").await()
            val conIndice = listar(objeto)
            reporte(
                "listar anio+predio_codigo (60 filas de un predio) sobre ${FILAS + 5000} filas: %.1f ms sin índice, %.1f ms con índice"
                    .format(sinIndice, conIndice)
            )
        }
    }

    @Test
    fun `9_1 unicidad - el segundo create concurrente de la misma clave`() {
        val objeto = objetoCuota()
        val predio = predio()
        val contribuyente = inscribir()
        val atributos = atributos(predio, contribuyente, 1, "misma-clave")
        val pool = Executors.newFixedThreadPool(2)
        val estados =
            listOf(1, 2)
                .map {
                    pool.submit(
                        Callable { exchange("POST", "/api/objects/$objeto/records", mapOf("attributes" to atributos)) }
                    )
                }.map { it.get() }
        pool.shutdown()
        reporte("dos POST concurrentes con la misma clave: ${estados.map { it.first }} ${estados.map { it.second.take(160) }}")
        assertEquals(1, estados.count { it.first == HttpStatus.CREATED })
        conAdmin {
            val error = runCatching { records.create(objeto, RecordRequest(atributos)) }.exceptionOrNull()
            val cadena = generateSequence(error) { it.cause }.map { it.javaClass.name }.toList()
            reporte("en proceso, el duplicado lanza: $cadena")
        }
    }

    @Test
    fun `9_3 transaccion - un rollback exterior deshace los creates`() {
        val objeto = objetoCuota()
        val predio = predio()
        val contribuyente = inscribir()
        val marca = "rollback-${UUID.randomUUID()}"
        conAdmin {
            val operador = TransactionalOperator.create(transacciones)
            val resultado =
                runCatching {
                    operador.executeAndAwait {
                        repeat(3) { records.create(objeto, RecordRequest(atributos(predio, contribuyente, it, "$marca-$it", zona = marca))) }
                        error("falla a mitad de camino")
                    }
                }
            val quedan = records.list(objeto, RecordQuery(page = PageRequest.of(0, 10), filters = mapOf("zona" to marca))).totalElements
            reporte("rollback exterior: ${resultado.exceptionOrNull()?.message}; quedan $quedan de 3 cuotas")
        }
    }

    private fun objetoCuota(): String {
        val nombre = uniqueName("cp")

        fun campo(
            n: String,
            tipo: String,
            unique: Boolean = false
        ) = mapOf("name" to n, "label" to n, "type" to tipo, "unique" to unique)
        post(
            "/api/objects",
            mapOf(
                "name" to nombre,
                "label" to nombre,
                "pluralLabel" to nombre,
                "fields" to
                    listOf(
                        campo("anio", "INTEGER"),
                        campo("periodo", "INTEGER"),
                        campo("servicio", "TEXT"),
                        campo("monto", "DECIMAL"),
                        campo("parametro_aplicado", "TEXT"),
                        campo("zona", "TEXT"),
                        campo("uso_arbitrio", "TEXT"),
                        campo("fecha_calculo", "DATE"),
                        campo("observacion", "TEXT"),
                        campo("predio_codigo", "TEXT"),
                        campo("clave", "TEXT", unique = true)
                    )
            )
        )
        for (destino in listOf("predio", "contribuyente")) {
            post(
                "/api/relationships",
                mapOf(
                    "name" to "${nombre}_$destino".take(35),
                    "label" to destino,
                    "inverseLabel" to nombre,
                    "type" to "MANY_TO_ONE",
                    "source" to nombre,
                    "target" to destino,
                    "fieldName" to destino
                )
            )
        }
        return nombre
    }

    private fun atributos(
        predio: String,
        contribuyente: String,
        i: Int,
        clave: String,
        zona: String = "Z1"
    ) = mapOf(
        "predio" to predio,
        "contribuyente" to contribuyente,
        "anio" to 2026,
        "periodo" to i % 12 + 1,
        "servicio" to "SERV${i % 4}",
        "monto" to "8.50",
        "parametro_aplicado" to "TASA_ARBITRIO:SERV${i % 4}:$zona:CASA",
        "zona" to zona,
        "uso_arbitrio" to "CASA",
        "fecha_calculo" to "2026-03-15",
        "observacion" to "medición del spike",
        "predio_codigo" to "P-${i / 10}",
        "clave" to clave
    )

    private suspend fun crear(
        objeto: String,
        predio: String,
        contribuyente: String,
        i: Int
    ) {
        records.create(objeto, RecordRequest(atributos(predio, contribuyente, i, "c-$i")))
    }

    // the average of 20 lists filtered by anio and one predio_codigo of the sql fill, in ms
    private suspend fun listar(objeto: String): Double {
        val t0 = System.nanoTime()
        repeat(20) {
            records.list(objeto, RecordQuery(page = PageRequest.of(0, 200), filters = mapOf("anio" to "2026", "predio_codigo" to "B-${it * 397}")))
        }
        return (System.nanoTime() - t0) / 1e6 / 20
    }

    private companion object {
        const val FILAS = 500_000
    }

    private suspend fun tablaFisica(
        objeto: String,
        campos: List<String>
    ): Pair<String, Map<String, String>> {
        val tabla =
            db
                .sql("SELECT physical_table FROM ${schemas.metadata}.custom_objects WHERE name = :n")
                .bind("n", objeto)
                .map { r -> r.get(0, String::class.java)!! }
                .awaitSingle()
        val columnas =
            campos.associateWith { campo ->
                db
                    .sql(
                        "SELECT f.column_name FROM ${schemas.metadata}.custom_fields f JOIN ${schemas.metadata}.custom_objects o " +
                            "ON o.id = f.object_id WHERE o.name = :o AND f.name = :f"
                    ).bind("o", objeto)
                    .bind("f", campo)
                    .map { r -> r.get(0, String::class.java)!! }
                    .awaitSingle()
            }
        return schemas.dataTable(tabla) to columnas
    }

    private fun conAdmin(bloque: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking {
            val usuario = UUID.fromString(tree(String(Base64.getUrlDecoder().decode(token.removePrefix("Bearer ").split('.')[1])))["sub"].asString())
            val auth = identidad.de(usuario)!!
            withContext(ReactiveSecurityContextHolder.withAuthentication(auth).asCoroutineContext()) { bloque() }
        }

    private fun reporte(linea: String) = System.err.println("COMPROBACION $linea")
}
