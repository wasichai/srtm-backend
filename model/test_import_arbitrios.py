"""Tests for import_arbitrios.py: an ordinance of arbitrios, its servicios and the inafectaciones, checked before
anything is sent and loaded idempotently into a fake Core.

Every value here is FICTITIOUS, only to test the shape: no ordinance of arbitrios of Perené is transcribed and
verified yet (D-02b). Run: cd model && python3 -m unittest -v
"""
import copy
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import import_arbitrios as ia
from fake_core import FakeCore

ORDENANZA = {
    "anio": 2026,
    "numero": "000-2025-MDP (ficticia)",
    "fecha_publicacion": "2025-12-20",
    "acuerdo_ratificacion": "Acuerdo de Concejo 000-2025-MPCH (ficticio)",
    "fecha_ratificacion": "2025-12-28",
    "municipalidad_ratificante": "MUNICIPALIDAD PROVINCIAL DE CHANCHAMAYO",
}
SERVICIOS = [
    {"codigo": "BARRIDO", "nombre": "Barrido de calles", "orden": 1, "vigencia_desde": "2026-01-01", "ordenanza": 2026},
    {"codigo": "SERENAZGO", "nombre": "Serenazgo", "orden": 2, "vigencia_desde": "2026-01-01", "ordenanza": 2026},
]
INAFECTACIONES = [
    {"predio": "01-01-0001", "servicio": "BARRIDO", "vigencia_desde": "2026-01-01", "motivo": "Sin vía pavimentada",
     "observacion": "Informe técnico de prueba"},
]


def archivo(**cambios):
    datos = {"ordenanzas": [ORDENANZA], "servicios": SERVICIOS, "inafectaciones": INAFECTACIONES}
    datos = copy.deepcopy(datos)
    datos.update(cambios)
    return datos


class ValidarTests(unittest.TestCase):
    def test_a_well_formed_file_passes(self):
        self.assertEqual(ia.errores(archivo()), [])

    def test_required_fields(self):
        sin_numero = dict(ORDENANZA, numero=" ")
        self.assertTrue(any("numero" in e for e in ia.errores(archivo(ordenanzas=[sin_numero]))))
        sin_motivo = dict(INAFECTACIONES[0], motivo=None)
        self.assertTrue(any("motivo" in e for e in ia.errores(archivo(inafectaciones=[sin_motivo]))))

    def test_a_servicio_code_is_short_and_unique(self):
        largo = dict(SERVICIOS[0], codigo="X" * 21)
        self.assertTrue(any("20" in e for e in ia.errores(archivo(servicios=[largo]))))
        self.assertTrue(any("repetido" in e for e in ia.errores(archivo(servicios=[SERVICIOS[0], SERVICIOS[0]]))))

    def test_one_ordinance_per_year(self):
        self.assertTrue(any("repetid" in e for e in ia.errores(archivo(ordenanzas=[ORDENANZA, ORDENANZA]))))

    def test_an_observation_is_5_to_500_characters(self):
        corta = dict(INAFECTACIONES[0], observacion=" ok  ")
        self.assertTrue(any("observacion" in e for e in ia.errores(archivo(inafectaciones=[corta]))))
        larga = dict(INAFECTACIONES[0], observacion="x" * 501)
        self.assertTrue(any("observacion" in e for e in ia.errores(archivo(inafectaciones=[larga]))))

    def test_a_vigencia_ends_after_it_starts(self):
        al_reves = dict(SERVICIOS[0], vigencia_hasta="2025-12-31")
        self.assertTrue(any("vigencia" in e for e in ia.errores(archivo(servicios=[al_reves]))))

    def test_an_inafectacion_names_a_servicio_of_the_file_or_one_core_has(self):
        # an unknown servicio is only known to be wrong once core is read: the file alone accepts it
        otro = dict(INAFECTACIONES[0], servicio="PARQUES")
        self.assertEqual(ia.errores(archivo(inafectaciones=[otro])), [])


class CargaTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        self.predio = self.core.add_record("predio", {"codigo": "01-01-0001"})
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.ruta = os.path.join(tmp.name, "arbitrios.json")

    def run_main(self, datos, *extra):
        with open(self.ruta, "w", encoding="utf-8") as f:
            json.dump(datos, f)
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = ia.main(["--core", self.core.base_url, "--archivo", self.ruta, *extra])
        return code, out.getvalue(), err.getvalue()

    def writes(self):
        return [(m, p.split("?")[0]) for m, p, _, _ in self.core.requests if m in ("POST", "PUT", "DELETE") and p != "/api/auth/login"]

    def test_creates_in_order_with_the_relations_resolved(self):
        code, out, err = self.run_main(archivo())
        self.assertEqual(code, 0, err)
        [ordenanza] = self.core.records["ordenanza_arbitrio"]
        servicios = {s["attributes"]["codigo"]: s for s in self.core.records["servicio_arbitrio"]}
        self.assertEqual(set(servicios), {"BARRIDO", "SERENAZGO"})
        self.assertEqual(servicios["BARRIDO"]["attributes"]["ordenanza"], ordenanza["id"])
        [inafectacion] = self.core.records["inafectacion_arbitrio"]
        self.assertEqual(inafectacion["attributes"]["predio"], self.predio["id"])
        self.assertEqual(inafectacion["attributes"]["servicio"], servicios["BARRIDO"]["id"])
        self.assertEqual([o for _, o in self.writes()], ["/api/objects/ordenanza_arbitrio/records"]
                         + ["/api/objects/servicio_arbitrio/records"] * 2 + ["/api/objects/inafectacion_arbitrio/records"])
        self.assertIn("done: 4 created, 0 updated, 0 skipped", out)

    def test_a_second_run_writes_nothing(self):
        self.run_main(archivo())
        self.core.requests.clear()
        code, out, err = self.run_main(archivo())
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn("done: 0 created, 0 updated, 4 skipped", out)

    def test_a_ratification_added_later_updates_the_ordinance_in_place(self):
        sin_ratificar = {k: v for k, v in ORDENANZA.items() if k not in ("acuerdo_ratificacion", "fecha_ratificacion")}
        self.run_main(archivo(ordenanzas=[sin_ratificar]))
        self.core.requests.clear()
        code, out, err = self.run_main(archivo())
        self.assertEqual(code, 0, err)
        self.assertEqual([m for m, _ in self.writes()], ["PUT"])
        self.assertEqual(self.core.records["ordenanza_arbitrio"][0]["attributes"]["fecha_ratificacion"], "2025-12-28")

    def test_dry_run_reads_core_and_writes_nothing(self):
        code, out, err = self.run_main(archivo(), "--dry-run")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn("dry run: 4 to create, 0 to update, 0 skipped; nothing written", out)

    def test_an_unknown_predio_or_servicio_exits_2_before_writing(self):
        otro = dict(INAFECTACIONES[0], predio="99-99-9999")
        code, out, err = self.run_main(archivo(inafectaciones=[otro]))
        self.assertEqual(code, 2)
        self.assertIn("99-99-9999", err)
        self.assertEqual(self.writes(), [])
        code, out, err = self.run_main(archivo(inafectaciones=[dict(INAFECTACIONES[0], servicio="PARQUES")]))
        self.assertEqual(code, 2)
        self.assertIn("PARQUES", err)

    def test_a_servicio_of_an_ordinance_core_already_has(self):
        self.core.add_record("ordenanza_arbitrio", dict(ORDENANZA))
        code, out, err = self.run_main(archivo(ordenanzas=[]))
        self.assertEqual(code, 0, err)
        ordenanza = self.core.records["ordenanza_arbitrio"][0]
        self.assertTrue(all(s["attributes"]["ordenanza"] == ordenanza["id"] for s in self.core.records["servicio_arbitrio"]))

    def test_a_malformed_file_exits_2_without_calling_core(self):
        code, out, err = self.run_main(archivo(servicios=[dict(SERVICIOS[0], codigo="")]))
        self.assertEqual(code, 2)
        self.assertEqual(self.core.requests, [])

    def test_a_refusal_exits_1(self):
        self.core.fail_on_record = "servicio_arbitrio"
        code, out, err = self.run_main(archivo())
        self.assertEqual(code, 1)
        self.assertIn("boom-record", err)


if __name__ == "__main__":
    unittest.main()
