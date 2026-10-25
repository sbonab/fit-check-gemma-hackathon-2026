package dev.local.outfitroast

/** Small RMS gate for foreground capture; transcription still decides whether speech is a command. */
internal class SpeechOnsetDetector(private val sampleRate: Int = 16000) {
    private data class Frame(var samples: Int, val voiced: Boolean)
    private val window = ArrayDeque<Frame>()
    private val windowLimit = sampleRate * 300 / 1000
    private val onsetMinimum = sampleRate * 100 / 1000
    private var windowSamples = 0
    private var windowVoicedSamples = 0
    var noiseRms = 80.0
        private set
    var thresholdRms = 240.0
        private set
    var onsetDetected = false
        private set
    var maxWindowVoicedSamples = 0
        private set

    init { require(sampleRate >= 1000) }

    /** Returns whether this frame is voiced. Brief gaps do not reset the onset window. */
    fun accept(rms: Double, samples: Int, learnNoise: Boolean = true): Boolean {
        require(rms.isFinite() && rms >= 0 && samples > 0)
        thresholdRms = maxOf(240.0, noiseRms * 2.5)
        val voiced = rms > thresholdRms
        if (learnNoise && !onsetDetected && !voiced) noiseRms = noiseRms * 0.98 + rms * 0.02
        window.addLast(Frame(samples, voiced))
        windowSamples += samples
        if (voiced) windowVoicedSamples += samples
        while (windowSamples > windowLimit) {
            val first = window.first()
            val removed = minOf(first.samples, windowSamples - windowLimit)
            first.samples -= removed
            windowSamples -= removed
            if (first.voiced) windowVoicedSamples -= removed
            if (first.samples == 0) window.removeFirst()
        }
        maxWindowVoicedSamples = maxOf(maxWindowVoicedSamples, windowVoicedSamples)
        if (voiced && windowVoicedSamples >= onsetMinimum) onsetDetected = true
        return voiced
    }
}
