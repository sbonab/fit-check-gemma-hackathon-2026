package dev.local.outfitroast

import org.junit.Assert.*
import org.junit.Test

class RoastContractTest {
    private val good = """{"outfit_visible":true,"garments":["black hoodie","dark jeans"],"sentences":["That hoodie looks ready to pitch a startup before breakfast.","Those jeans have survived more sprints than your product roadmap.","The dark colors look clean and work well together."]}"""
    private fun rejected(text: String, kind: String = "roast") {
        try { RoastContract.parse(text, kind); fail("Accepted invalid response: $text") }
        catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    @Test fun acceptsAndJoinsExactlyThreeShortSentences() {
        val parsed = RoastContract.parse(good, "roast")
        assertTrue(parsed.visible)
        assertEquals(listOf("black hoodie", "dark jeans"), parsed.garments)
        assertEquals(3, parsed.sentences.size)
        assertEquals(3, parsed.text.count { it == '.' })
    }

    @Test fun acceptsOpenerAndTwoGarmentJokesWithoutCompliment() {
        val raw = """{"outfit_visible":true,"garments":["black hoodie","dark jeans"],"sentences":["Your outfit tells a story and the story needs a second draft before it’s ready to be shared publicly.","That black hoodie has been in stealth mode so long even its launch party gave up.","Those dark jeans look like casual Friday filed for an extension."]}"""
        val answer = RoastContract.parse(raw, "roast")
        assertEquals(3, answer.sentences.size)
        val session = OutfitSession()
        session.acceptRoast(answer, "/outfit.jpg")
        assertEquals(answer.text, session.original!!.roast)
        assertTrue(RoastContract.prompt("improve", session.original).contains("black hoodie"))
    }

    @Test fun alternateStyleChangesRoastButCannotLeakIntoImprovement() {
        val alternate = RoastStyle.CURRENT.copy(id = "test-style", opening = "OPENING_OVERRIDE",
            toneRules = listOf("TONE_OVERRIDE"), garmentJokes = "GARMENT_OVERRIDE",
            techFlavor = "FLAVOR_OVERRIDE", ending = "ENDING_OVERRIDE")
        val roast = RoastContract.system("roast", alternate)
        for (part in listOf("OPENING", "TONE", "GARMENT", "FLAVOR", "ENDING")) {
            assertTrue(roast.contains("${part}_OVERRIDE"))
        }
        assertEquals(RoastContract.system("improve"), RoastContract.system("improve", alternate))
        assertFalse(roast.contains(RoastStyle.CURRENT.toneRules.first()))
    }

    @Test fun defaultStyleUsesToneInstructionsWithoutCannedOpeningSentences() {
        val prompt = RoastContract.system("roast")
        assertFalse(prompt.contains("You dress like you googled"))
        assertFalse(prompt.contains("The fit is giving"))
        assertFalse(prompt.contains("Your outfit tells a story"))
        assertTrue(prompt.contains("original deadpan verdict"))
        assertTrue(prompt.contains("leave individual garment names to the next two statements"))
    }

    @Test fun quotedPunchlineAllowsFinalPeriodInsideQuoteButStillRejectsTwoSentences() {
        val old = "That hoodie looks ready to pitch a startup before breakfast."
        for (quote in listOf("'", "’", "”")) {
            val single = good.replace(old, "This outfit says ${quote}I got dressed using the default settings.$quote")
            assertTrue(RoastContract.parse(single, "roast").visible)
            rejected(good.replace(old, "This outfit says ${quote}I tried. It failed.$quote"))
        }
        rejected(good.replace(old, "''''"))
    }

    @Test fun noOutfitDiscardsAllGeneratedContentAndNeverProducesARoast() {
        val answer = RoastContract.parse("""{"outfit_visible":false,"garments":[],"sentences":[]}""", "roast")
        assertFalse(answer.visible)
        assertEquals("", answer.text)
        val contradictory = RoastContract.parse(good.replace("\"outfit_visible\":true", "\"outfit_visible\":false"), "roast")
        assertFalse(contradictory.visible)
        assertTrue(contradictory.garments.isEmpty())
        assertTrue(contradictory.sentences.isEmpty())
    }

    @Test fun rejectsExtraMissingAndEmbeddedSentences() {
        rejected(good.replace("The dark colors look clean and work well together.", "The colors work. The shoes work too."))
        rejected(good.replace("\"The dark colors look clean and work well together.\"", "\"Looks good.\",\"Great shoes.\""))
        rejected(good.replace(",\"The dark colors look clean and work well together.\"", ""))
        rejected(good.replace("That hoodie looks ready", "That hoodie! Looks ready"))
    }

    @Test fun rejectsMalformedJsonWrappersDuplicateKeysAndUnknownFields() {
        rejected("Here is your roast: $good")
        assertEquals(RoastContract.parse(good, "roast"), RoastContract.parse("```json\n$good\n```", "roast"))
        rejected("```json\n$good\n```\nHere is some extra advice.")
        rejected("```json\n$good\n$good\n```")
        rejected(good + " trailing")
        rejected(good.replace("\"outfit_visible\":true", "\"outfit_visible\":true,\"outfit_visible\":true"))
        rejected(good.replace("\"outfit_visible\":true", "\"outfit_visible\":true,\"confidence\":true"))
        rejected(good.replace("\"garments\":[\"black hoodie\",\"dark jeans\"]", "\"garments\":true"))
        rejected(good.dropLast(1))
    }

    @Test fun rejectsDeviceRegressionOfValidRoastFollowedByAnotherTask() {
        rejected(good + "\n" + """{"IMPROVE":["Try cuffing the jeans.","Layer a shirt.","Add an optional scarf."]}""")
        rejected(good + "\n" + good)
    }

    @Test fun duplicatedJokesAfterFencedJsonReportTheActualFormatFailure() {
        val raw = "```json\n$good\n```\nJoke 1: Another outfit joke.\nJoke 2: Another hoodie joke.\nJoke 3: Another jeans joke."
        try { RoastContract.parse(raw, "roast"); fail("Accepted extra text") }
        catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("trailing text"))
            assertFalse(error.message!!.contains("numeric IDs"))
            assertTrue(RoastContract.prompt("roast", previousRaw = raw, validationError = error.message).contains("trailing text"))
        }
    }

    @Test fun eachGenerationSeesOnlyOneTaskAndOneObjectInstruction() {
        val roast = RoastContract.system("roast")
        val improve = RoastContract.system("improve")
        assertTrue(roast.contains("Your only task is ROAST"))
        assertFalse(roast.contains("IMPROVE"))
        assertTrue(roast.contains("single sentence"))
        assertTrue(improve.contains("Your only task is IMPROVE"))
        assertFalse(improve.contains("ROAST"))
        assertTrue(roast.contains("exactly one JSON object"))
        assertTrue(improve.contains("exactly one JSON object"))
    }

    @Test fun repairIncludesSpecificDeviceFailureAndPriorOutput() {
        val raw = good.replace("That hoodie looks ready to pitch a startup before breakfast.", "That hoodie is in stealth mode. Is that logo a roadmap?")
        val error = try { RoastContract.parse(raw, "roast"); fail("Accepted double sentence"); "" }
            catch (error: IllegalArgumentException) { error.message!! }
        assertTrue(error.contains("sentences[0]"))
        val prompt = RoastContract.prompt("roast", previousRaw = raw, validationError = error)
        assertTrue(prompt.contains(raw))
        assertTrue(prompt.contains(error))
        assertTrue(prompt.contains("ONE sentence per array item"))
        val bounded = RoastContract.prompt("roast", previousRaw = "x".repeat(10000), validationError = "e".repeat(1000))
        assertFalse(bounded.contains("x".repeat(3001)))
        assertFalse(bounded.contains("e".repeat(241)))
    }

    @Test fun boundsGeneratedContentAndRejectsControlCharactersAndInsults() {
        rejected(good.replace("That hoodie looks ready to pitch a startup before breakfast.", "word ".repeat(29) + "."))
        rejected(good.replace("black hoodie", "x".repeat(81)))
        rejected(good.replace("hoodie looks ready", "hoodie\\nlooks ready"))
        rejected(good.replace("That hoodie looks ready", "That ugly hoodie looks ready"))
        rejected(" ".repeat(6001))
    }

    @Test fun doesNotRejectLegitimateGarmentSynonymsOrSkinnyJeans() {
        val varied = good.replace("black hoodie", "cross body bag").replace("dark jeans", "skinny jeans")
            .replace("That hoodie", "That pullover").replace("Those jeans", "Those trousers")
        assertTrue(RoastContract.parse(varied, "roast").visible)
    }

    @Test fun oneWordPastThePromptTargetDoesNotForceAnotherModelCall() {
        val actual = "That white shirt is clearly the beta test for a garment that hasn't quite decided if it wants to be a tee or a napkin."
        assertEquals(25, actual.split(" ").size)
        val accepted = RoastContract.parse(good.replace("The dark colors look clean and work well together.", actual), "roast")
        assertEquals(actual, accepted.sentences.last())
        assertEquals(3, accepted.sentences.size)
    }

    @Test fun originalOutfitIsBoundedReplacedAndResetExplicitly() {
        val session = OutfitSession()
        assertNull(session.original)
        val answer = RoastContract.parse(good, "roast")
        session.acceptRoast(answer, "/first/image.jpg")
        val original = session.original!!
        val prompt = RoastContract.prompt("improve", original)
        assertTrue(prompt.contains("original outfit photo"))
        assertFalse(prompt.contains(answer.text))
        assertTrue(prompt.contains("black hoodie"))
        // Merely planning an improvement cannot overwrite the approved original roast.
        assertEquals(original, session.original)
        session.acceptRoast(answer.copy(garments = listOf("blue jacket")), "/second/image.jpg")
        assertEquals("/second/image.jpg", session.original!!.imagePath)
        assertFalse(session.original!!.garments.contains("black hoodie"))
        session.reset()
        assertNull(session.original)
    }

    @Test fun improvementCannotBePlannedWithoutAnApprovedOutfit() {
        try { RoastContract.prompt("improve"); fail("Missing outfit accepted") } catch (_: IllegalArgumentException) {}
        val session = OutfitSession()
        try { session.acceptRoast(OutfitAnswer(false, emptyList(), emptyList()), "/image.jpg"); fail("No outfit stored") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun improvementUsesSameStructuralContractWithNoJokeRequirement() {
        val raw = """{"sentences":["Cuff the jeans once to make the hem cleaner.","Push the hoodie sleeves up slightly for a relaxed finish.","Keep the dark colors coordinated with simple accessories."]}"""
        val garments = listOf("black hoodie", "dark jeans")
        val parsed = RoastContract.parse(raw, "improve", garments)
        assertEquals(3, parsed.sentences.size)
        assertEquals(garments, parsed.garments)
        try { RoastContract.parse(raw, "improve"); fail("Improvement accepted without original garment context") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun numericArrayFailuresNameTheExactKeyAndIndex() {
        for ((raw, key) in listOf(good.replace("\"black hoodie\"", "1") to "garments[0]",
                """{"sentences":[3,4,5]}""" to "sentences[0]")) {
            try { RoastContract.parse(raw, if (key.startsWith("sentences")) "improve" else "roast", listOf("hoodie")); fail("Numeric IDs accepted") }
            catch (error: IllegalStateException) {
                assertTrue(error.message!!.contains(key))
                assertTrue(error.message!!.contains("quoted text"))
            }
        }
    }

    @Test fun rejectsUnclearEmptyEchoedOrUnboundedTranscription() {
        for (raw in listOf("", "...", "UNINTELLIGIBLE", "Unintelligible!", RoastContract.TRANSCRIPTION_PROMPT, "x".repeat(801))) {
            try { RoastContract.transcript(raw); fail("Invalid transcription accepted") } catch (_: IllegalArgumentException) {}
        }
        assertEquals("hey gemma roast my outfit", RoastContract.transcript(" hey gemma roast my outfit "))
        assertEquals("how would you make it better", RoastContract.transcript("how would you make it better"))
    }

    @Test fun timestampedSpeechRetainsActualWordsAndPunctuation() {
        assertEquals("Hey Gemma roast my outfit", RoastContract.transcript(
            "[ 0m0s192ms - 0m1s122ms ] Hey Gemma [ 0m1s122ms - 0m2s422ms ] roast my outfit"))
        assertEquals("How would you make it better?", RoastContract.transcript(
            "[ 0m0s192ms - 0m1s122ms ]\nHow would you make it better?\n[ 0m1s122ms - 0m1s422ms ]"))
        assertEquals("I think it's a", RoastContract.transcript(
            "[ 0m0s192ms - 0m1s122ms ] I think it's a\n[ 0m1s122ms - 0m1s422ms ]"))
        // Unrelated annotations, casing, and quoted speech are preserved for the command gate to assess.
        assertEquals("[unclear] Hey Gemma", RoastContract.transcript("[unclear] Hey Gemma"))
        assertEquals("\"Hey Gemma\"", RoastContract.transcript("\"Hey Gemma\""))
        assertEquals("[00:01 - 00:02] roast my outfit", RoastContract.transcript("[00:01 - 00:02] roast my outfit"))
    }

    @Test fun rejectsCompleteAndTruncatedTimestampOnlyDeviceOutputs() {
        val marker = "[ 0m0s192ms - 0m1s122ms ]"
        for (raw in listOf(marker, "$marker\n$marker", "$marker\n[ 0m1s122ms - 0m2s12",
                "[ 0m0s192ms - 0m1s", "[ 0m0s192ms - 0m", "0m0s192ms")) {
            try { RoastContract.transcript(raw); fail("Timing-only output accepted: $raw") }
            catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun rejectsKnownPromptContaminationEvenInsideOtherwisePlausibleSpeech() {
        for (raw in listOf("Hey Gemma can you transcribe the attached recording",
                "[ 0m0s192ms - 0m1s122ms ] Hey Gemma, transcribe the attached recording, roast my outfit",
                "Hey Gemma TRANSCRIBE, the attached recording!",
                "Hey Gemma roast my outfit and do not answer the speaker",
                "Hey Gemma transcribe only the words spoken in this recording")) {
            try { RoastContract.transcript(raw); fail("Prompt-contaminated output accepted: $raw") }
            catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("echoed")) }
        }
        assertEquals("Hey Gemma, roast my outfit!", RoastContract.transcript("Hey Gemma, roast my outfit!"))
        assertEquals("Do not roast my outfit", RoastContract.transcript("Do not roast my outfit"))
    }
}
