"""Pruebas de roles.json y de apply_roles.py contra un FakeCore.

Correr: cd model && python3 -m unittest -v
"""
import ast
import copy
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import apply_roles
from fake_core import FakeCore

HERE = os.path.dirname(os.path.abspath(__file__))
ROLES_PATH = os.path.join(HERE, "roles.json")
MODEL_PATH = os.path.join(HERE, "model.json")
# la extracción de srtm del corte analítico: sus llamadas a cliente.registros("<objeto>", ...) son lo que lee
CORTE_SRTM = os.path.join(HERE, "..", "docs", "informes", "brecha", "herramientas", "srtm.py")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def permisos(rol):
    """Los permisos de un rol de roles.json como pares (objeto, acción)."""
    return {(objeto, accion) for objeto, acciones in rol["permisos"].items() for accion in acciones}


def objetos_del_corte():
    """Los objetos de srtm que lee el corte: el primer argumento de cada cliente.registros(...) de su srtm.py."""
    with open(CORTE_SRTM, encoding="utf-8") as f:
        arbol = ast.parse(f.read())
    objetos = set()
    for nodo in ast.walk(arbol):
        if isinstance(nodo, ast.Call) and isinstance(nodo.func, ast.Attribute) and nodo.func.attr == "registros":
            primero = nodo.args[0]
            if not (isinstance(primero, ast.Constant) and isinstance(primero.value, str)):
                raise AssertionError(f"srtm.py:{nodo.lineno}: registros() sin un nombre de objeto literal")
            objetos.add(primero.value)
    return objetos


class RolesJsonTests(unittest.TestCase):
    def setUp(self):
        self.roles = {r["name"]: r for r in load(ROLES_PATH)["roles"]}

    def test_declara_solo_el_rol_del_corte_analitico(self):
        # los demás roles de srtm (CAJA, los perfiles de la demo) se manejan en otro lado y apply_roles no los toca
        self.assertEqual(list(self.roles), ["ANALITICA"])
        self.assertTrue(self.roles["ANALITICA"]["label"].strip())
        self.assertTrue(self.roles["ANALITICA"]["description"].strip())

    def test_valida_contra_el_modelo(self):
        self.assertEqual(apply_roles.validate(load(ROLES_PATH), load(MODEL_PATH)), [])

    def test_el_corte_lee_los_once_objetos_de_srtm(self):
        # si esto cambia, la lista blanca del corte cambió: revisa roles.json con ella (#256: brecha_nivel)
        self.assertEqual(objetos_del_corte(), {
            "predio", "contribuyente", "declaracion_predial", "cuota_predial", "nivel_construccion",
            "obra_complementaria", "brecha_corrida", "brecha_predio", "brecha_nivel", "parametro_tributario",
            "arancel"})

    def test_analitica_solo_lee_y_exactamente_lo_que_lee_el_corte(self):
        self.assertEqual(permisos(self.roles["ANALITICA"]), {(o, "READ") for o in objetos_del_corte()})


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.roles = load(ROLES_PATH)
        self.model = load(MODEL_PATH)

    def mutate(self, fn):
        roles = copy.deepcopy(self.roles)
        fn(roles)
        return apply_roles.validate(roles, self.model)

    def test_un_objeto_que_el_modelo_no_tiene_se_rechaza(self):
        errors = self.mutate(lambda r: r["roles"][0]["permisos"].update({"ficha": ["READ"]}))
        self.assertTrue(any("'ficha'" in e for e in errors), errors)

    def test_una_accion_que_core_no_conoce_se_rechaza(self):
        errors = self.mutate(lambda r: r["roles"][0]["permisos"].update({"predio": ["MANAGE_METADATA"]}))
        self.assertTrue(any("MANAGE_METADATA" in e for e in errors), errors)

    def test_un_nombre_que_core_no_acepta_se_rechaza(self):
        errors = self.mutate(lambda r: r["roles"][0].update({"name": "analitica"}))
        self.assertTrue(any("analitica" in e for e in errors), errors)

    def test_un_nombre_repetido_se_rechaza(self):
        errors = self.mutate(lambda r: r["roles"].append(copy.deepcopy(r["roles"][0])))
        self.assertTrue(any("duplicate" in e for e in errors), errors)

    def test_un_rol_sin_etiqueta_se_rechaza(self):
        errors = self.mutate(lambda r: r["roles"][0].update({"label": ""}))
        self.assertTrue(any("label" in e for e in errors), errors)

    def test_admin_no_se_declara(self):
        errors = self.mutate(lambda r: r["roles"][0].update({"name": "ADMIN"}))
        self.assertTrue(any("ADMIN" in e for e in errors), errors)


class ApplyRolesTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        self.roles = load(ROLES_PATH)

    def run_cli(self, extra=(), roles_path=ROLES_PATH):
        args = ["--core", self.core.base_url, "--email", "a@b", "--password", "p", "--roles", roles_path, *extra]
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = apply_roles.main(args)
        return code, out.getvalue(), err.getvalue()

    def writes(self):
        return [(m, p) for m, p, _, _ in self.core.requests if m in ("POST", "PUT", "DELETE") and p != "/api/auth/login"]

    def test_crea_el_rol_con_sus_permisos(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [("POST", "/api/roles"), ("PUT", "/api/roles/ANALITICA/permissions")])
        rol = self.core.roles["ANALITICA"]
        self.assertEqual(rol["label"], self.roles["roles"][0]["label"])
        self.assertEqual({(p["objectName"], p["action"]) for p in rol["permissions"]}, permisos(self.roles["roles"][0]))
        self.assertIn("done: 1 created, 0 updated, 0 skipped", out)

    def test_la_segunda_corrida_no_escribe(self):
        self.run_cli()
        self.core.requests.clear()
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn("done: 0 created, 0 updated, 1 skipped", out)

    def test_no_toca_los_roles_que_no_declara(self):
        caja = {"name": "CAJA", "label": "Caja", "permissions": [{"objectName": "cuota_predial", "action": "UPDATE",
                                                                  "allowed": True}]}
        self.core.roles["CAJA"] = copy.deepcopy(caja)
        code, _, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.core.roles["CAJA"], caja)
        self.assertFalse(any("CAJA" in p for _, p in self.writes()))

    def test_un_permiso_de_mas_se_quita_y_la_etiqueta_se_rehace(self):
        self.run_cli()
        rol = self.core.roles["ANALITICA"]
        rol["label"] = "otra"
        rol["permissions"].append({"objectName": "predio", "action": "UPDATE", "allowed": True})
        self.core.requests.clear()
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [("PUT", "/api/roles/ANALITICA"), ("PUT", "/api/roles/ANALITICA/permissions")])
        self.assertNotIn(("predio", "UPDATE"), {(p["objectName"], p["action"]) for p in rol["permissions"]})
        self.assertEqual(rol["label"], self.roles["roles"][0]["label"])
        self.assertIn("done: 0 created, 1 updated, 0 skipped", out)

    def test_diff_muestra_el_rol_pendiente_sin_escribir_y_despues_de_aplicar_nada(self):
        code, out, err = self.run_cli(["--diff"])
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertEqual(self.core.roles, {})
        self.assertIn("would POST /api/roles ANALITICA", out)
        self.assertIn("would PUT /api/roles/ANALITICA/permissions", out)
        self.assertIn("diff: 2 writes pending, none sent", out)
        self.run_cli()
        self.core.requests.clear()
        code, out, err = self.run_cli(["--diff"])
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn("diff: nothing pending", out)
        self.assertNotIn("would", out)

    def test_diff_muestra_el_permiso_que_cambiaria(self):
        self.run_cli()
        self.core.roles["ANALITICA"]["permissions"].pop()
        self.core.requests.clear()
        code, out, err = self.run_cli(["--diff"])
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn("would PUT /api/roles/ANALITICA/permissions", out)
        self.assertIn("+arancel:READ", out)
        self.assertIn("diff: 1 writes pending, none sent", out)

    def test_dry_run_no_llama_a_core(self):
        code, out, err = self.run_cli(["--dry-run"])
        self.assertEqual(code, 0, err)
        self.assertEqual(self.core.requests, [])
        self.assertIn("# POST /api/roles", out)
        self.assertIn("# PUT /api/roles/ANALITICA/permissions", out)

    def test_diff_y_dry_run_no_van_juntos(self):
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            apply_roles.main(["--diff", "--dry-run"])

    def test_core_que_rechaza_el_rol_sale_con_1(self):
        self.core.fail_on_role = "ANALITICA"
        code, _, err = self.run_cli()
        self.assertEqual(code, 1)
        self.assertIn("500", err)

    def test_roles_json_invalido_sale_con_2_sin_llamar_a_core(self):
        roles = copy.deepcopy(self.roles)
        roles["roles"][0]["permisos"]["ficha"] = ["READ"]
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "roles.json")
            with open(path, "w", encoding="utf-8") as f:
                json.dump(roles, f)
            code, out, _ = self.run_cli(roles_path=path)
        self.assertEqual(code, 2)
        self.assertIn("'ficha'", out)
        self.assertEqual(self.core.requests, [])

    def test_roles_json_que_falta_sale_con_2(self):
        code, _, err = self.run_cli(roles_path="/no/existe/roles.json")
        self.assertEqual(code, 2)
        self.assertIn("/no/existe/roles.json", err)


if __name__ == "__main__":
    unittest.main()
