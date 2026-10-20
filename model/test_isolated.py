"""Every command of model/ runs under python3 -I from another directory: the ones that read files from outside the
repository (an xlsx, a csv) are run that way, so they put back only their own directory on sys.path."""

import glob
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
COMMANDS = sorted(
    os.path.basename(p)
    for p in glob.glob(os.path.join(HERE, "*.py"))
    if not os.path.basename(p).startswith("test_") and os.path.basename(p) not in ("core_client.py", "fake_core.py")
)


class IsolatedTests(unittest.TestCase):
    def test_every_command_answers_help_under_isolated_mode(self):
        with tempfile.TemporaryDirectory() as cwd:
            for command in COMMANDS:
                with self.subTest(command=command):
                    run = subprocess.run(
                        [sys.executable, "-I", os.path.join(HERE, command), "--help"],
                        cwd=cwd, capture_output=True, text=True, timeout=60,
                    )
                    self.assertEqual(run.returncode, 0, run.stderr[-500:])


if __name__ == "__main__":
    unittest.main()
