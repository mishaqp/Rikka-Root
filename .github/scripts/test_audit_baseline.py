import importlib.util
from pathlib import Path
import re
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

    def test_every_required_condition_matches_a_current_test_declaration(self):
        root = Path(__file__).resolve().parents[2]
        declarations = {}
        for module, classes in (("ai", audit.AI_CLASSES), ("app", audit.APP_CLASSES)):
            for class_name in classes:
                source = (root / Path(module, "src/test/java", *class_name.split(".")).with_suffix(".kt")).read_text()
                methods = re.findall(r"@Test(?:\([^)]*\))?\s+fun\s+(?:`([^`]+)`|([A-Za-z_]\w*))\s*\(", source)
                declarations[class_name.rsplit(".", 1)[-1]] = [quoted or ordinary for quoted, ordinary in methods]
        missing = []
        for number, conditions in audit.REQUIRED_FAILURES.items():
            for class_name, selector in conditions:
                if not any(re.search(selector, name, re.IGNORECASE) for name in declarations.get(class_name, [])):
                    missing.append(f"{number}: {class_name} / {selector}")
        self.assertEqual([], missing, "Required RED selectors must name checked-in @Test declarations")

    def test_compatibility_api_absence_is_never_behavioral_red(self):
        marker = "BASELINE_COMPAT_API_ABSENT_NOT_RED_EVIDENCE"
        failures = [
            f'<failure type="java.lang.AssertionError" message="{marker}: missing helper"/>',
            f'<failure type="java.lang.AssertionError">{marker}: missing helper</failure>',
            f'<failure type="java.lang.AssertionError"><detail>{marker}: missing helper</detail></failure>',
        ]
        for failure in failures:
            with self.subTest(failure=failure), self.assertRaisesRegex(ValueError, "1:"):
                self.check('<testsuite><testcase classname="app.RegressionTest" name="critical case">' + failure +
                           '</testcase><testcase classname="ai.OtherTest" name="schema null"><failure type="java.lang.AssertionError"/></testcase></testsuite>')

    def test_copy_adapts_only_the_detached_checkout_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            relative = Path("app/src/test/java/example/RegressionTest.kt")
            (root / relative).parent.mkdir(parents=True)
            (root / relative).write_text("original fixture\n")
            (root / "material3/material-color-utilities").mkdir(parents=True)
            (root / "web-ui/node_modules").mkdir(parents=True)
            observed = []

            def fake_run(command, **kwargs):
                if command[:3] == ["git", "worktree", "add"]:
                    checkout = Path(command[4])
                    (checkout / "material3/material-color-utilities").mkdir(parents=True)
                    (checkout / "web-ui").mkdir()
                if command[0] == "./gradlew":
                    checkout = Path(kwargs["cwd"])
                    observed.append((checkout / relative).read_text())
                    reports = checkout / "app/build/test-results/testDebugUnitTest"
                    reports.mkdir(parents=True)
                    (reports / "TEST-fixture.xml").write_text('<testsuite><testcase classname="example.RegressionTest" name="critical case"><failure type="java.lang.AssertionError"/></testcase></testsuite>')
                    return subprocess.CompletedProcess(command, 1)
                return subprocess.CompletedProcess(command, 0)

            def fake_output(command, **kwargs):
                return "160000 commit same-object material3/material-color-utilities" if command[1] == "ls-tree" else "same-object"

            with patch.object(audit.subprocess, "run", side_effect=fake_run), \
                    patch.object(audit.subprocess, "check_output", side_effect=fake_output), \
                    patch.object(audit, "AI_CLASSES", []), patch.object(audit, "APP_CLASSES", ["example.RegressionTest"]), \
                    patch.dict(audit.REQUIRED_FAILURES, {1: [("RegressionTest", "^critical")]}, clear=True), \
                    patch.object(audit, "adapt_baseline_test", return_value="adapted fixture\n", create=True) as adapter:
                audit.run_baseline(root, Path(directory) / "output")
            self.assertEqual(["adapted fixture\n"], observed)
            self.assertEqual("original fixture\n", (root / relative).read_text())
            adapter.assert_called_once()
            self.assertEqual(relative, adapter.call_args.args[0])
            self.assertEqual("original fixture\n", adapter.call_args.args[1])


