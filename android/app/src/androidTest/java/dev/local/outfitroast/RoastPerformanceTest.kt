package dev.local.outfitroast

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Sequential real-photo measurements; schema success is separate from human quality review. */
@RunWith(AndroidJUnit4::class)
class RoastPerformanceTest {
    @Test fun measureSamePhoto() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val variant = args.getString("variant", "baseline")
        val tuning = when (variant) {
            "baseline" -> RoastTuning()
            "mtp" -> RoastTuning(name = variant, speculative = true)
            "vision-gpu" -> RoastTuning(name = variant, visionGpu = true)
            "vision-140" -> RoastTuning(name = variant, visualTokens = 140)
            "context-2048" -> RoastTuning(name = variant, contextTokens = 2048)
            "brief" -> RoastTuning(name = variant, briefResponses = true)
            "prewarm" -> RoastTuning(name = variant, prewarm = true)
            "prewarm-140" -> RoastTuning(name = variant, prewarm = true, visualTokens = 140)
            "fast" -> RoastTuning(name = variant, prewarm = true, visualTokens = 140, briefResponses = true)
            else -> error("Unknown benchmark variant: $variant")
        }
        val image = File(requireNotNull(args.getString("image_path")))
        require(image.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator) && image.isFile)
        val count = args.getString("repeats", "2").toInt().also { require(it in 1..5) }
        val directory = File(context.filesDir, "runs/performance-$variant-${System.currentTimeMillis()}").apply { mkdirs() }
        val runs = JSONArray()
        val report = JSONObject().put("variant", variant).put("passed", false).put("runs", runs)
        val power = context.getSystemService(PowerManager::class.java)
        try {
            RoastEngine(context, tuning).use { engine ->
                engine.load()
                repeat(count) { index ->
                    val before = power.currentThermalStatus
                    val result = engine.roast(image)
                    val record = JSONObject(File(result.runDir, "result.json").readText())
                    record.put("repeat_index", index).put("thermal_before", before)
                        .put("thermal_after", power.currentThermalStatus)
                    runs.put(record)
                    File(directory, "verification.json").writeText(report.toString(2))
                    if (index + 1 < count) engine.prepareResponses()
                }
            }
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("error", error.toString())
            throw error
        } finally { File(directory, "verification.json").writeText(report.toString(2)) }
    }
}
