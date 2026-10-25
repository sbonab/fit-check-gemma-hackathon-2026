package dev.local.outfitroast

import java.util.Locale

enum class VoiceIntent { WAKE, ROAST, IMPROVE, IGNORE }

/** Exact whole-utterance commands, after local Gemma transcription. No fuzzy wake word. */
object VoiceCommands {
    private val punctuation = Regex("[\\p{P}\\p{S}]+")
    private val whitespace = Regex("\\s+")
    private val quotation = Regex("[\"'“”‘’„‟«»‹›`]")
    private val roastRequest = Regex("(?:please )?(?:(?:can|could|would) you (?:please )?)?roast my outfit(?: please)?")
    private val negation = Regex("\\b(?:no|not|never|dont|cannot|cant|wont|cancel|stop)\\b|\\b(?:don|can|won|wouldn|shouldn|couldn) t\\b")
    private val improveCommands = setOf(
        "how would you make it better",
        "make it better",
        "how could you make it better",
        "how can i make it better",
        "how could i make it better",
        "how can i improve it",
        "how could i improve it",
        "how would you improve it",
        "how can i improve my outfit",
        "how would you improve my outfit",
        "how would you make my outfit better",
        "what would you change",
        "what should i change",
        "can you improve it",
        "could you improve it",
        "can you make it better",
        "can you improve my outfit",
    )

    /** Stateless idle classification. Bare requests require a separate, armed VoiceGate. */
    fun classify(text: String, hasRoast: Boolean): VoiceIntent = VoiceGate().accept(text, hasRoast, 0L)

    internal fun normalizedCommand(text: String): String? {
        // Quotes usually mean the command is being discussed or repeated. Keeping
        // this guard before punctuation normalization also rejects quoted commands.
        val trimmed = text.trim()
        if (quotation.containsMatchIn(trimmed)) return null
        val command = whitespace.replace(
            punctuation.replace(trimmed.lowercase(Locale.ROOT), " "), " "
        ).trim()
        return command.takeUnless { negation.containsMatchIn(it) }
    }

    internal fun requestIntent(request: String, hasRoast: Boolean): VoiceIntent = when {
        roastRequest.matches(request) -> VoiceIntent.ROAST
        hasRoast && isImproveRequest(request) -> VoiceIntent.IMPROVE
        else -> VoiceIntent.IGNORE
    }

    internal fun isImproveRequest(request: String) = request in improveCommands
}

/** One foreground wake window. Call with a monotonic clock; reset on pause/voice-off/session reset. */
class VoiceGate {
    private var armedUntilMs: Long? = null

    fun isArmed(nowMs: Long): Boolean {
        val deadline = armedUntilMs ?: return false
        if (nowMs >= deadline) {
            reset()
            return false
        }
        return true
    }

    fun reset() { armedUntilMs = null }

    fun accept(text: String, hasRoast: Boolean, nowMs: Long): VoiceIntent {
        val armed = isArmed(nowMs)
        val command = VoiceCommands.normalizedCommand(text) ?: return VoiceIntent.IGNORE
        val hasWake = command == WAKE_PHRASE || command.startsWith("$WAKE_PHRASE ")
        val request = if (hasWake) command.removePrefix(WAKE_PHRASE).trim() else command
        // An embedded/repeated wake phrase is discussion, not a fresh activation.
        if (Regex("\\bhey gemma\\b").containsMatchIn(request)) return VoiceIntent.IGNORE
        if (!hasWake && !armed) return VoiceIntent.IGNORE
        val intent = VoiceCommands.requestIntent(request, hasRoast)
        if (intent == VoiceIntent.ROAST || intent == VoiceIntent.IMPROVE) {
            reset()
            return intent
        }
        if (hasWake) {
            if (!hasRoast && VoiceCommands.isImproveRequest(request)) return VoiceIntent.IGNORE
            armedUntilMs = nowMs + 8000L
            return VoiceIntent.WAKE
        }
        return VoiceIntent.IGNORE
    }

    private companion object { const val WAKE_PHRASE = "hey gemma" }
}
