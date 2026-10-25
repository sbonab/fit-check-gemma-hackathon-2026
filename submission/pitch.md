# Fit Check

**Your outfit. A roast. A better idea.**

A playful Android mirror that sees your outfit, delivers three short spoken
roast lines, and follows up with practical styling advice. Built for the Mobile AI
track at the Google Gemma Hardware Hackathon, October 24, 2026.

## The moment

1. Frame your outfit with the front or rear camera.
2. Say “Hey Gemma, roast my outfit,” or tap the demo button.
3. Hear a deadpan verdict and jokes about visible outfit pieces.
4. Ask “Hey Gemma, how would you make it better?” for advice on that same outfit.

## What makes it work

Gemma 4 E2B handles images, audio transcription and language through LiteRT-LM
0.18.1. CameraX supplies the photo; offline Android text-to-speech speaks the
answer. Everything runs on the Pixel. The app has no Internet permission.

The roast style is editable. Structured decoding enforces the response shape;
the app keeps one original photo for the styling follow-up and requests a clearer
frame when no outfit is visible. Jokes target clothing, not personal attributes.

## Why Fit Check deserves Best Use of LiteRT

Fit Check turns LiteRT-LM's multimodal capabilities into a complete, personal
experience: Gemma sees an outfit, transcribes a spoken request and generates a
roast or styling advice entirely on a Pixel. Offline Android speech completes
the interaction. The app has no Internet permission, so cloud inference cannot
silently substitute for the device.

We made deliberate runtime choices: GPU language generation, CPU image/audio
encoders, JSON-schema constrained decoding and fresh prepared system contexts.
We profiled the request path and compared vision backends, context sizes, visual
token budgets and multi-token prediction on the actual phone. Prepared contexts
and a 140-token image budget helped bring two saved-photo runs from 12.6/21.8
seconds to 7.3/13.8 seconds. These are small-sample inference measurements, not
guaranteed end-to-end latency; prewarming moves work to startup or playback and
the image budget trades detail for speed.

Our case for the award is a working multimodal product, measured optimization
and a reproducible implementation that other builders can inspect and run.
Voice reliability and clothing accuracy remain visible next steps; we claim
the GPU implementation we demonstrated, not an unbuilt TPU acceleration path.

## Actual model response

> This ensemble screams 'I just woke up like this' with a palette that suggests a failed attempt at coordinated effort.
> The blue and orange jacket looks like it was designed by a committee that couldn't agree on a primary color.
> That white shirt is clearly trying too hard to be the neutral anchor in this aggressively colored disaster.

Unedited Gemma output from a saved replay of the outfit in the screenshot.
The screenshot is an earlier development build; screenshot and output are separate
captures. This is selected demo evidence, not a claim that every attempt is accurate
or funny. Source excerpt: `assets/sample-response.json`.

## Draft status

- Camera → roast → offline speech → same-photo styling follow-up verified on Pixel.
- Front and rear cameras supported; buttons provide a reliable demo trigger.
- Build, lint and 46 unit tests passed; prerecorded wake/command tests passed.
- Recent saved-photo inference: 7.3 and 13.8 seconds, excluding capture and speech.
- Live wake recognition is inconsistent; garment accuracy and comic specificity
  need more work. Activation feedback for combined wake-and-command requests is
  a requested refinement, not claimed complete in this submission.
- No cloud inference or image upload. Debug builds retain local test evidence;
  deletion controls remain unfinished.

## Demo script

Open Fit Check, frame two clearly visible pieces and tap **Roast my outfit**.
Let the spoken roast finish, then tap **Make it better**. Try the voice equivalent
when the status reads **Listening**. Keep the buttons available for the draft demo.

## Submission links

- [Narrated pitch video](../output/video/fit-check-pitch.mp4) - 85 seconds, 1080p,
  captioned PDF walkthrough with synthetic narration, not a live app recording.
- [Public GitHub repository](https://github.com/sbonab/fit-check-gemma-hackathon-2026)
- [Five-slide pitch PDF](../output/pdf/fit-check-pitch.pdf): product, actual response,
  Mobile AI, Best Use of LiteRT, and Android & Pixel Experience.
- [App screenshot](assets/app-screenshot.png)
- [Implementation progress](../docs/milestone-progress.md)
- [Performance experiments](../docs/performance.md)
