# Outfit evaluation

Report pipeline completion separately from visual grounding, humor and latency.

For device latency experiments and current runtime settings, see
[performance measurements](../docs/performance.md). Run only one model benchmark
at a time; retain raw responses and report retries, not just successful token rates.

## Automated gates

1. Pure contract: exactly three single sentences, bounded JSON, malformed or
   no-outfit output handled; styling retains the approved original outfit.
   On-device generation also checks JSON-schema decoding is active and emits no
   Markdown. Keep full-response validation; do not silently extract an object
   from a response containing extra jokes or a second answer.
2. Voice intent: exact wake request accepted; unrelated/negated speech ignored;
   improvement only available after a successful roast.
3. Real Pixel model fixtures: two different outfit photos and an apples/no-outfit
   image. Inspect observations, opener plus two garment jokes, practical
   improvement, retries and raw timings. A test cannot objectively certify humor.
4. Gemma audio transcription: locally synthesized trigger, improvement, unrelated
   and negative recordings. This tests audio/model/intent without physical mic.
5. Camera/button/mic lifecycle and offline TTS callbacks; stop listening while
   speaking or backgrounded. Human voice-trigger trial remains a distinct gate.

## Repeatable local loop

From the repository root:

```bash
bash outfit-roast/scripts/build.sh --offline assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
bash outfit-roast/scripts/device.sh install
bash outfit-roast/scripts/eval.sh engine outfit-roast/eval/results/fixtures/hoodie.jpg hoodie
bash outfit-roast/scripts/eval.sh engine outfit-roast/eval/results/fixtures/denim.jpg jacket,skirt
bash outfit-roast/scripts/eval.sh engine outfit-roast/eval/results/fixtures/no-outfit.png no-outfit
bash outfit-roast/scripts/eval.sh voice
bash outfit-roast/scripts/eval.sh ui
bash outfit-roast/scripts/eval.sh ui-polish
bash outfit-roast/scripts/eval.sh caption-tts
bash outfit-roast/scripts/eval.sh wake-ui
bash outfit-roast/scripts/device.sh pull
```

The voice fixture files are already staged in this development phone's private
`files/eval/`. Fixtures are local and ignored, so a fresh checkout needs the
attributed downloads and synthesized audio described below. Only one device
model test should run at a time. Use `eval.sh camera` only with a real outfit in
the front camera; it checks the roast button, spoken answer and styling button.

`eval.sh acoustic` is an optional synthetic speaker-to-microphone diagnostic. Its
latest run captures speech but does not recognize the correct command; do not count
it as a pass. `eval.sh camera-empty` checks a real clothing-free camera scene through
clarification, offline speech and mic rearm. Run it only when the preview has no outfit.

## Human review

Show an outfit clearly in the preview. Say “Hey Gemma, roast my outfit”, wait for
the spoken reply, then “Hey Gemma, how would you make it better?”. Judge whether the clothes
were correct, the jokes specific and amusing, the third line a joke rather than a compliment, and the advice
useful. Report the failed step; saved runs provide exact input/output evidence.
Do not repeatedly retake a correctly transcribed failed request before replay.

## Fixture sources (downloaded into ignored results; not bundled)

- `hoodie.jpg`: [Hoodie man](https://commons.wikimedia.org/wiki/File:Hoodie_man.jpg),
  SteelAvenger; public-domain dedication, brightness revision by Ubcule.
  Visible black hooded sweatshirt and dark trousers. No identification claims.
- `denim.jpg`: [Young woman in marinière, denim jacket en wrap skirt](https://commons.wikimedia.org/wiki/File:Young_woman_in_marini%C3%A8re,_denim_jacket_en_wrap_skirt.jpg),
  Maria Morri, [CC BY-SA 2.0](https://creativecommons.org/licenses/by-sa/2.0/).
  Wikimedia 500px thumbnail; blue denim jacket, striped top, gray wrap skirt,
  black shoulder bag and gray flats. No endorsement is implied.
- `no-outfit.png`: existing apples image; provenance in
  `../../mobile-coach/eval/bundle/fixtures/PROVENANCE.md`.
- `trigger.wav`, `improve.wav`, `background.wav`, `negative.wav`: synthesized
  locally with macOS Samantha, then canonicalized to 16kHz mono 16-bit PCM WAV.
  Phrases respectively: “Hey Gemma, roast my outfit.”, “How would you make it
  better?”, “Can you pass me the charger please?”, “Do not roast my outfit.”

No private user media or third-party image bytes are added to Git or app assets.

Wake regression: `eval.sh voice` now includes `wake.wav` (“Hey Gemma”) and
`request.wav` (“Roast my outfit”), synthesized locally with Samantha.
`eval.sh wake-ui` replays those audio files through the real Activity and Gemma,
then checks activation, camera action and offline speech. It does not use the physical microphone.

`eval.sh ui-polish` checks the red Voice off control, camera-switch icon, scheduled
caption expiry, replacement-response timer cancellation and background cleanup.
It injects captions only to test presentation; `wake-ui` separately exercises real
Gemma inference and speech. Finish all instrumentation before handing the phone
back for human testing.

`eval.sh caption-tts` uses a synthetic long caption with real offline speech. It
checks the caption stays visible past the old five-second cutoff, throughout
playback, and for a reading pause after TTS completion before fading. It does not
test Gemma inference or microphone recognition.
