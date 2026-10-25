package dev.local.outfitroast

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt

/** One foreground-only capture. No speech recognizer or network service is involved. */
class ForegroundRecorder(private val context: Context) {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var keepAudio = false
    @Volatile private var generation = 0
    @Volatile var active = false
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(automatic: Boolean, onSpeech: () -> Unit, onComplete: (Result<File?>) -> Unit) {
        check(!active) { "Microphone is still stopping" }
        val token = ++generation
        val chunkSamples = Wav.SAMPLE_RATE / 50 // 20 ms reads keep cancellation responsive.
        val min = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "16 kHz microphone input unavailable" }
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Wav.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunkSamples * 8))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); error("Microphone unavailable") }
        try { rec.startRecording() } catch (e: Exception) { rec.release(); throw e }
        recorder = rec
        active = true
        running = true
        keepAudio = true
        Thread({
            val startedAt = System.currentTimeMillis()
            val startedElapsed = SystemClock.elapsedRealtime()
            val vad = SpeechOnsetDetector(Wav.SAMPLE_RATE)
            var peakRms = 0.0
            var totalSamples = 0
            var voicedSamples = 0
            var longestOnsetSamples = 0
            var detected = false
            var capturedBytes = 0
            var stopReason = "stopped"
            val result = runCatching {
                val pcm = ByteArrayOutputStream()
                val preRoll = ArrayDeque<ByteArray>()
                val samples = ShortArray(chunkSamples)
                var speech = !automatic
                var onsetSamples = 0
                var silenceSamples = 0
                while (running && token == generation) {
                    val n = rec.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    // AudioRecord.stop() may unblock read with zero or an error. A user
                    // stop is an endpoint, not a failed recording; preserve earlier PCM.
                    if (!running || token != generation) break
                    check(n > 0) { "Microphone read failed: $n" }
                    val bytes = ByteArray(n * 2)
                    var energy = 0.0
                    for (i in 0 until n) {
                        val value = samples[i].toInt()
                        energy += value.toDouble() * value
                        bytes[i * 2] = value.toByte()
                        bytes[i * 2 + 1] = (value shr 8).toByte()
                    }
                    val rms = sqrt(energy / n)
                    val voiced = vad.accept(rms, n, learnNoise = !speech)
                    peakRms = maxOf(peakRms, rms)
                    totalSamples += n
                    if (voiced) voicedSamples += n
                    onsetSamples = if (voiced) onsetSamples + n else 0
                    longestOnsetSamples = maxOf(longestOnsetSamples, onsetSamples)
                    if (vad.onsetDetected) detected = true
                    if (!speech) {
                        preRoll.addLast(bytes)
                        while (preRoll.size > 20) preRoll.removeFirst() // 400 ms onset context.
                        if (vad.onsetDetected) {
                            speech = true
                            preRoll.forEach { pcm.write(it) }
                            preRoll.clear()
                            ui.post { if (token == generation) onSpeech() }
                        }
                        if (totalSamples >= Wav.SAMPLE_RATE * 30) { stopReason = "idle_timeout"; break } // Restart bounded idle captures.
                    } else {
                        pcm.write(bytes)
                        silenceSamples = if (voiced) 0 else silenceSamples + n
                        // The onset has already established speech. Count silence from
                        // that onset even for a short utterance; requiring more voiced
                        // samples here otherwise leaves short speech open for 8 seconds.
                        if (automatic && silenceSamples >= Wav.SAMPLE_RATE * 9 / 10) { stopReason = "silence_endpoint"; break }
                        val limitSeconds = 8
                        if (pcm.size() >= Wav.SAMPLE_RATE * 2 * limitSeconds) { stopReason = "max_duration"; break }
                    }
                }
                capturedBytes = pcm.size()
                if (!keepAudio || token != generation || !speech || pcm.size() < Wav.SAMPLE_RATE / 2) null
                else File(context.cacheDir, "audio-${System.nanoTime()}.wav").also { it.writeBytes(Wav.encode(pcm.toByteArray())) }
            }
            runCatching { rec.stop() }
            rec.release()
            val canceled = !keepAudio || token != generation
            if (canceled) stopReason = "canceled"
            else if (result.isFailure) stopReason = "error"
            else if (stopReason == "stopped") stopReason = "manual_stop"
            saveDiagnostic(JSONObject()
                .put("schema_version", 1).put("automatic", automatic)
                .put("started_at_ms", startedAt).put("ended_at_ms", System.currentTimeMillis())
                .put("duration_ms", SystemClock.elapsedRealtime() - startedElapsed)
                .put("sample_rate", Wav.SAMPLE_RATE).put("samples", totalSamples)
                .put("sample_duration_ms", totalSamples.toLong() * 1000 / Wav.SAMPLE_RATE)
                .put("peak_rms", peakRms).put("noise", vad.noiseRms).put("threshold", vad.thresholdRms)
                .put("voiced_samples", voicedSamples).put("max_consecutive_voiced_samples", longestOnsetSamples)
                .put("onset_window_ms", 300).put("onset_voiced_ms", 100)
                .put("max_window_voiced_samples", vad.maxWindowVoicedSamples)
                .put("detected", detected).put("canceled", canceled).put("stop_reason", stopReason)
                .put("captured_audio_samples", capturedBytes / 2)
                .put("audio_saved", result.getOrNull() != null && !canceled)
                .put("error", result.exceptionOrNull()?.toString() ?: JSONObject.NULL), token)
            synchronized(this) {
                if (recorder === rec) recorder = null
                active = false
            }
            ui.post {
                if (token == generation) onComplete(result)
                else result.getOrNull()?.delete()
            }
        }, "outfit-foreground-audio").start()
    }

    /** Bounded numeric-only diagnostics; no idle PCM is written to disk. */
    private fun saveDiagnostic(diagnostic: JSONObject, token: Int) {
        runCatching {
            val directory = File(context.filesDir, "runs/audio-diagnostics").apply { mkdirs() }
            val text = diagnostic.toString(2).toByteArray(Charsets.UTF_8)
            fun atomicWrite(file: File) {
                val atomic = AtomicFile(file)
                val stream = atomic.startWrite()
                try { stream.write(text); atomic.finishWrite(stream) }
                catch (failure: Exception) { atomic.failWrite(stream); throw failure }
            }
            atomicWrite(File(directory, "capture-${diagnostic.getLong("started_at_ms")}-$token.json"))
            atomicWrite(File(directory, "latest.json"))
            directory.listFiles { file -> file.name.startsWith("capture-") && file.name.endsWith(".json") }
                .orEmpty().sortedByDescending { it.name }.drop(16).forEach { it.delete() }
            Log.i("OutfitRoastAudio", "automatic=${diagnostic.getBoolean("automatic")} samples=${diagnostic.getInt("samples")} peak_rms=${diagnostic.getDouble("peak_rms")} detected=${diagnostic.getBoolean("detected")} reason=${diagnostic.getString("stop_reason")}")
        }.onFailure { Log.w("OutfitRoastAudio", "Could not save capture diagnostics", it) }
    }

    /** keep=true ends push-to-talk; false discards audio and all pending callbacks. */
    @Synchronized
    fun stop(keep: Boolean = false) {
        keepAudio = keep
        if (!keep) generation++
        running = false
        runCatching { recorder?.stop() }
    }
}
