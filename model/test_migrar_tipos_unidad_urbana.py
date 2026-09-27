"""Tests for migrar_tipos_unidad_urbana.py: the options TIPO_UU does not have (ANEXO, HABILITACION URBANA, OTROS…) moved
to its types in the predios, unidades urbanas, domicilios and lotes of the catastro, a unidad urbana that would repeat
another deleted, the report, an idempotent run against a fake Core, and the order the README gives with apply.py
(wasichai/srtm-backend#34).

Run: cd model && python3 -m unittest -v test_migrar_tipos_unidad_urbana
"""
import csv
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import migrar_tipos_unidad_urbana as mtu
from fake_core import FakeCore
from test_apply import ApplyCliTestCase, core_fields, load_model

OPCIONES = set(load_model()["enums"]["tipo_unidad_urbana"])
# the list before TIPO_UU: the 15 options it keeps, then the five it leaves
ANTES = ["AGRUPACION", "ANEXO", "ASENTAMIENTO HUMANO", "ASOCIACION", "ASOCIACION DE VIVIENDA",
         "ASOCIACION DE VIVIENDA DE INTERES SOCIAL", "ASOCIACION DE VIVIENDA E INTERES SOCIAL", "CASERIO", "CENTRO POBLADO",
         "CERCADO", "COMUNIDAD CAMPESINA", "COMUNIDAD NATIVA", "COOPERATIVA", "HABILITACION URBANA", "LOTIZACION",
         "PUEBLO JOVEN", "SECTOR", "URBANIZACION", "ZONA", "OTROS"]


def record(record_id, **attributes):
    return {"id": record_id, "attributes": attributes}


class TipoSrtmTests(unittest.TestCase):
    def test_the_padrons_words_take_the_srtms_type(self):
        casos = {
            ("ANEXO", "CENTRO POBLADO MIRICHARO"): ("CENTRO POBLADO", "MIRICHARO"),
            ("ANEXO", "ALTO YURINAKI URBANO"): ("CENTRO POBLADO", "ALTO YURINAKI URBANO"),
            ("HABILITACION URBANA", "SECTOR 10 DE OCTUBRE"): ("SECTOR", "10 DE OCTUBRE"),
            ("HABILITACION URBANA", "RESIDENCIAL IPANEMA"): ("RESIDENCIAL", "IPANEMA"),
            ("HABILITACION URBANA", "LA LUZ DEL VALLE DE PICHANAKI"): ("URBANIZACION", "LA LUZ DEL VALLE DE PICHANAKI"),
            ("HABILITACION URBANA", "10 DE OCTUBRE"): ("URBANIZACION", "10 DE OCTUBRE"),
            ("HABILITACION URBANA", "LOS COCOS"): ("URBANIZACION", "LOS COCOS"),
            ("OTROS", "CENTRO URBANO INFORMAL VISTA ALEGRE"): ("POSESION INFORMAL", "VISTA ALEGRE"),
            # a word alone still says its type
            ("ANEXO", None): ("CENTRO POBLADO", None),
        }
        for (tipo, nombre), esperado in casos.items():
            with self.subTest(tipo=tipo, nombre=nombre):
                self.assertEqual(mtu.tipo_srtm(tipo, nombre), esperado)

    def test_what_has_no_type_of_the_srtm_stays(self):
        self.assertIsNone(mtu.tipo_srtm("OTROS", "VILLA SOL"))
        self.assertIsNone(mtu.tipo_srtm("OTROS", None))
        self.assertIsNone(mtu.tipo_srtm("COMUNIDAD NATIVA", "HUACAMAYO"))


