"""Tests for import_parametros.py: the shipped copy of normativa's rows and an idempotent load into a fake Core.

Run: cd model && python3 -m unittest -v
"""
import csv
import io
import os
import unittest
from contextlib import redirect_stderr, redirect_stdout

import import_parametros as ip
from fake_core import FakeCore

HERE = os.path.dirname(os.path.abspath(__file__))
SHIPPED = os.path.join(HERE, "data", "parametros-predial.csv")
# the verified source, in a checkout of normativa next to this repo or at $NORMATIVA (not in CI)
NORMATIVA = os.path.join(os.environ.get("NORMATIVA", os.path.join(HERE, "..", "..", "normativa")),
                         "docs", "10-negocio", "valores-normativos", "publicacion", "parametros-2026.csv")
OBJECT = "parametro_tributario"


class ShippedParametrosTests(unittest.TestCase):
    def setUp(self):
        self.parametros = ip.read_parametros(SHIPPED)

    def test_the_predial_rows_and_nothing_else(self):
        tipos = [p["tipo"] for p in self.parametros]
        self.assertEqual(len(self.parametros), 13)
        self.assertEqual(set(tipos), {"UIT", "TRAMO_PREDIAL", "TRAMO_PREDIAL_LIMITE", "PREDIAL_MINIMO", "DEDUCCION_PENSIONISTA",
                                      "DEDUCCION_ADULTO_MAYOR"})
        self.assertEqual(tipos.count("UIT"), 5)
        self.assertEqual(tipos.count("TRAMO_PREDIAL"), 3)
        self.assertEqual(tipos.count("TRAMO_PREDIAL_LIMITE"), 2)

    def test_a_row_as_attributes(self):
        uit = next(p for p in self.parametros if p["tipo"] == "UIT" and p["vigencia_desde"] == "2026-01-01")
        # empty cells are left out: core stores no empty strings
        self.assertEqual(uit, {"tipo": "UIT", "vigencia_desde": "2026-01-01", "vigencia_hasta": "2026-12-31", "valor_numerico": "5500.00",
                               "norma": "D.S. N.° 301-2025-EF", "fuente": "uit.md", "transcribio": "JNA", "verifico": "HNA"})

    def test_every_row_is_signed_twice(self):
        for p in self.parametros:
            self.assertTrue(p["transcribio"] and p["verifico"] and p["transcribio"] != p["verifico"], p)

    def test_natural_keys_are_unique(self):
        keys = [ip.key(p) for p in self.parametros]
        self.assertEqual(len(keys), len(set(keys)))

    @unittest.skipUnless(os.path.exists(NORMATIVA), "normativa is not checked out next to this repo")
    def test_every_row_is_a_copy_of_normativa(self):
        with open(NORMATIVA, encoding="utf-8") as f:
            source = {(r["tipo"], r["clave"], r["vigencia_desde"]): r for r in csv.DictReader(line for line in f if not line.startswith("#"))}
        columns = {"texto": "valor_texto", "norma": "documento_fuente", "fuente": "archivo_del_corpus"}
        for p in self.parametros:
            row = source[(p["tipo"], p.get("clave", ""), p["vigencia_desde"])]
            for field in ("vigencia_hasta", "valor_numerico", "texto", "norma", "fuente", "transcribio", "verifico"):
                self.assertEqual(p.get(field, ""), row[columns.get(field, field)], f"{ip.key(p)} {field}")


class LoadTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        self.parametros = ip.read_parametros(SHIPPED)

    def run_main(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = ip.main(["--core", self.core.base_url, "--csv", SHIPPED, *extra])
        return code, out.getvalue(), err.getvalue()

    def writes(self):
        return [(m, p) for m, p, _, _ in self.core.requests if m in ("POST", "PUT", "DELETE") and p != "/api/auth/login"]

    def test_first_load_creates_every_row(self):
        code, out, err = self.run_main()
        self.assertEqual(code, 0, err)
        stored = [r["attributes"] for r in self.core.records[OBJECT]]
        self.assertEqual(sorted(map(ip.key, stored)), sorted(map(ip.key, self.parametros)))
        self.assertIn({"tipo": "TRAMO_PREDIAL", "clave": "2", "vigencia_desde": "2004-11-15", "valor_numerico": "0.6",
                       "texto": "Más de 15 UIT y hasta 60 UIT; 0.6%",
                       "norma": "TUO de la Ley de Tributación Municipal (D.S. 156-2004-EF), artículo 13",
                       "fuente": "predial-tramos-y-alicuotas.md", "transcribio": "JNA", "verifico": "HNA"}, stored)
        self.assertIn(f"{OBJECT}: 13 created, 0 updated, 0 skipped", out)
        # the report: one line per row it writes
        self.assertIn("  create UIT 2026-01-01: 5500.00", out)

    def test_a_second_run_changes_nothing(self):
        self.run_main()
        self.core.requests.clear()
        code, out, err = self.run_main()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn(f"{OBJECT}: 0 created, 0 updated, 13 skipped", out)

    def test_a_second_run_skips_what_core_reads_back_as_numbers(self):
        # core answers a DECIMAL as a number: 5500.00 comes back 5500.0, and it is the same value
        for p in self.parametros:
            self.core.add_record(OBJECT, {**p, "valor_numerico": float(p["valor_numerico"])})
        code, out, err = self.run_main()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn(f"{OBJECT}: 0 created, 0 updated, 13 skipped", out)

    def test_a_changed_row_is_updated_in_place(self):
        self.run_main()
        record = next(r for r in self.core.records[OBJECT] if r["attributes"]["tipo"] == "UIT" and r["attributes"]["vigencia_desde"] == "2026-01-01")
        record["attributes"]["valor_numerico"] = "5000.00"
        self.core.requests.clear()
        code, out, err = self.run_main()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [("PUT", f"/api/objects/{OBJECT}/records/{record['id']}")])
        self.assertEqual(record["attributes"]["valor_numerico"], "5500.00")
        self.assertIn("  update UIT 2026-01-01: valor_numerico 5000.00 -> 5500.00", out)
        self.assertIn(f"{OBJECT}: 0 created, 1 updated, 12 skipped", out)

    def test_dry_run_reads_core_and_writes_nothing(self):
        code, out, err = self.run_main("--dry-run")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertNotIn(OBJECT, self.core.records)
        self.assertIn(f"{OBJECT}: 13 to create, 0 to update, 0 skipped", out)
        self.assertIn("dry run: nothing written", out)

    def test_a_refusal_exits_1(self):
        self.core.fail_on_record = OBJECT
        code, out, err = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("boom-record", err)



