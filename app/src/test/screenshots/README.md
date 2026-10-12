# Chat screenshot baselines

`chat-before` contains the 208 preserved audit reference PNGs (26 viewports × four
themes × font scales 1.0/1.3). These files describe the historical interface. The
stage-1 source cannot render that old header and composer, so the live suite now
verifies `chat-after-stage1` and publishes both directories in `ui-screenshots`.
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

Normal tests always verify the committed AFTER baselines, including Daily Build
with `-Proborazzi.test.record=true`. Global Roborazzi record does not authorize
recording either chat baseline set. Explicit stage-1 recording is:

```sh
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.screenshot.ChatBeforeScreenshotsTest' -Pchat.stage1.record=true --no-configuration-cache
```

`-Pchat.stage1.record.dir=/tmp/chat-stage1-review` redirects intentional review
captures. The legacy `chat.audit.record` and `chat.audit.record.dir` properties
remain accepted, but target AFTER images. Recording into `chat-before` or its
children is rejected. Review actual/diff images before accepting new baselines.

Artifacts contain historical BEFORE references and verified current AFTER images
under `app/build/outputs/roborazzi/<testTask>/`. On a successful Verify, the copied
AFTER artifact contains the actual current capture. The comparator allows native
RGB rounding with a per-pixel distance of 0.007; geometry changes still fail.
Tool-detail captures wait for the pressed-card ripple to settle. Verify failures
retain Roborazzi's actual/diff output. Screenshots cover JVM/native rendering; they do
not establish device permissions, real tool execution, streaming timing, IME,
TalkBack, WebView rendering, or performance.

`python app/src/test/screenshots/compare_stage1_messages.py` reports exact pixel
equality for fixed message rectangles after integer vertical alignment. The
fixed header/composer exclusions avoid counting intentional stage-1 changes.
Tool-detail windows use a fixed modal-content rectangle. The report includes
every crop boundary and offset; differing captures receive diagnostic crops in
`/tmp/chat-stage1-message-qa`. This proves equality only in the reported crops,
not in excluded or offscreen message areas. Python Pillow and NumPy are needed.
