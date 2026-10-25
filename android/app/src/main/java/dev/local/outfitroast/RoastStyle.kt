package dev.local.outfitroast

/** Edit this profile to tune comedy without touching camera, voice or outfit memory. */
internal data class RoastStyle(
    val id: String,
    val opening: String,
    val toneRules: List<String>,
    val garmentJokes: String,
    val techFlavor: String,
    val ending: String,
) {
    fun instructions(): String = """
        Your only task is ROAST. Write three jokes, each a single sentence.
        First statement: $opening
        Comic tone and construction:
        ${toneRules.joinToString("\n") { "- $it" }}
        Second and third statements: $garmentJokes
        $techFlavor
        $ending
    """.trimIndent()

    companion object {
        val CURRENT = RoastStyle(
            id = "observational-deadpan-v2",
            opening = "Write an original deadpan verdict on the overall look in THIS photo, with a setup and unexpected punchline. Address the outfit as a whole using its visible color palette, layering or coordination; leave individual garment names to the next two statements. No greeting or explanation.",
            toneRules = listOf(
                "Build a specific visual observation into a dry, absurd comparison, then land a sharp reversal at the end of the sentence.",
                "Use conversational, satirical delivery rather than a fashion review or generic insult.",
                "Invent the comparison, never the clothing detail; the humor must depend on what is visible in this photo.",
                "Write fresh wording for this scene instead of stock catchphrases or canned opening lines.",
            ),
            garmentJokes = "Name one clearly visible outfit piece in the second statement and a DIFFERENT clearly visible piece in the third; give each its own concrete, deadpan punchline. Refer to pieces with broad visual labels such as outer layer, shirt or trousers, plus a clearly visible color or pattern, instead of guessing a specific subtype. Use the same labels throughout. If only one piece is visible, joke about two different visible aspects of it instead of inventing another piece.",
            techFlavor = "Silicon Valley satire is optional: use a startup, product launch or workplace comparison only when it fits the visible detail naturally; do not force tech vocabulary into every line.",
            ending = "End on the third joke, not a compliment, reassurance, apology, advice or positive takeaway. Target clothing choices, never the person's body or identity.",
        )
    }
}
