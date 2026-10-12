# Chat screenshot baselines

`chat-before` contains the 208 preserved audit reference PNGs (26 viewports × four
themes × font scales 1.0/1.3). These files describe the historical interface. The
current source cannot render that old header and composer, so the live suite now
verifies `chat-after-stage4` and publishes reference/current directories in `ui-screenshots`.
The audit-era `ChatBeforeScreenshotsTest` class name is retained for existing
targeted Gradle commands; its tests capture the current production `ChatPage`.

`chat-after-stage1` contains 264 PNGs: the same 26 dialogue captures plus seven new
states for each of the eight theme/font combinations: empty chat, active search/reasoning,
workspace header, paused queue with draft, editing, the actual “+” sheet window, and a fresh running
root-command checkpoint. The running capture observes the real UI state change
without executing a command. It shows waiting for output because client runtimes
publish output at completion. The screenshot clock freezes the pulsing indicator.
The new state tests also set Android's resource font scale, so the separate “+”
dialog window uses the requested 1.0/1.3 scale; its actual text layout is asserted.
The 26 historical viewport comparisons retain their original density fixture.

`chat-after-stage1` is a preserved archive of commit `3c2adc4a`.
`chat-after-stage1-fixes` preserves the reviewed stage-1 corrections, with the
same 33 captures per theme/font case. Both archives contain 264 PNGs and are
protected alongside `chat-before`, including every child directory.

`chat-after-stage2` is a preserved 448-PNG archive. `chat-after-stage2-fixes` is
the preserved baseline after the 16dp list and inline-code chrome corrections. It retains
all 56 stage-2 captures and adds 11 geometry captures per theme/font combination
(67 × 8 = 536 PNGs). The original 23 added stage-2 states include default user message;
user avatar with timestamp; assistant bubbles at
full and 0.35 opacity; independently hidden, icon-only, name-only, timestamp-only,
and token-only assistant metadata; thirteen-line user text collapsed to nine
lines, expanded, and with collapse disabled; streaming text, its next fragment
during fade, and three waiting dots; inline and fenced code; a table; HTML code
with its real controls; sources summary and the actual sources sheet; expanded,
collapsed, and pending translation. The geometry states cover short/long user text
with avatar off/on, attachments with avatar off/on, a full-width tool card,
wrapped AST and HTML inline code, and visible navigation overlays on both sides.

New states replace mutable ViewModel/settings boundaries and mount the actual
`ChatPage` and message renderers. They also update Android's resource font scale
so new dialog windows use actual 1.0/1.3 platform fonts. Metadata flag visibility,
message bounds between header and composer, user expansion height, and the
sources sheet's window bounds and title font scale are asserted. Streaming and
translation animation frames use a frozen Compose clock. Production defaults
and live settings updates are exercised directly; these captures contain no
substitute message UI. Settings controls have a separate focused integration
test in `MessageBehaviorDisplaySettingsTest`.
The three precisely timed streaming text/fade/waiting captures use the existing
`showMessageJumper=false` preference, keeping transient scroll navigation out of
the 100ms text-fade frame. Dedicated enabled-navigation captures and runtime tests
cover its positioning, activity, and idle hide behavior.
An additional production-page interaction test clicks the user branch selector
with message actions disabled and checks the real page's ViewModel update/save
callbacks. It verifies exactly one selector with actions enabled, disables
branch mutation for read-only history, permits read-only user text expansion,
and preserves the loading gate. Expansion/collapse clicks must leave the actual
input state's editing message unset.

Normal tests always verify the committed AFTER baselines, including Daily Build
with `-Proborazzi.test.record=true`. Global Roborazzi record does not authorize
recording any chat baseline set. The live baseline is now `chat-after-stage3`, which
retains all stage-2-fixes scenes and adds actual `ChatPage` timeline, approval,
reasoning, file, and narrow-width scenes.

The stage-3 Record run passed 104 test invocations (13 methods × eight theme/font
combinations) and produced 780 PNGs: 97 scenes per combination, plus four 320dp
scenes at font scale 1.3. The 1,720 historical PNGs retained their hashes and
modification times. A separate Verify run checks the newly recorded baselines.

