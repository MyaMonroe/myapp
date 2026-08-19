package com.deffrow.akuji

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AkujiMemoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "akuji_local_memory",
        Context.MODE_PRIVATE,
    )

    fun remember(text: String) {
        val clean = text
            .replace(SEPARATOR, " ")
            .trim()
            .take(MAX_MEMORY_LENGTH)

        if (clean.isBlank()) return

        val updated = (listOf(clean) + recent(MAX_MEMORY_ITEMS))
            .distinct()
            .take(MAX_MEMORY_ITEMS)
            .joinToString(SEPARATOR)

        preferences.edit().putString(MEMORY_KEY, updated).apply()
    }

    fun recent(limit: Int = 3): List<String> = preferences
        .getString(MEMORY_KEY, "")
        .orEmpty()
        .split(SEPARATOR)
        .map { it.trim() }
        .filter(String::isNotBlank)
        .take(limit)

    /**
     * Keeps an honest local transcript so later questions can retrieve earlier
     * work. This is stored only inside AKUJI's app data; it is not model training.
     */
    fun logExchange(userText: String, assistantText: String) {
        val user = userText.clean(MAX_EXCHANGE_INPUT)
        val assistant = assistantText.clean(MAX_EXCHANGE_OUTPUT)
        if (user.isBlank() || assistant.isBlank()) return

        val updated = JSONArray().apply {
            put(
                JSONObject()
                    .put("time", System.currentTimeMillis())
                    .put("user", user)
                    .put("assistant", assistant),
            )
            val old = exchanges()
            for (index in 0 until minOf(old.length(), MAX_EXCHANGES - 1)) {
                put(old.optJSONObject(index))
            }
        }
        preferences.edit().putString(EXCHANGES_KEY, updated.toString()).apply()
    }

    fun relevantContext(query: String): String {
        val terms = searchTerms(query)
        if (terms.isEmpty()) return ""

        val candidates = mutableListOf<MemoryCandidate>()
        recent(MAX_MEMORY_ITEMS).forEachIndexed { index, text ->
            candidates += MemoryCandidate(
                text = "Saved memory: $text",
                score = score(text, terms) + EXPLICIT_MEMORY_BONUS,
                recency = index,
            )
        }

        val stored = exchanges()
        for (index in 0 until stored.length()) {
            val item = stored.optJSONObject(index) ?: continue
            val user = item.optString("user")
            val assistant = item.optString("assistant")
            val combined = "Mya: $user\nAKUJI: $assistant"
            candidates += MemoryCandidate(
                text = combined,
                score = score(combined, terms),
                recency = index,
            )
        }

        val selected = StringBuilder()
        candidates
            .filter { it.score > 0 }
            .sortedWith(
                compareByDescending<MemoryCandidate> { it.score }
                    .thenBy { it.recency },
            )
            .take(MAX_CONTEXT_ITEMS)
            .forEach { candidate ->
                val remaining = MAX_CONTEXT_CHARACTERS - selected.length
                if (remaining <= 0) return@forEach
                if (selected.isNotEmpty()) selected.append("\n\n")
                selected.append(candidate.text.take(remaining))
            }

        return selected.toString().trim()
    }

    fun exchangeCount(): Int = exchanges().length()

    private fun exchanges(): JSONArray = runCatching {
        JSONArray(preferences.getString(EXCHANGES_KEY, "[]"))
    }.getOrDefault(JSONArray())

    private fun score(text: String, terms: Set<String>): Int {
        val lower = text.lowercase()
        return terms.sumOf { term ->
            Regex("\\b${Regex.escape(term)}\\b").findAll(lower).count()
        }
    }

    private fun searchTerms(query: String): Set<String> = Regex("[a-zA-Z0-9']+")
        .findAll(query.lowercase())
        .map { it.value.trim('\'') }
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .toSet()

    private fun String.clean(limit: Int): String = replace(SEPARATOR, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(limit)

    private data class MemoryCandidate(
        val text: String,
        val score: Int,
        val recency: Int,
    )

    companion object {
        private const val MEMORY_KEY = "memories"
        private const val EXCHANGES_KEY = "exchanges"
        private const val SEPARATOR = "\u001E"
        private const val MAX_MEMORY_ITEMS = 200
        private const val MAX_MEMORY_LENGTH = 2_000
        private const val MAX_EXCHANGES = 160
        private const val MAX_EXCHANGE_INPUT = 1_200
        private const val MAX_EXCHANGE_OUTPUT = 2_400
        private const val MAX_CONTEXT_ITEMS = 3
        private const val MAX_CONTEXT_CHARACTERS = 650
        private const val EXPLICIT_MEMORY_BONUS = 2
        private val STOP_WORDS = setOf(
            "about", "after", "again", "also", "and", "are", "but", "can", "did",
            "does", "for", "from", "have", "her", "here", "how", "into", "just",
            "more", "not", "now", "that", "the", "their", "then", "this", "was",
            "what", "when", "where", "which", "who", "why", "with", "you", "your",
        )
    }
}
