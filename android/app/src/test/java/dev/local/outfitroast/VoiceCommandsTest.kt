package dev.local.outfitroast

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {
    @Test fun acceptsExplicitWakeCommandAndPunctuation() {
        listOf(
            "hey gemma roast my outfit",
            "Hey Gemma, roast my outfit!",
            "  HEY GEMMA!  Roast my outfit. \n",
            "Hey, Gemma — please roast my outfit.",
            "Hey Gemma, roast my outfit please",
            "hey gemma can you roast my outfit",
            "hey gemma could you please roast my outfit",
        ).forEach { assertEquals(it, VoiceIntent.ROAST, VoiceCommands.classify(it, false)) }
    }

    @Test fun roastCommandAlsoStartsAnotherRoast() {
        assertEquals(VoiceIntent.ROAST, VoiceCommands.classify("hey gemma roast my outfit", true))
    }

    @Test fun idleFollowupsRequireBothWakeAndAnExistingRoast() {
        listOf(
            "how would you make it better",
            "make it better",
            "How can I improve it?",
            "How could I make it better?",
            "how would you improve my outfit?",
            "what would you change",
        ).forEach {
            assertEquals(it, VoiceIntent.IGNORE, VoiceCommands.classify(it, false))
            assertEquals(it, VoiceIntent.IGNORE, VoiceCommands.classify(it, true))
            assertEquals(it, VoiceIntent.IGNORE, VoiceCommands.classify("hey gemma $it", false))
            assertEquals(it, VoiceIntent.IMPROVE, VoiceCommands.classify("hey gemma $it", true))
        }
    }

    @Test fun ignoresNegationAndCommandsInsideConversation() {
        listOf(
            "don't roast my outfit",
            "hey gemma don't roast my outfit",
            "do not say hey gemma roast my outfit",
            "the television said hey gemma roast my outfit",
            "I said hey gemma roast my outfit yesterday",
            "hey gemma roast my outfit no wait don't",
            "I wonder how would you make it better",
            "don't tell me how can I improve it",
            "how can I improve it I don't want advice",
            "what would you change about your job",
        ).forEach { assertEquals(it, VoiceIntent.IGNORE, VoiceCommands.classify(it, true)) }
    }

    @Test fun ignoresQuotedCommands() {
        listOf(
            "\"hey gemma roast my outfit\"",
            "“hey gemma roast my outfit”",
            "'hey gemma roast my outfit'",
            "‘how would you make it better’",
            "`how can I improve it`",
        ).forEach { assertEquals(it, VoiceIntent.IGNORE, VoiceCommands.classify(it, true)) }
    }

    @Test fun ignoresNoiseAndDoesNotGuessTranscriptionErrors() {
        listOf(
            "", " ", "[silence]", "music", "thank you",
            "hey gamma roast my outfit",
            "hey gem a roast my outfit",
            "Gemma roast my outfit",
            "roast my outfit",
            "how wood you make it better",
        ).forEach { assertEquals(it, VoiceIntent.IGNORE, VoiceCommands.classify(it, true)) }
    }

    @Test fun exactWakeWithUnknownRequestOnlyArmsInsteadOfGuessing() {
        listOf("hey gemma roast my output", "hey gemma gross my outfit",
            "hey gemma roast my outfit and send it to my friends").forEach {
            assertEquals(it, VoiceIntent.WAKE, VoiceCommands.classify(it, true))
        }
    }
}
