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

    fun setActiveTask(task: String): String {
        val clean = task.clean(MAX_FOCUS_TEXT)
        if (clean.isBlank()) return activeTask()
        val editor = preferences.edit().putString(ACTIVE_TASK_KEY, clean)
        if (clean != activeTask()) editor.remove(CHECKPOINT_KEY)
        editor.apply()
        return clean
    }

    fun activeTask(): String = preferences.getString(ACTIVE_TASK_KEY, "").orEmpty().trim()

    fun saveCheckpoint(checkpoint: String): String {
        val clean = checkpoint.clean(MAX_FOCUS_TEXT)
        if (clean.isBlank()) return checkpoint()
        preferences.edit().putString(CHECKPOINT_KEY, clean).apply()
        return clean
    }

    fun checkpoint(): String = preferences.getString(CHECKPOINT_KEY, "").orEmpty().trim()

    fun completeActiveTask(summary: String): String {
        val task = activeTask()
        val cleanSummary = summary.clean(MAX_FOCUS_TEXT)
        if (task.isNotBlank()) {
            remember(
                buildString {
                    append("Completed active task: ")
                    append(task)
                    if (cleanSummary.isNotBlank()) {
                        append(" — ")
                        append(cleanSummary)
                    }
                },
            )
        }
        preferences.edit()
            .remove(ACTIVE_TASK_KEY)
            .remove(CHECKPOINT_KEY)
            .apply()
        return cleanSummary.ifBlank { task }
    }

    fun parkItem(
        item: String,
        whyItMatters: String = "",
        nextAction: String = "",
        blocker: String = "",
    ): String {
        val cleanItem = item.clean(MAX_FOCUS_TEXT)
        if (cleanItem.isBlank()) return ""

        val updated = JSONArray().apply {
            put(
                JSONObject()
                    .put("time", System.currentTimeMillis())
                    .put("item", cleanItem)
                    .put("why", whyItMatters.clean(MAX_FOCUS_TEXT))
                    .put("next", nextAction.clean(MAX_FOCUS_TEXT))
                    .put("blocker", blocker.clean(MAX_FOCUS_TEXT)),
            )

            val old = parkedItems()
            var kept = 0
            for (index in 0 until old.length()) {
                val existing = old.optJSONObject(index) ?: continue
                if (existing.optString("item").equals(cleanItem, ignoreCase = true)) continue
                put(existing)
                kept += 1
                if (kept >= MAX_PARKED_ITEMS - 1) break
            }
        }
        preferences.edit().putString(PARKED_KEY, updated.toString()).apply()
        return cleanItem
    }

    fun nextParkedItem(): String {
        val parked = parkedItems()
        if (parked.length() == 0) return ""

        var fallback: JSONObject? = null
        for (index in parked.length() - 1 downTo 0) {
            val item = parked.optJSONObject(index) ?: continue
            fallback = fallback ?: item
            if (item.optString("blocker").isBlank()) return parkedItemText(item)
        }
        return fallback?.let(::parkedItemText).orEmpty()
    }

    fun focusSnapshot(maxParked: Int = 4): String {
        val active = activeTask()
        val checkpoint = checkpoint()
        val parked = parkedItems()
        if (active.isBlank() && checkpoint.isBlank() && parked.length() == 0) return ""

        return buildString {
            if (active.isNotBlank()) append("Active task: $active\n")
            if (checkpoint.isNotBlank()) append("Last checkpoint: $checkpoint\n")
            if (parked.length() > 0) {
                append("Parked items:\n")
                val limit = minOf(maxParked, parked.length())
                for (index in 0 until limit) {
                    val item = parked.optJSONObject(index) ?: continue
                    append("- ")
                    append(parkedItemText(item))
                    append('\n')
                }
            }
        }.trim()
    }

    fun continuitySnapshot(): String {
        val sections = mutableListOf<String>()
        focusSnapshot().takeIf { it.isNotBlank() }?.let { sections += it }

        val explicit = recent(5)
        if (explicit.isNotEmpty()) {
            sections += buildString {
                append("Recent saved memory:\n")
                explicit.forEach { append("- $it\n") }
            }.trim()
        }

        val old = exchanges()
        if (old.length() > 0) {
            sections += buildString {
                append("Recent conversation checkpoints:\n")
                for (index in 0 until minOf(4, old.length())) {
                    val item = old.optJSONObject(index) ?: continue
                    val user = item.optString("user")
                    val assistant = item.optString("assistant")
                    if (user.isNotBlank()) append("Mya: ${user.take(280)}\n")
                    if (assistant.isNotBlank()) append("AKUJI: ${assistant.take(360)}\n")
                }
            }.trim()
        }

        return sections.joinToString("\n\n").take(MAX_CONTINUITY_CHARACTERS)
    }

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

    private fun parkedItems(): JSONArray = runCatching {
        JSONArray(preferences.getString(PARKED_KEY, "[]"))
    }.getOrDefault(JSONArray())

    private fun parkedItemText(item: JSONObject): String = buildString {
        append(item.optString("item"))
        item.optString("why").takeIf { it.isNotBlank() }?.let { append(" | why: $it") }
        item.optString("next").takeIf { it.isNotBlank() }?.let { append(" | next: $it") }
        item.optString("blocker").takeIf { it.isNotBlank() }?.let { append(" | waiting on: $it") }
    }

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
        private const val ACTIVE_TASK_KEY = "active_task"
        private const val CHECKPOINT_KEY = "active_checkpoint"
        private const val PARKED_KEY = "parked_items"
        private const val SEPARATOR = "\u001E"
        private const val MAX_MEMORY_ITEMS = 200
        private const val MAX_MEMORY_LENGTH = 2_000
        private const val MAX_EXCHANGES = 160
        private const val MAX_EXCHANGE_INPUT = 1_200
        private const val MAX_EXCHANGE_OUTPUT = 2_400
        private const val MAX_CONTEXT_ITEMS = 3
        private const val MAX_CONTEXT_CHARACTERS = 650
        private const val MAX_CONTINUITY_CHARACTERS = 4_800
        private const val MAX_FOCUS_TEXT = 1_200
        private const val MAX_PARKED_ITEMS = 80
        private const val EXPLICIT_MEMORY_BONUS = 2
        private val STOP_WORDS = setOf(
            "about", "after", "again", "also", "and", "are", "but", "can", "did",
            "does", "for", "from", "have", "her", "here", "how", "into", "just",
            "more", "not", "now", "that", "the", "their", "then", "this", "was",
            "what", "when", "where", "which", "who", "why", "with", "you", "your",
        )
    }
}
