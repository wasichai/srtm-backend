"""Tests for migrar_derivados.py: frontis_principal from longitud_frente, the fila that pairs a row across the DJ of a
condominio, an idempotent run against a fake Core (wasichai/srtm-backend#156).

Run: cd model && python3 -m unittest -v test_migrar_derivados
"""
import io
import unittest
from contextlib import redirect_stderr, redirect_stdout

import migrar_derivados as md
from fake_core import FakeCore


def dj(record_id, predio="p1", contribuyente="c1", **attributes):
    return {"id": record_id, "attributes": {"predio": predio, "contribuyente": contribuyente, "anio": 2026,
                                            "secuencia_uso": "001", **attributes}}


def fila(record_id, declaracion, **attributes):
    return {"id": record_id, "attributes": {"declaracion": declaracion, **attributes}}


def piso(record_id, declaracion, numero=1, **attributes):
    return fila(record_id, declaracion, tipo_nivel="PISO", numero_piso=numero, area_construida=100, **attributes)


def listas(niveles=(), obras=(), frentes=()):
    return {"nivel_construccion": list(niveles), "obra_complementaria": list(obras), "otro_frente": list(frentes)}


def filas(updates, objeto):
    return {u.record_id: u.attributes["fila"] for u in updates if u.object_name == objeto}


class FrontisTests(unittest.TestCase):
    def test_the_frontis_principal_is_the_longitud_frente_where_it_is_empty(self):
        updates, _ = md.plan([
            dj("d1", longitud_frente="12.50"),
            dj("d2", predio="p2", longitud_frente="8.00", frontis_principal="7.00"),
            dj("d3", predio="p3"),
            dj("d4", predio="p4", longitud_frente="5.00", frontis_principal=""),
        ], listas())
        self.assertEqual({u.record_id: u.attributes.get("frontis_principal") for u in updates}, {"d1": "12.50", "d4": "5.00"})
        # every other field goes back as it was: core's update replaces them all
        [d1] = [u for u in updates if u.record_id == "d1"]
        self.assertEqual(d1.attributes, {**dj("d1")["attributes"], "longitud_frente": "12.50", "frontis_principal": "12.50"})


class FilaTests(unittest.TestCase):
    def test_the_condominos_rows_pair_by_their_natural_key(self):
        updates, resumen = md.plan(
            [dj("a", contribuyente="c1"), dj("b", contribuyente="c2")],
            listas(
                niveles=[piso("na1", "a"), piso("na2", "a", 2), piso("nb1", "b"), piso("nb2", "b", 2),
                         fila("nb3", "b", tipo_nivel="SOTANO", numero_piso=1)],
                obras=[fila("oa", "a", tipo_obra="MUROS PERIMETRICOS O CERCOS", numero_piso=1, categoria="3. MURO"),
                       fila("ob", "b", tipo_obra="MUROS PERIMETRICOS O CERCOS", numero_piso=1, categoria="3. MURO"),
                       fila("oc", "b", tipo_obra="MUROS PERIMETRICOS O CERCOS", numero_piso=1, categoria="4. OTRO")],
                frentes=[fila("fa", "a", via="LIMA", numero="12", frontis=5), fila("fb", "b", via=" lima ", numero="12", frontis=5)],
            ),
        )
        niveles = filas(updates, "nivel_construccion")
        self.assertEqual(len(niveles), 5)
        self.assertEqual(niveles["na1"], niveles["nb1"])
        self.assertEqual(niveles["na2"], niveles["nb2"])
        self.assertNotEqual(niveles["na1"], niveles["na2"])
        self.assertNotIn(niveles["nb3"], {niveles["na1"], niveles["na2"]})
        obras = filas(updates, "obra_complementaria")
        self.assertEqual(obras["oa"], obras["ob"])
        self.assertNotEqual(obras["oa"], obras["oc"])
        frentes = filas(updates, "otro_frente")
        self.assertEqual(frentes["fa"], frentes["fb"])
        self.assertEqual(resumen["nivel_construccion"], {"filas": 5, "emparejadas": 4, "sueltas": 1})
        # the row keeps every other field
        [na1] = [u for u in updates if u.record_id == "na1"]
        self.assertEqual(na1.attributes, {**piso("na1", "a")["attributes"], "fila": niveles["na1"]})

    def test_the_same_key_twice_pairs_in_order(self):
        updates, _ = md.plan(
            [dj("a", contribuyente="c1"), dj("b", contribuyente="c2")],
            listas(niveles=[piso("na1", "a"), piso("na2", "a"), piso("nb1", "b"), piso("nb2", "b")]),
        )
        niveles = filas(updates, "nivel_construccion")
        self.assertEqual(niveles["na1"], niveles["nb1"])
        self.assertEqual(niveles["na2"], niveles["nb2"])
        self.assertNotEqual(niveles["na1"], niveles["na2"])

    def test_other_groups_and_annulled_ones_do_not_pair(self):
        updates, resumen = md.plan(
            [dj("a"), dj("otro_anio", contribuyente="c2", anio=2025), dj("otra_secuencia", contribuyente="c3", secuencia_uso="002"),
             dj("anulada", contribuyente="c4", estado="ANULADA"), dj("misma", contribuyente="c5", secuencia_uso="1")],
            listas(niveles=[piso("n-a", "a"), piso("n-anio", "otro_anio"), piso("n-sec", "otra_secuencia"),
                            piso("n-anulada", "anulada"), piso("n-misma", "misma")]),
        )
        niveles = filas(updates, "nivel_construccion")
        self.assertEqual(len(set(niveles.values())), 4)
        # "1" is the secuencia "001"
        self.assertEqual(niveles["n-a"], niveles["n-misma"])
        self.assertEqual(resumen["nivel_construccion"], {"filas": 5, "emparejadas": 2, "sueltas": 3})

    def test_a_row_that_has_its_fila_keeps_it_and_lends_it_to_its_pair(self):
        updates, _ = md.plan(
            [dj("a", contribuyente="c1"), dj("b", contribuyente="c2")],
            listas(niveles=[piso("na", "a", fila="11111111-1111-1111-1111-111111111111"), piso("nb", "b")]),
        )
        self.assertEqual(filas(updates, "nivel_construccion"), {"nb": "11111111-1111-1111-1111-111111111111"})

    def test_a_second_run_changes_nothing(self):
        declaraciones = [dj("a", contribuyente="c1", longitud_frente="9"), dj("b", contribuyente="c2")]
        rows = listas(niveles=[piso("na", "a"), piso("nb", "b")], frentes=[fila("f", "a", via="LIMA", frontis=3)])
        updates, _ = md.plan(declaraciones, rows)
        hechas = {u.record_id: u.attributes for u in updates}

        def aplicadas(records):
            return [{"id": r["id"], "attributes": hechas.get(r["id"], r["attributes"])} for r in records]

        again, _ = md.plan(aplicadas(declaraciones), {k: aplicadas(v) for k, v in rows.items()})
        self.assertEqual(again, [])


class MigrarCliTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        a = self.core.add_record("declaracion_predial", {"predio": "p1", "contribuyente": "c1", "anio": 2026, "secuencia_uso": "001",
                                                          "longitud_frente": 10.5, "area_construida": 80})
        b = self.core.add_record("declaracion_predial", {"predio": "p1", "contribuyente": "c2", "anio": 2026, "secuencia_uso": "001"})
        self.a, self.b = a, b
        self.na = self.core.add_record("nivel_construccion", {"declaracion": a["id"], "tipo_nivel": "PISO", "numero_piso": 1})
        self.nb = self.core.add_record("nivel_construccion", {"declaracion": b["id"], "tipo_nivel": "PISO", "numero_piso": 1})
        self.f = self.core.add_record("otro_frente", {"declaracion": a["id"], "via": "LIMA", "frontis": 4})

    def run_main(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = md.main(["--core", self.core.base_url, "--workers", "2", *extra])
        return code, out.getvalue(), err.getvalue()

    def puts(self):
        return [r for r in self.core.requests if r[0] == "PUT"]

    def test_dry_run_counts_and_changes_nothing(self):
        code, out, err = self.run_main("--dry-run")
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.puts(), [])
        self.assertIn("declaraciones: 2 leídas, 1 con frontis_principal por copiar", out)
        self.assertIn("nivel_construccion: 2 filas sin fila, 2 emparejadas, 0 sueltas", out)
        self.assertIn("otro_frente: 1 filas sin fila, 0 emparejadas, 1 sueltas", out)
        self.assertNotIn("fila", self.na["attributes"])

    def test_migrates_without_recomputing_then_is_idempotent(self):
        code, out, err = self.run_main()
        self.assertEqual(code, 0, msg=err)
        self.assertIn("done: 4 updated", out)
        self.assertEqual(self.a["attributes"]["frontis_principal"], 10.5)
        # the derived values stay as imported: the portal recomputes them on the DJ's first change
        self.assertEqual(self.a["attributes"]["longitud_frente"], 10.5)
        self.assertEqual(self.a["attributes"]["area_construida"], 80)
        self.assertEqual(self.na["attributes"]["fila"], self.nb["attributes"]["fila"])
        self.assertEqual(self.na["attributes"]["numero_piso"], 1)
        self.assertTrue(self.f["attributes"]["fila"])

        self.core.requests.clear()
        code, out, _ = self.run_main()
        self.assertEqual(code, 0)
        self.assertEqual(self.puts(), [])
        self.assertIn("done: 0 updated", out)

    def test_a_refusal_stops_with_the_record(self):
        self.core.fail_on_update = "nivel_construccion"
        code, _, err = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("error PUT nivel_construccion", err)


if __name__ == "__main__":
    unittest.main()
