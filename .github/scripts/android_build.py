"""Checks for the existing daily build. Never print signing inputs or exception details."""
import argparse
import base64
import binascii
import hashlib
import json
import os
import re
import shutil
import subprocess
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

EXPECTED_CERT = "7050d80a9edd3e66b26df1890fe8f0b0d88f06259faa5c04bf68192262a0db16"
MAX_LINT_ERRORS = 51  # Upstream 4a7a39c4; this does not suppress any lint finding.
REPOSITORY = "mishaqp/Rikka-Root"


class BuildError(Exception):
    pass


def lint_errors(root):
    reports = sorted(root.glob("*/build/reports/lint-results-debug.xml"))
    if root / "app/build/reports/lint-results-debug.xml" not in reports:
        raise BuildError("Fresh app lint XML is missing; refusing to accept an incomplete analysis.")
    return sum(issue.get("severity") in ("Error", "Fatal")
               for report in reports for issue in ET.parse(report).getroot().findall("issue"))


def check_lint(root, exit_code):
    count = lint_errors(root)
    if count > MAX_LINT_ERRORS:
        raise BuildError(f"Lint: {count} errors, allowed inherited count: {MAX_LINT_ERRORS}.")
    if exit_code not in (0, 1):
        raise BuildError("Lint process did not finish normally.")
    return count


def signing_inputs(encoded, config):
    try:
        key = base64.b64decode("".join(encoded.split()), validate=True)
    except (ValueError, binascii.Error):
        raise BuildError("KEY_BASE64 is missing or invalid.") from None
    values = {}
    lines = config.splitlines()
    if len(lines) != 4:
        raise BuildError("SIGNING_CONFIG must contain exactly four local.properties lines.")
    for line in lines:
        if "=" not in line:
            raise BuildError("SIGNING_CONFIG is invalid.")
        name, value = line.split("=", 1)
        if name in values or not value:
            raise BuildError("SIGNING_CONFIG contains a duplicate or empty property.")
        values[name] = value
    if not key or set(values) != {"storeFile", "storePassword", "keyAlias", "keyPassword"} or values["storeFile"] != "app.key":
        raise BuildError("Signing must use the upstream app/app.key and four required properties.")
    return key, config


def apk_name(version, sha, tag=None):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+-root\.[0-9]+", version) or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise BuildError("Invalid fork version or commit hash.")
    if tag is not None and tag != "v" + version:
        raise BuildError("Release tag must match the APK version, for example v2.5.6-root.2.")
    return f"Rikka-Root-{version}-{sha[:8]}-arm64-v8a.apk"


def release_element(metadata):
    elements = metadata["elements"]
    if len(elements) != 1 or elements[0]["filters"] not in ([], [{"filterType": "ABI", "value": "arm64-v8a"}]):
        raise BuildError("Release must produce one APK; native ABI is verified from its contents.")
    return elements[0]


def check_abis(apk):
    with zipfile.ZipFile(apk) as archive:
        abis = {name.split("/")[1] for name in archive.namelist() if re.fullmatch(r"lib/[^/]+/[^/]+\.so", name)}
        if abis != {"arm64-v8a"} or archive.testzip() is not None:
            raise BuildError("APK must be a valid archive containing only arm64-v8a native libraries.")


def certificate_digest(output):
    digests = re.findall(r"^(?:Signer #[0-9]+ certificate|V[0-9]+(?:\.[0-9]+)? Signer: certificate) SHA-256 digest: ([0-9a-fA-F]+)$", output, re.MULTILINE)
    if [value.lower() for value in digests] != [EXPECTED_CERT]:
        raise BuildError("APK certificate differs from the existing Rikka-Root signing certificate.")
    return EXPECTED_CERT


def prepare_nightly(sha, request):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise BuildError("Invalid nightly commit hash.")
    tag = request("GET", "git/ref/tags/nightly")
    if tag is None:
        request("POST", "git/refs", {"ref": "refs/tags/nightly", "sha": sha})
    else:
        request("PATCH", "git/refs/tags/nightly", {"sha": sha, "force": True})


def cleanup_nightly(name, request):
    release = request("GET", "releases/tags/nightly")
    assets = (release or {}).get("assets", [])
    if not any(asset["name"] == name for asset in assets):
        raise BuildError("New nightly APK is missing; previous APKs were preserved.")
    # Delete old names only AFTER the new APK upload succeeds.
    for asset in assets:
        if asset["name"].lower().endswith(".apk") and asset["name"] != name:
            request("DELETE", f"releases/assets/{int(asset['id'])}")


