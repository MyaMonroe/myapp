package com.deffrow.akuji

data class BrainReply(
    val text: String,
    val shouldSpeak: Boolean = true,
)

interface BrainEngine {
    val engineName: String
    fun respond(input: String): BrainReply
}

/**
 * Honest offline core for the first permanent shell.
 *
 * This is not presented as Gemma, Qwen, or DeepSeek. A model runtime can replace
 * this class without changing the avatar, voice, or memory pipeline.
 */
class LocalAkujiCore(
    private val memory: AkujiMemoryStore,
) : BrainEngine {
    override val engineName: String = "AKUJI Local Core"

    override fun respond(input: String): BrainReply {
        val clean = input.trim()
        val normalized = clean.lowercase()

        return when {
            normalized.startsWith("remember ") -> {
                val item = clean.drop(9).trim()
                if (item.isBlank()) {
                    BrainReply("Tell me what you want saved after the word remember.")
                } else {
                    memory.remember(item)
                    BrainReply("I saved it on this phone.")
                }
            }

            normalized.contains("what do you remember") ||
                normalized.contains("read my memory") -> {
                val items = memory.recent()
                if (items.isEmpty()) {
                    BrainReply("My local memory is empty right now.")
                } else {
                    BrainReply("My latest saved memory is: ${items.joinToString(". ")}")
                }
            }

            normalized == "akuji" || normalized == "echo" ||
                normalized.startsWith("akuji ") || normalized.startsWith("echo ") -> {
                BrainReply("I'm here, Mya.")
            }

            else -> BrainReply(
                "I heard you. My body, voice, and local memory are active. " +
                    "The Gemma, Qwen, or DeepSeek model brain is not connected yet.",
            )
        }
    }
}
