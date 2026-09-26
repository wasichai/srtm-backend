"""End-to-end tests for apply.py's CLI against a fake Core.

Run: cd model && python3 -m unittest -v
"""
import io
import json
import os
import socket
import unittest
from contextlib import redirect_stdout, redirect_stderr

import apply
from fake_core import FakeCore

MODEL_PATH = os.path.join(os.path.dirname(__file__), "model.json")

OBJECTS = 3
RELATIONSHIPS = 2


class ApplyCliTestCase(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)

    def run_cli(self, extra_args, core_url=None):
        args = [
            "--model", MODEL_PATH,
            "--core", core_url or self.core.base_url,
            "--email", "admin@wasichai.local",
            "--password", "admin",
        ] + extra_args
        out = io.StringIO()
        err = io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = apply.main(args)
        return code, out.getvalue(), err.getvalue()


class DryRunTests(ApplyCliTestCase):
    def test_dry_run_makes_no_requests_and_prints_headers(self):
        code, out, err = self.run_cli(["--dry-run"])
        self.assertEqual(code, 0)
        self.assertEqual(self.core.requests, [])
        self.assertEqual(out.count("# POST /api/objects"), OBJECTS)
        self.assertEqual(out.count("# POST /api/relationships"), RELATIONSHIPS)


class HappyPathTests(ApplyCliTestCase):
    def test_creates_everything_in_order(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)

        object_posts = [r[3]["name"] for r in self.core.requests if r[1] == "/api/objects" and r[0] == "POST"]
        self.assertEqual(object_posts, ["contribuyente", "predio", "declaracion_predial"])

        rel_posts = [r[3]["name"] for r in self.core.requests if r[1] == "/api/relationships" and r[0] == "POST"]
        self.assertEqual(rel_posts, ["declaracion_predial_contribuyente", "declaracion_predial_predio"])

        # both relations are required: one PUT each, right after its POST
        puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT"]
        self.assertEqual(puts, [
            ("/api/metadata/objects/declaracion_predial/fields/contribuyente", {"required": True}),
            ("/api/metadata/objects/declaracion_predial/fields/predio", {"required": True}),
        ])

        for method, path, auth, body in self.core.requests:
            if path == "/api/auth/login":
                self.assertIsNone(auth)
            else:
                self.assertEqual(auth, "Bearer t")

        self.assertIn("done: 5 created, 0 skipped", out)


class IdempotencyTests(ApplyCliTestCase):
    def setUp(self):
        with open(MODEL_PATH, encoding="utf-8") as f:
            model = json.load(f)
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
        )
        self.addCleanup(self.core.stop)

    def test_everything_skips(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        object_posts = [r for r in self.core.requests if r[1] == "/api/objects" and r[0] == "POST"]
        self.assertEqual(object_posts, [])
        self.assertIn("done: 0 created, 5 skipped", out)


class FailureStopsTests(ApplyCliTestCase):
    def setUp(self):
        self.core = FakeCore(fail_on_post_object="predio")
        self.addCleanup(self.core.stop)

    def test_500_on_second_object_aborts(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 1)
        self.assertIn("boom", err)
        object_posts = [r for r in self.core.requests if r[1] == "/api/objects" and r[0] == "POST"]
        self.assertEqual(len(object_posts), 2)
        self.assertEqual([r for r in self.core.requests if r[1] == "/api/relationships"], [])


class RequiredPutFailureTests(ApplyCliTestCase):
    """The required PUT tolerates nothing, not even 409."""

    def setUp(self):
        self.core = FakeCore(fail_put=True, fail_put_status=409)
        self.addCleanup(self.core.stop)

    def test_409_on_required_put_aborts(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 1)
        self.assertIn("/api/metadata/objects/declaracion_predial/fields/contribuyente", err)
        rel_posts = [r[3]["name"] for r in self.core.requests if r[1] == "/api/relationships" and r[0] == "POST"]
        self.assertEqual(rel_posts, ["declaracion_predial_contribuyente"])


class ConnectionFailureTests(unittest.TestCase):
    def test_closed_port_is_fatal_with_no_traceback(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()

        out = io.StringIO()
        err = io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = apply.main(["--model", MODEL_PATH, "--core", f"http://127.0.0.1:{port}"])
        self.assertEqual(code, 1)
        self.assertIn("connection failed", err.getvalue())
        self.assertNotIn("Traceback", err.getvalue())


class LoginMissingTokenTests(ApplyCliTestCase):
    def setUp(self):
        self.core = FakeCore(login_response={})
        self.addCleanup(self.core.stop)

    def test_login_without_token_is_fatal(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 1)
        self.assertIn("error POST /api/auth/login -> no token in response", err)
        self.assertEqual(len(self.core.requests), 1)


class DropTests(ApplyCliTestCase):
    def test_drop_deletes_relationships_then_objects_in_reverse(self):
        code, out, err = self.run_cli(["--drop"])
        self.assertEqual(code, 0, msg=err)
        deletes = [r[1] for r in self.core.requests if r[0] == "DELETE"]
        self.assertEqual(deletes, [
            "/api/relationships/declaracion_predial_predio",
            "/api/relationships/declaracion_predial_contribuyente",
            "/api/objects/declaracion_predial",
            "/api/objects/predio",
            "/api/objects/contribuyente",
        ])
        self.assertIn("done: 5 deleted, 0 skipped", out)

    def test_drop_dry_run_makes_no_requests(self):
        code, out, err = self.run_cli(["--drop", "--dry-run"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.core.requests, [])
        self.assertEqual(out.count("# DELETE "), OBJECTS + RELATIONSHIPS)


class ValidateOnlyTests(ApplyCliTestCase):
    def test_validate_only_makes_no_requests(self):
        code, out, err = self.run_cli(["--validate-only"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.core.requests, [])


if __name__ == "__main__":
    unittest.main()
