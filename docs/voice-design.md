# Foreground voice interaction

With microphone permission and the app visible, the app captures local microphone
audio and uses Gemma to transcribe speech. Say **“Hey Gemma, roast my outfit.”**
Or say **“Hey Gemma”**, then **“Roast my outfit”** after the app acknowledges the
wake phrase. Each wake opens an eight-second window for one request. After the
spoken roast finishes, say **“Hey Gemma, how would you make it better?”** for
outfit advice, or wake first and say **“How can I improve it?”**. A testing button
can invoke the same roast path directly.

This is foreground voice activity detection followed by local model transcription
and whole-utterance classification. It is not a dedicated, always-on wake-word
engine: any sufficiently loud speech may be transcribed before the command is
accepted or ignored, and model transcription introduces a delay. The user message contains audio only; an appended text instruction was being echoed
into short wake transcripts. Complete timestamp markers are removed, while prompt echoes
and timing-only output are rejected. No platform network speech recognizer is used. The UI owns camera capture, inference,
text-to-speech, and the decision to rearm listening.

## Recorder contract

`ForegroundRecorder.start(automatic = true, onSpeech, onComplete)` starts one
capture. The recorder uses 16 kHz mono PCM, 20 ms reads, an adaptive RMS threshold
with a floor of 240 and a noise multiplier of 2.5. An onset needs 100 ms of voiced
audio within a rolling 300 ms window, allowing brief gaps between quiet syllables.
It preserves up to 400 ms of pre-roll so the opening “Hey” is retained. Once
speech begins, 900 ms of silence ends the capture; captured audio is bounded to
eight seconds including pre-roll. A capture with no onset ends after 30 seconds,
allowing the UI to rearm. Idle PCM stays in memory and is never saved to disk.

`onComplete` runs on the main thread after the microphone has been released. Its
successful value is a cache WAV file or `null` for a capture without speech.
The caller owns deletion of returned files after transcription.
`stop(keep = false)` cancels capture and suppresses pending callbacks;
`stop(keep = true)` retains captured audio for optional push-to-talk use.
`active` stays true until worker cleanup finishes, so a canceled capture must
finish cleanup before a subsequent start. Start/stop state changes are serialized.
Numeric diagnostics keep the latest 16 captures under `files/runs/audio-diagnostics`;
they contain timing, energy, and endpoint metadata, without transcription text.

The UI must stop capture before model work or speech playback, keep it stopped
through the entire TTS utterance, and rearm after playback completes. It must also
cancel capture when leaving the foreground. This prevents the app from interpreting
its own roast or advice as a new command. An idle/error retry must wait for the
previous capture to become inactive; cancellation intentionally has no completion
callback.

## Command contract

`VoiceGate.accept(text, hasRoast, nowMs)` returns `VoiceIntent.WAKE`, `ROAST`,
`IMPROVE`, or `IGNORE`. The UI keeps one gate and uses a monotonic clock. Idle
speech requires the exact leading words “hey gemma”. Bare “hey gemma” returns WAKE
and arms one request for eight seconds. “Hey Gemma, can you roast my outfit?” and
similar explicit polite variants act directly. An unknown wake-prefixed request
can acknowledge the wake but never starts a roast or advice action. While armed,
an exact roast request or known advice request can omit “hey gemma”; advice also
requires an existing roast. Each accepted action consumes the wake. Ignored input
does not extend the deadline. Expiry, pause, voice-off, or session reset disarm it.

`isArmed(nowMs)` expires an elapsed window; `reset()` disarms immediately. For a
request beginning within the window, the UI saves its `onSpeech` time, avoids
expiring the gate while that request is in flight, and uses the saved time for
classification after transcription. A slower model response therefore does not
expire speech that began on time. `VoiceCommands.classify(text, hasRoast)` remains
a stateless idle classifier, so a bare advice request is ignored even after a roast.

For example, “Hey Gemma, can you see my outfit?” arms the window but does not
answer the question or start a roast. The user then needs to ask for a roast.
Live background chatter can fill the utterance and be transcribed before the
request; the mic pauses during transcription. This is a known limitation of
using utterance transcription as wake activation instead of a dedicated detector.

Case, whitespace, and punctuation variations are accepted. Quoted or negated
commands, embedded/repeated wake phrases, and incomplete wake approximations are
ignored. There is deliberately no substring or fuzzy matching of the wake or
action. A background speaker saying the exact leading wake and request can still
trigger it; RMS detection cannot identify the speaker.

`SpeechOnsetDetectorTest` covers quiet speech, brief gaps, noise bursts, background
noise adaptation, and partial microphone reads. `VoiceCommandsTest` and
`VoiceGateTest` cover explicit commands, natural polite variants, wake expiry and
consumption, punctuation, conversation/negation, quotes, advice state, and noisy
transcription. `VoicePipelineTest` transcribes six original WAV fixtures including
a two-utterance wake/request sequence; it also checks idle request/advice rejection.
Recorder behavior and TTS handoff additionally require target-device testing.