class PlanTests(unittest.TestCase):
    def plan(self, **records):
        return mtu.plan({objeto: records.get(objeto, []) for objeto in mtu.CAMPOS}, OPCIONES)

    def test_moves_every_object_keeping_every_other_field(self):
        updates, borrados, rows = self.plan(
            predio=[
                record("p1", codigo="01-01-0001", tipo_zona="ANEXO", habilitacion_urbana="CENTRO POBLADO MIRICHARO", manzana="A"),
                record("p2", codigo="01-01-0002", tipo_zona="CERCADO", habilitacion_urbana="II MESETA"),
                record("p3", codigo="01-01-0003", via="JIRON LIMA"),
            ],
            unidad_urbana=[record("u1", tipo_unidad_urbana="HABILITACION URBANA", nombre="SECTOR 10 DE OCTUBRE", ubigeo="120302")],
            domicilio=[record("d1", codigo="000001", tipo_unidad_urbana="OTROS", unidad_urbana="CENTRO URBANO INFORMAL VISTA ALEGRE",
                              contribuyente="c1")],
            catastro_fiscal=[record("l1", codigo_cpu="1203020001", tipo_zona="ANEXO", zona="CENTRO POBLADO IPANEMA")],
        )
        self.assertEqual([(u.object_name, u.record_id, u.attributes) for u in updates], [
            ("predio", "p1", {"codigo": "01-01-0001", "tipo_zona": "CENTRO POBLADO", "habilitacion_urbana": "MIRICHARO", "manzana": "A"}),
            ("unidad_urbana", "u1", {"tipo_unidad_urbana": "SECTOR", "nombre": "10 DE OCTUBRE", "ubigeo": "120302"}),
            ("domicilio", "d1", {"codigo": "000001", "tipo_unidad_urbana": "POSESION INFORMAL", "unidad_urbana": "VISTA ALEGRE",
                                 "contribuyente": "c1"}),
            ("catastro_fiscal", "l1", {"codigo_cpu": "1203020001", "tipo_zona": "CENTRO POBLADO", "zona": "IPANEMA"}),
        ])
        self.assertEqual(borrados, [])
        self.assertEqual([(r["objeto"], r["clave"], r["accion"], r["tipo_antes"], r["nombre_antes"], r["tipo_despues"],
                           r["nombre_despues"], r["nota"]) for r in rows], [
            ("predio", "01-01-0001", "migrado", "ANEXO", "CENTRO POBLADO MIRICHARO", "CENTRO POBLADO", "MIRICHARO", ""),
            ("unidad_urbana", "120302", "migrado", "HABILITACION URBANA", "SECTOR 10 DE OCTUBRE", "SECTOR", "10 DE OCTUBRE", ""),
            ("domicilio", "000001", "migrado", "OTROS", "CENTRO URBANO INFORMAL VISTA ALEGRE", "POSESION INFORMAL", "VISTA ALEGRE", ""),
            ("catastro_fiscal", "1203020001", "migrado", "ANEXO", "CENTRO POBLADO IPANEMA", "CENTRO POBLADO", "IPANEMA", ""),
        ])

    def test_no_type_of_the_srtm_is_reported_and_left(self):
        updates, borrados, rows = self.plan(
            predio=[record("p1", codigo="01-01-0001", tipo_zona="OTROS", habilitacion_urbana="VILLA SOL")],
            unidad_urbana=[record("u1", tipo_unidad_urbana="COMUNIDAD NATIVA", nombre="HUACAMAYO", ubigeo="120302")],
        )
        self.assertEqual((updates, borrados), ([], []))
        self.assertEqual([(r["id"], r["accion"], r["tipo_antes"], r["tipo_despues"]) for r in rows],
                         [("p1", "sin cambio", "OTROS", ""), ("u1", "sin cambio", "COMUNIDAD NATIVA", "")])
        self.assertTrue(all(r["nota"].startswith("sin tipo del srtm: no se cambia") for r in rows))

    def test_a_unidad_urbana_that_would_repeat_another_is_deleted_and_the_other_stays(self):
        updates, borrados, rows = self.plan(unidad_urbana=[
            record("u1", tipo_unidad_urbana="CENTRO POBLADO", nombre="MIRICHARO", ubigeo="120302"),
            record("u2", tipo_unidad_urbana="ANEXO", nombre="CENTRO POBLADO MIRICHARO", ubigeo="120302"),
            # another district's is another unidad
            record("u3", tipo_unidad_urbana="ANEXO", nombre="CENTRO POBLADO MIRICHARO", ubigeo="120301"),
            # two that become the same: the first moves, the second goes
            record("u4", tipo_unidad_urbana="HABILITACION URBANA", nombre="10 DE OCTUBRE", ubigeo="120302"),
            record("u5", tipo_unidad_urbana="OTROS", nombre="URBANIZACION 10 DE OCTUBRE", ubigeo="120302"),
        ])
        self.assertEqual([u.record_id for u in updates], ["u3", "u4"])
        self.assertEqual([(b.object_name, b.record_id, b.queda_id) for b in borrados], [("unidad_urbana", "u2", "u1"), ("unidad_urbana", "u5", "u4")])
        self.assertEqual([(r["id"], r["accion"], r["queda_id"], r["tipo_despues"], r["nombre_despues"], r["nota"]) for r in rows], [
            ("u2", "borrado duplicado", "u1", "CENTRO POBLADO", "MIRICHARO", ""),
            ("u3", "migrado", "", "CENTRO POBLADO", "MIRICHARO", ""),
            ("u4", "migrado", "", "URBANIZACION", "10 DE OCTUBRE", ""),
            ("u5", "borrado duplicado", "u4", "URBANIZACION", "10 DE OCTUBRE", ""),
        ])

    def test_a_second_run_changes_nothing(self):
        unidades = [
            record("u1", tipo_unidad_urbana="CENTRO POBLADO", nombre="MIRICHARO", ubigeo="120302"),
            record("u2", tipo_unidad_urbana="ANEXO", nombre="CENTRO POBLADO MIRICHARO", ubigeo="120302"),
            record("u3", tipo_unidad_urbana="ANEXO", nombre="CENTRO POBLADO IPANEMA", ubigeo="120302"),
        ]
        updates, borrados, _ = self.plan(
            predio=[record("p1", tipo_zona="ANEXO", habilitacion_urbana="CENTRO POBLADO MIRICHARO")], unidad_urbana=unidades,
        )
        self.assertEqual((len(updates), len(borrados)), (2, 1))
        despues = {"predio": [], "unidad_urbana": [u for u in unidades if u["id"] not in {b.record_id for b in borrados}]}
        for u in updates:
            despues[u.object_name] = [r for r in despues[u.object_name] if r["id"] != u.record_id] + [record(u.record_id, **u.attributes)]
        self.assertEqual(mtu.plan({objeto: despues.get(objeto, []) for objeto in mtu.CAMPOS}, OPCIONES), ([], [], []))


class MigrarCliTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.anexos = [self.core.add_record("predio", {"codigo": f"01-01-000{i}", "tipo_zona": "ANEXO",
                                                       "habilitacion_urbana": "CENTRO POBLADO MIRICHARO"}) for i in (1, 2)]
        self.habilitacion = self.core.add_record("predio", {"codigo": "02-01-0001", "tipo_zona": "HABILITACION URBANA",
                                                            "habilitacion_urbana": "RESIDENCIAL IPANEMA"})
        self.cercado = self.core.add_record("predio", {"codigo": "03-01-0001", "tipo_zona": "CERCADO", "habilitacion_urbana": "II MESETA"})
        self.unidad = self.core.add_record("unidad_urbana", {"tipo_unidad_urbana": "ANEXO", "nombre": "CENTRO POBLADO MIRICHARO",
                                                             "ubigeo": "120302"})
        # IPANEMA is already there as a CENTRO POBLADO: the ANEXO one repeats it
        self.ipanema = self.core.add_record("unidad_urbana", {"tipo_unidad_urbana": "CENTRO POBLADO", "nombre": "IPANEMA", "ubigeo": "120302"})
        self.repetida = self.core.add_record("unidad_urbana", {"tipo_unidad_urbana": "ANEXO", "nombre": "CENTRO POBLADO IPANEMA",
                                                               "ubigeo": "120302"})
        self.otros = self.core.add_record("unidad_urbana", {"tipo_unidad_urbana": "OTROS", "nombre": "VILLA SOL", "ubigeo": "120302"})
        self.tmp = tempfile.TemporaryDirectory()
        self.report = os.path.join(self.tmp.name, "reports", "migrar_tipos_unidad_urbana.csv")

    def tearDown(self):
        self.core.stop()
        self.tmp.cleanup()

    def run_main(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = mtu.main(["--core", self.core.base_url, "--report", self.report, "--workers", "2", *extra])
        return code, out.getvalue(), err.getvalue()

    def puts(self):
        return [r for r in self.core.requests if r[0] == "PUT"]

    def deletes(self):
        return [r[1] for r in self.core.requests if r[0] == "DELETE"]

    def test_dry_run_counts_each_option_and_changes_nothing(self):
        code, out, err = self.run_main("--dry-run")
        self.assertEqual(code, 0, msg=err)
        self.assertEqual((self.puts(), self.deletes()), ([], []))
        self.assertIn("predio: 4 registros, 3 por migrar\n", out)
        self.assertIn("  ANEXO -> CENTRO POBLADO: 2", out)
        self.assertIn("  HABILITACION URBANA -> RESIDENCIAL: 1", out)
        self.assertIn(
            "unidad_urbana: 4 registros, 1 por migrar, 1 por borrar\n"
            "  ANEXO -> CENTRO POBLADO: 1\n"
            "  ANEXO -> CENTRO POBLADO, ya existía (se borra): 1\n"
            "  no se cambian (ver el reporte): 1\n",
            out,
        )
        self.assertIn("domicilio: 0 registros, 0 por migrar", out)
        self.assertIn("catastro_fiscal: 0 registros, 0 por migrar", out)
        self.assertIn(f"notas: 1 -> {self.report}", out)
        with open(self.report, encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
        self.assertEqual(len(rows), 6)
        [nota] = [r for r in rows if r["nota"]]
        self.assertEqual((nota["id"], nota["accion"], nota["tipo_antes"], nota["nombre_antes"]), (self.otros["id"], "sin cambio", "OTROS", "VILLA SOL"))
        [borrado] = [r for r in rows if r["accion"] == "borrado duplicado"]
        self.assertEqual((borrado["id"], borrado["queda_id"]), (self.repetida["id"], self.ipanema["id"]))
        self.assertEqual(self.anexos[0]["attributes"]["tipo_zona"], "ANEXO")
        self.assertEqual(len(self.core.records["unidad_urbana"]), 4)

    def test_migrates_then_is_idempotent(self):
        code, out, err = self.run_main()
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(sorted(p[1] for p in self.puts()), sorted(
            [f"/api/objects/predio/records/{r['id']}" for r in [*self.anexos, self.habilitacion]]
            + [f"/api/objects/unidad_urbana/records/{self.unidad['id']}"]
        ))
        self.assertEqual(self.anexos[0]["attributes"],
                         {"codigo": "01-01-0001", "tipo_zona": "CENTRO POBLADO", "habilitacion_urbana": "MIRICHARO"})
        self.assertEqual((self.habilitacion["attributes"]["tipo_zona"], self.habilitacion["attributes"]["habilitacion_urbana"]),
                         ("RESIDENCIAL", "IPANEMA"))
        self.assertEqual(self.unidad["attributes"], {"tipo_unidad_urbana": "CENTRO POBLADO", "nombre": "MIRICHARO", "ubigeo": "120302"})
        self.assertEqual(self.otros["attributes"]["tipo_unidad_urbana"], "OTROS")
        # the repeated one goes, the one that was there stays
        self.assertEqual(self.deletes(), [f"/api/objects/unidad_urbana/records/{self.repetida['id']}"])
        self.assertEqual([r["id"] for r in self.core.records["unidad_urbana"]], [self.unidad["id"], self.ipanema["id"], self.otros["id"]])
        self.assertIn("done: 4 updated, 1 deleted", out)

        self.core.requests.clear()
        code, out, _ = self.run_main()
        self.assertEqual(code, 0)
        self.assertEqual((self.puts(), self.deletes()), ([], []))
        self.assertIn("predio: 4 registros, 0 por migrar", out)
        self.assertIn("unidad_urbana: 3 registros, 0 por migrar\n", out)
        self.assertIn("done: 0 updated, 0 deleted", out)

    def test_a_refusal_stops_with_the_record(self):
        self.core.fail_on_update = "predio"
        code, _, err = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("error PUT predio", err)
        self.assertIn("boom-update", err)
        # nothing is deleted before every update went through
        self.assertEqual(self.deletes(), [])

    def test_a_refused_delete_stops_with_the_record(self):
        self.core.fail_on_delete = "unidad_urbana"
        code, _, err = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn(f"error DELETE unidad_urbana {self.repetida['id']}", err)
        self.assertIn("boom-delete", err)


class WithApplyTests(ApplyCliTestCase):
    """The order the README gives for a Core with the padrón: apply.py, the migration, apply.py again."""

    def setUp(self):
        model = load_model()
        self.campos = [(o["name"], f["name"]) for o in model["objects"] for f in o["fields"] if f.get("enum") == "tipo_unidad_urbana"]
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        for objeto, campo in self.campos:
            fields[objeto] = core_fields(model, objeto, options={campo: ANTES})
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            existing_fields=fields,
        )
        self.addCleanup(self.core.stop)
        for tipo, nombre in [("ANEXO", "CENTRO POBLADO MIRICHARO"), ("HABILITACION URBANA", "LOS COCOS"),
                             ("OTROS", "CENTRO URBANO INFORMAL VISTA ALEGRE"), ("CERCADO", "II MESETA")]:
            self.core.add_record("predio", {"codigo": nombre, "tipo_zona": tipo, "habilitacion_urbana": nombre})
        self.core.add_record("unidad_urbana", {"tipo_unidad_urbana": "ANEXO", "nombre": "CENTRO POBLADO MIRICHARO", "ubigeo": "120302"})
        # one the padrón wrote with and without ANEXO: the migration deletes the ANEXO one
        self.core.add_record("unidad_urbana", {"tipo_unidad_urbana": "CENTRO POBLADO", "nombre": "IPANEMA", "ubigeo": "120302"})
        self.core.add_record("unidad_urbana", {"tipo_unidad_urbana": "ANEXO", "nombre": "CENTRO POBLADO IPANEMA", "ubigeo": "120302"})
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.report = os.path.join(tmp.name, "migrar_tipos_unidad_urbana.csv")

    def option_puts(self):
        puts = [(r[1].rsplit("/", 3)[1], r[3]["enumOptions"]) for r in self.core.requests if r[0] == "PUT" and "enumOptions" in (r[3] or {})]
        self.core.requests.clear()
        return dict(puts)

    def test_the_old_options_leave_once_nobody_uses_them_and_the_list_ends_as_the_srtms(self):
        tipos = load_model()["enums"]["tipo_unidad_urbana"]
        self.assertEqual(len(self.campos), 4)

        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        self.assertIn("keep   option predio.tipo_zona ANEXO: 1 record uses it", out)
        self.assertIn("keep   option unidad_urbana.tipo_unidad_urbana ANEXO: 2 records use it", out)
        # the 28 types it lacked, in page 5's order, then the old ones still in use until the migration moves them
        self.assertEqual(self.option_puts(), {
            "predio": tipos + ["ANEXO", "HABILITACION URBANA", "OTROS"],
            "unidad_urbana": tipos + ["ANEXO"],
            "domicilio": tipos,
            "catastro_fiscal": tipos,
        })
        self.assertIn("(+" + ", ".join(t for t in tipos if t not in ANTES) + "; -COMUNIDAD CAMPESINA, COMUNIDAD NATIVA)", out)
        self.assertEqual(len([t for t in tipos if t not in ANTES]), 28)

        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = mtu.main(["--core", self.core.base_url, "--report", self.report])
        self.assertEqual(code, 0, msg=err.getvalue())
        self.assertIn("predio: 4 registros, 3 por migrar", out.getvalue())
        self.assertIn("unidad_urbana: 3 registros, 1 por migrar, 1 por borrar", out.getvalue())
        self.core.requests.clear()

        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        self.assertNotIn("keep   option", out)
        puts = self.option_puts()
        # no unidad urbana keeps an old type: none is left behind
        self.assertEqual((puts["predio"], puts["unidad_urbana"]), (tipos, tipos))


if __name__ == "__main__":
    unittest.main()
