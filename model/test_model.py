"""Tests for model.json and apply.py's pure parts (validate, payload builders).

Run: cd model && python3 -m unittest -v
"""
import copy
import json
import os
import unittest

from apply import enum_option_valid, object_payload, relationship_payload, validate

MODEL_PATH = os.path.join(os.path.dirname(__file__), "model.json")

# the relations a record may lack (SPEC §4, «opcional»); every other one is required
OPCIONALES = {
    "notif_adm_contribuyente", "notif_adm_predio", "papeleta_contribuyente", "papeleta_predio",
    "papeleta_notificacion_previa", "resolucion_descargo", "notif_resolucion_plazo", "anuncio_predio",
    "movimiento_parametro",
}

# object -> (its fields in order, the required ones, the unique ones)
OBJETOS = {
    "codigo_infraccion": (
        ["familia", "codigo", "descripcion", "materia", "porcentaje_uit", "porcentaje_uit_segunda", "porcentaje_uit_tercera",
         "medida_complementaria", "base_legal", "vigencia_desde", "vigencia_hasta", "observacion", "clave", "clave_vigente"],
        {"familia", "codigo", "descripcion", "porcentaje_uit", "base_legal", "vigencia_desde", "observacion", "clave"},
        {"clave", "clave_vigente"}),
    "notificacion_administrativa": (
        ["numero", "fecha", "direccion", "motivo", "plazo_dias", "observacion"],
        {"numero", "fecha", "direccion", "motivo", "observacion"},
        {"numero"}),
    "subsanacion_notificacion": (["fecha", "observacion", "clave"], {"fecha", "observacion", "clave"}, {"clave"}),
    "papeleta": (
        ["familia", "numero", "clave", "fecha_infraccion", "hora_infraccion", "lugar", "expediente", "inspector",
         "descripcion_hecho", "reincidencia", "medida_complementaria", "base_imponible", "porcentaje_infraccion",
         "importe_infraccion", "porcentaje_a_cobrar", "importe_a_pagar", "importe_con_beneficio", "fecha_calculo", "observacion"],
        {"familia", "numero", "clave", "fecha_infraccion", "lugar", "reincidencia", "base_imponible", "porcentaje_infraccion",
         "importe_infraccion", "porcentaje_a_cobrar", "importe_a_pagar", "fecha_calculo", "observacion"},
        {"clave"}),
    "anulacion_papeleta": (["fecha", "motivo", "observacion", "clave"], {"fecha", "motivo", "observacion", "clave"}, {"clave"}),
    "descargo_papeleta": (
        ["numero_expediente", "tipo_recurso", "fecha", "presentado_hasta", "en_plazo", "plazo_texto", "sustento", "observacion"],
        {"numero_expediente", "tipo_recurso", "fecha", "presentado_hasta", "en_plazo", "plazo_texto", "sustento", "observacion"},
        {"numero_expediente"}),
    "resolucion_gerencia": (
        ["tipo", "anio", "correlativo", "numero", "fecha", "sentido", "efecto", "sancion_accesoria", "sustento", "plazo_texto",
         "clave_ris", "clave_descargo", "observacion"],
        {"tipo", "anio", "correlativo", "numero", "fecha", "sustento", "plazo_texto", "observacion"},
        {"numero", "clave_ris", "clave_descargo"}),
    "notificacion_resolucion": (
        ["intento", "clave", "fecha_diligencia", "modalidad", "resultado", "notificador", "direccion", "receptor",
         "documento_receptor", "vinculo", "acuse", "exigible_desde", "plazo_texto", "observacion"],
        {"intento", "clave", "fecha_diligencia", "modalidad", "resultado", "notificador", "direccion", "observacion"},
        {"clave"}),
    "anuncio": (
        ["anio", "correlativo", "numero", "clase", "tipo", "emplazamiento", "forma", "denominacion", "direccion", "area", "lados",
         "cantidad", "fecha_autorizacion", "vigencia_hasta", "expediente", "fecha_expediente", "licencia_texto",
         "clave_idempotencia", "observacion"],
        {"anio", "correlativo", "numero", "clase", "tipo", "direccion", "area", "lados", "cantidad", "fecha_autorizacion",
         "observacion"},
        {"numero", "clave_idempotencia"}),
    "movimiento_anuncio": (
        ["tipo", "fecha", "anio", "referencia_cargo", "tasa", "vigencia_hasta", "motivo", "clave", "observacion"],
        {"tipo", "fecha", "clave", "observacion"},
        {"referencia_cargo", "clave"}),
}

