package com.kenny.tellofollow.voice

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * Glues engine -> matcher -> gate -> caption. Owned by MainActivity.
 * Phase 1: gate is dryRun, so nothing reaches the drone.
 */
class VoiceController(
    context: Context,
    private val contextProvider: () -> GateContext,
    private val gate: VoiceActionGate = VoiceActionGate(dryRun = true),
) {
    var book: PhraseBook = PhraseBook.load(context)
        private set
    private var matcher = PhraseMatcher(book)

    val engine = VoiceCommandEngine(context) { alts, _ -> handle(alts) }
    val state get() = engine.state

    fun reload(context: Context) {
        book = PhraseBook.load(context)
        matcher = PhraseMatcher(book)
    }

    private fun handle(alts: List<String>) {
        val heard = alts.firstOrNull().orEmpty()
        val match = matcher.match(alts)
        if (match == null) {
            engine.showResult(heard, "-> (no match)", "")
            Log.i(TAG, "heard=$alts -> no match")
            return
        }
        val entry = book.entry(match.intent) ?: return
        val decision = gate.evaluate(entry, contextProvider())
        val conf = String.format(Locale.US, "%.2f", match.confidence)
        val reply = if (entry.reply.isNotBlank()) "  \"${entry.reply}\"" else ""
        engine.showResult(match.transcript, "-> ${match.intent.label} ($conf)$reply", decision.line)
        Log.i(TAG, "heard=$alts -> ${match.intent} $conf via '${match.matchedPhrase}' : ${decision.line}")
    }

    fun destroy() = engine.destroy()

    private companion object { const val TAG = "VoiceController" }
}