The new scenes exercise text → tool → chart → text order, four completed timeline
steps and show-more, mandatory pending/error/checkpoint rows, result previews and
full details, scoped approvals with a single callback, deny reason, ask_user,
server failure and provider JSON preview/full details, active/completed reasoning with live display setting changes,
actual workspace and Document file results, and one 320dp capture per theme at
font scale 1.3. The 320dp case asserts the 16dp message margins. A second
mandatory-status viewport scrolls to the root checkpoint. The existing fresh
root-command scene also checks that the timeline shares the strip's active
observation; a new persisted-checkpoint scene shows the unknown outcome after
generation stops.
Remote MCP pending arguments and details retain their actual input, while the
native MCP configuration details are checked for secret redaction. The summary
remains manually usable when automatic collapse is disabled.
The denial dialog uses its real accessibility SetText and confirm actions, with
explicit layout of the actual dialog between them. This follows the existing
header integration test workaround for Robolectric's wrap-content text-selection
placement loop; normal idle synchronization resumes after dismissal. The test
checks the edited reason and exact approval callback, without claiming IME coverage.

Explicit stage-3 recording:

```sh
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.screenshot.ChatBeforeScreenshotsTest' -Pchat.stage3.record=true --no-configuration-cache
```

`-Pchat.stage3.record.dir=/tmp/chat-stage3-review` redirects intentional review
captures. Older chat record switches do not activate recording for this live suite.
Recording or publishing artifacts inside any of the five historical directories,
including `chat-after-stage2-fixes`, is rejected. Review actual/diff images before
accepting new baselines.

Artifacts contain historical BEFORE references and verified current AFTER images
under `app/build/outputs/roborazzi/<testTask>/`. On a successful Verify, the copied
AFTER artifact contains the actual current capture. The comparator allows native
RGB rounding with a per-pixel distance of 0.007; geometry changes still fail.
Tool-detail captures wait for the pressed-card ripple to settle. The actual +
sheet's pressed-button ripple also settles before freezing the running-command
frame. A Coil event listener registers actual request completion with Compose
idleness, including offline HTTP 404 errors, so favicon captures show the real
terminal painter state instead of a placeholder affected by test order.

Before captures with an advancing clock, the fixture drains the real scroll
navigation's 1500ms idle timeout and exit animation through Compose and Android
clocks, then asserts that all four navigation buttons have disappeared. This
keeps current captures independent of recording/verification wall time. Frozen
streaming, waiting, running-command, and pending-translation frames retain their
explicitly selected clock phase.

Verify failures retain Roborazzi's actual/diff output. The HTML capture records the actual code
and controls; WebView content itself requires a device check. Screenshots cover JVM/native rendering; they do
not establish device permissions, real tool execution, streaming timing, IME,
TalkBack, WebView rendering, or performance.

The historical `python app/src/test/screenshots/compare_stage1_messages.py` reports exact pixel
equality for fixed message rectangles after integer vertical alignment. The
fixed header/composer exclusions avoid counting intentional stage-1 changes.
Tool-detail windows use a fixed modal-content rectangle. The report includes
every crop boundary and offset; differing captures receive diagnostic crops in
`/tmp/chat-stage1-message-qa`. This proves equality only in the reported crops,
not in excluded or offscreen message areas. Python Pillow and NumPy are needed.

`chat-after-stage3` is now a preserved 780-PNG archive. The live baseline is
`chat-after-stage4`; only `-Pchat.stage4.record=true` may write it. Its first
recording contains the stage-3 corrections checked on the phone: readable tool
result previews (command stdout/stderr with real line breaks, Russian error
reasons, short summaries instead of JSON), one-line titles with the call's
essence, per-category tool icons and two 32dp scroll buttons. The new
`timeline_tool_previews` scene shows root, Termux, SSH, file and browser steps
(14 methods × eight theme/font cases = 112 tests, 788 PNGs).

Stage 4 re-records the same live set with Moru message actions (16dp glyphs
in 28dp slots, pencil on user messages, ‹ 2/3 › switcher), the token line
with arrow/timer icons hidden while a reply streams, and continuation
bubbles under the last answer. The new `actions_tokens_suggestions` scene
also checks that a tap inserts the suggestion by default and sends it when
"insert only" is off (15 methods = 120 tests, 796 PNGs).