def github_request(method, path, body=None):
    if os.environ.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise BuildError("Release publication is restricted to this repository.")
    token = os.environ.get("GH_TOKEN")
    if not token:
        raise BuildError("Release token is missing.")
    request = urllib.request.Request(f"https://api.github.com/repos/{REPOSITORY}/{path}",
        data=None if body is None else json.dumps(body).encode(), method=method,
        headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            content = response.read()
            return json.loads(content) if content else None
    except urllib.error.HTTPError as error:
        if method == "GET" and error.code == 404:
            return None
        raise BuildError(f"GitHub release request failed (HTTP {error.code}).") from None


def write_outputs(values):
    destination = os.environ.get("GITHUB_OUTPUT")
    if destination:
        with open(destination, "a", encoding="utf-8") as output:
            for name, value in values.items():
                output.write(f"{name}={value}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare-signing", "cleanup-signing", "clear-lint", "check-lint", "prepare-apk", "verify-apk", "prepare-nightly", "cleanup-nightly"))
    parser.add_argument("--exit-code", type=int, default=0)
    args = parser.parse_args()
    root = Path.cwd()
    os.umask(0o077)
    if args.command == "prepare-signing":
        key, config = signing_inputs(os.environ.pop("KEY_BASE64", ""), os.environ.pop("SIGNING_CONFIG", ""))
        created = []
        try:
            for path, content in ((root / "app/app.key", key), (root / "local.properties", config.encode())):
                with path.open("xb") as output:
                    created.append(path)
                    output.write(content)
        except OSError:
            for path in created:
                path.unlink(missing_ok=True)
            raise BuildError("Signing paths already exist or cannot be created; existing files were preserved.") from None
        print("Upstream signing files prepared privately; values were not printed.")
    elif args.command == "cleanup-signing":
        (root / "app/app.key").unlink(missing_ok=True)
        (root / "local.properties").unlink(missing_ok=True)
        print("Temporary signing files removed.")
    elif args.command == "clear-lint":
        for report in root.glob("*/build/reports/lint-results-debug.*"):
            report.unlink()
    elif args.command == "check-lint":
        count = check_lint(root, args.exit_code)
        print(f"Lint: {count} errors; inherited limit {MAX_LINT_ERRORS} (4a7a39c4). Reports are published without suppression.")
    elif args.command == "prepare-apk":
        metadata = json.loads((root / "app/build/outputs/apk/release/output-metadata.json").read_text())
        entry = release_element(metadata)
        ref = os.environ.get("GITHUB_REF", "")
        tag = ref.removeprefix("refs/tags/") if ref.startswith("refs/tags/v") else None
        sha = os.environ["GITHUB_SHA"]
        name = apk_name(entry["versionName"], sha, tag)
        filename = entry["outputFile"]
        if Path(filename).name != filename or "unsigned" in filename or not filename.endswith(".apk"):
            raise BuildError("Invalid release APK output path.")
        source = root / "app/build/outputs/apk/release" / filename
        check_abis(source)
        target = root / "build/ci" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        values = {"apk": target.relative_to(root).as_posix(), "artifact_name": name.removesuffix(".apk"), "version": entry["versionName"]}
        (root / "build/ci/release.json").write_text(json.dumps(values))
        (root / "build/ci/release-notes.txt").write_text(f"Rikka-Root {entry['versionName']} · commit `{sha}`\n\nSigned arm64-v8a APK. Nightly builds are public test builds installed manually.\n")
        write_outputs(values)
        print(f"Prepared {name}, {target.stat().st_size} bytes.")
    elif args.command == "verify-apk":
        values = json.loads((root / "build/ci/release.json").read_text())
        apk = root / values["apk"]
        signer = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0/apksigner"
        result = subprocess.run([str(signer), "verify", "--verbose", "--print-certs", str(apk)], capture_output=True, text=True)
        if result.returncode:
            raise BuildError("apksigner verify failed.")
        digest = certificate_digest(result.stdout)
        sha = hashlib.sha256(apk.read_bytes()).hexdigest()
        print(f"apksigner verify: OK\nCertificate SHA-256: {digest}\nAPK SHA-256: {sha}")
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a", encoding="utf-8") as output:
                output.write(f"Signed APK: `{apk.name}` ({apk.stat().st_size} bytes)\n\nCertificate SHA-256: `{digest}`\n\nAPK SHA-256: `{sha}`\n")
    elif args.command == "prepare-nightly":
        prepare_nightly(os.environ["GITHUB_SHA"], github_request)
    elif args.command == "cleanup-nightly":
        values = json.loads((root / "build/ci/release.json").read_text())
        cleanup_nightly(Path(values["apk"]).name, github_request)


if __name__ == "__main__":
    try:
        main()
    except BuildError as error:
        print(error)
        raise SystemExit(1)
    except Exception as error:
        # Exception payloads may contain user-supplied signing configuration. Only emit the type.
        print(f"Build check failed ({type(error).__name__}); no input values were printed.")
        raise SystemExit(1)
