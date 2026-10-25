package dev.local.outfitroast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGateTest {
    @Test fun idleIgnoresEveryRequestWithoutLeadingWake() {
        val gate = VoiceGate()
        listOf("roast my outfit", "can you roast my outfit", "how would you make it better",
            "what would you change", "the TV said hey gemma roast my outfit").forEach {
            assertEquals(it, VoiceIntent.IGNORE, gate.accept(it, true, 1000))
            assertFalse(gate.isArmed(1000))
        }
    }

    @Test fun wakeAloneArmsForExactlyEightSeconds() {
        val gate = VoiceGate()
        assertEquals(VoiceIntent.WAKE, gate.accept("Hey, Gemma!", false, 1000))
        assertTrue(gate.isArmed(8999))
        assertFalse(gate.isArmed(9000))
        assertEquals(VoiceIntent.IGNORE, gate.accept("roast my outfit", false, 9000))
    }

    @Test fun armedRoastIsAcceptedOnce() {
        val gate = VoiceGate()
        gate.accept("hey gemma", false, 1000)
        assertEquals(VoiceIntent.ROAST, gate.accept("can you roast my outfit please", false, 2000))
        assertFalse(gate.isArmed(2000))
        assertEquals(VoiceIntent.IGNORE, gate.accept("roast my outfit", false, 2100))
    }

    @Test fun armedAdviceRequiresARoastAndConsumesWake() {
        val gate = VoiceGate()
        gate.accept("hey gemma", false, 1000)
        assertEquals(VoiceIntent.IGNORE, gate.accept("how would you make it better", false, 2000))
        assertEquals(VoiceIntent.IMPROVE, gate.accept("make it better", true, 2500))
        assertFalse(gate.isArmed(2500))
        assertEquals(VoiceIntent.IGNORE, gate.accept("how would you make it better", true, 2600))
    }

    @Test fun directNaturalRequestDoesNotLeaveAnArmedWindow() {
        listOf("hey gemma roast my outfit", "Hey Gemma, can you roast my outfit?",
            "hey gemma would you please roast my outfit", "hey gemma please could you roast my outfit").forEach {
            val gate = VoiceGate()
            assertEquals(it, VoiceIntent.ROAST, gate.accept(it, false, 1000))
            assertFalse(gate.isArmed(1000))
        }
        val gate = VoiceGate()
        assertEquals(VoiceIntent.IMPROVE, gate.accept("hey gemma how would you make it better", true, 1000))
        assertFalse(gate.isArmed(1000))
    }

    @Test fun unknownWakeRequestArmsWithoutPerformingAction() {
        val gate = VoiceGate()
        assertEquals(VoiceIntent.WAKE, gate.accept("hey gemma what can you do", true, 1000))
        assertTrue(gate.isArmed(1000))
        assertEquals(VoiceIntent.IMPROVE, gate.accept("how can I improve it", true, 2000))
    }

    @Test fun visibilityQuestionActivatesThenWaitsForAnExplicitRoast() {
        val gate = VoiceGate()
        assertEquals(VoiceIntent.WAKE, gate.accept("Hey Gemma, can you see my outfit?", false, 1000))
        assertTrue(gate.isArmed(1001))
        assertEquals(VoiceIntent.ROAST, gate.accept("roast my outfit", false, 2000))
        assertFalse(gate.isArmed(2000))
    }

    @Test fun quotedNegatedAndEmbeddedWakeAreIgnored() {
        listOf("\"hey gemma\"", "'hey gemma roast my outfit'", "hey gemma don't roast my outfit",
            "hey gemma do not roast my outfit", "hey gemma never roast my outfit",
            "I said hey gemma", "the television said hey gemma roast my outfit",
            "hey gemma they said hey gemma roast my outfit", "hey gemma 'roast my outfit'.").forEach {
            val gate = VoiceGate()
            assertEquals(it, VoiceIntent.IGNORE, gate.accept(it, true, 1000))
            assertFalse(gate.isArmed(1000))
            gate.accept("hey gemma", true, 1000)
            assertEquals(it, VoiceIntent.IGNORE, gate.accept(it, true, 2000))
        }
    }

    @Test fun ignoredSpeechDoesNotExtendTheDeadline() {
        val gate = VoiceGate()
        gate.accept("hey gemma", true, 1000)
        assertEquals(VoiceIntent.IGNORE, gate.accept("UNINTELLIGIBLE", true, 8000))
        assertEquals(VoiceIntent.IGNORE, gate.accept("how would you make it better", true, 9000))
        assertFalse(gate.isArmed(9000))
    }

    @Test fun resetDisarmsAndFreshWakeCanRearm() {
        val gate = VoiceGate()
        gate.accept("hey gemma", true, 1000)
        gate.reset()
        assertFalse(gate.isArmed(1001))
        assertEquals(VoiceIntent.IGNORE, gate.accept("roast my outfit", true, 1002))
        gate.accept("hey gemma", true, 2000)
        gate.accept("hey gemma", true, 8000)
        assertTrue(gate.isArmed(15000))
        assertFalse(gate.isArmed(16000))
    }
}
