# Kimi Code Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add persistent Kimi Code account login and native chat generation alongside Codex.

**Architecture:** A built-in credential-free ProviderSetting routes to an app-level KimiProvider. An encrypted, lazily loaded account repository owns credentials and refresh; an independent device-flow coordinator owns browser authorization and cancellation. Existing provider transports handle model requests.

**Tech Stack:** Kotlin, coroutines, OkHttp, kotlinx.serialization, AndroidKeyStore, Compose, Koin, JUnit/Robolectric.

**Spec:** docs/kimi-code.md

## Global Constraints

- Work on ccr-e93188a6-grrqko, no PR, preserve existing Codex, applicationId, signing and deep links.
- No user secrets in settings, backups, logs or test fixtures; use synthetic credentials in tests.
- Local affected compilation and affected unit tests only; all tests, lint and signed APK in Daily Build.
- At most 60 changed files per publication; Russian UI; no main-thread disk IO.

## Review Focus

- Cold start must load stored credentials before a model call.
- Concurrent refresh, logout, cancellation and repeated login must not resurrect an old session.
- Whitespace-only tool-argument fragments and reasoning must survive the reused streaming transport.
- API failures and exhausted subscriptions must stay errors without deleting a refreshable session.
- Region and model catalog changes must preserve stable local model IDs and current chat settings.

### Task 1: Settings and protocol

**Files:** ai/ProviderSetting.kt and ProviderManager.kt; app/data/kimi/KimiAccount.kt, KimiApi.kt; ai and app Kimi tests.
**Interfaces:** ProviderSetting.KimiCode; KimiRegion; KimiTokens; device authorization/poll/refresh API results.
- [x] Write and run RED serialization/protocol tests.
- [x] Add built-in settings and RFC 8628 protocol with validation and bounded requests.
- [x] Run affected tests, verify polling fields, identity, errors and both regions.

### Task 2: Persistent account and authorization

**Files:** app/data/kimi/KimiCredentialStore.kt, KimiAccountRepository.kt, KimiOAuthManager.kt; matching tests.
**Interfaces:** loaded account StateFlow; acquire/refresh/signOut; device-flow status and cancellation.
- [x] Write and run RED persistence, refresh and lifecycle tests.
- [x] Implement encrypted atomic writes off the main thread, serialized refresh and cancellable device flow.
- [x] Verify restart, invalid grant, temporary errors, logout races, polling slowdown and expiry.

### Task 3: Provider and settings UI

**Files:** app/data/kimi/KimiProvider.kt; provider/default settings, DI, Compose Kimi configuration/model merge; compatible importers.
**Interfaces:** KimiProvider implements Provider<ProviderSetting.KimiCode>; catalog merge applied atomically to current settings.
- [x] Write and run RED transport, catalog merge and default-provider integration tests.
- [x] Implement model listing, stream/nonstream generation, metadata, account/region/login/error UI and complete sealed-type integration.
- [x] Run affected Kimi/Codex/settings/transport tests and compile affected modules.

### Task 4: Review and publish

- [x] Review whole change for credentials, cancellation, cold starts and compatibility; add regression tests for discovered defects.
- [ ] Update usage docs and phone checks; run final affected checks and git diff --check.
- [ ] Commit and push, await Daily Build, inspect failures and repair if needed.
- [ ] Verify signed nightly certificate, applicationId and CI reports; report Actions/APK and remaining phone verification.
