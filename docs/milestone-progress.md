# Fit Check — outfit roast progress

Updated October 24, 2026. Project: `outfit-roast/`. App: **Fit Check**
(`dev.local.outfitroast`). The existing poker project and installed app are intact.

## Draft submission

[Five-slide pitch PDF](../output/pdf/fit-check-pitch.pdf) and
[submission summary](../submission/pitch.md) are prepared with a real development
screenshot and unedited saved model output. A standalone source export excludes
the poker app, private evaluation recordings, model weights and developer caches.
Public submission repository:
[sbonab/fit-check-gemma-hackathon-2026](https://github.com/sbonab/fit-check-gemma-hackathon-2026).
`submission/package_source.py` refreshes the curated source export; subsequent
updates are committed and pushed from `tmp/submission-repo`.
The original two product pages are followed by dedicated Mobile AI, LiteRT and
Android & Pixel slides, with current implementation diagrams and measured evidence.

## User story

1. Open the style-themed app, frame yourself with the front camera or use **Flip**.
2. Say **“Hey Gemma, roast my outfit”** while the status says **Listening**.
   Or say **“Hey Gemma”**, wait for **Activated**, then **“Roast my outfit”**
   within eight seconds. Each new spoken request needs activation.
   **Roast my outfit** is the test-button alternative.
3. Hear three sentences: an original deadpan overall-outfit opener, then two jokes about
   distinct visible pieces. Optional Silicon Valley satire; no positive closing.
4. After speech finishes, say **“Hey Gemma, how would you make it better?”**, or tap
   **Make it better**. Advice uses the original photo and approved garment labels.

## Current build

Three GPT-6.1 Sol agents (high reasoning) implemented UI/orchestration, model
contracts/context and foreground voice. Integrated and installed on the dev Pixel.

- Gemma 4 E2B through **LiteRT-LM 0.18.1**; GPU generation, CPU image/audio encoders.
- 140 visual tokens; fresh system-only contexts prepared at startup and during
  speech. See [performance experiments](performance.md) for measured tradeoffs.
- Offline Android English speech; no Internet permission or cloud inference.
- Camera preview, front/rear switch, automatic speech capture, explicit command
  classification and both test buttons. No poker UI or business logic included.
- Single model worker. Mic pauses during inference/speech and in the background.
- One saved original outfit for follow-up. New roast replaces context; reset clears it.
- Structured output, exactly three sentences, one repair attempt with the specific
  validation error. A single Markdown JSON wrapper is accepted; extra answers are rejected.
- A negative outfit-visible decision discards generated content and speaks a fixed
  retake request. Malformed output never becomes an invented successful roast.

## Verification

| Check | Evidence / result |
|---|---|
| Build, JVM tests, lint | Passing: 46 JVM tests, 0 lint errors |
| Device model provisioning | Full 2.59 GB bundle SHA-256 verified |
| Voice file fixtures | 6/6 passed: direct command, wake, request, armed follow-up, background and negative speech; unactivated requests ignored |
| Camera / mic lifecycle | Passed preview, primary controls, pause cancellation and resume listening |
| Hoodie image | Passed roast → improvement → reset; 10.1 s roast, 7.6 s improvement |
| No-outfit apples image | Passed detection and refusal to retain outfit context |
| **Real front-camera button flow** | **Passed** camera → roast → offline speech → same-photo improvement → offline speech, 50.3 s total test; user confirms both replies audible |
| Prerecorded wake through real UI | Latest rerun passed actual Gemma wake → Activated → request → real camera → offline speech; 20.4 s test |
| Live human voice commands | One real “Hey Gemma can you roast my outfit” was recognized and triggered the camera; generation then failed formatting. Repeated audio recognition remains unreliable |
| Denim outfit | Passed roast → improvement → reset; 11.2 s / 8.8 s |
| Rear-camera switch | Passed actual front → rear → front bindings and new preview frames, then mic pause/resume |
| UI cleanup regression | Passed red Voice off, flip icon, caption timer replacement and pause/resume cleanup; latest run 12.1 s |
| Synthetic speaker→mic diagnostic | Latest installed build rerun: capture/VAD passed, recognition failed (heard “Oh, I get it. Oh.” instead of the trigger); correctly ignored. Human recognition still needs verification |

Exact raw responses, photos, audio, timing, validation failures and verification
reports live in ignored `eval/results/` and device-private `files/runs/`. Latest wake evidence: `eval/results/wake-verified/runs/`. Latest
working camera evidence: `eval/results/device-live/runs/ui-camera-flow-*/verification.json`.

**Current limitation:** the reliable demo path is the two visible buttons with spoken
replies. One live request reached generation, alongside multiple recognition failures.
A consistently successful hands-free flow remains unverified. Keep the test buttons.

**Current priority:** pause speed optimization; remaining experiments are in the
[deferred performance backlog](performance.md#deferred-performance-backlog).
Latest speed build passed 46 JVM tests, lint, saved-outfit advice/reset, no-outfit,
six voice fixtures and the prerecorded wake UI flow. Recheck live microphone
recognition next, using “Hey Gemma” → Activated → “Roast my outfit,” then the
wake-prefixed improvement request. Codex reads retained capture/transcription
evidence after the user reports; no instrumentation runs during the human trial.

## Wake activation update

- Idle speech cannot trigger roast or advice without a leading **Hey Gemma**.
- Wake alone arms one request for eight seconds, with an **Activated** indicator
  and haptic feedback. Requests begun within the window survive transcription latency.
- Playback, buttons, camera flip, voice-off and backgrounding clear activation.
- Exact negations and quoted/embedded wake phrases are ignored; ordinary requests
  such as “can you roast my outfit” work after activation.
- Clean wake-only audio exposed instruction leakage: “Hey Gemma transcribe the
  attached recording.” Audio now enters Gemma without an appended text prompt;
  transcription instructions stay in the system message. All six audio fixtures and
  the real-Activity prerecorded wake/request flow now pass.
- Complete model timestamps are stripped; timing-only output and prompt echoes
  are rejected, never turned into a guessed command.

## UI cleanup update

- Removed decorative slogans; retained camera and voice instructions.
- **Voice off** is red with white text. **Flip** includes a camera-switch icon.
- Response captions remain throughout speech, then wait five seconds before a
  300 ms fade. Text-only or failed playback gets at least eight seconds and longer
  for longer text. New responses cancel old timers; backgrounding removes captions.
- Build, 39 JVM tests and lint passed. Pixel automation passed UI cleanup (11.7 s),
  camera/mic lifecycle (6.9 s), and prerecorded wake → request → camera → spoken
  response (24.8 s). Visual inspection confirmed the red control and flip icon.
- Final human appearance and live-voice review remains pending. After handoff,
  automation stays stopped until the user reports results.

## Caption and live voice diagnosis

- A human run showed the old caption timer hiding text about five seconds into an
  18-second reply. Dismissal now starts from the offline TTS completion callback.
  A real offline speech regression passed (23.9 s), verifying visibility throughout
  playback and the reading pause before dismissal. Build, lint and 43 JVM tests pass.
  Latest evidence: ignored `eval/results/caption-tts-verified/`.
- “Hey Gemma, can you see my outfit?” activates an eight-second window when
  correctly transcribed; it does not start a roast or answer a visibility question.
  This exact phrase now has a command regression test.
- The latest live logs contained background speech followed by “Hey Jenna,” with
  no successful activation in that segment. This is evidence of recognition/capture
  trouble, not proof that the speaker used the wrong phrase. The implementation
  uses whole-utterance Gemma transcription with a strict leading wake phrase; it
  is not a dedicated wake detector, and capture pauses during transcription.
- Reliable activation in a noisy room remains unresolved. Do not treat prerecorded
  wake tests as live-microphone acceptance or loosen matching to arbitrary chatter.

## Inference performance update

- Same-photo baseline: 12.6 / 21.8 s. Final configuration: **7.3 / 13.8 s**,
  both without retries. Small samples and variable token throughput; not a sub-ten-second guarantee.
- Camera preparation is only ~0.2 s. Prewarming moves ~2.6 s of conversation
  setup off the request; lower image tokens reduce image work. Startup now includes
  ~3.4 s of preparation, and the next fresh contexts are prepared during TTS.
- MTP hit a native schema-decoding error and remains off. GPU vision, smaller KV
  context and shorter wording were measured but not selected. NPU requires a
  different compatible model/runtime and was assessed, not benchmarked.
- The prompt still targets 24 words per sentence; validation tolerates 28 after
  a 25-word line caused an unnecessary full retry. Three sentences remain mandatory.
- Vision accuracy/comedy issues remain; a lower visual budget trades fine detail
  for speed. Full measurements, sources and replay instructions: [performance.md](performance.md).

## Response-format fix

- A real voice request reached roast generation, but both attempts appended
  numbered jokes after a fenced JSON object. The app rejected the full response
  and spoke the fallback after 36.6 s.
- Roast and improvement now use **LiteRT-LM JSON-schema constrained decoding**.
  Output is constrained to the object schema; existing sentence/content checks
  remain. Audio transcription remains unconstrained plain text.
- Replayed the exact saved failure photo: roast passed first attempt in **13.5 s**,
  same-photo improvement passed first attempt in **16.4 s**, reset cleared context.
  Build, lint and 44 JVM tests passed. Fenced output with extra text now reports
  the actual trailing-text error instead of a misleading numeric-ID error.
- No-outfit integration also passed: clarification, no retained outfit, and reset.
- This fixes the tested formatting failure, not semantic accuracy: the replay
  still describes the same outer garment as both hoodie and jacket, and its
  suggestion to tuck a shirt into a hoodie is poor advice. Comedy/vision quality
  and noisy-room audio remain separate work.
- Evidence: ignored `eval/results/structured-output-verified/`.

## Original opener update

- The user's example lines describe tone and structure, not a phrase bank. Removed
  all sample roast sentences from the runtime prompt; `RoastStyle.toneRules` now
  describes observation → absurd comparison → punchline in abstract terms.
- Current profile is `observational-deadpan-v2`. Line one addresses the overall
  visible palette, layering or coordination; lines two and three address pieces.
  Broad labels and visible colors take priority over uncertain garment subtypes.
- The saved fleece photo confirmed a separate visual error: Gemma called its
  raised-collar jacket a hoodie. Removing example lines alone did not fix that
  in the first replay. Avoid claiming that fresh wording guarantees visual accuracy.
- Final replay passed the format/follow-up/reset checks (34.3 s total), with an
  overall-outfit opener, but **failed semantic review**: it still called the fleece
  a hoodie, used generic laundry imagery despite the vivid panels, and softened
  later lines into compliments. Removing the phrase bank is complete; accurate
  visual grounding and consistent roast tone remain unresolved. The final prompt
  uses broad-label guidance, without forcibly rewriting incorrect model output.
- Build, lint and 45 JVM tests pass. Final replay evidence is saved under ignored
  `eval/results/original-opener-verified/`.

## Earlier roast structure update

- [Editable style profile](roast-style.md): one deadpan opener inspired by the
  supplied examples, then two distinct visible garment jokes. No positive ending;
  tech satire is optional. Improvement remains practical advice.
- Creative instructions live in `RoastStyle.kt`; change the profile and reinstall.
  Saved runs include the profile ID and full prompt for comparison.
- Hoodie and denim fixtures passed roast → same-photo improvement → reset.
  Roasts took 12.1 s and 14.6 s without repair on the final validation rules.
  Both had separate garment targets and no complimentary closing.
- Fixed unnecessary retries for a single final period inside a closing quote;
  embedded extra sentences remain rejected. Build, lint and 42 JVM tests passed.
- No-outfit fixture passed: requests a clearer frame and retains no outfit context.
- Humor still needs human judgment: the denim output's skirt/dress comparison is
  weak, and the observation list still mislabels shoes as heeled sandals. Structural
  success is not a claim that all visual details or jokes are good.
- Evidence: ignored `eval/results/roast-style-verified/`. Human tone review pending.

## What the tests taught us

- Combining roast and improvement instructions caused unsolicited extra JSON.
  Each call now sees only its requested task.
- Long comedy instructions produced multiple sentences per item. Shorter prompts
  and specific repair feedback work better.
- Improvement regenerated numeric garment IDs. It now returns only sentence strings;
  the app preserves the original labels and photo itself.
- **Semantic quality still needs work:** an earlier hoodie roast invented a drawstring;
  the live color-block fleece was split into a jacket and sweater. Latest broad-detail
  guidance reduces speculation but does not certify visual accuracy.
- Quiet microphone input never crossed the old 550 RMS gate. The new gate uses a
  240 RMS floor and 100 ms voiced within 300 ms; capture now completes. Acoustic
  loopback still transcribes incorrectly, so capture and recognition are reported separately.
- Humor is functional but uneven. A format pass is not proof of a funny or grounded roast.

## Next stages

**v0 — complete the user-story check:** finish human wake request and spoken follow-up,
replay any failed spoken recording before asking for another attempt. Both lenses and two outfit fixtures now pass. Recheck the negative camera path when the scene is clothing-free. Keep the visible test buttons.

**v1 — better comedy and vision:** replay saved failure images, improve recognition of
color-block garments, score specificity / actual humor / opener-and-two-garment structure on a small outfit
set. Prefer reliable large details over logos, brands or tiny accessories. Measure retries
and end-to-end time, including speech and cooldown.

**v2 — demo polish:** tune voice pacing, add an interrupt/replay control if needed,
check layout across sizes, and prepare a short offline demo with a known-good fallback.
NPU optimization is secondary to the working LiteRT user story.

## Testing ownership

The Pixel is a development-only phone; Codex has ongoing authorization to install and
run device checks. **During an announced human trial, do not install, force-stop or
run instrumentation until the user reports the result.** Earlier overlapping tests
closed the app during the user's trial; no Fit Check crash appeared in the crash buffer. Codex owns build, model provisioning, fixtures, logs and regression tests.
The user is only needed to frame a real outfit, speak naturally and judge audibility,
accuracy and humor. No video upload or terminal commands are required.

Debug builds retain private input/output evidence locally for iteration. Retention and
user deletion controls are unfinished before public distribution. Models/media stay out of Git. The Windows Gradle wrapper retains its standard CRLF line endings.
