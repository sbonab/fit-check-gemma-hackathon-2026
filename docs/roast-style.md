# Roast structure

Current profile: `observational-deadpan-v2`.

1. One original sentence giving a dry, pointed verdict on the overall outfit:
   a visual observation, an absurd comparison, then a punchline. Use the visible
   palette, layering or coordination; individual garment names belong in lines 2–3.
2. One sentence roasting a clearly visible outfit piece.
3. One sentence roasting a different clearly visible piece. End on the joke.

Silicon Valley satire is optional when it fits the clothing detail. There is no
required compliment or positive closing. The target remains clothing choices.
If only one piece is visible, use two visible aspects of that piece rather than
inventing another. If there is no outfit, ask for a clearer frame.

## Changing it later

Edit `android/app/src/main/java/dev/local/outfitroast/RoastStyle.kt`:

- `opening` and `toneRules`: first-line structure and comic delivery. The user's
  examples inform these abstract rules; no sample roast sentences are supplied
  to the model, so it has no example bank to quote or lightly paraphrase.
- `garmentJokes`: the two following beats and grounding requirements.
- `techFlavor`: how much tech satire to use.
- `ending`: the closing rule.
- `id`: bump when changing behavior so saved evaluation runs identify the profile.

Rebuild and reinstall with `scripts/device.sh install`. This is a build-time
profile, not a settings screen. Camera, voice handling and original-outfit memory
do not need changes. Styling advice has its own unchanged prompt.

The structural contract remains three single sentences. The prompt targets at
most 24 words each; validation allows up to 28 to avoid a full retry for a minor
length overrun. The separate 200-character limit remains.
Output validation checks structure; humor, grounded garment details and whether the
last line is actually a joke require reviewing model output.

Roast and improvement generation use LiteRT's JSON-schema constrained decoding.
The schemas live in `RoastContract.responseSchema`; they prevent extra prose,
Markdown fences and numbered jokes outside the response object. The normal
validator still checks sentence count, length and content before speech. Audio
transcription remains plain text and does not use a JSON constraint.

## Evaluation

Run the JVM tests and `scripts/eval.sh engine` on the hoodie, denim and no-outfit
fixtures. Review the raw saved text for opener/piece/piece structure, distinct
visible targets, unnecessary compliments, invented details and forced tech terms.
Replay the saved color-block fleece photo to check it is not called a hoodie,
the raised collar is not mistaken for a hood, and panels do not become extra layers.
The current replay still fails that semantic check despite passing format and
context tests; prompt guidance is not a guarantee of correct garment recognition.
The engine test also checks that improvement reuses the original photo and that
reset clears it. Compare humor separately from a successful pipeline result.
