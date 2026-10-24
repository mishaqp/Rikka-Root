# Persistent Root Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans and TDD; implement in this session.

**Goal:** Reuse one authenticated root transport for foreground, screen and prepared background commands.

**Architecture:** RootShellManager owns one serialized persistent session with a separate headless grant. Preserve the existing per-request supervisor and the single-use fallback before submission only.

**Tech Stack:** Kotlin coroutines, Android su, POSIX shell, JVM tests using real processes and controlled su authentication.

**Spec:** `docs/superpowers/specs/2026-10-24-persistent-root-design.md`

## Global Constraints

- Keep commands byte-for-byte, stdout/stderr/exit status, byte caps and cancellation/timeout cleanup.
- Background calls never open su; foreground preparation never grants background access.
- Do not replay a submitted command or change applicationId, signing, workflow or existing approvals.
- Local checks: affected unit classes/compilation only; full test/lint/APK through Daily Build.

## Review Focus

- Cancellation while queued/preparing must prevent dispatch and close newly created shells.
- A close/preparation race must not resurrect root or launch the fallback.
- Missing or corrupt acknowledgement must preserve partial output without replay.
- Short FIFO reads and UTF-8 boundary output must preserve prefixes and clipping flags.
- A long/failing command must not corrupt another request or leak shell state.

### Task 1: Shared persistent execution

Files: `root/RootShellManager.kt`, new `root/RootPersistentSessionTest.kt` under unit tests.

Interfaces: existing exec(command, timeoutMs, headless, mayExecute) and verifyRoot/close unchanged.

- [x] Add real-shell tests for cold/warm foreground reuse, prepared mixed use, queue cancellation, approval revocation, shell-state isolation and pre-submission fallback.
- [x] Run the new class against old code and record intended failures and baseline timings.
- [x] Share the transport under the existing mutex, preserving a separate explicit headless grant and epoch/cancellation checks.
- [x] Run the new class and existing manager/process-group/result-integrity tests.

### Task 2: Bounded block output and compatibility

Files: `root/RootShellManager.kt`, persistent tests, existing root transport/automatic tests and stream-to-shell integration fixture if necessary.

Interface: existing RootProcessResult and literal command unchanged.

- [x] Add a behavioral output test whose dd shim rejects byte-sized reads; cover fragmented output, cap boundaries and UTF-8.
- [x] Run RED, use single-block dd reads and actual byte counts (bounded bs1 fallback if wc unavailable), continue draining the suffix. head-c lost partial output; count_bytes lost fragmented input; both rejected by real tests.
- [x] Reproduce delayed completion and failed auxiliary watchdog cleanup. Use native timed FIFO read when supported, otherwise original polling; no auxiliary watchdog. Recheck active after notification and preserve bounded group cleanup.
- [x] Reproduce proc checks depending on spawned cat; replace them with shell builtin read while retaining identity validation.
- [x] Update affected fixture resource ownership and command capture to cover persistent plus fallback paths; preserve assertions.
- [x] Run all affected Root classes and GenerationLoopRootCommandPreservationTest with existing CI flags; compare launch counts/timings without promising phone speed.
- [x] Request independent review; reproduce and fix substantive findings with tests.
- [ ] Commit/push the tested tree, await Daily Build test/lint/APK, inspect/fix any failure, verify APK certificate and provide the Russian report.
