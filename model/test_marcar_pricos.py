"""Tests for marcar_pricos.py (#273): the PRICO columns found in a synthetic padrón vinculado, the plan, and a dry run
and an idempotent load against a fake Core. No real padrón: every workbook is made here, its codes and rows invented.

Run: cd model && python3 -m unittest -v test_marcar_pricos
"""
import io
import os
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from xml.sax.saxutils import escape

import marcar_pricos as mp
from fake_core import FakeCore

NS = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
REL_NS = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'


def _columna(i):
    letras = ""
    i += 1
    while i:
        i, resto = divmod(i - 1, 26)
        letras = chr(65 + resto) + letras
    return letras


def escribir_xlsx(path, hojas):
    """A minimal xlsx of several sheets ({name: (header, rows)}), every value an inline string or a number."""
    with zipfile.ZipFile(path, "w") as z:
        sheets = "".join(f'<sheet name="{escape(n)}" sheetId="{i}" r:id="rId{i}"/>' for i, n in enumerate(hojas, start=1))
        z.writestr("xl/workbook.xml", f"<workbook {NS} {REL_NS}><sheets>{sheets}</sheets></workbook>")
        rels = "".join(
            f'<Relationship Id="rId{i}" Target="worksheets/sheet{i}.xml" '
            f'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"/>'
            for i in range(1, len(hojas) + 1)
        )
        z.writestr("xl/_rels/workbook.xml.rels", f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">{rels}</Relationships>')
        for i, (header, rows) in enumerate(hojas.values(), start=1):
            xml_rows = []
            for r, values in enumerate([dict(zip(header, header))] + rows, start=1):
                cells = []
                for c, name in enumerate(header):
                    value = values.get(name)
                    if value is None:
                        continue
                    ref = f"{_columna(c)}{r}"
                    if isinstance(value, (int, float)):
                        cells.append(f'<c r="{ref}"><v>{value}</v></c>')
                    else:
                        cells.append(f'<c r="{ref}" t="inlineStr"><is><t>{escape(str(value))}</t></is></c>')
                xml_rows.append(f'<row r="{r}">{"".join(cells)}</row>')
            z.writestr(f"xl/worksheets/sheet{i}.xml", f'<worksheet {NS}><sheetData>{"".join(xml_rows)}</sheetData></worksheet>')


CABECERA = ["cod_predio", "cod_contrib", "pricos", "pricos_codigo"]

# invented: P-1 and P-2 are PRICOs (P-2 twice, as a code read as a number), N-1 is not
FILAS = [
    {"cod_predio": "X-01", "cod_contrib": "900001", "pricos": 1, "pricos_codigo": "900001"},
    {"cod_predio": "X-02", "cod_contrib": "900002", "pricos": "1", "pricos_codigo": 900002.0},
    {"cod_predio": "X-03", "cod_contrib": "900002", "pricos": 1, "pricos_codigo": "900002"},
    {"cod_predio": "X-04", "cod_contrib": "900003", "pricos": None, "pricos_codigo": None},
    # a PRICO code no contribuyente of Core has
    {"cod_predio": "X-05", "cod_contrib": "900009", "pricos": 1, "pricos_codigo": "900009"},
]


def contribuyente(record_id, code, prico=None):
    attributes = {"codigo": code, "nombre_completo": "CONTRIBUYENTE DE PRUEBA"}
    if prico is not None:
        attributes["prico"] = prico
    return {"id": record_id, "attributes": attributes}


class LeerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, "padron.xlsx")

    def tearDown(self):
        self.tmp.cleanup()

    def test_the_sheet_with_both_columns_is_found_and_its_marked_codes_read(self):
        escribir_xlsx(self.path, {"resumen": (["total"], [{"total": 5}]), "vinculado": (CABECERA, FILAS)})
        hoja, rows = mp.leer_filas(self.path)
        self.assertEqual("vinculado", hoja)
        self.assertEqual({"900001", "900002", "900009"}, mp.marcados(rows, 2026))

    def test_other_column_names_by_their_options(self):
        escribir_xlsx(self.path, {"hoja": (["cod_contrib", "es_prico", "codigo_prico"], [{"cod_contrib": "1", "es_prico": "1", "codigo_prico": "000123"}])})
        hoja, rows = mp.leer_filas(self.path, col_prico="es_prico", col_codigo="codigo_prico")
        self.assertEqual({"000123"}, mp.marcados(rows, 2026, "es_prico", "codigo_prico"))

    def test_a_missing_column_or_sheet_is_a_clear_failure(self):
        escribir_xlsx(self.path, {"vinculado": (["cod_predio", "cod_contrib", "pricos"], [{"cod_predio": "X", "cod_contrib": "1", "pricos": 1}])})
        with self.assertRaises(mp.Falta) as e:
            mp.leer_filas(self.path)
        self.assertIn("'pricos_codigo'", str(e.exception))
        self.assertIn("vinculado", str(e.exception))
        with self.assertRaises(mp.Falta) as e:
            mp.leer_filas(self.path, hoja="otra")
        self.assertIn("'otra'", str(e.exception))

    def test_rows_of_another_year_are_left_out(self):
        rows = [{"anio": "2025", "pricos": "1", "pricos_codigo": "1"}, {"anio": 2026.0, "pricos": "1", "pricos_codigo": "2"}]
        self.assertEqual({"2"}, mp.marcados(rows, 2026))