# the arbitrios rows of an ordinance's transcription (plan of the arbitrios module, decisión 1 and 4). FICTITIOUS values,
# written here only to test their shape: no ordinance of Perené is transcribed and verified yet (D-02b)
def fila(tipo, clave, valor_numerico=None, texto=None, transcribio="ANA, 2026-01-05", verifico="BETO, 2026-01-09"):
    return {k: v for k, v in {
        "tipo": tipo, "clave": clave, "vigencia_desde": "2026-01-01", "valor_numerico": valor_numerico, "texto": texto,
        "norma": "Ordenanza ficticia de prueba", "fuente": "test", "transcribio": transcribio, "verifico": verifico,
    }.items() if v is not None}


class ArbitriosRowsTests(unittest.TestCase):
    def test_well_formed_rows_pass(self):
        filas = [
            fila("TASA_ARBITRIO", "BARRIDO:Z1:CASA", valor_numerico="8.50"),
            fila("ARBITRIO_ZONA", "01", texto="Z1"),
            fila("ARBITRIO_USO", "0101", texto="CASA"),
            fila("ARBITRIO_VENCIMIENTO", "3", texto="2026-03-31"),
        ]
        self.assertEqual(ip.errores(filas), [])

    def test_the_predial_rows_are_not_checked_here(self):
        self.assertEqual(ip.errores(ip.read_parametros(SHIPPED)), [])

    def test_a_tasa_is_a_non_negative_figure_of_servicio_zona_uso(self):
        for clave, valor in [("BARRIDO:Z1", "8.50"), ("BARRIDO::CASA", "8.50"), ("BARRIDO:Z1:CASA", None), ("BARRIDO:Z1:CASA", "-1"),
                             ("BARRIDO:Z1:CASA", "ocho")]:
            with self.subTest(clave=clave, valor=valor):
                self.assertEqual(len(ip.errores([fila("TASA_ARBITRIO", clave, valor_numerico=valor)])), 1)

    def test_a_mapping_names_its_zona_or_uso(self):
        self.assertEqual(len(ip.errores([fila("ARBITRIO_ZONA", "01")])), 1)
        self.assertEqual(len(ip.errores([fila("ARBITRIO_USO", "010", texto="CASA")])), 1)
        self.assertEqual(len(ip.errores([fila("ARBITRIO_USO", "01A1", texto="CASA")])), 1)

    def test_a_vencimiento_is_a_month_and_a_date(self):
        self.assertEqual(len(ip.errores([fila("ARBITRIO_VENCIMIENTO", "13", texto="2026-03-31")])), 1)
        self.assertEqual(len(ip.errores([fila("ARBITRIO_VENCIMIENTO", "3", texto="31/03/2026")])), 1)

    def test_an_ordinance_value_is_signed_by_two_people(self):
        self.assertEqual(len(ip.errores([fila("ARBITRIO_ZONA", "01", texto="Z1", verifico="ANA, 2026-01-09")])), 1)
        self.assertEqual(len(ip.errores([fila("ARBITRIO_ZONA", "01", texto="Z1", verifico=None)])), 1)

    def test_a_malformed_csv_exits_2_before_calling_core(self):
        core = FakeCore()
        self.addCleanup(core.stop)
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
            f.write("# ficticio\n" + ",".join(ip.FIELDS) + "\n")
            f.write("TASA_ARBITRIO,BARRIDO:Z1,2026-01-01,,8.50,,norma,test,ANA,BETO\n")
        self.addCleanup(os.unlink, f.name)
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = ip.main(["--core", core.base_url, "--csv", f.name])
        self.assertEqual(code, 2, err.getvalue())
        self.assertIn("TASA_ARBITRIO BARRIDO:Z1", err.getvalue())
        self.assertEqual(core.requests, [])