class BaselineFixtureCompatibilityTest(unittest.TestCase):
    generation = Path("app/src/test/java/me/rerere/rikkahub/data/ai/GenerationLoopAuditRegressionTest.kt")
    screenshot = Path("app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/ScreenshotStoragePrivacyTest.kt")
    metadata = Path("app/src/test/java/me/rerere/rikkahub/service/ChatServiceMetadataTest.kt")
    generation_api = Path("app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt")
    screenshot_api = Path("app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/ScreenshotTool.kt")
    old_generation = '''fun generateText(
        prepareToolApproval: (String, JsonElement) -> JsonElement,
        beforeModelRequest: suspend () -> Unit,
    ): Flow<GenerationChunk> = flow {
        val hasShellAccess = tools.any { it.name == "workspace_shell" }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output
    }
'''
    old_screenshot = "fun takeScreenshotTool(context: Context, service: RootScreenService): Tool = Tool(\n"

    def current(self, relative):
        return (Path(__file__).resolve().parents[2] / relative).read_text()

    def adapt(self, relative, source, checkout):
        adapter = getattr(audit, "adapt_baseline_test", None)
        self.assertTrue(callable(adapter), "The baseline COPY step needs an explicit fixture adapter")
        return adapter(relative, source, checkout)

    def old_checkout(self, checkout):
        for relative, source in ((self.generation_api, self.old_generation), (self.screenshot_api, self.old_screenshot)):
            (checkout / relative).parent.mkdir(parents=True, exist_ok=True)
            (checkout / relative).write_text(source)

    def test_generation_bridge_preserves_every_existing_test_body_and_assertion(self):
        source = self.current(self.generation)
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            self.old_checkout(checkout)
            adapted = self.adapt(self.generation, source, checkout)
        anchor = "    private class Fixture("
        before, after = source.split(anchor)
        self.assertTrue(adapted.startswith(before))
        self.assertTrue(adapted.endswith(anchor + after))
        helper = adapted[len(before):len(adapted) - len(anchor + after)]
        self.assertIn("private fun boundToolOutputsForModel", helper)
        self.assertIn("catch (_: NoSuchMethodException)", helper)
        self.assertIn("return messages", helper)
        self.assertNotIn("@Test", helper)
        self.assertNotIn("catch (_: Exception)", helper)
        self.assertEqual(source, adapted.replace(helper, "", 1))

    def test_screenshot_bridge_only_appends_a_helper_and_marks_absent_api_as_not_red(self):
        source = self.current(self.screenshot)
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            self.old_checkout(checkout)
            adapted = self.adapt(self.screenshot, source, checkout)
        self.assertTrue(source.endswith("\n}\n"))
        self.assertTrue(adapted.startswith(source[:-2]))
        self.assertTrue(adapted.endswith("}\n"))
        helper = adapted[len(source) - 2:-2]
        self.assertIn("private suspend fun keepWithChat", helper)
        self.assertIn("BASELINE_COMPAT_API_ABSENT_NOT_RED_EVIDENCE", helper)
        self.assertIn("method.invoke(null, cacheFile, displayName, files, continuation)", helper)
        self.assertNotIn("@Test", helper)
        self.assertEqual(source, adapted[:len(source) - 2] + adapted[-2:])

    def test_metadata_removes_only_the_five_unsupported_mock_arguments(self):
        source = self.current(self.metadata)
        unsupported = ", approveEverything = any()"
        self.assertEqual(5, source.count(unsupported))
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            self.old_checkout(checkout)
            adapted = self.adapt(self.metadata, source, checkout)
        self.assertEqual(source.replace(unsupported, ""), adapted)
        self.assertEqual(source.count("@Test"), adapted.count("@Test"))

    def test_supported_current_apis_leave_all_three_fixtures_byte_identical(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            for relative in (self.generation_api, self.screenshot_api):
                (checkout / relative).parent.mkdir(parents=True, exist_ok=True)
                (checkout / relative).write_text(self.current(relative))
            for relative in (self.generation, self.screenshot, self.metadata):
                with self.subTest(relative=relative):
                    source = self.current(relative)
                    self.assertEqual(source, self.adapt(relative, source, checkout))
            self.assertEqual("unrelated source", self.adapt(Path("unrelated.kt"), "unrelated source", checkout))

    def test_missing_fixture_anchors_fail_closed_instead_of_dropping_tests(self):
        variants = [
            (self.generation, self.current(self.generation).replace("    private class Fixture(", "    private class RenamedFixture(")),
            (self.screenshot, self.current(self.screenshot).replace("class ScreenshotStoragePrivacyTest {", "class RenamedScreenshotTest {")),
            (self.metadata, self.current(self.metadata).replace(", approveEverything = any()", "", 1)),
        ]
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            self.old_checkout(checkout)
            for relative, source in variants:
                with self.subTest(relative=relative), self.assertRaises(ValueError):
                    self.adapt(relative, source, checkout)

    def test_unknown_baseline_api_shapes_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            for relative in (self.generation_api, self.screenshot_api):
                (checkout / relative).parent.mkdir(parents=True, exist_ok=True)
                (checkout / relative).write_text("unknown API\n")
            for relative in (self.generation, self.screenshot, self.metadata):
                with self.subTest(relative=relative), self.assertRaises(ValueError):
                    self.adapt(relative, self.current(relative), checkout)


if __name__ == "__main__":
    unittest.main()
