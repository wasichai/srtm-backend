package srtm.normativa

import org.springframework.stereotype.Component
import srtm.Violaciones
import srtm.leerRegistro
import srtm.normativa.CamposArancel.CLAVE
import srtm.normativa.CamposArancel.ID_ARANC
import srtm.normativa.CamposArancel.SINCRONIZACION
import srtm.normativa.CamposArancel.VALOR_M2

// the double signature of an arancel (#188, ReglaDeDobleFirma), whoever writes it: its clave is id_aranc|anio and its
// valor_m2 is not negative (a 0 is loaded as it is, as catastro does). a VIGENTE one is never edited (an arancel is of
// one year: nothing to close). the sincronización with catastro (sincronizado_en, error_sincronizacion) changes in any
// estado, alone or with a transition, but a new one is not born synchronized
@Component
class ReglaDeArancel : ReglaDeDobleFirma("arancel", "aranceles", SINCRONIZACION) {
    override val objetos = setOf(ARANCEL)

    override fun datos(
        attrs: Map<String, Any?>,
        v: Violaciones,
        alta: Boolean
    ) {
        val a = leerRegistro(Arancel::class.java, attrs)
        val id = v.requerido(ID_ARANC, a.idAranc)
        val anio = v.requerido(CamposArancel.ANIO, a.anio)
        if (v.requerido(VALOR_M2, a.valorM2) && a.valorM2!!.signum() < 0) v.mal(VALOR_M2, "no es negativo")
        if (v.requerido(CLAVE, a.clave) && id && anio) v.clave(CLAVE, a.clave, claveDeArancel(a.idAranc, a.anio), "id_aranc|anio")
        if (alta) SINCRONIZACION.filter { lleno(attrs[it]) }.forEach { v.mal(it, "va vacío al registrar: lo escribe la copia a catastro") }
    }
}
