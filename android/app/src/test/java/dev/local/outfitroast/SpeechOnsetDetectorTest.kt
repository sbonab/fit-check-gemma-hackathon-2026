package dev.local.outfitroast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechOnsetDetectorTest {
    private val frameSamples = 320 // 20 ms at 16 kHz.

    @Test fun quietSpeechBelowFormerThresholdHasAnOnset() {
        val vad = SpeechOnsetDetector()
        repeat(4) {
            assertTrue(vad.accept(320.0, frameSamples))
            assertFalse(vad.onsetDetected)
        }
        vad.accept(320.0, frameSamples)
        assertTrue(vad.onsetDetected)
        assertEquals(1600, vad.maxWindowVoicedSamples)
    }

    @Test fun voicedSyllablesCanHaveShortGaps() {
        val vad = SpeechOnsetDetector()
        repeat(4) {
            vad.accept(420.0, frameSamples)
            vad.accept(90.0, frameSamples)
            assertFalse(vad.onsetDetected)
        }
        vad.accept(420.0, frameSamples)
        assertTrue(vad.onsetDetected) // 100 ms voiced in 180 ms, never consecutive.
    }

    @Test fun separatedShortBurstsDoNotAccumulateAcrossTheWindow() {
        val vad = SpeechOnsetDetector()
        repeat(4) { vad.accept(700.0, frameSamples) }
        repeat(16) { vad.accept(80.0, frameSamples) }
        repeat(4) { vad.accept(700.0, frameSamples) }
        assertFalse(vad.onsetDetected)
        assertEquals(1280, vad.maxWindowVoicedSamples)
    }

    @Test fun isolatedImpulsesDoNotEstablishSpeech() {
        val vad = SpeechOnsetDetector()
        repeat(6) {
            vad.accept(1400.0, frameSamples)
            repeat(15) { vad.accept(100.0, frameSamples) }
        }
        assertFalse(vad.onsetDetected)
        assertEquals(frameSamples, vad.maxWindowVoicedSamples)
    }

    @Test fun backgroundNoiseLearnsWithoutEstablishingSpeech() {
        val vad = SpeechOnsetDetector()
        repeat(100) { assertFalse(vad.accept(180.0, frameSamples)) }
        assertFalse(vad.onsetDetected)
        assertTrue(vad.thresholdRms > 400)
        repeat(5) { vad.accept(528.0, frameSamples) }
        assertTrue(vad.onsetDetected)
    }

    @Test fun windowUsesSampleDurationsForPartialReads() {
        val vad = SpeechOnsetDetector()
        repeat(9) { vad.accept(400.0, 160) }
        assertFalse(vad.onsetDetected)
        vad.accept(400.0, 160)
        assertTrue(vad.onsetDetected)
        assertEquals(1600, vad.maxWindowVoicedSamples)
    }
}
