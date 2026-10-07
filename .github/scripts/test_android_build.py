import base64
import tempfile
import unittest
import zipfile
import re
import subprocess
from pathlib import Path

from android_build import (
    BuildError, apk_name, certificate_digest, check_abis, check_lint,
    lint_errors, prepare_nightly, cleanup_nightly, signing_inputs, release_element,
)

CERT = "7050d80a9edd3e66b26df1890fe8f0b0d88f06259faa5c04bf68192262a0db16"
SHA = "a1234567" + "0" * 32


class AndroidBuildTest(unittest.TestCase):
    def test_ci_abi_environment_survives_the_gradle_wrapper_shell(self):
        workflow = (Path(__file__).resolve().parents[1] / "workflows/daily-build.yml").read_text()
        name = re.search(r"^\s+(ORG_GRADLE_PROJECT_[^:]+): arm64-v8a$", workflow, re.MULTILINE).group(1)
        # gradlew uses /bin/sh; names with dots are dropped by Ubuntu's shell.
        process = subprocess.run(["/bin/sh", "-c", 'printf "%s" "$ORG_GRADLE_PROJECT_rikkarootReleaseAbis"'],
                                 env={name: "arm64-v8a"}, capture_output=True, text=True, check=True)
        self.assertEqual("arm64-v8a", process.stdout)

    def report(self, root, errors, warnings=0):
        file = root / "app/build/reports/lint-results-debug.xml"
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text("<issues>" + '<issue severity="Error"/>' * errors
                        + '<issue severity="Warning"/>' * warnings + "</issues>")
        return file

    def test_inherited_51_errors_accept_nonzero_lint_exit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.report(root, 51, 500)
            self.assertEqual(51, check_lint(root, 1))

    def test_52_errors_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.report(root, 52)
            with self.assertRaises(BuildError):
                check_lint(root, 1)

    def test_fatal_counts_and_warnings_do_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            file = self.report(root, 0, 2000)
            file.write_text(file.read_text().replace("</issues>", '<issue severity="Fatal"/></issues>'))
            self.assertEqual(1, lint_errors(root))

    def test_missing_lint_report_cannot_silently_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(BuildError):
                check_lint(Path(tmp), 1)

    def test_signing_uses_only_upstream_four_properties(self):
        config = "storeFile=app.key\nstorePassword=test-only\nkeyAlias=test-key\nkeyPassword=test-only\n"
        self.assertEqual((b"test-only-key", config), signing_inputs(base64.b64encode(b"test-only-key").decode(), config))
        for bad in (config.replace("app.key", "../foreign.key"), config + "extra=value\n", config.replace("keyPassword=test-only", "keyPassword=")):
            with self.assertRaises(BuildError):
                signing_inputs("dGVzdA==", bad)
        with self.assertRaises(BuildError):
            signing_inputs("not base64", config)

    def test_asset_name_matches_version_short_hash_and_arm64(self):
        self.assertEqual("Rikka-Root-2.5.6-root.2-a1234567-arm64-v8a.apk", apk_name("2.5.6-root.2", SHA, "v2.5.6-root.2"))
        self.assertEqual("Rikka-Root-2.5.6-root.2-a1234567-arm64-v8a.apk", apk_name("2.5.6-root.2", SHA))
        for version, sha, tag in (("2.5.6", SHA, None), ("2.5.6-root.2", "bad", None), ("2.5.6-root.2", SHA, "v2.5.6-root.1")):
            with self.assertRaises(BuildError):
                apk_name(version, sha, tag)

    def test_apk_must_contain_only_arm64_libraries(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "app.apk"
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("lib/arm64-v8a/libtest.so", b"test-only")
            check_abis(apk)
            with zipfile.ZipFile(apk, "a") as z:
                z.writestr("lib/x86_64/libtest.so", b"test-only")
            with self.assertRaises(BuildError):
                check_abis(apk)

    def test_one_unsplit_output_is_allowed_but_multiple_or_other_architectures_are_not(self):
        entry = {"filters": [], "outputFile": "app-release.apk"}
        self.assertEqual(entry, release_element({"elements": [entry]}))
        arm = {"filters": [{"filterType": "ABI", "value": "arm64-v8a"}]}
        self.assertEqual(arm, release_element({"elements": [arm]}))
        for entries in ([], [entry, arm], [{"filters": [{"filterType": "ABI", "value": "x86_64"}]}]):
            with self.assertRaises(BuildError):
                release_element({"elements": entries})

    def test_old_and_new_apksigner_formats_accept_exact_certificate(self):
        for prefix in ("Signer #1 certificate", "V2 Signer: certificate"):
            self.assertEqual(CERT, certificate_digest(f"{prefix} SHA-256 digest: {CERT}\n"))

    def test_wrong_or_multiple_certificates_fail(self):
        for output in ("", f"Signer #1 certificate SHA-256 digest: {'0' * 64}\n", f"Signer #1 certificate SHA-256 digest: {CERT}\nSigner #2 certificate SHA-256 digest: {CERT}\n"):
            with self.assertRaises(BuildError):
                certificate_digest(output)

    def test_nightly_moves_fixed_tag_without_deleting_any_asset_before_upload(self):
        calls = []
        def request(method, path, body=None):
            calls.append((method, path, body))
            if path == "releases/tags/nightly":
                return {"assets": [{"id": 7, "name": "old.apk"}, {"id": 8, "name": "notes.txt"}]}
            return {"ref": "refs/tags/nightly"}
        prepare_nightly(SHA, request)
        self.assertIn(("PATCH", "git/refs/tags/nightly", {"sha": SHA, "force": True}), calls)
        self.assertFalse(any(method == "DELETE" for method, _, _ in calls))

    def test_nightly_cleanup_keeps_new_apk_and_other_assets(self):
        calls = []
        def request(method, path, body=None):
            calls.append((method, path, body))
            return {"assets": [{"id": 7, "name": "old.apk"}, {"id": 8, "name": "notes.txt"}, {"id": 9, "name": "new.apk"}]}
        cleanup_nightly("new.apk", request)
        self.assertIn(("DELETE", "releases/assets/7", None), calls)
        self.assertNotIn(("DELETE", "releases/assets/8", None), calls)
        self.assertNotIn(("DELETE", "releases/assets/9", None), calls)

    def test_failed_nightly_upload_never_deletes_previous_apk(self):
        calls = []
        def request(method, path, body=None):
            calls.append(method)
            return {"assets": [{"id": 7, "name": "old.apk"}]}
        with self.assertRaises(BuildError):
            cleanup_nightly("new.apk", request)
        self.assertEqual(["GET"], calls)

    def test_first_nightly_creates_only_the_fixed_tag(self):
        calls = []
        def request(method, path, body=None):
            calls.append((method, path, body))
            return None
        prepare_nightly(SHA, request)
        self.assertIn(("POST", "git/refs", {"ref": "refs/tags/nightly", "sha": SHA}), calls)
        self.assertFalse(any(method == "DELETE" for method, _, _ in calls))


if __name__ == "__main__":
    unittest.main()
