# Settings dashboard implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development for independent units; the coordinator owns integration and verification.

**Goal:** A Russian settings dashboard, quiet Rikka-Root nightly updates and consistent branding without changing persisted settings.

**Architecture:** One ordered UI catalog supplies rows, section colors, search and navigation targets. Independent asynchronous status flows keep the screen responsive. A separate updater cache supplies the dashboard and About/update pages.

**Tech Stack:** Kotlin, Compose Material 3, HugeIcons, Navigation 3, coroutines, OkHttp, JUnit/Robolectric.

**Spec:** The user's settings redesign request in this session, sections 1–9.

## Global constraints

- Preserve Settings, Assistant, LocalToolOption and backup serialization and existing function behavior.
- Russian labels; workflows are called «Сценарии» throughout the visible UI.
- No new Android permissions; root probes/actions only after explicit user interaction.
- Use nightly public GitHub API, commit hashes, compare titles capped at 30 and automatic checks no more often than six hours.
- Network errors appear only in About. Do not log secrets or request/response bodies.
- Local verification: compile affected modules and run affected unit-test classes only. Release APK, full tests and lint run in Daily Build.
- One final push to ccr-e93188a6-grrqko, green Daily Build, report and stop.

## Review focus

- Large fonts, narrow landscape windows and TalkBack must preserve labels, touch targets and navigation.
- A pending/failed status source must not delay other status chips or the initial screen.
- Same/prefix hashes, older nightly builds and missing compare data must not advertise a false update.
- Restart and failed network checks must still enforce the six-hour automatic-check interval.
- Existing theme controls, hidden debug navigation, root settings and scheduling power controls remain reachable.

### Task 1: Catalog and search

- [x] Create `SettingsCatalog.kt` with 24 main items in five ordered sections, HugeIcons, descriptions and Route/Url/Share targets.
- [x] Expand `SettingsSearchIndex.kt` from the catalog and implemented nested settings pages; include root/рут/su, ssh/сервер, батарея/doze and обновление.
- [x] Test catalog completeness, unique IDs, navigation and synonym/word matching in `SettingsCatalogTest` / `SettingsSearchIndexTest`.

### Task 2: Passive status flows

- [x] Add `SettingsDashboardStatus.kt` and a `rememberSettingsDashboardStatuses(Settings)` hook returning independent Loading/Ready/Error values.
- [x] Consume actual root cache, repositories, MCP and web flows; collect file size on IO and use existing backup timestamps. Browser may omit its chip when no reliable site-login count exists.
- [x] Test that slow and failing sources preserve initial placeholders and do not delay other statuses.

### Task 3: Nightly updater

- [x] Adapt `UpdateChecker.kt` / `ForkReleaseUpdate.kt`; expose `nightlyState` and manual `checkNow`; separate persistent cache, startup integration and quiet errors.
- [x] Add BuildConfig commit, date and ABI information in `app/build.gradle.kts`.
- [x] Test asset hash parsing, current/prefix/older/newer comparisons, 30-title limit and six-hour throttling including failed checks.

### Task 4: Dashboard and supporting pages

- [x] Replace `SettingPage.kt` with state panel, responsive quick tiles, common `SettingsItem` and grouped/searchable rows with highlighted matches.
- [x] Add Root, schedules and update routes/pages using existing root/scheduling controls; redesign About with build information, copy, fork links and AGPL attribution.
- [x] Move the existing color-mode control into theme preferences and preserve navigation transitions/predictive back.

### Task 5: Branding and release

- [x] Update visible resource/web labels, preserving protocol/package names and original-service attribution.
- [x] Compile affected app/web modules, run affected tests, inspect the whole diff and address review findings.
- [ ] Commit by subsystem, push once, verify exact-HEAD Daily Build, CI tests/lint identities and signed nightly APK.
- [ ] Report screen behavior, links and phone checks; stop.