# object -> {fieldName: (target, required)}
RELACIONES = {
    "codigo_infraccion": {},
    "notificacion_administrativa": {"contribuyente": ("contribuyente", False), "predio": ("predio", False)},
    "subsanacion_notificacion": {"notificacion": ("notificacion_administrativa", True)},
    "papeleta": {"codigo_infraccion": ("codigo_infraccion", True), "uit": ("parametro_tributario", True),
                 "obligado": ("contribuyente", True), "contribuyente": ("contribuyente", False), "predio": ("predio", False),
                 "notificacion_previa": ("notificacion_administrativa", False)},
    "anulacion_papeleta": {"papeleta": ("papeleta", True)},
    "descargo_papeleta": {"papeleta": ("papeleta", True), "plazo": ("parametro_tributario", True)},
    "resolucion_gerencia": {"papeleta": ("papeleta", True), "descargo": ("descargo_papeleta", False),
                            "plazo": ("parametro_tributario", True)},
    "notificacion_resolucion": {"resolucion": ("resolucion_gerencia", True), "plazo": ("parametro_tributario", False)},
    "anuncio": {"contribuyente": ("contribuyente", True), "predio": ("predio", False)},
    "movimiento_anuncio": {"anuncio": ("anuncio", True), "parametro": ("parametro_tributario", False)},
}


def load_model():
    with open(MODEL_PATH, encoding="utf-8") as f:
        return json.load(f)