class PlanTests(unittest.TestCase):
    def test_marks_unmarks_and_counts(self):
        core = [contribuyente("a", "900001"), contribuyente("b", "900002", True), contribuyente("c", "900003", True), contribuyente("d", "900004", False)]
        cambios, conteos = mp.plan(core, {"900001", "900002", "900009"})
        self.assertEqual({"a": True, "c": False}, cambios)
        self.assertEqual({"marcados": 1, "desmarcados": 1, "sin_cambios": 2, "sin_contribuyente": 1}, conteos)

    def test_sin_desmarcar_keeps_who_was_marked(self):
        cambios, conteos = mp.plan([contribuyente("c", "900003", True)], set(), desmarcar=False)
        self.assertEqual({}, cambios)
        self.assertEqual(1, conteos["sin_cambios"])


class CargaTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, "padron-vinculado.xlsx")
        escribir_xlsx(self.path, {"vinculado": (CABECERA, FILAS)})
        self.core = FakeCore(existing_fields={"contribuyente": [{"name": "codigo"}, {"name": "prico"}]})
        self.core.add_record("contribuyente", {"codigo": "900001"})
        self.core.add_record("contribuyente", {"codigo": "900002"})
        self.core.add_record("contribuyente", {"codigo": "900003", "prico": True})

    def tearDown(self):
        self.core.stop()
        self.tmp.cleanup()

    def run_main(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = mp.main(["--excel", self.path, "--anio", "2026", "--core", self.core.base_url, *extra])
        return code, out.getvalue(), err.getvalue()

    def pricos(self):
        return {r["attributes"]["codigo"]: r["attributes"].get("prico") for r in self.core.records["contribuyente"]}

    def patches(self):
        return [r for r in self.core.requests if r[0] == "PATCH"]

    def test_dry_run_counts_and_writes_nothing(self):
        code, out, _ = self.run_main("--dry-run")
        self.assertEqual(0, code)
        self.assertIn("marcados: 2", out)
        self.assertIn("desmarcados: 1", out)
        self.assertIn("sin cambios: 0", out)
        self.assertIn("códigos sin contribuyente: 1", out)
        self.assertEqual([], self.patches())
        self.assertEqual({"900001": None, "900002": None, "900003": True}, self.pricos())
        # counts only: no code of the padrón is printed
        self.assertNotIn("900001", out)

    def test_the_load_patches_prico_alone_with_its_reason_and_a_second_run_changes_nothing(self):
        code, out, _ = self.run_main()
        self.assertEqual(0, code, out)
        self.assertEqual({"900001": True, "900002": True, "900003": False}, self.pricos())
        self.assertTrue(all(set(body["attributes"]) == {"prico"} for *_, body in self.patches()))
        self.assertEqual(3, len(self.patches()))
        self.assertEqual({"pricos:padron-vinculado.xlsx"}, {razon for _, _, razon in self.core.change_reasons})
        self.assertEqual(3, len(self.core.change_reasons))
        code, out, _ = self.run_main()
        self.assertEqual(0, code)
        self.assertIn("sin cambios: 3", out)
        self.assertEqual(3, len(self.patches()))

    def test_sin_desmarcar_leaves_the_old_marks(self):
        code, _, _ = self.run_main("--sin-desmarcar")
        self.assertEqual(0, code)
        self.assertEqual({"900001": True, "900002": True, "900003": True}, self.pricos())

    def test_without_the_field_in_the_model_nothing_is_sent(self):
        self.core.existing_fields["contribuyente"] = [{"name": "codigo"}]
        code, _, err = self.run_main()
        self.assertEqual(2, code)
        self.assertIn("prico", err)
        self.assertEqual([], self.patches())

    def test_a_workbook_without_the_columns_stops_before_core(self):
        escribir_xlsx(self.path, {"vinculado": (["cod_predio", "cod_contrib"], [{"cod_predio": "X", "cod_contrib": "1"}])})
        code, _, err = self.run_main()
        self.assertEqual(2, code)
        self.assertIn("--col-prico", err)
        self.assertEqual([], self.core.requests)


if __name__ == "__main__":
    unittest.main()
