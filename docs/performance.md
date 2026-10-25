# Pixel inference latency — October 24, 2026

## Method

Pixel 11 Pro / Tensor G6, Gemma 4 E2B, LiteRT-LM 0.18.1. Replayed the same saved
576×768 fleece photo using the real app engine, JSON-schema output, thinking off,
GPU language generation and CPU4 audio. One device test at a time. Each variant
used a fresh engine followed by repeated requests; generation remained isolated
in fresh conversations. No private image or transcript is committed.

Request timings include image copying, conversation creation, generation, response
validation and any retry. They exclude model loading, microphone transcription,
camera capture and speech playback. Prewarming is explicitly outside request time;
it moves work to startup or spoken playback rather than making that work free.
Small samples, varying output lengths and phone performance make these directional
measurements, not a guarantee or a controlled statistical benchmark.

## Where the wait goes

- Existing app traces: capture/preprocessing **0.18–0.25 s**; a typical complete
  request **14–16 s**, nearly all in the inference path. Model loading is already
  finished before the action becomes available.
- Baseline conversation creation: **2.55–2.73 s** per request.
- Baseline generation call: **10.0–18.9 s**. Native statistics reported 79–88
  decoded tokens at **6.1–15.8 tokens/s**. Token generation is a major cost.
- LiteRT reported approximately **0.8–0.9 s** to first token, but that native metric
  does not explain the full wall time. Version 0.18.1's Kotlin API does not expose
  separate image-encoder stage timings; the unaccounted time includes image work
  and runtime overhead. Do not equate native TTFT with the user's wait.
- Thermal status reported 0 in completed measurements. That does not prove clock
  rates or temperature had no effect; repeated requests were often slower.

## Experiments

| Variant | Measured roast latency | Result |
|---|---|---|
| Baseline | 12.63, 21.75 s | Valid responses |
| Multi-token prediction | No valid completed response | Native JSON constraint error; leave disabled |
| GPU vision encoder | 21.00, 14.51 s | First conversation included 13.8 s setup; not selected |
| 140 visual tokens | 9.19, 13.59 s | Valid responses; less image work |
| 2,048-token context | 16.36, 16.02 s | No clear benefit; retain 4,096 |
| Prewarmed conversations | 10.22, 17.60 s | Request-time conversation creation reduced to 0 ms |
| Prewarm + 140 image tokens | 8.67, 13.22, 13.08 s | Selected combination; all first-attempt format passes |

A later startup refinement prepares only the roast context before showing Ready,
and prepares advice during playback. Its first trial took 18.65 s because a
**25-word** sentence triggered the old 24-word validator and a full repair. The
first model call itself took 7.32 s. Validation now tolerates up to 28 words while
the prompt still targets 24; sentence count and all other checks remain enforced.
This avoids an observed formatting-only latency spike without truncating output.
The final configuration replay took **7.29 s and 13.85 s**, both accepted on the
first attempt; startup preparation was **3.43 s**. These are two measured runs,
not a promise that every request completes under ten seconds.

Shorter 10–14-word sentence guidance was also tested, alone and combined. It did
not consistently shorten the generated text or improve repeated-request latency,
so it is not enabled in the app. Detailed runs are retained in
`eval/results/performance/runs/performance-*/verification.json` (ignored).

## Selected app configuration

`RoastTuning.CURRENT`: GPU language model, CPU4 vision/audio, 140 visual tokens,
4,096 context tokens, MTP off, existing roast style and output limits retained.

- Prepare a fresh **system-only roast conversation** at startup.
  Measured extra startup preparation was approximately **3.4 s** with this refinement.
- After delivering a response, prepare fresh roast/advice conversations on the
  model worker while Android TTS speaks. No image, user request, previous roast
  or previous game state is put into the prepared conversations.
- Consume each prepared conversation once, then close it. A missing prepared
  conversation falls back to normal creation; requests still undergo full validation.