class ShippedModelTests(unittest.TestCase):
    def setUp(self):
        self.model = load_model()

    def test_validates_clean(self):
        self.assertEqual(validate(self.model), [])

    def test_objects_in_topological_order(self):
        names = [o["name"] for o in self.model["objects"]]
        self.assertEqual(names[:3], ["contribuyente", "predio", "declaracion_predial"])
        self.assertEqual(len(names), 39)
        self.assertEqual(len(self.model["relationships"]), 42)

    def test_the_municipalidad_holds_the_documents_header(self):
        # the PU and HR's header, one record per organization edited in the admin (the escudo is a file, not a field)
        municipalidad = next(o for o in self.model["objects"] if o["name"] == "municipalidad")
        campos = {f["name"]: f for f in municipalidad["fields"]}
        self.assertEqual(list(campos), ["nombre", "oficina", "ruc", "gerencia", "direccion"])
        self.assertEqual([n for n, f in campos.items() if f.get("required")], ["nombre"])
        self.assertTrue(all(f["type"] == "TEXT" for f in campos.values()))

    def test_new_contribuyente_fields_are_optional(self):
        # 11 840 contribuyentes came from the padron without them: a required field would be refused by Core
        # numero_documento neither: SIN DOCUMENTO has none (it stays unique: many nulls are allowed)
        first = {"tipo_persona", "tipo_documento", "nombre_completo"}
        contribuyente = next(o for o in self.model["objects"] if o["name"] == "contribuyente")
        required = {f["name"] for f in contribuyente["fields"] if f.get("required")}
        self.assertEqual(required, first)

    def test_declaracion_estado_is_optional(self):
        # the imported declarations have none: they count as VIGENTE. anular sets the three
        declaracion = next(o for o in self.model["objects"] if o["name"] == "declaracion_predial")
        fields = {f["name"]: f for f in declaracion["fields"]}
        self.assertEqual(fields["estado"]["enum"], "estado_declaracion")
        self.assertEqual(self.model["enums"]["estado_declaracion"], ["VIGENTE", "ANULADA"])
        self.assertEqual(fields["fecha_anulacion"]["type"], "DATE")
        self.assertIn("DESCARGO", self.model["enums"]["motivo_declaracion"])
        self.assertFalse(any(fields[f].get("required") for f in ("estado", "motivo_anulacion", "fecha_anulacion")))

    def test_parametro_tributario_has_the_columns_of_the_published_csv(self):
        # normativa's publicacion/parametros-2026.csv, by the names of the issue. the natural key is (tipo, clave,
        # vigencia_desde), which import_parametros.py keeps: clave is empty for a tipo of one value (the UIT)
        parametro = next(o for o in self.model["objects"] if o["name"] == "parametro_tributario")
        fields = {f["name"]: f for f in parametro["fields"]}
        self.assertEqual(list(fields), ["tipo", "clave", "vigencia_desde", "vigencia_hasta", "valor_numerico", "texto", "norma",
                                        "fuente", "transcribio", "verifico"])
        self.assertEqual({n for n, f in fields.items() if f.get("required")}, {"tipo", "vigencia_desde", "transcribio", "verifico"})
        self.assertEqual(fields["vigencia_desde"]["type"], "DATE")
        self.assertEqual(fields["vigencia_hasta"]["type"], "DATE")
        self.assertEqual(fields["valor_numerico"]["type"], "DECIMAL")
        self.assertFalse(any(f.get("unique") for f in fields.values()))

    def test_emision_masiva_is_the_job_of_the_contract(self):
        # wasichai/srtm-backend#41: the masiva's job, with the fields of the epic's contract (wasichai/srtm-backend#37).
        # errores is a json list [{contribuyente, mensaje}]; archivo the file's name, its key in the almacén is
        # emision-<id>/emision-<anio>-<id>.<ext>. latido is the lease of the instance preparing or assembling it
        # (wasichai/srtm-backend#54)
        emision = next(o for o in self.model["objects"] if o["name"] == "emision_masiva")
        fields = {f["name"]: f for f in emision["fields"]}
        self.assertEqual(list(fields), ["anio", "formato", "estado", "total", "procesados", "errores", "archivo", "tamano",
                                        "mensaje", "iniciado", "terminado", "latido", "documentos"])
        types = {n: f["type"] for n, f in fields.items()}
        self.assertEqual(types, {"anio": "INTEGER", "formato": "ENUM", "estado": "ENUM", "total": "INTEGER",
                                 "procesados": "INTEGER", "errores": "LONG_TEXT", "archivo": "TEXT", "tamano": "INTEGER",
                                 "mensaje": "LONG_TEXT", "iniciado": "DATETIME", "terminado": "DATETIME",
                                 "latido": "DATETIME", "documentos": "LONG_TEXT"})
        self.assertEqual(self.model["enums"][fields["formato"]["enum"]], ["PDF", "ZIP"])
        self.assertEqual(self.model["enums"][fields["estado"]["enum"]],
                         ["PENDIENTE", "EN_PROCESO", "ENSAMBLANDO", "TERMINADA", "FALLIDA"])
        self.assertEqual({n for n, f in fields.items() if f.get("required")}, {"anio", "formato", "estado"})

    def test_emision_lote_is_a_batch_of_an_emision(self):
        # wasichai/srtm-backend#53: the padron of an emission cut in lotes that any instance's workers take.
        # contribuyentes is the json of the lote's contribuyentes; errores a json list [{contribuyente, mensaje}];
        # parte its file's key in the almacén
        lote = next(o for o in self.model["objects"] if o["name"] == "emision_lote")
        names = [o["name"] for o in self.model["objects"]]
        self.assertEqual(names.index("emision_lote"), names.index("emision_masiva") + 1)
        fields = {f["name"]: f for f in lote["fields"]}
        self.assertEqual(list(fields), ["numero", "contribuyentes", "estado", "tomado_por", "latido", "intentos", "procesados",
                                        "documentos", "errores", "parte"])
        types = {n: f["type"] for n, f in fields.items()}
        self.assertEqual(types, {"numero": "INTEGER", "contribuyentes": "LONG_TEXT", "estado": "ENUM", "tomado_por": "TEXT",
                                 "latido": "DATETIME", "intentos": "INTEGER", "procesados": "INTEGER",
                                 "documentos": "INTEGER", "errores": "LONG_TEXT", "parte": "TEXT"})
        self.assertEqual({n for n, f in fields.items() if f.get("required")}, {"numero", "contribuyentes", "estado"})
        self.assertEqual(self.model["enums"][fields["estado"]["enum"]], ["PENDIENTE", "EN_PROCESO", "TERMINADO", "FALLIDO"])
        relationship = next(r for r in self.model["relationships"] if r["source"] == "emision_lote")
        self.assertEqual(relationship, {"name": "emision_lote_emision", "label": "Emisión", "inverseLabel": "Lotes",
                                        "source": "emision_lote", "target": "emision_masiva", "fieldName": "emision",
                                        "required": True})

    def test_the_arbitrios_objects_carry_rentas_guarantees(self):
        # determinacion_arbitrio of rentas, carried over: one cuota per predio, servicio, year and month (the unique
        # clave), every relation required, the amount, period and key required. periodo 1..12, monto >= 0 and the
        # 120 characters of parametro_aplicado are kotlin's; immutability is the RecordStore's (README, Arbitrios)
        objetos = {o["name"]: o for o in self.model["objects"]}
        cuota = {f["name"]: f for f in objetos["cuota_arbitrio"]["fields"]}
        self.assertEqual(list(cuota), ["anio", "periodo", "monto", "parametro_aplicado", "zona", "uso_arbitrio", "fecha_calculo",
                                       "observacion", "clave"])
        self.assertEqual({n for n, f in cuota.items() if f.get("required")},
                         {"anio", "periodo", "monto", "parametro_aplicado", "fecha_calculo", "observacion", "clave"})
        self.assertEqual({n for n, f in cuota.items() if f.get("unique")}, {"clave"})
        self.assertEqual(cuota["monto"]["type"], "DECIMAL")
        relaciones = {(r["source"], r["fieldName"]): r for r in self.model["relationships"]}
        for campo, destino in [("predio", "predio"), ("contribuyente", "contribuyente"), ("servicio", "servicio_arbitrio"),
                               ("parametro", "parametro_tributario")]:
            relacion = relaciones[("cuota_arbitrio", campo)]
            self.assertEqual((relacion["target"], relacion["required"]), (destino, True), campo)
        ordenanza = {f["name"]: f for f in objetos["ordenanza_arbitrio"]["fields"]}
        self.assertTrue(ordenanza["anio"]["unique"])
        servicio = {f["name"]: f for f in objetos["servicio_arbitrio"]["fields"]}
        self.assertTrue(servicio["codigo"]["unique"])
        self.assertEqual(relaciones[("servicio_arbitrio", "ordenanza")]["target"], "ordenanza_arbitrio")
        self.assertEqual(relaciones[("inafectacion_arbitrio", "servicio")]["target"], "servicio_arbitrio")
        self.assertEqual(relaciones[("inafectacion_arbitrio", "predio")]["target"], "predio")

    def test_an_anulacion_is_added_once_per_cuota(self):
        # decision 5 of the plan: a cuota is corrected by an anulación added to it, never by an update; its clave (the
        # cuota's id) is unique, so a cuota is annulled once
        objetos = {o["name"]: o for o in self.model["objects"]}
        fields = {f["name"]: f for f in objetos["anulacion_cuota_arbitrio"]["fields"]}
        self.assertEqual({n for n, f in fields.items() if f.get("required")}, {"anio", "motivo", "observacion", "fecha", "clave"})
        self.assertEqual({n for n, f in fields.items() if f.get("unique")}, {"clave"})
        relaciones = {r["fieldName"]: r["target"] for r in self.model["relationships"] if r["source"] == "anulacion_cuota_arbitrio"}
        self.assertEqual(relaciones, {"cuota": "cuota_arbitrio", "predio": "predio"})

    def test_the_masiva_de_arbitrios_is_a_twin_of_the_emision(self):
        # the same lotes, lease and attempts as emision_masiva / emision_lote; it writes cuotas, never a file
        objetos = {o["name"]: o for o in self.model["objects"]}
        masiva = [f["name"] for f in objetos["determinacion_arbitrio_masiva"]["fields"]]
        lote = [f["name"] for f in objetos["determinacion_arbitrio_lote"]["fields"]]
        self.assertEqual(masiva, ["anio", "estado", "total", "procesados", "generadas", "errores", "mensaje", "observacion", "iniciado",
                                  "terminado", "latido"])
        self.assertEqual(lote, ["numero", "predios", "estado", "tomado_por", "latido", "intentos", "procesados", "generadas", "errores"])
        self.assertNotIn("archivo", masiva)
        relacion = next(r for r in self.model["relationships"] if r["source"] == "determinacion_arbitrio_lote")
        self.assertEqual((relacion["target"], relacion["fieldName"], relacion["required"]),
                         ("determinacion_arbitrio_masiva", "determinacion", True))

    def campos(self, objeto):
        return {f["name"]: f for f in next(o for o in self.model["objects"] if o["name"] == objeto)["fields"]}

    def relaciones(self, objeto):
        return {r["fieldName"]: (r["target"], r["required"]) for r in self.model["relationships"] if r["source"] == objeto}

    def test_the_sanciones_and_anuncios_objects_in_order(self):
        # after the arbitrios, each one after the objects it points at
        names = [o["name"] for o in self.model["objects"]]
        self.assertEqual(names[29:], list(OBJETOS))

    def test_each_new_object_has_its_fields_required_and_unique(self):
        # SPEC §4, field by field: the order, what is required and what is unique. the lengths and ranges are kotlin's
        # (ReglaDeEscritura): core's TEXT has no length
        for objeto, (orden, requeridos, unicos) in OBJETOS.items():
            with self.subTest(objeto=objeto):
                campos = self.campos(objeto)
                self.assertEqual(list(campos), orden)
                self.assertEqual({n for n, f in campos.items() if f.get("required")}, requeridos)
                self.assertEqual({n for n, f in campos.items() if f.get("unique")}, unicos)
                self.assertTrue(campos["observacion"]["required"])

    def test_each_new_object_points_at_what_it_read(self):
        # the relation keeps the row that was read (a version of the CUIS, a parametro_tributario): its values are
        # copied on the record too
        for objeto, relaciones in RELACIONES.items():
            with self.subTest(objeto=objeto):
                self.assertEqual(self.relaciones(objeto), relaciones)

    def test_the_new_types(self):
        cuis = self.campos("codigo_infraccion")
        self.assertEqual({n: f["type"] for n, f in cuis.items() if f["type"] != "TEXT"},
                         {"familia": "ENUM", "descripcion": "LONG_TEXT", "porcentaje_uit": "DECIMAL",
                          "porcentaje_uit_segunda": "DECIMAL", "porcentaje_uit_tercera": "DECIMAL",
                          "vigencia_desde": "DATE", "vigencia_hasta": "DATE"})
        papeleta = self.campos("papeleta")
        dinero = ["base_imponible", "porcentaje_infraccion", "importe_infraccion", "porcentaje_a_cobrar", "importe_a_pagar",
                  "importe_con_beneficio"]
        self.assertTrue(all(papeleta[c]["type"] == "DECIMAL" for c in dinero))
        self.assertEqual(self.campos("descargo_papeleta")["en_plazo"]["type"], "BOOLEAN")
        self.assertEqual((self.campos("anuncio")["area"]["type"], self.campos("movimiento_anuncio")["tasa"]["type"]),
                         ("DECIMAL", "DECIMAL"))
        enums = {(o["name"], f["name"]): f["enum"] for o in self.model["objects"][29:] for f in o["fields"] if f["type"] == "ENUM"}
        self.assertEqual(enums, {
            ("codigo_infraccion", "familia"): "familia_infraccion", ("papeleta", "familia"): "familia_infraccion",
            ("papeleta", "reincidencia"): "grado_reincidencia", ("descargo_papeleta", "tipo_recurso"): "tipo_recurso",
            ("resolucion_gerencia", "tipo"): "tipo_resolucion_gerencia", ("resolucion_gerencia", "sentido"): "sentido_fallo",
            ("resolucion_gerencia", "efecto"): "efecto_multa", ("notificacion_resolucion", "modalidad"): "modalidad_notificacion",
            ("notificacion_resolucion", "resultado"): "resultado_notificacion", ("anuncio", "clase"): "clase_anuncio",
            ("anuncio", "tipo"): "tipo_anuncio", ("movimiento_anuncio", "tipo"): "tipo_movimiento_anuncio",
        })

    def test_the_new_enums(self):
        enums = self.model["enums"]
        self.assertEqual(enums["familia_infraccion"], ["ADMINISTRATIVA"])
        self.assertEqual(enums["grado_reincidencia"], ["PRIMERA", "SEGUNDA", "TERCERA_O_MAS"])
        self.assertEqual(enums["tipo_recurso"], ["DESCARGO", "RECONSIDERACION", "APELACION", "NULIDAD"])
        self.assertEqual(enums["tipo_resolucion_gerencia"], ["ADMINISTRATIVA", "RECURSO"])
        self.assertEqual(enums["sentido_fallo"], ["FUNDADO", "FUNDADO_EN_PARTE", "INFUNDADO", "IMPROCEDENTE"])
        self.assertEqual(enums["efecto_multa"], ["SE_MANTIENE", "SE_DEJA_SIN_EFECTO", "SE_REDUCE"])
        self.assertEqual(enums["modalidad_notificacion"], ["PERSONAL", "CEDULON", "PUBLICACION", "CORREO"])
        self.assertEqual(enums["resultado_notificacion"], ["NOTIFICADO", "NO_UBICADO", "RECHAZADO"])
        self.assertEqual(enums["clase_anuncio"], ["LETRERO", "PANEL", "TOLDO", "BANDEROLA", "PANTALLA_DIGITAL", "GLOBO_AEROSTATICO"])
        self.assertEqual(enums["tipo_anuncio"], ["AVISO_SIMPLE", "AVISO_LUMINOSO", "AVISO_ILUMINADO", "AVISO_ELECTRONICO"])
        self.assertEqual(enums["tipo_movimiento_anuncio"], ["AUTORIZACION", "RENOVACION", "CESE", "RETIRO"])

    def test_every_enum_option_passes_core_regex(self):
        for name, options in self.model["enums"].items():
            for opt in options:
                self.assertTrue(enum_option_valid(opt), f"{name}: {opt}")

    def test_only_the_listed_relations_are_optional(self):
        # a relation is required unless a record can lack it: a notificación or an acta names a contribuyente or a
        # predio (the acta, at least one: kotlin's rule), a resolución has a descargo only when it resolves one, a
        # notificación de resolución reads a plazo only when it takes effect, a movimiento reads a tasa only when it
        # accrues. a new optional relation is added here on purpose
        opcionales = {r["name"] for r in self.model["relationships"] if not r["required"]}
        self.assertEqual(opcionales, OPCIONALES)
        self.assertTrue(all(r["required"] is True for r in self.model["relationships"] if r["name"] not in OPCIONALES))

    def test_business_keys_are_unique(self):
        fields = {o["name"]: {f["name"]: f for f in o["fields"]} for o in self.model["objects"]}
        self.assertTrue(fields["contribuyente"]["numero_documento"]["unique"])
        self.assertTrue(fields["predio"]["codigo"]["unique"])

    def test_geometries_are_the_lotes_and_the_domicilio_point(self):
        geometries = {(o["name"], f["name"]): (f["geometryType"], f["srid"])
                      for o in self.model["objects"] for f in o["fields"] if f["type"] == "GEOMETRY"}
        self.assertEqual(geometries, {
            ("predio", "lote_geom"): ("POLYGON", 32718),
            ("catastro_fiscal", "lote_geom"): ("POLYGON", 32718),
            ("domicilio", "ubicacion"): ("POINT", 4326),
        })

    def test_geometry_payload_carries_its_shape(self):
        obj = next(o for o in self.model["objects"] if o["name"] == "catastro_fiscal")
        lote = next(f for f in object_payload(self.model, obj)["fields"] if f["name"] == "lote_geom")
        self.assertEqual((lote["type"], lote["geometryType"], lote["srid"], lote["dimension"]), ("GEOMETRY", "POLYGON", 32718, 2))

    def test_a_bad_geometry_is_refused(self):
        model = copy.deepcopy(self.model)
        obj = next(o for o in model["objects"] if o["name"] == "catastro_fiscal")
        lote = next(f for f in obj["fields"] if f["name"] == "lote_geom")
        lote["geometryType"] = "CIRCLE"
        lote["srid"] = 0
        errors = validate(model)
        self.assertTrue(any("geometryType" in e for e in errors), errors)
        self.assertTrue(any("srid" in e for e in errors), errors)


