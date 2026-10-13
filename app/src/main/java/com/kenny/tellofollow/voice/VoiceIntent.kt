package com.kenny.tellofollow.voice

/** Actions voice can ask for. RECORD and CLOSER are stubs until a later phase. */
enum class VoiceIntent(val label: String, val implemented: Boolean = true) {
    TAKEOFF("TAKEOFF"),
    LAND("LAND"),
    STOP("STOP"),
    FOLLOW("FOLLOW"),
    PHOTO("PHOTO"),
    RECORD("RECORD", implemented = false),
    CLOSER("CLOSER", implemented = false);

    companion object {
        fun parse(name: String): VoiceIntent? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** One phrase-book entry, loaded from assets/voice_phrases.json (+ user overrides). */
data class PhraseEntry(
    val intent: VoiceIntent,
    val phrases: List<String>,
    val reply: String = "",
    val requiresFlying: Boolean = false,
)