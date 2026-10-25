package dev.local.outfitroast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Synthetic speaker-to-microphone loopback; does not establish human wake-word reliability. */
@RunWith(AndroidJUnit4::class)
class VoiceAcousticLoopbackTest {
    @Test fun speakerTriggerIsCapturedByAutomaticVadThenTranscribed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configured = InstrumentationRegistry.getArguments().getString("audio_dir")
        val audioDirectory = if (configured == null) File(context.filesDir, "eval")
            else File(configured).let { if (it.isAbsolute) it else File(context.filesDir, configured) }
        val started = SystemClock.elapsedRealtime()
        val run = File(context.filesDir, "runs/voice-acoustic-eval-${System.currentTimeMillis()}")
            .apply { check(mkdirs()) { "Could not create acoustic verification directory." } }
        val report = JSONObject().put("schema_version", 1).put("passed", false)
            .put("scope", "synthetic_acoustic_loopback")
            .put("human_wakeword_verified", false).put("microphone_vad_verified", false)
            .put("tts_handoff_verified", false).put("model", MODEL_NAME)
            .put("backend", "GPU").put("audio_backend", "CPU4")
            .put("limitation", "Synthetic AudioTrack speaker playback captured by AudioRecord; not a human wake-word or app conversation test.")
            .put("activity_launched", false).put("capture_limit_ms", 15000)
            .put("audio_dir", audioDirectory.absolutePath).put("started_at_ms", System.currentTimeMillis())
        val recorder = ForegroundRecorder(context)
        val completion = CountDownLatch(1)
        val captured = AtomicReference<Result<File?>?>(null)
        val speechAt = AtomicLong(0)
        var track: AudioTrack? = null
        var captureStartedAtMs = Long.MAX_VALUE
        try {
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Unfit acoustic setup: grant RECORD_AUDIO to ${context.packageName} before running."
            }
            val original = File(audioDirectory, "trigger.wav")
            check(original.isFile) { "Missing staged acoustic fixture: ${original.absolutePath}" }
            val originalEvidence = original.copyTo(File(run, "original-trigger.wav"))
            report.put("original_audio", originalEvidence.name).put("original_sha256", sha256(original))
                .put("original_bytes", original.length())
            val pcm = canonicalPcm(original.readBytes())
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            checkNotNull(speaker) { "Unfit acoustic setup: no built-in speaker output is available." }
            report.put("media_volume", manager.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("media_volume_max", manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            check(manager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                "Unfit acoustic setup: media volume is muted; set an audible speaker volume before running."
            }
            val playback = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Wav.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
            track = playback
            // Static tracks remain STATE_NO_STATIC_DATA until their first PCM write.
            check(playback.state != AudioTrack.STATE_UNINITIALIZED) { "Unfit acoustic setup: AudioTrack initialization failed." }
            check(playback.setPreferredDevice(speaker)) { "Unfit acoustic setup: built-in speaker routing was rejected." }
            check(playback.write(pcm, 0, pcm.size) == pcm.size) { "Could not stage the entire trigger PCM for playback." }
            check(playback.state == AudioTrack.STATE_INITIALIZED) { "Unfit acoustic setup: AudioTrack did not initialize after staging PCM." }
            playback.setVolume(1f)
            captureStartedAtMs = System.currentTimeMillis()
            val captureStarted = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                recorder.start(automatic = true,
                    onSpeech = { speechAt.set(SystemClock.elapsedRealtime()) },
                    onComplete = { captured.set(it); completion.countDown() })
            }
            report.put("microphone_started_at_ms", captureStartedAtMs)
            SystemClock.sleep(350) // Establish an idle noise floor before playing speech.
            report.put("playback_started_after_capture_ms", SystemClock.elapsedRealtime() - captureStarted)
            playback.play()
            SystemClock.sleep(100)
            val route = playback.routedDevice
            report.put("playback_route_type", route?.type ?: JSONObject.NULL)
                .put("playback_route_name", route?.productName?.toString() ?: JSONObject.NULL)
            check(route?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                "Unfit acoustic setup: playback did not route to the built-in speaker."
            }
            val remaining = (15000 - (SystemClock.elapsedRealtime() - captureStarted)).coerceAtLeast(0)
            val completed = completion.await(remaining, TimeUnit.MILLISECONDS)
            report.put("capture_ms", SystemClock.elapsedRealtime() - captureStarted)
                .put("vad_onset_detected", speechAt.get() > 0)
                .put("vad_onset_after_capture_ms", if (speechAt.get() > 0) speechAt.get() - captureStarted else JSONObject.NULL)
            check(completed) {
                "Unfit acoustic setup: no completed VAD utterance within 15 seconds; check microphone access, speaker volume, and the acoustic path."
            }
            assertFalse("Recorder must release the microphone before model initialization.", recorder.active)
            val capture = checkNotNull(captured.get()) { "Recorder completed without a result." }.getOrThrow()
            checkNotNull(capture) { "Unfit acoustic setup: recorder returned no speech audio." }
            val evidence = capture.copyTo(File(run, "captured-trigger.wav"))
            report.put("captured_audio", evidence.name).put("captured_sha256", sha256(evidence))
                .put("captured_bytes", evidence.length()).put("microphone_released_before_engine_load", true)
            playback.stop()
            playback.release()
            track = null
            assertTrue("Automatic VAD did not report an onset.", speechAt.get() > 0)
            report.put("microphone_vad_verified", true)
            // There is exactly one engine and it cannot overlap microphone capture.
            RoastEngine(context).use { engine ->
                val loadStarted = SystemClock.elapsedRealtime()
                try { engine.load() }
                finally { report.put("engine_load_ms", SystemClock.elapsedRealtime() - loadStarted) }
                val transcriptionStarted = SystemClock.elapsedRealtime()
                val transcript = try { engine.transcribe(evidence) }
                finally { report.put("transcription_ms", SystemClock.elapsedRealtime() - transcriptionStarted) }
                val intent = VoiceCommands.classify(transcript, hasRoast = false)
                report.put("transcript", transcript).put("intent", intent.name)
                    .put("expected_intent", VoiceIntent.ROAST.name)
                assertEquals("Synthetic acoustic loopback transcript: '$transcript'", VoiceIntent.ROAST, intent)
            }
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("error", error.toString())
            throw error
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            // End a timed-out/failed setup at the capture bound, retaining any partial
            // speech for diagnosis rather than discarding the acoustic evidence.
            if (recorder.active) runCatching { instrumentation.runOnMainSync { recorder.stop(keep = true) } }
            val stopDeadline = SystemClock.elapsedRealtime() + 3000
            while (recorder.active && SystemClock.elapsedRealtime() < stopDeadline) SystemClock.sleep(25)
            if (!recorder.active) completion.await(500, TimeUnit.MILLISECONDS)
            report.put("recorder_active_after_cleanup", recorder.active)
            val partialCapture = captured.get()?.getOrNull()
            if (partialCapture != null && !File(run, "captured-trigger.wav").exists()) runCatching {
                val evidence = partialCapture.copyTo(File(run, "captured-trigger.wav"))
                report.put("captured_audio", evidence.name).put("captured_sha256", sha256(evidence))
                    .put("captured_bytes", evidence.length()).put("partial_capture", true)
            }.onFailure { report.put("capture_preservation_error", it.toString()) }
            val diagnostic = File(context.filesDir, "runs/audio-diagnostics/latest.json")
            if (diagnostic.isFile) runCatching {
                val details = JSONObject(diagnostic.readText())
                if (details.optLong("started_at_ms") >= captureStartedAtMs) {
                    diagnostic.copyTo(File(run, "recorder-diagnostic.json"))
                    report.put("recorder_diagnostic", details)
                }
            }.onFailure { report.put("diagnostic_error", it.toString()) }
            report.put("latency_ms", SystemClock.elapsedRealtime() - started)
                .put("ended_at_ms", System.currentTimeMillis())
            File(run, "verification.json").writeText(report.toString(2))
            Log.i("OutfitVoiceEval", "Synthetic acoustic verification: ${File(run, "verification.json").absolutePath}")
        }
    }

    private fun canonicalPcm(wav: ByteArray): ByteArray {
        require(wav.size > 44) { "Trigger WAV is empty or missing its canonical header." }
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(wav, 8, 8, Charsets.US_ASCII) == "WAVEfmt " && header.getInt(16) == 16 &&
            header.getShort(20).toInt() == 1 && header.getShort(22).toInt() == 1 &&
            header.getInt(24) == Wav.SAMPLE_RATE && header.getShort(34).toInt() == 16 &&
            String(wav, 36, 4, Charsets.US_ASCII) == "data" && header.getInt(40) == wav.size - 44 &&
            (wav.size - 44) % 2 == 0) { "Use the staged canonical 16 kHz mono PCM16 trigger WAV." }
        return wav.copyOfRange(44, wav.size)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(8192)
            var count = input.read(bytes)
            while (count >= 0) {
                if (count > 0) digest.update(bytes, 0, count)
                count = input.read(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
