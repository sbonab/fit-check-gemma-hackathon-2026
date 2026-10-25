package dev.local.outfitroast

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real model evaluation. Semantic joke quality and outfit grounding also require output review. */
@RunWith(AndroidJUnit4::class)
class RoastEngineTest {
    @Test fun originalOutfitRoastFollowUpAndReset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val imagePath = arguments.getString("image_path")
        val noOutfit = arguments.getString("no_outfit", if (imagePath == null) "true" else "false").toBoolean()
        val reportDir = File(context.filesDir, "runs/engine-eval-${System.currentTimeMillis()}").apply { mkdirs() }
        val report = JSONObject().put("kind", "outfit-engine-integration").put("passed", false)
            .put("expected_no_outfit", noOutfit).put("image_path", imagePath ?: "generated:plain-gray-bitmap")
            .put("semantic_review_required", true)
        val records = JSONArray()
        report.put("results", records)
        fun record(result: RoastResult): JSONObject = JSONObject().put("text", result.text).put("kind", result.kind)
            .put("accepted", result.accepted).put("latency_ms", result.latencyMs).put("run_dir", result.runDir)
            .also { records.put(it) }
        try {
            val image = if (imagePath != null) File(imagePath).also {
                require(it.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)) { "image_path must be inside the app files directory." }
                require(it.isFile) { "Fixture not found: $imagePath" }
            } else File(context.cacheDir, "no-outfit.png").also { target ->
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.LTGRAY)
                    target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
            RoastEngine(context).use { engine ->
                engine.load()
                assertTrue(engine.ready)
                val first = engine.roast(image)
                record(first)
                val run = JSONObject(File(first.runDir, "result.json").readText())
                assertTrue(File(first.runDir, run.getString("image")).isFile)
                assertTrue(run.getJSONArray("attempts").length() in 1..2)
                assertTrue(run.getLong("latency_ms") > 0)
                assertEquals("json_schema", run.getString("response_format"))
                assertFalse("Structured decoding emitted Markdown", run.getString("raw_model_output").contains("```"))
                if (noOutfit) {
                    assertFalse("A no-outfit fixture produced a roast: ${first.text}", first.accepted)
                    assertEquals("clarify", first.kind)
                    // Invalid responses are distinct from successful no-outfit recognition.
                    assertEquals("no_visible_outfit", run.getString("status"))
                    assertFalse(engine.improve().also(::record).accepted)
                } else {
                    assertTrue("Outfit response was rejected: ${first.text}; ${first.runDir}", first.accepted)
                    assertEquals("roast", first.kind)
                    val parsed = RoastContract.parse(run.getString("raw_model_output"), "roast")
                    assertEquals(3, parsed.sentences.size)
                    val expected = arguments.getString("expected_garments").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
                    val observations = parsed.garments.joinToString(" ").lowercase()
                    expected.forEach { label -> assertTrue("Expected garment '$label' absent from $observations", observations.contains(label.lowercase())) }
                    val improvement = engine.improve()
                    record(improvement)
                    assertTrue("Improvement was rejected: ${improvement.text}", improvement.accepted)
                    assertEquals("improve", improvement.kind)
                    val follow = JSONObject(File(improvement.runDir, "result.json").readText())
                    assertEquals("json_schema", follow.getString("response_format"))
                    assertTrue(follow.getBoolean("reused_original_image"))
                    assertEquals(run.getString("image_sha256"), follow.getString("image_sha256"))
                    assertEquals(first.text, follow.getString("previous_approved_roast"))
                    assertEquals(3, RoastContract.parse(follow.getString("raw_model_output"), "improve", parsed.garments).sentences.size)
                    assertEquals(parsed.garments, RoastContract.parse(follow.getString("raw_model_output"), "improve", parsed.garments).garments)
                }
                arguments.getString("audio_path")?.let { path ->
                    val audio = File(path)
                    require(audio.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
                    val transcript = engine.transcribe(audio)
                    report.put("transcript", transcript)
                    arguments.getString("expected_transcript")?.let { expected -> assertEquals(expected.lowercase(), transcript.lowercase().trimEnd('.', '!', '?')) }
                }
                engine.reset()
                val missing = engine.improve()
                record(missing)
                assertFalse("Reset retained an outfit", missing.accepted)
                assertEquals("clarify", missing.kind)
                assertEquals("missing_original_outfit", JSONObject(File(missing.runDir, "result.json").readText()).getString("status"))
                report.put("passed", true)
            }
        } catch (error: Throwable) {
            report.put("error", error.toString())
            throw error
        } finally {
            File(reportDir, "verification.json").writeText(report.toString(2))
        }
    }
}
