"""Verify that the audit regressions fail on the original code, without changing release checks."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

BASELINE = "9b74e54124b30e8086f893d2518238482ba06767"
AI_CLASSES = [
    "me.rerere.ai.provider.providers.EmptyToolParametersRegressionTest",
    "me.rerere.ai.provider.providers.google.GoogleSchemaFilteringRegressionTest",
    "me.rerere.ai.provider.providers.openai.ChatCompletionsToolIdentityRegressionTest",
    "me.rerere.ai.util.SchemaFilteringRegressionTest",
]
APP_CLASSES = [
    "me.rerere.rikkahub.data.ai.GenerationLoopAuditRegressionTest",
    "me.rerere.rikkahub.data.ai.mcp.control.McpAuditRegressionTest",
    "me.rerere.rikkahub.data.ai.mcp.McpToolNameAuditTest",
    "me.rerere.rikkahub.data.ai.mcp.McpToolDispatchAuditTest",
    "me.rerere.rikkahub.data.ai.mcp.McpEndpointOriginAuditTest",
    "me.rerere.rikkahub.data.ai.mcp.McpManualRedirectAuditTest",
    "me.rerere.rikkahub.data.datastore.migration.McpSharedLegacyReferenceAuditTest",
    "me.rerere.rikkahub.root.RootCommandGuardTest",
    "me.rerere.rikkahub.data.ai.tools.local.TermuxCommandGuardTest",
    "me.rerere.rikkahub.data.ai.tools.local.RootToolTest",
    "me.rerere.rikkahub.data.ai.tools.HardlineCommandGuardShellTest",
    "me.rerere.rikkahub.data.ai.tools.HardlineToolArgumentsAuditTest",
    "me.rerere.rikkahub.data.ai.tools.HardlineCommandPositionAuditTest",
    "me.rerere.rikkahub.data.ai.tools.ProtectedPathDelimiterAuditTest",
    "me.rerere.rikkahub.data.ai.tools.WorkspacePathTraversalAuditTest",
    "me.rerere.rikkahub.data.ai.tools.AssistantDataIsolationAuditTest",
    "me.rerere.rikkahub.data.ai.tools.local.ClipboardSensitiveContentTest",
    "me.rerere.rikkahub.data.ai.tools.local.ScreenshotStoragePrivacyTest",
    "me.rerere.rikkahub.data.ai.tools.local.DeviceToolTimeoutTest",
    "me.rerere.rikkahub.service.ToolMediaCommandTimeoutTest",
    "me.rerere.rikkahub.service.ApprovalAuditPolicyTest",
    "me.rerere.rikkahub.workflow.tools.WorkflowApprovalRendererAuditTest",
    "me.rerere.rikkahub.ui.screenshot.WorkflowApprovalScrollAuditTest",
    "me.rerere.rikkahub.service.ChatServiceMetadataTest",
]
# Each condition must fail by assertion on baseline. A compile/download failure cannot satisfy it.
REQUIRED_FAILURES = {
    1: [("GenerationLoopAuditRegressionTest", r"^1 headless prompt tool then final text")],
    2: [("EmptyToolParametersRegressionTest", r"^Claude ")],
    3: [("McpAuditRegressionTest", r"^audit 3 omitted"),
        ("McpAuditRegressionTest", r"^audit 3 changing host clears OAuth"),
        ("ApprovalAuditPolicyTest", r"^audit 3 MCP configuration mutations")],
    4: [("ApprovalAuditPolicyTest", r"^audit 4 SMS sending")],
    5: [("RootToolTest", r"^foreground automatic root blocks destruction")],
    6: [("GenerationLoopAuditRegressionTest", r"^6 hardline blocks MCP"),
        ("GenerationLoopAuditRegressionTest", r"^6 hardline also checks")],
    7: [("HardlineCommandGuardShellTest", r".*"),
        ("ProtectedPathDelimiterAuditTest", r"^root floor blocks"),
        ("ProtectedPathDelimiterAuditTest", r"^shared hardline blocks"),
        ("ProtectedPathDelimiterAuditTest", r"^Termux floor blocks")],
    8: [("WorkspacePathTraversalAuditTest", r"^writeRejectsDotSegments")],
    9: [("McpAuditRegressionTest", r"^audit 9 server views redact legacy URL"),
        ("McpAuditRegressionTest", r"^audit 9 opaque MCP references"),
        ("McpAuditRegressionTest", r"^audit 9 secret references cannot move to another server")],
    10: [("AssistantDataIsolationAuditTest", r"^editCannotChangeAnotherAssistantsMemory"),
         ("AssistantDataIsolationAuditTest", r"^deleteCannotRemoveAnotherAssistantsMemory")],
    11: [("AssistantDataIsolationAuditTest", r"^conversationSearchCannotReturnAnotherAssistantsChats")],
    12: [("WorkflowApprovalRendererAuditTest", r"^audit 12 ")],
    13: [("ClipboardSensitiveContentTest", r"^sensitiveClipboardReadPreservesText"),
         ("ApprovalAuditPolicyTest", r"^audit 13 calendar")],
    14: [("ScreenshotStoragePrivacyTest", r"^missingOrFalseGalleryOption")],
    15: [("ChatServiceMetadataTest", r"^audit 15 answer cannot turn ordinary"),
         ("ChatServiceMetadataTest", r"^audit 15 web rejects answer")],
    16: [("GenerationLoopAuditRegressionTest", r"^16 own tool timeout returns model")],
    17: [("GenerationLoopAuditRegressionTest", r"^17 output without workspace"),
         ("GenerationLoopAuditRegressionTest", r"^17 bounded structured search output")],
    18: [("McpToolNameAuditTest", r".*")],
    19: [("SchemaFilteringRegressionTest", r".*")],
    20: [("ChatCompletionsToolIdentityRegressionTest", r"^синхронные вызовы без id"),
         ("ChatCompletionsToolIdentityRegressionTest", r"^пустой id продолжения")],
    21: [("GenerationLoopAuditRegressionTest", r"^21 malformed JSON"),
         ("GenerationLoopAuditRegressionTest", r"^21 step limit"),
         ("GenerationLoopAuditRegressionTest", r"^21 elapsed turn budget")],
}


def check_evidence(report_files, requirements=REQUIRED_FAILURES):
    cases = []
    for report in report_files:
        cases.extend(ET.parse(report).getroot().iter("testcase"))
    evidence = {}
    missing = []
    for number, conditions in requirements.items():
        matches = []
        for class_name, method in conditions:
            found = []
            for case in cases:
                if case.get("classname", "").rsplit(".", 1)[-1] != class_name:
                    continue
                if not re.search(method, case.get("name", ""), re.IGNORECASE):
                    continue
                failure = case.find("failure")
                if failure is not None and failure.get("type", "").rsplit(".", 1)[-1] in {
                    "AssertionError", "ComparisonFailure", "AssertionFailedError",
                }:
                    found.append(case.get("classname", "") + "." + case.get("name", ""))
            if not found:
                missing.append(f"{number}: {class_name} / {method}")
            matches.extend(found)
        evidence[str(number)] = sorted(set(matches))
    if missing:
        raise ValueError("Не подтверждены ожидаемые падения baseline: " + "; ".join(missing))
    return evidence


def run_baseline(root, output):
    root = Path(root).resolve()
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    (output / "evidence.json").unlink(missing_ok=True)
    subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=root, check=True)
    with tempfile.TemporaryDirectory(prefix="rikka-audit-baseline-") as directory:
        checkout = Path(directory) / "checkout"
        subprocess.run(["git", "worktree", "add", "--detach", str(checkout), BASELINE], cwd=root, check=True)
        try:
            for module, classes in (("ai", AI_CLASSES), ("app", APP_CLASSES)):
                for class_name in classes:
                    relative = Path(module, "src/test/java", *class_name.split(".")).with_suffix(".kt")
                    target = checkout / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(root / relative, target)
            # Use the exact checked-out submodule and already-installed frontend dependencies.
            submodule = checkout / "material3/material-color-utilities"
            recorded = subprocess.check_output(["git", "ls-tree", BASELINE, "material3/material-color-utilities"], cwd=root, text=True).split()[2]
            actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root / "material3/material-color-utilities", text=True).strip()
            if recorded != actual:
                raise ValueError("Подмодуль baseline отличается от текущего: требуется его отдельная инициализация.")
            submodule.rmdir()
            submodule.symlink_to(root / "material3/material-color-utilities", target_is_directory=True)
            (checkout / "web-ui/node_modules").symlink_to(root / "web-ui/node_modules", target_is_directory=True)
            sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
            if sdk:
                (checkout / "local.properties").write_text("sdk.dir=" + sdk.replace("\\", "\\\\") + "\n")
            command = ["./gradlew", ":ai:testDebugUnitTest"]
            for name in AI_CLASSES:
                command.extend(["--tests", name])
            command.append(":app:testDebugUnitTest")
            for name in APP_CLASSES:
                command.extend(["--tests", name])
            command.extend(["--continue", "--no-configuration-cache", "-Proborazzi.test.record=true"])
            print(f"Проверка регрессий на исходном {BASELINE[:8]}; лог: {output / 'gradle.log'}", flush=True)
            with (output / "gradle.log").open("w") as log:
                result = subprocess.run(command, cwd=checkout, stdout=log, stderr=subprocess.STDOUT)
            reports = []
            for module in ("ai", "app"):
                source = checkout / module / "build/test-results/testDebugUnitTest"
                if source.exists():
                    target = output / module
                    shutil.copytree(source, target, dirs_exist_ok=True)
                    reports.extend(source.glob("TEST-*.xml"))
            evidence = check_evidence(reports)
            if result.returncode != 1:
                raise ValueError(f"Baseline Gradle завершился с неожиданным кодом {result.returncode}; ожидались тестовые падения.")
            (output / "evidence.json").write_text(json.dumps({"baseline": BASELINE, "points": evidence}, ensure_ascii=False, indent=2) + "\n")
            print("RED подтверждён для всех пунктов 1–21. Далее обязательны обычные тесты исправленной версии и lint.", flush=True)
        finally:
            subprocess.run(["git", "worktree", "remove", "--force", str(checkout)], cwd=root, check=True)


if __name__ == "__main__":
    run_baseline(Path(__file__).resolve().parents[2], Path("build/audit-baseline"))
