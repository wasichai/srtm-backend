package srtm.rentas

// the motivo of a contribuyente or a declaración (model.json motivo_declaracion): INSCRIPCION when the portal
// registers it, ACTUALIZACION once the portal edits it, DESCARGO when a declaración is annulled (Anulacion.kt).
// medio_determinacion and modificacion_oficio are the form's (read-only in the portal): a fiscalización would set them

const val ACTUALIZACION = "ACTUALIZACION"

// an edit keeps no history and no new number: it only says the record was updated. a descargo stays one
fun motivoAlEditar(stored: String?): String = if (stored == DESCARGO) stored else ACTUALIZACION
