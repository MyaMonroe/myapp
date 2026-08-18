package com.deffrow.akuji

import android.content.Context

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

    companion object {
        private const val MEMORY_KEY = "memories"
        private const val SEPARATOR = "\u001E"
        private const val MAX_MEMORY_ITEMS = 200
        private const val MAX_MEMORY_LENGTH = 2_000
    }
}
