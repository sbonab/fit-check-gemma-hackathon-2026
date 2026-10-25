package dev.local.outfitroast

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** A camera, a spoken fit check, and one contextual follow-up. All model work is serialized. */
class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var engine: RoastEngine
    private lateinit var recorder: ForegroundRecorder
    private lateinit var speaker: OfflineSpeaker
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var resultText: TextView
    private lateinit var resultLabel: TextView
    private lateinit var roastButton: Button
    private lateinit var improveButton: Button
    private lateinit var micButton: Button
    private lateinit var flipButton: Button
    private lateinit var resultCard: LinearLayout
    private var dismissResponse: Runnable? = null
    private var responseVersion = 0
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private var lens = CameraSelector.LENS_FACING_FRONT
    private var cameraEpoch = 0
    @Volatile private var lifecycleEpoch = 0
    private var foreground = false
    private var destroyed = false
    private var loading = false
    private var modelReady = false
    private var loadFailed = false
    private var hasRoast = false
    private var voiceEnabled = true
    private val voiceGate = VoiceGate()
    private var activationTask: Runnable? = null
    private var voiceFeedback: String? = null
    private var feedbackUntil = 0L
    private var armedRequestOnset: Long? = null
    private var speaking = false
    private var currentSpeech: RoastResult? = null
    private var operation: Long? = null
    private var capturePending: Long? = null
    private var serial = 0L
    private var listenAfter = 0L
    private var listenTask: Runnable? = null
    private val ink = Color.rgb(28, 30, 26)
    private val cream = Color.rgb(246, 244, 235)
    private val lime = Color.rgb(217, 240, 142)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startCamera()
        updateControls()
        scheduleListening()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        voiceEnabled = intent.getBooleanExtra("voice_enabled", true)
        engine = RoastEngine(this)
        recorder = ForegroundRecorder(this)
        buildScreen()
        speaker = OfflineSpeaker(this) { description ->
            logEvent("voice_availability", JSONObject().put("description", description))
        }
        loadModel()
        if (!hasPermission(Manifest.permission.CAMERA) || !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else startCamera()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cream)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(15), dp(22), dp(15))
        }
        header.addView(label("Fit Check", 29f, ink).apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            contentDescription = "Fit Check, outfit camera"
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val cameraFrame = FrameLayout(this).apply {
            setBackgroundColor(ink)
            background = rounded(ink, 24)
            clipToOutline = true
        }
        preview = PreviewView(this).apply {
            id = R.id.outfit_camera
            contentDescription = "Live outfit camera preview"
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        cameraFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        flipButton = smallButton("Flip", R.id.outfit_flip).apply {
            contentDescription = "Switch front and rear camera"
            val icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_flip_camera)!!.mutate()
            icon.setBounds(0, 0, dp(22), dp(22))
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = dp(5)
            setPadding(dp(12), 0, dp(10), 0)
            setOnClickListener {
                if (operation == null && !speaking) {
                    resetVoiceActivation()
                    recorder.stop()
                    lens = if (lens == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
                    startCamera()
                }
            }
        }
        cameraFrame.addView(flipButton, FrameLayout.LayoutParams(dp(88), dp(48), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(8), dp(9), 0)
        })
        val cameraBottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(32), dp(18), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xDD121411.toInt()))
        }
        cameraBottom.addView(label("Say “Hey Gemma,\nroast my outfit.”", 23f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        val voiceRow = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        micButton = smallButton("Voice on", R.id.outfit_mic).apply {
            contentDescription = "Turn voice listening off"
            setTextColor(lime)
            setOnClickListener {
                if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    voiceEnabled = true
                    permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                } else {
                    voiceEnabled = !voiceEnabled
                    if (!voiceEnabled) { resetVoiceActivation(); recorder.stop(); status.text = "Voice paused · use the button below" }
                }
                updateControls(); scheduleListening()
            }
        }
        voiceRow.addView(micButton, LinearLayout.LayoutParams(dp(92), dp(48)))
        cameraBottom.addView(voiceRow)
        cameraFrame.addView(cameraBottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(cameraFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(dp(12), 0, dp(12), 0)
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(13), dp(20), dp(15))
        }
        status = label("Warming up Gemma on this phone…", 11f, Color.rgb(92, 96, 83)).apply {
            id = R.id.outfit_status
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, 0, 0, dp(10))
        }
        controls.addView(status)
        resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(233, 233, 220), 16)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            visibility = View.GONE
        }
        resultLabel = label("THE FIT CHECK", 9f, Color.rgb(91, 97, 76)).apply { letterSpacing = 0.17f }
        resultCard.addView(resultLabel)
        resultText = label("", 15f, ink).apply {
            id = R.id.outfit_result
            setPadding(0, dp(7), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        resultCard.addView(resultText)
        controls.addView(resultCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        roastButton = Button(this).apply {
            id = R.id.outfit_roast; text = "Roast my outfit"
            isAllCaps = false; textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ink); background = rounded(lime, 30)
            setOnClickListener {
                when {
                    loadFailed -> loadModel()
                    !hasPermission(Manifest.permission.CAMERA) -> permissions.launch(arrayOf(Manifest.permission.CAMERA))
                    capture == null -> startCamera()
                    else -> requestAction(VoiceIntent.ROAST)
                }
            }
        }
        controls.addView(roastButton, LinearLayout.LayoutParams(-1, dp(56)))
        improveButton = Button(this).apply {
            id = R.id.outfit_improve; text = "Make it better  ↗"; isAllCaps = false; textSize = 13f
            setTextColor(ink); background = rounded(Color.TRANSPARENT, 24)
            visibility = View.GONE
            setOnClickListener { requestAction(VoiceIntent.IMPROVE) }
        }
        controls.addView(improveButton, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        updateControls()
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
    }
    private fun smallButton(value: String, viewId: Int) = Button(this).apply {
        id = viewId; text = value; isAllCaps = false; textSize = 11f
        setTextColor(Color.WHITE); background = rounded(0x66000000, 24)
        setPadding(dp(6), 0, dp(6), 0); minWidth = 0; minimumWidth = 0
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun hasPermission(value: String) = ContextCompat.checkSelfPermission(this, value) == PackageManager.PERMISSION_GRANTED
    private fun interactive() = foreground && hasWindowFocus() &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun updateControls() {
        if (destroyed) return
        val idle = operation == null && !loading && !speaking
        roastButton.isEnabled = idle && (modelReady || loadFailed)
        roastButton.text = when {
            loadFailed -> "Retry Gemma setup"
            !hasPermission(Manifest.permission.CAMERA) -> "Enable camera"
            modelReady && capture == null -> "Retry camera"
            else -> "Roast my outfit"
        }
        improveButton.visibility = if (hasRoast) View.VISIBLE else View.GONE
        improveButton.isEnabled = idle && modelReady && hasRoast
        flipButton.isEnabled = idle && capture != null
        val micAllowed = hasPermission(Manifest.permission.RECORD_AUDIO)
        val activated = voiceEnabled && voiceGate.isArmed(armedRequestOnset ?: SystemClock.elapsedRealtime())
        micButton.text = if (!micAllowed) "Enable voice" else if (activated) "Activated" else if (voiceEnabled) "Voice on" else "Voice off"
        val voiceOff = !voiceEnabled || !micAllowed
        micButton.background = rounded(if (voiceOff) 0xFF8E2028.toInt() else if (activated) lime else 0x66000000, 24)
        micButton.setTextColor(if (voiceOff) Color.WHITE else if (activated) ink else lime)
        micButton.contentDescription = if (!micAllowed) "Allow microphone access" else if (voiceEnabled) "Turn voice listening off" else "Turn voice listening on"
        micButton.isSelected = voiceEnabled
    }

    private fun loadModel() {
        if (loading || operation != null || destroyed) return
        loading = true; loadFailed = false
        status.text = "Warming up Gemma on this phone…"
        updateControls()
        worker.execute {
            val result = runCatching { engine.load() }
            ui.post {
                if (!destroyed) {
                    loading = false; modelReady = result.isSuccess && engine.ready; loadFailed = !modelReady
                    status.text = if (modelReady) "Ready · everything stays on this phone" else "Setup needed · ${result.exceptionOrNull()?.message ?: "model unavailable"}"
                    updateControls(); scheduleListening()
                }
            }
        }
    }

    private fun startCamera() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            status.text = "Camera access is needed for your fit check"
            return
        }
        val token = ++cameraEpoch
        capture = null; updateControls()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (destroyed || token != cameraEpoch) return@addListener
            runCatching {
                val cameraProvider = future.get()
                provider = cameraProvider
                var selector = CameraSelector.Builder().requireLensFacing(lens).build()
                if (!cameraProvider.hasCamera(selector)) {
                    lens = if (lens == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
                    selector = CameraSelector.Builder().requireLensFacing(lens).build()
                }
                val cameraPreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val photo = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, cameraPreview, photo)
                capture = photo
            }.onFailure { status.text = "Camera unavailable · ${it.message}" }
            updateControls(); scheduleListening()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scheduleListening(delay: Long = 650) {
        if (destroyed) return
        listenTask?.let { ui.removeCallbacks(it) }
        val epoch = lifecycleEpoch
        listenTask = Runnable {
            listenTask = null
            if (!destroyed && epoch == lifecycleEpoch) armListening()
        }.also { ui.postDelayed(it, delay) }
    }

    private fun resetVoiceActivation() {
        voiceGate.reset()
        armedRequestOnset = null
        activationTask?.let { ui.removeCallbacks(it) }; activationTask = null
        voiceFeedback = null; feedbackUntil = 0L
    }

    private fun listeningStatus(): String {
        if (SystemClock.elapsedRealtime() < feedbackUntil) return voiceFeedback.orEmpty()
        return if (voiceGate.isArmed(SystemClock.elapsedRealtime())) "I’m listening · roast or improve?"
        else "Listening · say “Hey Gemma”"
    }

    private fun voiceNotice(message: String) {
        voiceFeedback = message; feedbackUntil = SystemClock.elapsedRealtime() + 2500
        status.text = message
        val epoch = lifecycleEpoch
        ui.postDelayed({
            if (!destroyed && epoch == lifecycleEpoch && operation == null && !speaking && recorder.active && armedRequestOnset == null) status.text = listeningStatus()
        }, 2600)
    }

    private fun activateVoice() {
        voiceFeedback = null; feedbackUntil = 0L
        status.text = "I’m listening · roast or improve?"
        micButton.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        updateControls()
        logEvent("wake_activated", JSONObject().put("window_ms", 8000))
        activationTask?.let { ui.removeCallbacks(it) }
        val epoch = lifecycleEpoch
        activationTask = Runnable {
            activationTask = null
            if (!destroyed && epoch == lifecycleEpoch) {
                // A request that began within the window keeps its onset deadline
                // through microphone endpointing and local transcription latency.
                if (armedRequestOnset == null) {
                    if (!voiceGate.isArmed(SystemClock.elapsedRealtime())) logEvent("wake_expired")
                    updateControls()
                    if (operation == null && !speaking && recorder.active) status.text = listeningStatus()
                }
            }
        }.also { ui.postDelayed(it, 8050) }
    }

    private fun armListening() {
        if (!interactive() || !voiceEnabled || !modelReady || loading || operation != null || speaking || capture == null ||
            !hasPermission(Manifest.permission.RECORD_AUDIO)) return
        if (SystemClock.elapsedRealtime() < listenAfter || recorder.active) { scheduleListening(150); return }
        val epoch = lifecycleEpoch
        runCatching {
            recorder.start(automatic = true, onSpeech = {
                if (epoch == lifecycleEpoch && foreground) {
                    val onset = SystemClock.elapsedRealtime()
                    armedRequestOnset = onset.takeIf { voiceGate.isArmed(it) }
                    status.text = "Listening to your request…"
                }
            }, onComplete = { audio ->
                if (destroyed || epoch != lifecycleEpoch || !interactive() || !voiceEnabled) {
                    audio.getOrNull()?.delete()
                } else {
                    audio.onSuccess { file ->
                        if (file == null) { armedRequestOnset = null; scheduleListening(200) } else transcribe(file, epoch)
                    }.onFailure {
                        resetVoiceActivation()
                        status.text = "Microphone paused · ${it.message}"
                        voiceEnabled = false; updateControls()
                    }
                }
            })
            logEvent("listening_started")
            status.text = listeningStatus()
            updateControls()
        }.onFailure {
            resetVoiceActivation()
            status.text = "Microphone paused · ${it.message}"
            voiceEnabled = false; updateControls()
        }
    }

    private fun transcribe(audio: File, epoch: Int) {
        if (operation != null || speaking) { audio.delete(); scheduleListening(); return }
        val token = ++serial
        operation = token; status.text = "Understanding your request…"; updateControls()
        worker.execute {
            val decoded = runCatching { engine.transcribe(audio) }
            audio.delete()
            ui.post {
                if (!valid(token, epoch) || !voiceEnabled || !interactive()) { finish(token); return@post }
                decoded.onSuccess { text ->
                    val now = SystemClock.elapsedRealtime()
                    val onset = armedRequestOnset
                    var intent = voiceGate.accept(text, hasRoast, onset ?: now)
                    // A fresh wake always receives its full window after ASR completes.
                    if (intent == VoiceIntent.WAKE && onset != null) intent = voiceGate.accept(text, hasRoast, now)
                    armedRequestOnset = null
                    logEvent("voice_intent", JSONObject().put("text", text).put("intent", intent.name))
                    when (intent) {
                        VoiceIntent.WAKE -> { activateVoice(); finish(token, 0) }
                        VoiceIntent.IGNORE -> {
                            voiceNotice(if (voiceGate.isArmed(SystemClock.elapsedRealtime())) "Try “Roast my outfit” or “Make it better”" else "Say “Hey Gemma” to activate voice")
                            finish(token)
                        }
                        else -> beginAction(intent, token, epoch, "voice")
                    }
                }.onFailure {
                    resetVoiceActivation()
                    voiceNotice("Couldn’t catch that · say “Hey Gemma” and try again")
                    logEvent("voice_transcription_error", JSONObject().put("error", it.message ?: "unintelligible"))
                    finish(token)
                }
            }
        }
    }

    private fun requestAction(intent: VoiceIntent) {
        if (!interactive() || !modelReady || operation != null || loading || speaking || (intent == VoiceIntent.IMPROVE && !hasRoast)) return
        resetVoiceActivation()
        val token = ++serial
        operation = token
        val epoch = lifecycleEpoch
        recorder.stop()
        status.text = if (intent == VoiceIntent.ROAST) "Getting your fit in frame…" else "Finding your next upgrade…"
        updateControls()
        val deadline = SystemClock.elapsedRealtime() + 3000
        fun released() {
            if (!valid(token, epoch)) { finish(token); return }
            if (!recorder.active) beginAction(intent, token, epoch, "button")
            else if (SystemClock.elapsedRealtime() < deadline) ui.postDelayed({ released() }, 40)
            else { status.text = "Microphone is busy · try again"; finish(token) }
        }
        released()
    }

    private fun beginAction(intent: VoiceIntent, token: Long, epoch: Int, source: String) {
        if (!valid(token, epoch)) { finish(token); return }
        resetVoiceActivation()
        logEvent("action_started", JSONObject().put("intent", intent.name).put("source", source))
        check(!recorder.active) { "Microphone must be released before inference" }
        if (intent == VoiceIntent.IMPROVE) {
            status.text = "Styling the same fit…"
            runInference(token, epoch, intent) { engine.improve() }
            return
        }
        hasRoast = false
        updateControls()
        val camera = capture ?: run { status.text = "Camera is getting ready · try again"; finish(token); return }
        status.text = "Looking at your outfit…"
        val raw = File(cacheDir, "outfit-capture-${System.nanoTime()}.jpg")
        capturePending = token
        val watchdog = Runnable {
            if (capturePending == token && operation == token) {
                if (valid(token, epoch)) status.text = "Camera took too long · try again"
                raw.delete(); finish(token)
            }
        }
        ui.postDelayed(watchdog, 12000)
        try { camera.takePicture(ImageCapture.OutputFileOptions.Builder(raw).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    ui.removeCallbacks(watchdog)
                    if (capturePending == token) capturePending = null
                    if (!valid(token, epoch)) { raw.delete(); finish(token); return }
                    runInference(token, epoch, intent) {
                        try {
                            val normalized = normalizeImage(raw)
                            try { engine.roast(normalized) } finally { normalized.delete() }
                        } finally { raw.delete() }
                    }
                }
                override fun onError(error: ImageCaptureException) {
                    ui.removeCallbacks(watchdog)
                    raw.delete()
                    if (valid(token, epoch)) status.text = "Couldn’t capture your fit · try again"
                    finish(token)
                }
            })
        } catch (error: Exception) {
            ui.removeCallbacks(watchdog); raw.delete()
            if (valid(token, epoch)) status.text = "Couldn’t capture your fit · try again"
            finish(token)
        }
    }

    private fun runInference(token: Long, epoch: Int, intent: VoiceIntent, action: () -> RoastResult) {
        worker.execute {
            val result = runCatching(action)
            val missingOriginal = result.getOrNull()?.let { response ->
                runCatching { JSONObject(File(response.runDir, "result.json").readText()).optString("status") == "missing_original_outfit" }.getOrDefault(false)
            } ?: false
            ui.post {
                if (!valid(token, epoch)) {
                    // An unseen result must not become the context for a later follow-up.
                    if (!destroyed && (intent == VoiceIntent.ROAST || missingOriginal)) {
                        hasRoast = false; worker.execute { engine.reset() }
                    }
                    finish(token); return@post
                }
                result.onSuccess { response ->
                    if (response.accepted && response.kind == "roast") hasRoast = true
                    else if (intent == VoiceIntent.ROAST || missingOriginal) hasRoast = false
                    resultLabel.text = when (response.kind) { "improve" -> "THE UPGRADE"; "clarify" -> "ONE QUICK THING"; else -> "THE FIT CHECK" }
                    showResponseText(response.text)
                    status.text = "Response ready"
                    logEvent("result_shown", JSONObject().put("run_dir", response.runDir).put("kind", response.kind).put("accepted", response.accepted))
                    speak(response, epoch)
                    finish(token)
                    // Prepare fresh, image-free contexts while the current answer is spoken.
                    worker.execute {
                        runCatching { engine.prepareResponses() }.onFailure { error ->
                            ui.post { if (!destroyed) logEvent("response_prewarm_failed", JSONObject().put("error", error.toString())) }
                        }
                    }
                }.onFailure {
                    status.text = "Fit check interrupted · ${it.message ?: "try again"}"
                    finish(token)
                }
            }
        }
    }

    /** Captions stay visible throughout queued speech and playback. */
    private fun showResponseText(text: String) {
        hideResponseText()
        resultText.text = text
        resultCard.visibility = View.VISIBLE
    }

    private fun scheduleResponseDismissal(delayMs: Long = 5000) {
        dismissResponse?.let { ui.removeCallbacks(it) }
        val version = responseVersion
        dismissResponse = Runnable {
            dismissResponse = null
            if (!destroyed && responseVersion == version) {
                resultCard.animate().alpha(0f).setDuration(300).withEndAction {
                    if (responseVersion == version) {
                        hideResponseText()
                        logEvent("response_text_hidden")
                    }
                }.start()
            }
        }.also { ui.postDelayed(it, delayMs) }
    }

    private fun hideResponseText() {
        responseVersion++
        dismissResponse?.let { ui.removeCallbacks(it) }; dismissResponse = null
        resultCard.animate().cancel()
        resultCard.visibility = View.GONE
        resultCard.alpha = 1f
    }

    private fun speak(response: RoastResult, epoch: Int) {
        if (!interactive() || epoch != lifecycleEpoch) return
        resetVoiceActivation()
        speaking = true; currentSpeech = response
        speechEvent(response, "queued")
        val queued = speaker.speak(response.text, onStart = {
            if (epoch == lifecycleEpoch && !destroyed) { status.text = "Speaking your ${if (response.kind == "improve") "upgrade" else "fit check"}…"; speechEvent(response, "speaking") }
        }, onComplete = { error ->
            if (epoch == lifecycleEpoch && !destroyed) {
                speaking = false; currentSpeech = null
                resetVoiceActivation()
                speechEvent(response, if (error == null) "done" else "error", error)
                scheduleResponseDismissal(if (error == null) 5000 else textReadingTime(response.text))
                status.text = if (error == null) {
                    if (hasRoast) "Say “Hey Gemma” to ask for an upgrade" else "Try a clearer, full-outfit frame"
                } else "Text ready · $error"
                listenAfter = SystemClock.elapsedRealtime() + 900
                updateControls(); scheduleListening(950)
            }
        })
        if (!queued) {
            resetVoiceActivation()
            speaking = false; currentSpeech = null
            speechEvent(response, "text_only", speaker.description)
            status.text = "Text ready · ${speaker.description}"
            scheduleResponseDismissal(textReadingTime(response.text))
            listenAfter = SystemClock.elapsedRealtime() + 900
        }
        updateControls()
    }

    private fun textReadingTime(text: String): Long =
        maxOf(8000L, text.split(Regex("\\s+")).size * 400L)

    private fun speechEvent(response: RoastResult, state: String, error: String? = null) {
        logEvent("tts_$state", JSONObject().put("run_dir", response.runDir).put("network_required", false)
            .put("error", error ?: JSONObject.NULL))
    }

    private fun valid(token: Long, epoch: Int) = !destroyed && foreground && epoch == lifecycleEpoch && operation == token
    private fun finish(token: Long, listeningDelay: Long = 650) {
        if (capturePending == token) capturePending = null
        if (operation == token) operation = null
        if (!destroyed) { updateControls(); scheduleListening(listeningDelay) }
    }

    private fun normalizeImage(source: File): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Camera frame unavailable" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        var bitmap = checkNotNull(BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }))
        try {
            val exif = ExifInterface(source)
            val matrix = Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
            }
            val scale = minOf(1.0, 768.0 / maxOf(bitmap.width, bitmap.height))
            if (scale < 1) {
                val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                if (scaled !== bitmap) { bitmap.recycle(); bitmap = scaled }
            }
            return File(cacheDir, "outfit-frame-${System.nanoTime()}.jpg").also { image ->
                image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) { "Couldn’t save camera frame" } }
            }
        } finally { bitmap.recycle() }
    }

    private fun logEvent(name: String, details: JSONObject = JSONObject()) {
        val event = details.put("event", name).put("at_ms", System.currentTimeMillis()).put("epoch", lifecycleEpoch)
        android.util.Log.i("FitCheck", event.toString())
        runCatching {
            val file = File(filesDir, "ui-events.jsonl")
            if (file.length() > 256 * 1024) file.writeText("")
            file.appendText(event.toString() + "\n")
        }
    }

    override fun onResume() {
        super.onResume(); foreground = true
        logEvent("foreground_resumed")
        if (::recorder.isInitialized) { updateControls(); scheduleListening() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::recorder.isInitialized) {
            if (hasFocus) scheduleListening() else { resetVoiceActivation(); recorder.stop(); updateControls() }
        }
    }

    override fun onPause() {
        foreground = false; lifecycleEpoch++
        hideResponseText()
        resetVoiceActivation()
        listenTask?.let { ui.removeCallbacks(it) }; listenTask = null
        recorder.stop()
        capturePending?.let { finish(it) }
        currentSpeech?.let { speechEvent(it, "canceled") }
        currentSpeech = null; speaker.stop(); speaking = false
        logEvent("foreground_paused")
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true; lifecycleEpoch++; cameraEpoch++
        ui.removeCallbacksAndMessages(null)
        recorder.stop(); speaker.close(); provider?.unbindAll()
        worker.execute { engine.close() }; worker.shutdown()
        super.onDestroy()
    }
}
