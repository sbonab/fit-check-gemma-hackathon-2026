package dev.local.outfitroast

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

const val MODEL_NAME = "gemma-4-E2B-it.litertlm"
const val MODEL_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
const val MODEL_BYTES = 2588147712L

data class RoastResult(val text: String, val kind: String, val runDir: String, val latencyMs: Long, val accepted: Boolean)

/** Explicit settings let the device benchmark change one variable at a time. */
data class RoastTuning(
    val name: String = "baseline",
    val speculative: Boolean = false,
    val visionGpu: Boolean = false,
    val visualTokens: Int? = null,
    val contextTokens: Int = 4096,
    val briefResponses: Boolean = false,
    val prewarm: Boolean = false,
) {
    companion object {
        val CURRENT = RoastTuning(name = "prewarm-140", prewarm = true, visualTokens = 140)
    }
}

/** Own on one worker, including load, audio, generation, reset and close. No network calls. */
@OptIn(ExperimentalApi::class)
class RoastEngine(private val context: Context, private val tuning: RoastTuning = RoastTuning.CURRENT) : AutoCloseable {
    private var engine: Engine? = null
    private val session = OutfitSession()
    private var loadMs = 0L
    private var startupPrewarmMs = 0L
    private val prepared = mutableMapOf<String, Conversation>()
    val ready get() = engine != null