class PayloadTests(unittest.TestCase):
    def setUp(self):
        self.model = load_model()

    def test_enum_field_carries_its_options(self):
        obj = next(o for o in self.model["objects"] if o["name"] == "contribuyente")
        payload = object_payload(self.model, obj)
        tipo = next(f for f in payload["fields"] if f["name"] == "tipo_documento")
        self.assertEqual(tipo["enumOptions"], ["SIN DOCUMENTO", "DNI", "CARNET DE EXTRANJERIA", "RUC", "PASAPORTE", "PTP-CPP",
                                              "CI", "OTROS"])
        self.assertTrue(tipo["required"])
        self.assertNotIn("enum", tipo)
        self.assertNotIn("source", tipo)

    def test_relationship_payload_is_many_to_one(self):
        rel = self.model["relationships"][0]
        self.assertEqual(relationship_payload(rel), {
            "name": "declaracion_predial_contribuyente",
            "label": "Contribuyente",
            "inverseLabel": "Declaraciones prediales",
            "type": "MANY_TO_ONE",
            "source": "declaracion_predial",
            "target": "contribuyente",
            "fieldName": "contribuyente",
        })


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.model = load_model()

    def mutate(self, fn):
        model = copy.deepcopy(self.model)
        fn(model)
        return validate(model)

    def test_option_with_comma_is_refused(self):
        errors = self.mutate(lambda m: m["enums"]["clasificacion"].append("TIENDAS,DEPOSITOS"))
        self.assertTrue(any("TIENDAS,DEPOSITOS" in e for e in errors))

    def test_option_over_64_chars_is_refused(self):
        errors = self.mutate(lambda m: m["enums"]["uso"].append("X" * 65))
        self.assertTrue(any("invalid characters or length" in e for e in errors))

    def test_geometry_without_its_shape_is_refused(self):
        errors = self.mutate(lambda m: m["objects"][1]["fields"].append({"name": "geom", "label": "G", "type": "GEOMETRY"}))
        self.assertTrue(any("geometryType must be one of" in e for e in errors))

    def test_target_after_source_is_refused(self):
        errors = self.mutate(lambda m: m["objects"].reverse())
        self.assertTrue(any("must appear before source" in e for e in errors))

    def test_reserved_and_keyword_names_are_refused(self):
        errors = self.mutate(lambda m: m["objects"][0]["fields"].extend([
            {"name": "version", "label": "V", "type": "TEXT"},
            {"name": "order", "label": "O", "type": "TEXT"},
        ]))
        self.assertTrue(any("reserved by Core" in e for e in errors))
        self.assertTrue(any("reserved SQL keyword" in e for e in errors))

    def test_unknown_enum_is_refused(self):
        errors = self.mutate(lambda m: m["objects"][0]["fields"].append({"name": "x", "label": "X", "type": "ENUM", "enum": "nope"}))
        self.assertTrue(any("enum 'nope' is not defined" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
