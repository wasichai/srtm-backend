"""Tests for normalizar_padron.py: what a predio and a declaración of the padrón need, the report, and an idempotent
run against a fake Core.

Run: cd model && python3 -m unittest -v
"""
import csv
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import normalizar_padron as npad
from fake_core import FakeCore

# a predio as import_predios.py created it before it split the types off
PADRON = {
    "codigo": "01-01-0001",
    "sector_catastral": "01",
    "condicion": "URBANO",
    "direccion": "JIRON LIMA Nro.: 12 Mz.: A Lt.: 5 Km.: 1 CERCADO II MESETA",
    "via": "JIRON LIMA",
    "numero": "12",
    "manzana": "A",
    "lote": "5",
    "habilitacion_urbana": "CERCADO II MESETA",
    "ubicacion_area_verde": "OTRAS UBICACIONES",
}

# one registered in the portal: its ubicación is the srtm's
PORTAL = {
    "codigo": "01-01-0002",
    "numero_registro": 1,
    "direccion": "JIRON JR. LIMA, JUNIN-CHANCHAMAYO-PERENE",
    "tipo_via": "JIRON",
    "via": "JR. LIMA",
    "tipo_zona": "URBANIZACION",
    "habilitacion_urbana": "LOS PINOS",
}


class NormalizarPredioTests(unittest.TestCase):
    def test_types_split_off_and_kilometro_read(self):
        self.assertEqual(
            npad.normalizar_predio(PADRON),
            {"tipo_via": "JIRON", "via": "LIMA", "tipo_zona": "CERCADO", "habilitacion_urbana": "II MESETA", "kilometro": "1"},
        )

    def test_an_abbreviated_type(self):
        self.assertEqual(npad.normalizar_predio({"via": "JR. LIMA", "direccion": "JR. LIMA Mz.: A"}), {"tipo_via": "JIRON", "via": "LIMA"})

    def test_no_type_is_otros_and_the_name_stays(self):
        self.assertEqual(npad.normalizar_predio({"via": "SECTOR IPANEMA", "direccion": "SECTOR IPANEMA"}), {"tipo_via": "OTROS"})

    def test_the_lots_leftovers_leave_the_zona(self):
        self.assertEqual(
            npad.normalizar_predio({"habilitacion_urbana": "03-B CERCADO III MESETA"}),
            {"tipo_zona": "CERCADO", "habilitacion_urbana": "III MESETA"},
        )

    def test_a_portal_predio_is_left_alone(self):
        self.assertEqual(npad.normalizar_predio({**PORTAL, "direccion": "X Km.: 3"}), {})

    def test_a_second_run_changes_nothing(self):
        for predio in [
            PADRON,
            # a street named after a type: its name must not lose "CALLE" on the second run
            {"via": "CALLE CALLE 01 - STA. INES", "direccion": "CALLE CALLE 01 - STA. INES Mz.: C"},
            {"habilitacion_urbana": "03-B CERCADO III MESETA"},
            {"direccion": "S/N Mz.: 99 Km.: 4 ANEXO X"},
        ]:
            with self.subTest(predio=predio):
                once = {**predio, **npad.normalizar_predio(predio)}
                self.assertEqual(npad.normalizar_predio(once), {})
        once = {**PADRON, **npad.normalizar_predio({"via": "CALLE CALLE 01 - STA. INES"})}
        self.assertEqual(once["via"], "CALLE 01 - STA. INES")


class NotasTests(unittest.TestCase):
    def test_what_needs_a_look(self):
        self.assertEqual(npad.notas_predio({"via": "SECTOR IPANEMA"}), ["vía sin tipo reconocido: queda OTROS"])
        self.assertEqual(npad.notas_predio({"habilitacion_urbana": "VILLA SOL"}), ["zona sin tipo reconocido: queda OTROS"])
        self.assertEqual(npad.notas_predio({"habilitacion_urbana": "03-B CERCADO III MESETA"}), ["se descartan restos de lote de la zona: 03-B"])
        self.assertEqual(npad.notas_predio(PADRON), [])
        # a misspelled type is read whole, nothing is left over
        self.assertEqual(npad.notas_predio({"via": "CACARROZABLE SAN CRISTOBAL"}), [])

    def test_a_portal_predio_that_repeats_its_type(self):
        self.assertEqual(
            npad.notas_predio(PORTAL),
            ["la vía repite su tipo (JIRON JR. LIMA): corregir la vía en el portal; al guardar se rearma la dirección"],
        )
        self.assertEqual(npad.notas_predio({**PORTAL, "via": "LIMA"}), [])
        # a street named after a type: its direccion (the padrón's, or one written with the abbreviation) is fine
        self.assertEqual(npad.notas_predio({**PORTAL, "tipo_via": "CALLE", "via": "CALLE 01", "direccion": "CALLE CALLE 01 Mz.: C"}), [])
        self.assertEqual(
            npad.notas_predio(
                {**PORTAL, "via": "LIMA", "habilitacion_urbana": "URB. LOS PINOS", "direccion": "JR. LIMA, URBANIZACION URB. LOS PINOS, JUNIN"}
            ),
            ["la zona repite su tipo (URBANIZACION URB. LOS PINOS): corregir la zona en el portal; al guardar se rearma la dirección"],
        )