# the rows of the sanciones and the anuncios (SPEC §4, Parámetros nuevos). FICTITIOUS values, written here only to test
# their shape: no plazo, feriado or tasa de anuncios of Perené is transcribed and verified yet
def fila_del(tipo, clave, desde="2026-01-01", hasta=None, **campos):
    return {k: v for k, v in {**fila(tipo, clave, **campos), "vigencia_desde": desde, "vigencia_hasta": hasta}.items() if v is not None}


class SancionesYAnunciosRowsTests(unittest.TestCase):
    def test_the_tipos_the_code_reads(self):
        self.assertEqual(ip.TIPOS_SANCIONES, ("PLAZO", "FERIADOS"))
        self.assertEqual(ip.TIPOS_ANUNCIO, ("TASA_ANUNCIO",))
        self.assertEqual(ip.CLAVES_PLAZO, ("DESCARGO_PAPELETA", "RG_RECURSO"))

    def test_well_formed_rows_pass(self):
        filas = [
            fila_del("PLAZO", "DESCARGO_PAPELETA", valor_numerico="5", texto="DIAS_HABILES"),
            fila_del("PLAZO", "RG_RECURSO", valor_numerico="15.00", texto="DIAS_HABILES"),
            fila_del("FERIADOS", "2026", hasta="2026-12-31", texto="2026-04-02, 2026-04-03"),
            # a year without movable holidays: an empty texto
            fila_del("FERIADOS", "2027", desde="2027-01-01", hasta="2027-12-31"),
            fila_del("TASA_ANUNCIO", "PANEL", valor_numerico="12.50"),
        ]
        self.assertEqual(ip.errores(filas), [])

    def test_a_plazo_is_a_whole_number_of_dias_habiles_of_a_known_clave(self):
        for clave, valor, texto in [("DESCARGO", "5", "DIAS_HABILES"), ("RG_RECURSO", "0", "DIAS_HABILES"),
                                    ("RG_RECURSO", "-3", "DIAS_HABILES"), ("RG_RECURSO", "2.5", "DIAS_HABILES"),
                                    ("RG_RECURSO", None, "DIAS_HABILES"), ("RG_RECURSO", "15", "DIAS_CALENDARIO"),
                                    ("RG_RECURSO", "15", None)]:
            with self.subTest(clave=clave, valor=valor, texto=texto):
                malas = ip.errores([fila_del("PLAZO", clave, valor_numerico=valor, texto=texto)])
                self.assertEqual(len(malas), 1)
                self.assertTrue(malas[0].startswith(f"PLAZO {clave}: "), malas)

    def test_the_feriados_are_dates_of_their_year_for_the_whole_year(self):
        for clave, desde, hasta, texto in [("26", "2026-01-01", "2026-12-31", None),
                                           ("2026", "2026-01-01", "2026-12-31", "2026-04-02,2027-01-01"),
                                           ("2026", "2026-01-01", "2026-12-31", "02/04/2026"),
                                           ("2026", "2026-03-01", "2026-12-31", "2026-04-02"),
                                           ("2026", "2026-01-01", None, "2026-04-02")]:
            with self.subTest(clave=clave, desde=desde, hasta=hasta, texto=texto):
                self.assertEqual(len(ip.errores([fila_del("FERIADOS", clave, desde=desde, hasta=hasta, texto=texto)])), 1)

    def test_a_tasa_de_anuncio_is_above_zero_for_a_clase_of_the_model(self):
        for clave, valor in [("PANEL", "0"), ("PANEL", "-1"), ("PANEL", None), ("PANEL", "doce"), ("CARTEL", "12.50")]:
            with self.subTest(clave=clave, valor=valor):
                self.assertEqual(len(ip.errores([fila_del("TASA_ANUNCIO", clave, valor_numerico=valor)])), 1)

    def test_they_are_signed_by_two_people_too(self):
        self.assertEqual(len(ip.errores([fila_del("TASA_ANUNCIO", "PANEL", valor_numerico="12.50", verifico="ANA, 2026-01-09")])), 1)
        self.assertEqual(len(ip.errores([fila_del("PLAZO", "RG_RECURSO", valor_numerico="15", texto="DIAS_HABILES", transcribio=None)])), 1)

    def test_a_tasa_de_anuncio_at_zero_exits_2_before_calling_core(self):
        core = FakeCore()
        self.addCleanup(core.stop)
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
            f.write("# ficticio\n" + ",".join(ip.FIELDS) + "\n")
            f.write("TASA_ANUNCIO,PANEL,2026-01-01,,0,,norma,test,ANA,BETO\n")
        self.addCleanup(os.unlink, f.name)
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = ip.main(["--core", core.base_url, "--csv", f.name])
        self.assertEqual(code, 2, err.getvalue())
        self.assertIn("TASA_ANUNCIO PANEL", err.getvalue())
        self.assertEqual(core.requests, [])


if __name__ == "__main__":
    unittest.main()
