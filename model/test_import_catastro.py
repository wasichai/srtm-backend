"""Tests for import_catastro.py: property mapping, polygons, problems, and an idempotent load.

Run: cd model && python3 -m unittest -v
"""
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import import_catastro as ic
from fake_core import FakeCore

HERE = os.path.dirname(os.path.abspath(__file__))
SQUARE = [[[-75.2250, -10.9480], [-75.2245, -10.9480], [-75.2245, -10.9475], [-75.2250, -10.9475], [-75.2250, -10.9480]]]


def enums():
    with open(os.path.join(HERE, "model.json"), encoding="utf-8") as f:
        return json.load(f)["enums"]


def feature(props, geometry=None):
    return {"type": "Feature", "properties": props, "geometry": geometry or {"type": "Polygon", "coordinates": SQUARE}}


class LotesFromTests(unittest.TestCase):
    def test_maps_properties_and_takes_a_one_part_multipolygon(self):
        collection = {"features": [
            feature({"CPU": "54102166-0001-2", "via": "MARGINAL", "tipo_via": "AVENIDA", "manzana": 3}),
            feature({"CPU": "54102167-0001-7"}, {"type": "MultiPolygon", "coordinates": [SQUARE]}),
        ]}
        lotes, problems = ic.lotes_from(collection, ic.parse_map(["codigo_cpu=CPU"]), enums())
        self.assertEqual(problems, [])
        self.assertEqual(lotes[0][1]["codigo_cpu"], "54102166-0001-2")
        self.assertEqual(lotes[0][1]["manzana"], "3")
        self.assertEqual(lotes[1][2], {"type": "Polygon", "coordinates": SQUARE})

    def test_problems_stop_the_load(self):
        collection = {"features": [
            feature({"codigo_cpu": None}),
            feature({"codigo_cpu": "A"}),
            feature({"codigo_cpu": "A"}),
            feature({"codigo_cpu": "B", "tipo_via": "AUTOPISTA"}),
            feature({"codigo_cpu": "C"}, {"type": "Point", "coordinates": [-75.2, -10.9]}),
        ]}
        _, problems = ic.lotes_from(collection, ic.parse_map([]), enums())
        self.assertEqual(len(problems), 4, problems)

    def test_a_bad_map_is_refused(self):
        with self.assertRaises(ValueError):
            ic.parse_map(["color=COLOR"])


class LoadTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)

    def run_cli(self, collection, *extra):
        with tempfile.NamedTemporaryFile("w", suffix=".geojson", delete=False, encoding="utf-8") as f:
            json.dump(collection, f)
        self.addCleanup(os.unlink, f.name)
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = ic.main(["--geojson", f.name, "--core", self.core.base_url, *extra])
        return code, out.getvalue(), err.getvalue()

    def test_posts_polygons_once(self):
        collection = {"type": "FeatureCollection", "features": [feature({"codigo_cpu": "A"}), feature({"codigo_cpu": "B"})]}
        self.core.add_record("catastro_fiscal", {"codigo_cpu": "A"})
        code, out, err = self.run_cli(collection)
        self.assertEqual(code, 0, err)
        self.assertIn("done: 1 created, 1 skipped", out)
        created = self.core.records["catastro_fiscal"][-1]
        self.assertEqual(created["attributes"], {"codigo_cpu": "B"})
        self.assertEqual(created["geometries"]["lote_geom"]["type"], "Polygon")

    def test_dry_run_and_problems_send_nothing(self):
        code, _, _ = self.run_cli({"features": [feature({"codigo_cpu": "A"})]}, "--dry-run")
        self.assertEqual(code, 0)
        code, _, err = self.run_cli({"features": [feature({})]})
        self.assertEqual(code, 2)
        self.assertIn("nothing was sent", err)
        self.assertEqual([r for r in self.core.requests if r[0] == "POST" and "records" in r[1]], [])


if __name__ == "__main__":
    unittest.main()
