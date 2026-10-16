import importlib.util
from pathlib import Path
import tempfile
import subprocess
from unittest.mock import patch
import unittest

spec = importlib.util.spec_from_file_location("audit_baseline", Path(__file__).with_name("audit_baseline.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class BaselineEvidenceTest(unittest.TestCase):
    def check(self, content):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "TEST-regression.xml"
            report.write_text(content)
            return audit.check_evidence([report], {1: [("RegressionTest", "^critical")], 2: [("OtherTest", "^schema")]})

    def test_requires_an_assertion_failure_for_every_condition(self):
        result = self.check('<testsuite><testcase classname="app.RegressionTest" name="critical case"><failure type="java.lang.AssertionError"/></testcase><testcase classname="ai.OtherTest" name="schema null"><failure type="org.junit.ComparisonFailure"/></testcase></testsuite>')
        self.assertEqual({"1", "2"}, set(result))
        self.assertEqual(["ai.OtherTest.schema null"], result["2"])

    def test_download_or_class_initialization_error_cannot_count_as_red(self):
        with self.assertRaisesRegex(ValueError, "1:"):
            self.check('<testsuite><testcase classname="app.RegressionTest" name="critical case"><failure type="java.lang.ExceptionInInitializerError"/></testcase><testcase classname="ai.OtherTest" name="schema null"><failure type="java.lang.AssertionError"/></testcase></testsuite>')

    def test_passed_skipped_and_missing_cases_cannot_count_as_red(self):
        for first in ('<testcase classname="app.RegressionTest" name="critical case"/>', '<testcase classname="app.RegressionTest" name="critical case"><skipped/></testcase>', ''):
            with self.subTest(first=first), self.assertRaisesRegex(ValueError, "1:"):
                self.check('<testsuite>' + first + '<testcase classname="ai.OtherTest" name="schema null"><failure type="java.lang.AssertionError"/></testcase></testsuite>')

    def test_unrelated_failing_method_cannot_satisfy_condition(self):
        with self.assertRaisesRegex(ValueError, "1:"):
            self.check('<testsuite><testcase classname="app.RegressionTest" name="unrelated"><failure type="java.lang.AssertionError"/></testcase><testcase classname="ai.OtherTest" name="schema null"><failure type="java.lang.AssertionError"/></testcase></testsuite>')

    def test_stale_output_xml_cannot_supply_missing_fresh_assertion_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            root.mkdir()

            def git(*args, cwd=root):
                return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()

            git("init", "-q")
            git("config", "user.name", "Audit Test")
            git("config", "user.email", "audit@example.invalid")
            submodule = root / "material3/material-color-utilities"
            submodule.mkdir(parents=True)
            git("init", "-q", str(submodule))
            git("-c", "user.name=Audit Test", "-c", "user.email=audit@example.invalid",
                "commit", "-q", "--allow-empty", "-m", "fixture", cwd=submodule)
            submodule_commit = git("rev-parse", "HEAD", cwd=submodule)
            git("update-index", "--add", "--cacheinfo",
                f"160000,{submodule_commit},material3/material-color-utilities")
            (root / "web-ui/node_modules").mkdir(parents=True)
            (root / "web-ui/.gitkeep").touch()
            gradlew = root / "gradlew"
            gradlew.write_text(
                "#!/bin/sh\n"
                "mkdir -p ai/build/test-results/testDebugUnitTest\n"
                "printf '%s' '<testsuite><testcase classname=\"app.RegressionTest\" name=\"critical fresh\"/></testsuite>' "
                "> ai/build/test-results/testDebugUnitTest/TEST-fresh.xml\n"
                "exit 1\n"
            )
            gradlew.chmod(0o755)
            git("add", "gradlew", "web-ui/.gitkeep")
            git("commit", "-q", "-m", "fixture")
            baseline = git("rev-parse", "HEAD")
            output = Path(directory) / "output"
            (output / "ai").mkdir(parents=True)
            stale = output / "ai/TEST-stale.xml"
            stale.write_text('<testsuite><testcase classname="app.RegressionTest" name="critical old"><failure type="java.lang.AssertionError"/></testcase></testsuite>')
            with patch.object(audit, "BASELINE", baseline), patch.object(audit, "AI_CLASSES", []), \
                    patch.object(audit, "APP_CLASSES", []), \
                    patch.dict(audit.REQUIRED_FAILURES, {1: [("RegressionTest", "^critical")]}, clear=True):
                with self.assertRaisesRegex(ValueError, "1:"):
                    audit.run_baseline(root, output)
            self.assertTrue(stale.exists(), "old copied reports can remain without becoming evidence")
            self.assertFalse((output / "evidence.json").exists())

    def test_previous_evidence_is_removed_even_when_new_checkout_cannot_start(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            output.mkdir()
            evidence = output / "evidence.json"
            evidence.write_text('{"stale":true}')
            failure = subprocess.CalledProcessError(1, ["git", "merge-base"])
            with patch.object(audit.subprocess, "run", side_effect=failure):
                with self.assertRaises(subprocess.CalledProcessError):
                    audit.run_baseline(Path(directory), output)
            self.assertFalse(evidence.exists(), "a failed new attempt must not publish old evidence")

    def test_point_map_covers_exactly_all_twenty_one_audit_items(self):
        self.assertEqual(set(range(1, 22)), set(audit.REQUIRED_FAILURES))
        self.assertTrue(all(audit.REQUIRED_FAILURES.values()))


if __name__ == "__main__":
    unittest.main()