class NormalizarDeclaracionTests(unittest.TestCase):
    def test_secuencia_has_three_digits(self):
        self.assertEqual(npad.normalizar_declaracion({"secuencia_uso": "1"}), {"secuencia_uso": "001"})
        self.assertEqual(npad.normalizar_declaracion({"secuencia_uso": "001"}), {})
        self.assertEqual(npad.normalizar_declaracion({"secuencia_uso": "A"}), {})

    def test_a_padded_secuencia_that_meets_another_is_noted(self):
        declaraciones = [
            {"id": "d1", "attributes": {"numero_declaracion": 1, "predio": "p", "contribuyente": "c", "anio": 2026, "secuencia_uso": "001"}},
            {"id": "d2", "attributes": {"numero_declaracion": 2, "predio": "p", "contribuyente": "c", "anio": 2026, "secuencia_uso": "1"}},
        ]
        updates, rows = npad.plan([], declaraciones)
        self.assertEqual([(u.object_name, u.record_id) for u in updates], [("declaracion_predial", "d2")])
        [nota] = [r for r in rows if r["nota"]]
        self.assertIn("otra declaración del mismo predio, contribuyente y año ya tiene la secuencia 001", nota["nota"])


class NormalizarCliTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.padron = self.core.add_record("predio", PADRON)
        self.portal = self.core.add_record("predio", PORTAL)
        self.core.add_record("declaracion_predial", {"numero_declaracion": 1, "anio": 2026, "secuencia_uso": "001", "predio": self.padron["id"]})
        self.dj = self.core.add_record(
            "declaracion_predial", {"numero_declaracion": 2, "anio": 2026, "secuencia_uso": "1", "predio": self.portal["id"], "area_terreno": 120.5}
        )
        self.tmp = tempfile.TemporaryDirectory()
        self.report = os.path.join(self.tmp.name, "normalizar_padron.csv")

    def tearDown(self):
        self.core.stop()
        self.tmp.cleanup()

    def run_main(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = npad.main(["--core", self.core.base_url, "--report", self.report, "--workers", "2", *extra])
        return code, out.getvalue(), err.getvalue()

    def puts(self):
        return [r for r in self.core.requests if r[0] == "PUT"]

    def report_rows(self):
        with open(self.report, encoding="utf-8") as f:
            return list(csv.DictReader(f))

    def test_dry_run_reports_and_changes_nothing(self):
        code, out, _ = self.run_main("--dry-run")
        self.assertEqual(code, 0)
        self.assertEqual(self.puts(), [])
        self.assertIn("predios: 2 leídos, 1 por normalizar", out)
        self.assertIn("declaraciones: 2 leídas, 1 por normalizar", out)
        changes = {(r["clave"], r["campo"]): (r["antes"], r["despues"]) for r in self.report_rows() if r["campo"]}
        self.assertEqual(changes[("01-01-0001", "via")], ("JIRON LIMA", "LIMA"))
        self.assertEqual(changes[("01-01-0001", "tipo_via")], ("", "JIRON"))
        self.assertEqual(changes[("2", "secuencia_uso")], ("1", "001"))
        self.assertTrue(any("la vía repite su tipo" in r["nota"] for r in self.report_rows() if r["clave"] == "01-01-0002"))

    def test_normalizes_keeping_every_other_field_then_is_idempotent(self):
        code, out, _ = self.run_main()
        self.assertEqual(code, 0)
        self.assertEqual(sorted(p[1] for p in self.puts()), sorted([
            f"/api/objects/predio/records/{self.padron['id']}",
            f"/api/objects/declaracion_predial/records/{self.dj['id']}",
        ]))
        predio = self.padron["attributes"]
        self.assertEqual((predio["tipo_via"], predio["via"], predio["tipo_zona"], predio["habilitacion_urbana"], predio["kilometro"]),
                         ("JIRON", "LIMA", "CERCADO", "II MESETA", "1"))
        # core's update replaces every field: what the migration does not touch goes back as it was
        self.assertEqual(predio["direccion"], PADRON["direccion"])
        self.assertEqual(predio["ubicacion_area_verde"], "OTRAS UBICACIONES")
        self.assertEqual(self.dj["attributes"], {"numero_declaracion": 2, "anio": 2026, "secuencia_uso": "001", "predio": self.portal["id"], "area_terreno": 120.5})
        self.assertIn("done: 2 updated", out)

        self.core.requests.clear()
        code, out, _ = self.run_main()
        self.assertEqual(code, 0)
        self.assertEqual(self.puts(), [])
        self.assertIn("predios: 2 leídos, 0 por normalizar", out)

    def test_a_refusal_stops_with_the_record(self):
        self.core.fail_on_update = "predio"
        code, _, err = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("predio 01-01-0001", err)


if __name__ == "__main__":
    unittest.main()
