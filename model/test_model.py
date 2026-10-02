"""Tests for model.json and apply.py's pure parts (validate, payload builders).

Run: cd model && python3 -m unittest -v
"""
import copy
import json
import os
import unittest

from apply import enum_option_valid, object_payload, relationship_payload, validate

MODEL_PATH = os.path.join(os.path.dirname(__file__), "model.json")


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
        self.assertEqual(len(names), 28)
        self.assertEqual(len(self.model["relationships"]), 19)

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

    def test_every_enum_option_passes_core_regex(self):
        for name, options in self.model["enums"].items():
            for opt in options:
                self.assertTrue(enum_option_valid(opt), f"{name}: {opt}")

    def test_every_relation_is_required(self):
        self.assertTrue(all(r["required"] for r in self.model["relationships"]))

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