- Log model load, startup preparation, conversation creation, send time, token
  counts, throughput and configuration in each local run for future comparisons.

The image budget is a speed/visual-detail tradeoff. This does not fix the known
fleece/hoodie misclassification or inconsistent comedy. Those existed at the
original budget too. Preparing extra native contexts also uses memory; comprehensive
peak-memory and long-duration thermal measurements remain future work.

## LiteRT recommendations and assessed alternatives

- Google's [Gemma 4 guide](https://developers.google.com/edge/litert-lm/models/gemma-4)
  recommends MTP on GPU. It was tested here, but with this pinned runtime and JSON
  constraints it failed with `compute_mask() called after stop`. Do not enable it
  until a compatible runtime/model combination passes the same tests.
- [Gemma vision token budgets](https://ai.google.dev/gemma/docs/capabilities/vision#variable-resolution)
  and the checked-in LiteRT `ExperimentalFlags.visualTokenBudget` document lower
  visual-token budgets; the app now explicitly selects 140.
- Google's [LiteRT performance overview](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/)
  discusses session/context reuse. We prepare only clean system prefixes to avoid
  stale outfit contamination; we do not reuse a previous outfit conversation.
- Local Tensor skill `check_compiled.py` confirmed the current 2.59 GB model is
  **CPU/GPU, not Tensor-NPU compiled**. NPU use needs a matching Tensor G6 model
  and runtime integration. No NPU speed claim is made and no model was replaced.

## Deferred performance backlog

Speed work is paused at the user's request; prioritize the live audio user story.
Revisit these in order when latency becomes a priority again:

1. **Measure a representative outfit set.** Compare baseline and selected settings
   with matched photos, output lengths, cold/warm runs and rest periods. Report
   median/tail latency, retry rate, garment accuracy, memory and temperature.
2. **Reduce repair calls.** Replay actual rejected outputs and distinguish formatting
   failures from incorrect clothing claims. Keep grounding checks; do not trade
   correctness for a faster apparent success.
3. **Investigate runtime upgrades and MTP.** The current 0.18.1 JSON-grammar path
   failed under MTP. Test a compatible version in isolation before changing the
   app runtime; require structured-output and voice regression passes.
4. **Evaluate Tensor G6 NPU deployment.** Obtain/compile the matching bundle and
   verify model/runtime compatibility, then compare complete multimodal latency
   and quality against GPU. Current weights are not NPU compiled.
5. **Tune visual budget by task.** Compare 70/140/280 tokens on clothing details,
   especially garment boundaries and patterned items. Keep 140 until evidence
   supports another choice; do not lower resolution blindly.
6. **Revisit output length and perceived wait.** Shorter wording did not reliably
   help in this trial. Validated sentence-by-sentence speech might reduce perceived
   delay, but must preserve grounding and avoid speaking invalid partial JSON.
7. **Profile encoder and memory costs.** Use deeper runtime tracing when available;
   quantify prepared-context memory and sustained thermal behavior. GPU vision and
   a smaller context did not show a clear gain in the initial measurements.

## Verification after the selected changes

Build, lint and all 46 JVM tests passed. On the installed app, the saved-outfit
roast → same-photo advice → reset test, no-outfit handling, all six audio-file
fixtures and the real-Activity prerecorded wake → request → camera → offline
speech flow passed. The latter took 21.9 s for the full test. Prerecorded inputs
do not verify human microphone recognition in the room.

## Repeatable loop

```bash
bash outfit-roast/scripts/eval.sh performance baseline PATH_TO_IMAGE 2
bash outfit-roast/scripts/eval.sh performance prewarm-140 PATH_TO_IMAGE 3
bash outfit-roast/scripts/device.sh pull outfit-roast/eval/results/performance
```

Other variants: `mtp`, `vision-gpu`, `vision-140`, `context-2048`, `prewarm`,
`brief`, `fast` (prewarm + 140 + shorter-wording request). Run sequentially.
Assess garment accuracy and humor separately from format acceptance and latency.