    fun load() {
        close()
        val model = File(context.filesDir, "models/$MODEL_NAME")
        require(model.isFile && model.length() == MODEL_BYTES) { "Gemma model is missing or incomplete. Run scripts/device.sh model." }
        val start = SystemClock.elapsedRealtime()
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableSpeculativeDecoding = tuning.speculative
        ExperimentalFlags.visualTokenBudget = tuning.visualTokens
        val candidate = Engine(EngineConfig(modelPath = model.absolutePath, backend = Backend.GPU(),
            visionBackend = if (tuning.visionGpu) Backend.GPU() else Backend.CPU(4),
            audioBackend = Backend.CPU(4), maxNumTokens = tuning.contextTokens,
            maxNumImages = 1, cacheDir = context.cacheDir.absolutePath))
        try { candidate.initialize() } catch (error: Exception) {
            if (candidate.isInitialized()) runCatching { candidate.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        engine = candidate
        loadMs = SystemClock.elapsedRealtime() - start
        val prewarmStart = SystemClock.elapsedRealtime()
        if (tuning.prewarm) prepareResponses(includeImprovement = false)
        startupPrewarmMs = SystemClock.elapsedRealtime() - prewarmStart
    }

    fun roast(image: File): RoastResult = respond("roast", image)
    fun improve(): RoastResult = respond("improve", null)
    fun reset() { session.reset() }

    private fun systemFor(kind: String) = RoastContract.system(kind) +
        if (tuning.briefResponses) "\nKeep each sentence to 10–14 words. Use compact JSON without indentation." else ""

    /** Fresh system-only conversations: no photo, user request or prior answer is cached. */
    fun prepareResponses(includeImprovement: Boolean = true) {
        if (!tuning.prewarm) return
        val active = engine ?: return
        for (kind in if (includeImprovement) listOf("roast", "improve") else listOf("roast")) {
            val system = systemFor(kind)
            if (system !in prepared) prepared[system] = active.createConversation(
                conversationConfig(system, 384, structured = true, prefill = true))
        }
    }

    private fun conversationConfig(system: String, maxTokens: Int, structured: Boolean, prefill: Boolean = false) =
        ConversationConfig(systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(1, 1.0, 0.0, 42), thinkingConfig = ThinkingConfig(false),
            maxOutputToken = maxTokens, enableResponseFormat = structured, prefillPrefaceOnInit = prefill)

    private fun respond(kind: String, image: File?): RoastResult {
        val start = SystemClock.elapsedRealtime()
        val run = newRun()
        val system = systemFor(kind)
        val schema = RoastContract.responseSchema(kind)
        val record = baseRecord(kind, run).put("system", system)
            .put("roast_style_id", if (kind == "roast") RoastStyle.CURRENT.id else JSONObject.NULL)
            .put("response_format", "json_schema").put("response_schema", JSONObject(schema))
            .put("context_strategy", if (kind == "improve") "one_original_image_and_approved_garments" else "fresh_outfit_image")
            .put("model_response_schema", if (kind == "improve") "sentences_only" else "outfit_and_sentences")
            .put("semantic_validation", "prompt_guided_requires_fixture_or_human_review")
        try {
            val active = checkNotNull(engine) { "Load Gemma first." }
            val memory = session.original
            record.put("previous_approved_roast", memory?.roast ?: JSONObject.NULL)
                .put("previous_observations", memory?.garments?.let { JSONArray(it) } ?: JSONArray())
            if (kind == "roast") session.reset()
            val source = if (kind == "improve") memory?.imagePath?.let(::File) else image
            if (source == null || (kind == "improve" && !source.isFile)) {
                session.reset()
                return finish(run, record, start, RoastContract.RETAKE, "clarify", false, "missing_original_outfit")
            }
            require(source.isFile && source.length() in 1..RoastContract.MAX_IMAGE_BYTES) { "Use an outfit image smaller than 8 MB." }
            val extension = source.extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
            val savedImage = source.copyTo(File(run, "image.$extension"))
            record.put("image", savedImage.name).put("image_sha256", sha256(savedImage))
                .put("image_bytes", savedImage.length()).put("reused_original_image", kind == "improve")
                .put("original_image_source", if (kind == "improve") memory?.imagePath else savedImage.absolutePath)
            val attempts = JSONArray()
            record.put("attempts", attempts)
            var parsed: OutfitAnswer? = null
            var previousRaw: String? = null
            var validationError: String? = null
            for (attempt in 0..1) {
                val prompt = RoastContract.prompt(kind, memory, previousRaw, validationError)
                val item = JSONObject().put("attempt", attempt + 1).put("prompt", prompt)
                    .put("image", savedImage.name).put("system", system).put("max_output_tokens", 384)
                if (attempt == 1) item.put("repair_of_attempt", 1).put("repair_validation_error", validationError)
                attempts.put(item)
                val generationStart = SystemClock.elapsedRealtime()
                val raw = try {
                    generate(active, system,
                        listOf(Content.ImageFile(savedImage.absolutePath), Content.Text(prompt)), 384, schema, item)
                } finally { item.put("latency_ms", SystemClock.elapsedRealtime() - generationStart) }
                item.put("raw_output", raw)
                File(run, "attempt-${attempt + 1}.txt").writeText(raw)
                record.put("raw_model_output", raw).put("retry_count", attempt)
                try {
                    parsed = RoastContract.parse(raw, kind, memory?.garments.orEmpty())
                    item.put("structurally_valid", true)
                    break
                } catch (error: IllegalArgumentException) {
                    validationError = error.message ?: "Invalid JSON response."
                } catch (error: IllegalStateException) {
                    validationError = error.message ?: "Invalid JSON response."
                }
                previousRaw = raw
                item.put("structurally_valid", false).put("validation_error", validationError)
            }
            val answer = parsed ?: return finish(run, record, start, RoastContract.INVALID, "clarify", false, "invalid_model_response")
            record.put("observed_garments", JSONArray(answer.garments)).put("outfit_visible", answer.visible)
                .put("sentences", JSONArray(answer.sentences))
            if (!answer.visible) return finish(run, record, start, RoastContract.RETAKE, "clarify", false, "no_visible_outfit")
            if (kind == "roast") session.acceptRoast(answer, savedImage.absolutePath)
            return finish(run, record, start, answer.text, kind, true, "ok")
        } catch (error: Exception) {
            record.put("status", "error").put("error", error.toString()).put("accepted", false)
            throw error
        } finally {
            record.put("latency_ms", SystemClock.elapsedRealtime() - start)
            File(run, "result.json").writeText(record.toString(2))
        }
    }

    /** Fresh audio-only conversation; a photo or prior roast cannot influence what was heard. */
    fun transcribe(audio: File): String {
        val start = SystemClock.elapsedRealtime()
        val run = newRun()
        val record = baseRecord("transcribe", run).put("system", RoastContract.TRANSCRIPTION_SYSTEM)
            .put("prompt", JSONObject.NULL).put("input_format", "audio_only_with_system_transcription_instruction").put("max_output_tokens", 128)
            .put("image", JSONObject.NULL).put("audio_isolated", true)
        try {
            val active = checkNotNull(engine) { "Load Gemma first." }
            require(audio.isFile && audio.length() in 46..RoastContract.MAX_AUDIO_BYTES) { "Record at most 16 seconds of speech." }
            val savedAudio = audio.copyTo(File(run, "audio.wav"))
            record.put("audio", savedAudio.name).put("audio_sha256", sha256(savedAudio)).put("audio_bytes", savedAudio.length())
            val header = ByteArray(44)
            savedAudio.inputStream().use { java.io.DataInputStream(it).readFully(header) }
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 8, Charsets.US_ASCII) == "WAVEfmt " &&
                buffer.getInt(16) == 16 && buffer.getShort(20).toInt() == 1 && buffer.getShort(22).toInt() == 1 &&
                buffer.getInt(24) == 16000 && buffer.getShort(34).toInt() == 16 && String(header, 36, 4, Charsets.US_ASCII) == "data" &&
                buffer.getInt(40).toLong() == savedAudio.length() - 44) { "Use a 16 kHz mono PCM16 WAV recording." }
            val raw = generate(active, RoastContract.TRANSCRIPTION_SYSTEM,
                listOf(Content.AudioFile(savedAudio.absolutePath)), 128, metrics = record)
            record.put("raw_model_output", raw)
            File(run, "raw-output.txt").writeText(raw)
            return RoastContract.transcript(raw).also { record.put("transcript", it).put("status", "ok").put("accepted", true) }
        } catch (error: Exception) {
            record.put("status", "error").put("error", error.toString()).put("accepted", false)
            throw error
        } finally {
            record.put("latency_ms", SystemClock.elapsedRealtime() - start)
            File(run, "result.json").writeText(record.toString(2))
        }
    }

