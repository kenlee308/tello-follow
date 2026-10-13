package com.kenny.tellofollow.voice

/** Snapshot of drone state the gate needs to decide. */
data class GateContext(
    val connected: Boolean,
    val flying: Boolean,
    val followEnabled: Boolean,
    val battery: Int,
)

data class GateDecision(
    val intent: VoiceIntent,
    val allowed: Boolean,
    val executed: Boolean,
    val line: String, // "would do X" / "did X" / "blocked: reason"
)

/**
 * Single choke point between voice and the drone.
 * Phase 1: dryRun = true -> NEVER calls [executor]; only reports what it would do.
 * Phase 2/3: construct with dryRun = false (and optionally narrow [liveIntents]).
 */
class VoiceActionGate(
    val dryRun: Boolean = true,
    private val liveIntents: Set<VoiceIntent> = VoiceIntent.entries.toSet(),
    private val executor: (VoiceIntent) -> Unit = {},
) {
    fun evaluate(entry: PhraseEntry, ctx: GateContext): GateDecision {
        val intent = entry.intent
        val reason = blockReason(entry, ctx)
        if (reason != null) return GateDecision(intent, false, false, "blocked: $reason")
        if (dryRun || intent !in liveIntents) {
            return GateDecision(intent, true, false, "would do ${describe(intent)} (dry run)")
        }
        executor(intent)
        return GateDecision(intent, true, true, "did ${describe(intent)}")
    }

    private fun blockReason(entry: PhraseEntry, ctx: GateContext): String? = when {
        !entry.intent.implemented -> "${entry.intent.label} not implemented yet"
        // Safety intents are always allowed while linked.
        entry.intent == VoiceIntent.STOP || entry.intent == VoiceIntent.LAND ->
            if (!ctx.connected) "not connected" else null
        !ctx.connected -> "not connected"
        entry.intent == VoiceIntent.TAKEOFF && ctx.flying -> "already flying"
        entry.intent == VoiceIntent.TAKEOFF && ctx.battery in 0..19 -> "battery ${ctx.battery}% too low"
        entry.requiresFlying && !ctx.flying -> "not flying"
        entry.intent == VoiceIntent.FOLLOW && ctx.followEnabled -> "already following"
        else -> null
    }

    companion object {
        fun describe(intent: VoiceIntent): String = when (intent) {
            VoiceIntent.TAKEOFF -> "takeoff"
            VoiceIntent.LAND -> "land"
            VoiceIntent.STOP -> "stop follow + hover"
            VoiceIntent.FOLLOW -> "follow ON"
            VoiceIntent.PHOTO -> "take photo"
            VoiceIntent.RECORD -> "toggle record"
            VoiceIntent.CLOSER -> "move closer"
        }
    }
}