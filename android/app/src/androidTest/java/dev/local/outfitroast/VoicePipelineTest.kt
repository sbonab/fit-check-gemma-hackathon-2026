package dev.local.outfitroast

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Exercises original WAV fixtures through local Gemma audio and command classification. */
@RunWith(AndroidJUnit4::class)
class VoicePipelineTest {
    @Test fun originalAudioTranscribesToExpectedIntents() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Absolute paths are accepted; relative paths resolve inside app files/.
        val configured = InstrumentationRegistry.getArguments().getString("audio_dir")
        val audioDirectory = if (configured == null) File(context.filesDir, "eval")
            else File(configured).let { if (it.isAbsolute) it else File(context.filesDir, configured) }
        val started = SystemClock.elapsedRealtime()
        val run = File(context.filesDir, "runs/voice-eval-${System.currentTimeMillis()}")
            .apply { check(mkdirs()) { "Could not create voice verification directory." } }
        val records = JSONArray()
        val report = JSONObject().put("schema_version", 1).put("passed", false)
            .put("scope", "fixture_transcription_and_command_classification")
            .put("physical_wakeword_verified", false).put("microphone_vad_verified", false)
            .put("tts_handoff_verified", false)
            .put("limitation", "File input does not verify physical microphone capture, wake phrase detection, or TTS handoff.")
            .put("backend", "GPU").put("audio_backend", "CPU4")
            .put("model", MODEL_NAME).put("audio_dir", audioDirectory.absolutePath)
            .put("started_at_ms", System.currentTimeMillis()).put("cases", records)
        val failures = mutableListOf<String>()
        try {
            RoastEngine(context).use { engine ->
                val loadStarted = SystemClock.elapsedRealtime()
                try { engine.load() }
                finally { report.put("engine_load_ms", SystemClock.elapsedRealtime() - loadStarted) }
                val cases = listOf(
                    Case("trigger", VoiceIntent.ROAST, false),
                    Case("wake", VoiceIntent.WAKE, false),
                    Case("request", VoiceIntent.ROAST, false),
                    Case("improve", VoiceIntent.IMPROVE, true),
                    Case("background", VoiceIntent.IGNORE, true),
                    Case("negative", VoiceIntent.IGNORE, true),
                )
                val sequenceGate = VoiceGate()
                var wakeTranscript: String? = null
                for (case in cases) {
                    val audio = File(audioDirectory, "${case.name}.wav")
                    val record = JSONObject().put("name", case.name)
                        .put("audio", audio.absolutePath).put("has_roast", case.hasRoast)
                        .put("expected_intent", case.expected.name).put("passed", false)
                    records.put(record)
                    val caseStarted = SystemClock.elapsedRealtime()
                    try {
                        check(audio.isFile) { "Missing audio fixture: ${audio.absolutePath}" }
                        record.put("audio_bytes", audio.length()).put("audio_sha256", sha256(audio))
                        val transcriptionStarted = SystemClock.elapsedRealtime()
                        val transcript = try { engine.transcribe(audio) }
                        finally { record.put("transcription_ms", SystemClock.elapsedRealtime() - transcriptionStarted) }
                        val gate = if (case.name == "wake" || case.name == "request") sequenceGate else VoiceGate()
                        val armedContext = case.name == "improve"
                        val now = SystemClock.elapsedRealtime()
                        if (armedContext) gate.accept(checkNotNull(wakeTranscript) { "Wake fixture did not produce a transcript." }, case.hasRoast, now)
                        // Like the app's saved onSpeech time, an already-armed request
                        // is timed from input start, allowing slower transcription.
                        val intentTime = if (case.name == "request") transcriptionStarted else now
                        val actual = gate.accept(transcript, case.hasRoast, intentTime)
                        if (case.name == "wake") wakeTranscript = transcript
                        record.put("transcript", transcript).put("actual_intent", actual.name)
                            .put("wake_armed_context", armedContext)
                            .put("gate_time_ms", intentTime)
                            .put("gate_clock_basis", if (case.name == "request") "fixture_input_start" else "transcription_completion")
                            .put("wake_armed_after", gate.isArmed(intentTime))
                        var passed = actual == case.expected
                        if (!passed) failures += "${case.name}: expected ${case.expected}, got $actual from '$transcript'"
                        if (case.name == "improve") {
                            val withoutRoastGate = VoiceGate().apply { accept(checkNotNull(wakeTranscript), false, now) }
                            val withoutRoast = withoutRoastGate.accept(transcript, false, now)
                            val withoutWake = VoiceGate().accept(transcript, true, now)
                            record.put("intent_without_roast", withoutRoast.name)
                                .put("expected_intent_without_roast", VoiceIntent.IGNORE.name)
                                .put("intent_without_wake", withoutWake.name)
                                .put("expected_intent_without_wake", VoiceIntent.IGNORE.name)
                            if (withoutRoast != VoiceIntent.IGNORE) {
                                passed = false
                                failures += "improve: follow-up without a roast returned $withoutRoast"
                            }
                            if (withoutWake != VoiceIntent.IGNORE) {
                                passed = false
                                failures += "improve: idle follow-up without a wake returned $withoutWake"
                            }
                        }
                        if (case.name == "request") {
                            val outsideWindow = VoiceGate().accept(transcript, false, now)
                            record.put("intent_without_wake", outsideWindow.name)
                                .put("expected_intent_without_wake", VoiceIntent.IGNORE.name)
                            if (outsideWindow != VoiceIntent.IGNORE) {
                                passed = false
                                failures += "request: idle request without a wake returned $outsideWindow"
                            }
                        }
                        record.put("passed", passed)
                    } catch (error: Exception) {
                        record.put("error", error.toString())
                        failures += "${case.name}: $error"
                    } finally {
                        record.put("latency_ms", SystemClock.elapsedRealtime() - caseStarted)
                        // Persist after each original fixture, including a failed transcription.
                        writeReport(run, report, started)
                    }
                }
            }
            report.put("failures", JSONArray(failures)).put("passed", failures.isEmpty())
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } catch (error: Throwable) {
            report.put("error", error.toString())
            throw error
        } finally {
            writeReport(run, report, started)
            Log.i("OutfitVoiceEval", "Voice fixture verification: ${File(run, "verification.json").absolutePath}")
        }
    }

    private data class Case(val name: String, val expected: VoiceIntent, val hasRoast: Boolean)

    private fun writeReport(run: File, report: JSONObject, started: Long) {
        report.put("latency_ms", SystemClock.elapsedRealtime() - started)
            .put("ended_at_ms", System.currentTimeMillis())
        File(run, "verification.json").writeText(report.toString(2))
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
