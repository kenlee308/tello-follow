package com.kenny.tellofollow.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhraseMatcherTest {

    private val defaults: String = listOf(
        File("src/main/assets/voice_phrases.json"),
        File("app/src/main/assets/voice_phrases.json"),
    ).first { it.exists() }.readText()

    private val book = PhraseBook.fromJson(defaults)
    private val matcher = PhraseMatcher(book)

    private fun intentOf(vararg alts: String) = matcher.match(alts.toList())?.intent

    @Test fun exactMatch() {
        val r = matcher.match(listOf("take off"))!!
        assertEquals(VoiceIntent.TAKEOFF, r.intent)
        assertEquals(1f, r.confidence, 0.001f)
    }

    @Test fun normalizesCaseAndPunctuation() {
        assertEquals(VoiceIntent.FOLLOW, intentOf("Let's go!"))
        assertEquals(VoiceIntent.LAND, intentOf("LAND."))
    }

    @Test fun containsInsideSentence() {
        val r = matcher.match(listOf("okay drone please follow me now"))!!
        assertEquals(VoiceIntent.FOLLOW, r.intent)
        assertTrue(r.confidence in 0.85f..0.95f)
    }

    @Test fun wholeWordOnly() {
        // "landscape" must not match LAND by substring
        assertNull(intentOf("nice landscape"))
    }

    @Test fun fuzzyMisrecognition() {
        assertEquals(VoiceIntent.FOLLOW, intentOf("folow me"))
        assertEquals(VoiceIntent.PHOTO, intentOf("take a fhoto"))
    }

    @Test fun usesLaterAlternatives() {
        val r = matcher.match(listOf("blah blah", "the cat", "land"))!!
        assertEquals(VoiceIntent.LAND, r.intent)
        assertTrue(r.confidence < 1f) // rank penalty applied
    }

    @Test fun longerPhraseWins() {
        assertEquals(VoiceIntent.RECORD, intentOf("stop recording"))
        assertEquals(VoiceIntent.STOP, intentOf("stop"))
    }

    @Test fun noMatchForGibberish() {
        assertNull(intentOf("purple banana keyboard"))
        assertNull(intentOf(""))
    }

    @Test fun userOverridesAddAndReplace() {
        val user = """{"intents":[
            {"intent":"FOLLOW","addPhrases":["heel"]},
            {"intent":"PHOTO","phrases":["smile"],"reply":"Got it"}]}"""
        val b = PhraseBook.fromJson(defaults, user)
        val m = PhraseMatcher(b)
        assertEquals(VoiceIntent.FOLLOW, m.match(listOf("heel"))?.intent)
        assertEquals(VoiceIntent.FOLLOW, m.match(listOf("follow me"))?.intent)
        assertEquals(VoiceIntent.PHOTO, m.match(listOf("smile"))?.intent)
        assertNull(m.match(listOf("say cheese"))?.takeIf { it.intent == VoiceIntent.PHOTO })
        assertEquals("Got it", b.entry(VoiceIntent.PHOTO)?.reply)
    }

    @Test fun gateDryRunNeverExecutes() {
        var calls = 0
        val gate = VoiceActionGate(dryRun = true) { calls++ }
        val ctx = GateContext(connected = true, flying = true, followEnabled = false, battery = 80)
        val d = gate.evaluate(book.entry(VoiceIntent.FOLLOW)!!, ctx)
        assertTrue(d.allowed)
        assertTrue(d.line.startsWith("would do"))
        assertEquals(0, calls)
    }

    @Test fun gateBlocksWithReasons() {
        val gate = VoiceActionGate(dryRun = true)
        val ground = GateContext(connected = true, flying = false, followEnabled = false, battery = 80)
        assertEquals("blocked: not flying", gate.evaluate(book.entry(VoiceIntent.FOLLOW)!!, ground).line)
        val air = ground.copy(flying = true)
        assertEquals("blocked: already flying", gate.evaluate(book.entry(VoiceIntent.TAKEOFF)!!, air).line)
        val off = ground.copy(connected = false)
        assertEquals("blocked: not connected", gate.evaluate(book.entry(VoiceIntent.LAND)!!, off).line)
        assertTrue(gate.evaluate(book.entry(VoiceIntent.CLOSER)!!, air).line.contains("not implemented"))
    }

    @Test fun gateLiveExecutes() {
        var got: VoiceIntent? = null
        val gate = VoiceActionGate(dryRun = false) { got = it }
        val ctx = GateContext(connected = true, flying = true, followEnabled = false, battery = 80)
        val d = gate.evaluate(book.entry(VoiceIntent.PHOTO)!!, ctx)
        assertTrue(d.executed)
        assertNotNull(got)
    }
}