package com.kenny.tellofollow.voice

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phrase book = built-in defaults (assets/voice_phrases.json) merged with an optional
 * user file (filesDir/voice_phrases_user.json). Same schema in both:
 *
 * { "version": 1, "intents": [
 *     { "intent": "FOLLOW", "phrases": ["follow me"], "addPhrases": ["lets go"],
 *       "reply": "Following", "requiresFlying": true } ] }
 *
 * In the user file, "phrases" REPLACES the default list, "addPhrases" APPENDS to it,
 * and reply / requiresFlying override only when present. Intents not mentioned keep defaults.
 */
class PhraseBook(val entries: List<PhraseEntry>) {

    fun entry(intent: VoiceIntent): PhraseEntry? = entries.firstOrNull { it.intent == intent }

    companion object {
        const val ASSET_NAME = "voice_phrases.json"
        const val USER_FILE_NAME = "voice_phrases_user.json"
        private const val TAG = "PhraseBook"

        fun load(context: Context): PhraseBook {
            val defaults = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            val userFile = File(context.filesDir, USER_FILE_NAME)
            val overrides = if (userFile.exists()) {
                runCatching { userFile.readText() }.getOrNull()
            } else null
            return try {
                fromJson(defaults, overrides)
            } catch (e: Exception) {
                Log.w(TAG, "User overrides invalid, using defaults only", e)
                fromJson(defaults, null)
            }
        }

        fun fromJson(defaultsJson: String, overridesJson: String? = null): PhraseBook {
            val map = LinkedHashMap<VoiceIntent, PhraseEntry>()
            parseIntents(defaultsJson).forEach { obj ->
                val intent = VoiceIntent.parse(obj.optString("intent")) ?: return@forEach
                map[intent] = PhraseEntry(
                    intent = intent,
                    phrases = obj.optJSONArray("phrases").toStrings() +
                        obj.optJSONArray("addPhrases").toStrings(),
                    reply = obj.optString("reply", ""),
                    requiresFlying = obj.optBoolean("requiresFlying", false),
                )
            }
            if (!overridesJson.isNullOrBlank()) {
                parseIntents(overridesJson).forEach { obj ->
                    val intent = VoiceIntent.parse(obj.optString("intent")) ?: return@forEach
                    val base = map[intent] ?: PhraseEntry(intent, emptyList())
                    val phrases = (if (obj.has("phrases")) obj.optJSONArray("phrases").toStrings() else base.phrases) +
                        obj.optJSONArray("addPhrases").toStrings()
                    map[intent] = base.copy(
                        phrases = phrases.distinct(),
                        reply = if (obj.has("reply")) obj.optString("reply") else base.reply,
                        requiresFlying = if (obj.has("requiresFlying")) obj.optBoolean("requiresFlying") else base.requiresFlying,
                    )
                }
            }
            return PhraseBook(map.values.toList())
        }

        private fun parseIntents(json: String): List<JSONObject> {
            val trimmed = json.trim()
            val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("intents") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }

        private fun JSONArray?.toStrings(): List<String> =
            if (this == null) emptyList()
            else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
}