    private fun generate(active: Engine, system: String, parts: List<Content>, maxTokens: Int,
                         schema: String? = null, metrics: JSONObject? = null): String {
        val createStart = SystemClock.elapsedRealtime()
        val warm = if (schema != null) prepared.remove(system) else null
        metrics?.put("prewarmed_conversation", warm != null)
        return (warm ?: active.createConversation(conversationConfig(system, maxTokens, schema != null))).use { chat ->
            metrics?.put("conversation_create_ms", SystemClock.elapsedRealtime() - createStart)
            val sendStart = SystemClock.elapsedRealtime()
            val result = chat.sendMessage(Contents.of(parts), responseFormat = schema?.let(ResponseFormat::json))
                .contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            metrics?.put("send_message_ms", SystemClock.elapsedRealtime() - sendStart)
            runCatching {
                val info = chat.getBenchmarkInfo()
                metrics?.put("runtime_metrics", JSONObject()
                    .put("time_to_first_token_ms", info.timeToFirstTokenInSecond * 1000)
                    .put("prefill_tokens", info.lastPrefillTokenCount)
                    .put("decode_tokens", info.lastDecodeTokenCount)
                    .put("prefill_tokens_per_second", info.lastPrefillTokensPerSecond)
                    .put("decode_tokens_per_second", info.lastDecodeTokensPerSecond))
            }.onFailure { metrics?.put("metrics_error", it.toString()) }
            result
        }
    }

    private fun newRun(): File = File(context.filesDir, "runs/${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}")
        .apply { check(mkdirs()) { "Could not create a local run directory." } }

    private fun baseRecord(kind: String, run: File) = JSONObject().put("schema_version", 1)
        .put("mode", kind).put("platform", "android").put("model", MODEL_NAME)
        .put("expected_model_sha256", MODEL_SHA256).put("expected_model_bytes", MODEL_BYTES)
        .put("model_load_validation", "file_size").put("runtime", "litert-lm-0.18.1")
        .put("backend", "GPU").put("vision_backend", if (tuning.visionGpu) "GPU" else "CPU4").put("audio_backend", "CPU4")
        .put("tuning", tuning.name).put("speculative_decoding", tuning.speculative)
        .put("visual_token_budget", tuning.visualTokens ?: JSONObject.NULL).put("brief_responses", tuning.briefResponses)
        .put("prewarm", tuning.prewarm)
        .put("context_tokens", tuning.contextTokens).put("max_images", 1).put("max_output_tokens", 384)
        .put("temperature", 0).put("seed", 42).put("thinking", false)
        .put("native_conversation", "fresh_per_attempt").put("engine_load_ms", loadMs)
        .put("startup_prewarm_ms", startupPrewarmMs)
        .put("started_at_ms", System.currentTimeMillis()).put("run_dir", run.absolutePath)
        .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")

    private fun finish(run: File, record: JSONObject, start: Long, text: String, kind: String, accepted: Boolean, status: String): RoastResult {
        val elapsed = SystemClock.elapsedRealtime() - start
        record.put("output", text).put("kind", kind).put("accepted", accepted).put("status", status).put("latency_ms", elapsed)
        return RoastResult(text, kind, run.absolutePath, elapsed, accepted)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(8192); var count = input.read(bytes)
            while (count >= 0) { if (count > 0) digest.update(bytes, 0, count); count = input.read(bytes) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun close() {
        try { prepared.values.forEach { it.close() }; prepared.clear(); engine?.close() }
        finally { engine = null; reset() }
    }
}
