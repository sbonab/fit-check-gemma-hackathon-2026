package dev.local.outfitroast

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.UseCase
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real camera/model/microphone/TTS checks. The phone must be unlocked before execution. */
@RunWith(AndroidJUnit4::class)
class OutfitUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun prepare() {
        assertFalse("Unlock the phone before UI verification; the test does not dismiss keyguard",
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        for (permission in listOf("CAMERA", "RECORD_AUDIO")) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.$permission")).use { it.readBytes() }
        }
    }

    private fun await(message: String, timeout: Long = 180_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(150)
        }
        fail(message)
    }

    private fun events(): List<JSONObject> = File(context.filesDir, "ui-events.jsonl")
        .takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }

    private fun saveReport(name: String, report: JSONObject) {
        File(context.filesDir, "runs/ui-$name-${System.currentTimeMillis()}").apply { mkdirs() }
            .resolve("verification.json").writeText(report.toString(2))
    }

    @Test fun simplifiedControlsAndTransientCaptions() {
        prepare()
        val report = JSONObject().put("passed", false).put("scope", "voice toggle, flip icon, caption replacement and timed dismissal")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var ready = false
                await("App did not become ready") {
                    scenario.onActivity { ready = it.findViewById<Button>(R.id.outfit_roast).isEnabled }
                    ready
                }
                val show = MainActivity::class.java.getDeclaredMethod("showResponseText", String::class.java).apply { isAccessible = true }
                val dismiss = MainActivity::class.java.getDeclaredMethod("scheduleResponseDismissal", Long::class.javaPrimitiveType!!).apply { isAccessible = true }
                scenario.onActivity { activity ->
                    val mic = activity.findViewById<Button>(R.id.outfit_mic)
                    mic.performClick()
                    assertEquals("Voice off", mic.text.toString())
                    val background = (mic.background as GradientDrawable).color!!.defaultColor
                    assertTrue("Voice off needs a red background", Color.red(background) > Color.green(background) * 2 && Color.red(background) > Color.blue(background) * 2)
                    assertEquals(Color.WHITE, mic.currentTextColor)
                    val flip = activity.findViewById<Button>(R.id.outfit_flip)
                    assertNotNull("Flip needs a camera-switch icon", flip.compoundDrawablesRelative[0])
                    show.invoke(activity, "First response.")
                    dismiss.invoke(activity, 5000L)
                    assertTrue(activity.findViewById<TextView>(R.id.outfit_result).isShown)
                }
                Thread.sleep(3000)
                scenario.onActivity { show.invoke(it, "New response."); dismiss.invoke(it, 5000L) }
                Thread.sleep(2500)
                scenario.onActivity {
                    val result = it.findViewById<TextView>(R.id.outfit_result)
                    assertTrue("An old timer hid the newer response", result.isShown)
                    assertEquals("New response.", result.text.toString())
                }
                await("Response caption did not fade away", 4000) {
                    scenario.onActivity { ready = !it.findViewById<TextView>(R.id.outfit_result).isShown }
                    ready
                }
                scenario.onActivity {
                    show.invoke(it, "Visible again.")
                    val result = it.findViewById<TextView>(R.id.outfit_result)
                    assertTrue(result.isShown)
                    assertEquals(1f, (result.parent as View).alpha, 0f)
                    val mic = it.findViewById<Button>(R.id.outfit_mic)
                    assertEquals("Voice off", mic.text.toString())
                    mic.performClick()
                    assertEquals("Voice on", mic.text.toString())
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity {
                    assertFalse("A stale caption returned after resume", it.findViewById<TextView>(R.id.outfit_result).isShown)
                }
                report.put("passed", true)
            }
        } finally { saveReport("ui-polish", report) }
    }

    @Test fun captionStaysThroughRealSpeechThenFades() {
        prepare()
        val report = JSONObject().put("passed", false).put("scope", "synthetic caption with real offline TTS completion and delayed fade")
        val runId = "caption-tts-${System.currentTimeMillis()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var ready = false
                await("App did not become ready") {
                    scenario.onActivity { ready = it.findViewById<Button>(R.id.outfit_roast).isEnabled }
                    ready
                }
                val show = MainActivity::class.java.getDeclaredMethod("showResponseText", String::class.java).apply { isAccessible = true }
                val speak = MainActivity::class.java.getDeclaredMethod("speak", RoastResult::class.java, Int::class.javaPrimitiveType!!).apply { isAccessible = true }
                val epoch = MainActivity::class.java.getDeclaredField("lifecycleEpoch").apply { isAccessible = true }
                val text = "Your outfit tells a story and the story needs a second draft before it is ready to be shared publicly. " +
                    "That black hoodie is clearly trying too hard to be mysterious, like a failed beta test. " +
                    "Those dark pants look like they were designed for a very serious, very low budget stealth mission."
                scenario.onActivity {
                    it.findViewById<Button>(R.id.outfit_mic).performClick()
                    show.invoke(it, text)
                    speak.invoke(it, RoastResult(text, "roast", runId, 0, true), epoch.getInt(it))
                }
                fun speechDone() = events().any { it.optString("run_dir") == runId && it.optString("event") == "tts_done" }
                await("Offline speech never started", 10000) {
                    events().any { it.optString("run_dir") == runId && it.optString("event") == "tts_speaking" }
                }
                Thread.sleep(6000)
                assertFalse("Use a long enough utterance to reproduce the early-caption regression", speechDone())
                await("Offline speech never finished", 60000) {
                    scenario.onActivity { assertTrue("Caption disappeared during playback", it.findViewById<TextView>(R.id.outfit_result).isShown) }
                    speechDone()
                }
                Thread.sleep(3000)
                scenario.onActivity { assertTrue("Caption disappeared without a reading pause", it.findViewById<TextView>(R.id.outfit_result).isShown) }
                await("Caption did not fade after speech", 4000) {
                    scenario.onActivity { ready = !it.findViewById<TextView>(R.id.outfit_result).isShown }
                    ready
                }
                report.put("passed", true).put("offline_tts_done", true).put("visible_during_speech", true)
            }
        } finally { saveReport("caption-tts", report) }
    }

    @Test fun cameraFirstScreenAndPauseStopsListening() {
        prepare()
        val report = JSONObject().put("passed", false).put("scope", "camera preview, primary controls, real foreground recorder lifecycle")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var listening = false
                var streaming = false
                await("Camera/model did not reach foreground listening") {
                    scenario.onActivity {
                        listening = it.findViewById<TextView>(R.id.outfit_status).text.startsWith("Listening")
                        streaming = it.findViewById<PreviewView>(R.id.outfit_camera).previewStreamState.value == PreviewView.StreamState.STREAMING
                    }
                    listening && streaming
                }
                scenario.onActivity {
                    for (id in listOf(R.id.outfit_camera, R.id.outfit_roast, R.id.outfit_mic, R.id.outfit_flip)) {
                        assertTrue("Primary control is not visible: $id", it.findViewById<View>(id).isShown)
                    }
                    assertFalse("Follow-up must wait for a valid roast", it.findViewById<View>(R.id.outfit_improve).isShown)
                    assertFalse("An empty result card leaked into the main camera", it.findViewById<View>(R.id.outfit_result).isShown)
                    assertEquals("Roast my outfit", it.findViewById<Button>(R.id.outfit_roast).text.toString())
                }
                // Inspect the bound CameraX use case and actual preview camera, not just
                // the requested lens or a STREAMING value left by the previous binding.
                val captureField = MainActivity::class.java.getDeclaredField("capture").apply { isAccessible = true }
                val lensField = MainActivity::class.java.getDeclaredField("lens").apply { isAccessible = true }
                val recorderField = MainActivity::class.java.getDeclaredField("recorder").apply { isAccessible = true }
                val previewInfoField = PreviewView::class.java.getDeclaredField("mCameraInfoInternal").apply { isAccessible = true }
                val boundCameraMethod = UseCase::class.java.getMethod("getCamera")
                fun boundCamera(capture: ImageCapture?): Camera? = capture?.let { boundCameraMethod.invoke(it) as? Camera }
                scenario.onActivity { activity ->
                    val selected = captureField.get(activity) as? ImageCapture
                    assertNotNull("Initial front capture use case is missing", selected)
                    assertEquals(CameraSelector.LENS_FACING_FRONT, boundCamera(selected)?.cameraInfo?.lensFacing)
                }
                val switches = JSONArray()
                var finalFlipAt = 0L
                for (target in listOf(CameraSelector.LENS_FACING_BACK, CameraSelector.LENS_FACING_FRONT)) {
                    var prior: ImageCapture? = null
                    var framesForNewBinding = 0
                    var switched = false
                    scenario.onActivity { activity ->
                        prior = captureField.get(activity) as? ImageCapture
                        val preview = activity.findViewById<PreviewView>(R.id.outfit_camera)
                        preview.setFrameUpdateListener(ContextCompat.getMainExecutor(activity)) {
                            val selected = captureField.get(activity) as? ImageCapture
                            val actual = boundCamera(selected)?.cameraInfo
                            val previewInfo = previewInfoField.get(preview) as? CameraInfo
                            if (selected != null && selected !== prior && lensField.getInt(activity) == target &&
                                actual?.lensFacing == target && previewInfo?.lensFacing == target &&
                                actual.cameraState.value?.type == CameraState.Type.OPEN &&
                                preview.previewStreamState.value == PreviewView.StreamState.STREAMING) framesForNewBinding++
                        }
                        val flip = activity.findViewById<Button>(R.id.outfit_flip)
                        assertTrue("Flip must be available on the main camera", flip.isEnabled)
                        finalFlipAt = System.currentTimeMillis()
                        flip.performClick()
                    }
                    await("Camera did not deliver new frames for lens $target after Flip", 15000) {
                        scenario.onActivity { activity ->
                            val selected = captureField.get(activity) as? ImageCapture
                            switched = selected != null && selected !== prior &&
                                boundCamera(selected)?.cameraInfo?.lensFacing == target && framesForNewBinding >= 2 &&
                                activity.findViewById<PreviewView>(R.id.outfit_camera).previewStreamState.value == PreviewView.StreamState.STREAMING
                        }
                        switched
                    }
                    switches.put(JSONObject().put("actual_lens", if (target == CameraSelector.LENS_FACING_BACK) "rear" else "front")
                        .put("new_capture_use_case", true).put("new_preview_frames", framesForNewBinding).put("streaming", true))
                }
                report.put("camera_switches", switches)
                await("Foreground voice did not rearm after camera flips", 10000) {
                    scenario.onActivity {
                        listening = (recorderField.get(it) as ForegroundRecorder).active &&
                            it.findViewById<TextView>(R.id.outfit_status).text.startsWith("Listening")
                    }
                    listening && events().any { it.optString("event") == "listening_started" && it.optLong("at_ms") > finalFlipAt }
                }
                val pausedAt = System.currentTimeMillis()
                scenario.moveToState(Lifecycle.State.CREATED)
                await("Foreground microphone was not canceled on pause", 5000) {
                    val latest = runCatching { JSONObject(File(context.filesDir, "runs/audio-diagnostics/latest.json").readText()) }.getOrNull()
                    latest?.optBoolean("canceled") == true && latest.optLong("ended_at_ms") >= pausedAt
                }
                Thread.sleep(1000)
                assertTrue("Activity never recorded its pause", events().any { it.optString("event") == "foreground_paused" && it.optLong("at_ms") >= pausedAt })
                assertFalse("Background activity started speech", events().any { it.optString("event") == "tts_speaking" && it.optLong("at_ms") >= pausedAt })
                val resumedAt = System.currentTimeMillis()
                scenario.moveToState(Lifecycle.State.RESUMED)
                await("Foreground voice did not rearm after resume", 10000) {
                    scenario.onActivity {
                        listening = (recorderField.get(it) as ForegroundRecorder).active &&
                            it.findViewById<TextView>(R.id.outfit_status).text.startsWith("Listening")
                    }
                    listening && events().any { it.optString("event") == "listening_started" && it.optLong("at_ms") >= resumedAt }
                }
                report.put("camera_streaming", streaming).put("microphone_canceled_on_pause", true)
                    .put("no_background_tts", true).put("resumed_listening", true).put("passed", true)
            }
        } finally { saveReport("foreground", report) }
    }

    /** Point the real camera at a scene without clothing before running this negative path. */
    @Test fun noOutfitCameraClarifiesSpeaksAndRearms() {
        prepare()
        val report = JSONObject().put("passed", false)
            .put("input", "real camera with no outfit, visible roast button, default foreground voice")
            .put("human_audibility_checked", false)
        var record: JSONObject? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var ready = false
                await("Camera and default foreground voice did not become ready") {
                    scenario.onActivity {
                        ready = it.findViewById<Button>(R.id.outfit_roast).isEnabled &&
                            it.findViewById<TextView>(R.id.outfit_status).text.startsWith("Listening") &&
                            it.findViewById<PreviewView>(R.id.outfit_camera).previewStreamState.value == PreviewView.StreamState.STREAMING
                    }
                    ready
                }
                val since = System.currentTimeMillis()
                scenario.onActivity { it.findViewById<Button>(R.id.outfit_roast).performClick() }
                var runDir: String? = null
                var doneAt = 0L
                await("No-outfit clarification and actual offline speech did not complete") {
                    val shown = events().firstOrNull { it.optString("event") == "result_shown" && it.optLong("at_ms") >= since }
                    if (shown != null) {
                        runDir = shown.getString("run_dir")
                        record = JSONObject(File(checkNotNull(runDir), "result.json").readText())
                        assertFalse("This negative test requires a real scene without an outfit: $record", shown.getBoolean("accepted"))
                        assertEquals("clarify", record!!.getString("kind"))
                        assertEquals("No-outfit detection must be a model observation, not a formatting failure", "no_visible_outfit", record!!.getString("status"))
                        val speech = events().lastOrNull { it.optString("run_dir") == runDir && it.optString("event") in setOf("tts_done", "tts_error", "tts_text_only") }
                        if (speech != null) {
                            assertEquals("Clarification must complete embedded speech: $speech", "tts_done", speech.getString("event"))
                            assertFalse(speech.getBoolean("network_required"))
                            doneAt = speech.getLong("at_ms")
                        }
                    }
                    doneAt > 0
                }
                await("Microphone did not rearm after the spoken clarification", 10000) {
                    scenario.onActivity {
                        ready = it.findViewById<TextView>(R.id.outfit_status).text.startsWith("Listening") &&
                            it.findViewById<Button>(R.id.outfit_roast).isEnabled
                    }
                    ready && events().any { it.optString("event") == "listening_started" && it.optLong("at_ms") >= doneAt + 850 }
                }
                scenario.onActivity { activity ->
                    val text = activity.findViewById<TextView>(R.id.outfit_result)
                    assertEquals(record!!.getString("output"), text.text.toString())
                    assertFalse("Clarification must not enable a contextual improvement", activity.findViewById<View>(R.id.outfit_improve).isShown)
                    for (id in listOf(R.id.outfit_roast, R.id.outfit_camera)) {
                        val view = activity.findViewById<View>(id)
                        val visible = Rect()
                        assertTrue("View must remain visible after clarification: $id", view.getGlobalVisibleRect(visible))
                        assertEquals("View is clipped at normal device settings: $id", view.height, visible.height())
                    }
                    if (text.isShown) assertTrue("Clarification text is clipped", text.layout.getLineBottom(text.layout.lineCount - 1) <= text.height - text.paddingTop - text.paddingBottom)
                }
                report.put("passed", true).put("run_dir", runDir).put("offline_tts_done", true)
                    .put("microphone_rearmed", true).put("improve_hidden", true).put("camera_and_text_unclipped", true)
            }
        } finally { report.put("camera_result", record ?: JSONObject.NULL); saveReport("no-outfit", report) }
    }

    /** Actual Gemma audio transcription through the activity; this does not test a physical microphone. */
    @Test fun prerecordedWakeThenRequestUsesRealActivityPipeline() {
        prepare()
        val wake = File(context.filesDir, "eval/wake.wav")
        val request = File(context.filesDir, "eval/request.wav")
        assertTrue("Push the recorded Hey Gemma fixture to files/eval/wake.wav", wake.isFile)
        assertTrue("Push the recorded roast request to files/eval/request.wav", request.isFile)
        val report = JSONObject().put("passed", false).put("input", "prerecorded WAVs injected at MainActivity.transcribe")
            .put("scope", "actual local Gemma audio transcription, wake UI, real camera, model response and offline TTS")
            .put("physical_microphone_checked", false).put("human_audibility_checked", false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var ready = false
                await("Gemma and real camera did not become ready") {
                    scenario.onActivity {
                        ready = it.findViewById<Button>(R.id.outfit_roast).isEnabled &&
                            it.findViewById<TextView>(R.id.outfit_status).text.startsWith("Listening") &&
                            it.findViewById<PreviewView>(R.id.outfit_camera).previewStreamState.value == PreviewView.StreamState.STREAMING
                    }
                    ready
                }
                val recorderField = MainActivity::class.java.getDeclaredField("recorder").apply { isAccessible = true }
                val epochField = MainActivity::class.java.getDeclaredField("lifecycleEpoch").apply { isAccessible = true }
                val gateField = MainActivity::class.java.getDeclaredField("voiceGate").apply { isAccessible = true }
                val onsetField = MainActivity::class.java.getDeclaredField("armedRequestOnset").apply { isAccessible = true }
                val transcribe = MainActivity::class.java.getDeclaredMethod("transcribe", File::class.java, Int::class.javaPrimitiveType!!).apply { isAccessible = true }
                fun submitAudio(source: File, inArmedWindow: Boolean) {
                    scenario.onActivity { (recorderField.get(it) as ForegroundRecorder).stop() }
                    var released = false
                    await("Microphone did not release before recorded audio injection", 3000) {
                        scenario.onActivity { released = !(recorderField.get(it) as ForegroundRecorder).active }
                        released
                    }
                    val audio = source.copyTo(File(context.cacheDir, "ui-replay-${System.nanoTime()}.wav"))
                    scenario.onActivity {
                        if (inArmedWindow) {
                            val onset = SystemClock.elapsedRealtime()
                            assertTrue("Wake window expired before request started", (gateField.get(it) as VoiceGate).isArmed(onset))
                            onsetField.set(it, onset)
                        }
                        transcribe.invoke(it, audio, epochField.getInt(it))
                    }
                }
                val since = System.currentTimeMillis()
                submitAudio(wake, false)
                await("Real wake transcription did not activate the UI", 30000) {
                    scenario.onActivity {
                        ready = (gateField.get(it) as VoiceGate).isArmed(SystemClock.elapsedRealtime()) &&
                            it.findViewById<Button>(R.id.outfit_mic).text == "Activated" &&
                            it.findViewById<TextView>(R.id.outfit_status).text.startsWith("I’m listening")
                    }
                    ready && events().any { it.optLong("at_ms") >= since && it.optString("event") == "wake_activated" }
                }
                report.put("wake_ui_activated", true)
                submitAudio(request, true)
                var runDir: String? = null
                await("Recorded request did not reach real camera and offline TTS", 180000) {
                    val action = events().firstOrNull { it.optLong("at_ms") >= since && it.optString("event") == "action_started" && it.optString("source") == "voice" }
                    if (action != null) assertEquals("ROAST", action.getString("intent"))
                    val shown = events().firstOrNull { it.optLong("at_ms") >= since && it.optString("event") == "result_shown" }
                    if (shown != null) {
                        runDir = shown.getString("run_dir")
                        val result = JSONObject(File(checkNotNull(runDir), "result.json").readText())
                        assertTrue("Real camera image was not saved", File(checkNotNull(runDir), result.getString("image")).length() > 0)
                        assertTrue("Unexpected camera response: $result", result.getString("kind") in setOf("roast", "clarify"))
                        val speech = events().lastOrNull { it.optString("run_dir") == runDir && it.optString("event") in setOf("tts_done", "tts_error", "tts_text_only") }
                        if (speech != null) assertEquals("Offline response speech failed: $speech", "tts_done", speech.getString("event"))
                        ready = action != null && speech?.optString("event") == "tts_done"
                    } else ready = false
                    ready
                }
                scenario.onActivity { assertFalse("The consumed wake must not authorize another request", (gateField.get(it) as VoiceGate).isArmed(SystemClock.elapsedRealtime())) }
                report.put("run_dir", runDir).put("wake_consumed", true).put("passed", true)
            }
        } finally { saveReport("prerecorded-wake", report) }
    }

    /** Point the camera at a real outfit before running this test. No fixture bypasses the camera. */
    @Test fun freshCameraRoastThenContextualImprovement() {
        prepare()
        val report = JSONObject().put("passed", false).put("input", "real camera and visible buttons")
            .put("human_audibility_checked", false)
        val accepted = JSONArray()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var ready = false
                await("Gemma and camera did not become ready") {
                    scenario.onActivity {
                        val button = it.findViewById<Button>(R.id.outfit_roast)
                        ready = button.isEnabled && button.text == "Roast my outfit" &&
                            it.findViewById<PreviewView>(R.id.outfit_camera).previewStreamState.value == PreviewView.StreamState.STREAMING
                    }
                    ready
                }
                // Exercise the microphone-release gate by clicking while default voice is armed.
                for (kind in listOf("roast", "improve")) {
                    val since = System.currentTimeMillis()
                    scenario.onActivity {
                        val action = it.findViewById<Button>(if (kind == "roast") R.id.outfit_roast else R.id.outfit_improve)
                        assertTrue("$kind action is not available", action.isShown && action.isEnabled)
                        action.performClick()
                    }
                    var result: JSONObject? = null
                    var speech: JSONObject? = null
                    await("$kind result and completed offline speech were not saved", 180000) {
                        val shown = events().lastOrNull { it.optString("event") == "result_shown" && it.optLong("at_ms") >= since }
                        if (shown != null) {
                            result = JSONObject(File(shown.getString("run_dir"), "result.json").readText())
                            assertTrue("Camera needs a clear real outfit; received clarification: $result", shown.optBoolean("accepted"))
                            assertEquals(kind, result!!.optString("kind"))
                            speech = events().lastOrNull { it.optString("run_dir") == shown.optString("run_dir") && it.optString("event") in setOf("tts_done", "tts_error", "tts_text_only") }
                            if (speech != null) assertEquals("Embedded speech must complete: $speech", "tts_done", speech!!.optString("event"))
                        }
                        speech?.optString("event") == "tts_done"
                    }
                    val record = checkNotNull(result)
                    assertEquals("ok", record.getString("status"))
                    assertEquals(3, record.getJSONArray("sentences").length())
                    assertFalse("Offline TTS used a network voice", checkNotNull(speech).getBoolean("network_required"))
                    if (kind == "improve") {
                        assertTrue("Follow-up must use the original outfit frame", record.getBoolean("reused_original_image"))
                        assertEquals(accepted.getJSONObject(0).getString("image_sha256"), record.getString("image_sha256"))
                    }
                    accepted.put(record)
                    scenario.onActivity {
                        assertEquals(record.getString("output"), it.findViewById<TextView>(R.id.outfit_result).text.toString())
                        assertTrue(it.findViewById<Button>(R.id.outfit_improve).isShown)
                    }
                    // Let the mandatory TTS-to-microphone cooldown finish before the next turn.
                    await("Primary controls did not recover after speech", 10000) {
                        scenario.onActivity { ready = it.findViewById<Button>(R.id.outfit_improve).isEnabled }
                        ready
                    }
                }
                report.put("passed", true)
            }
        } finally { report.put("turns", accepted); saveReport("camera-flow", report) }
    }
}
