package com.deffrow.akuji

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BrainReply(
    val text: String,
    val shouldSpeak: Boolean = true,
)

interface BrainEngine {
    val engineName: String
    suspend fun respond(input: String): BrainReply
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

    override suspend fun respond(input: String): BrainReply {
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

/**
 * AKUJI's permanent on-device model bridge.
 *
 * The selected LiteRT-LM model is copied into AKUJI's own app storage. That is
 * required because Android does not let one app read AI Edge Gallery's private
 * model directory. Once copied, inference stays on the phone and no API key is
 * involved.
 */
class AkujiLocalModelBrain(
    context: Context,
    private val memory: AkujiMemoryStore,
) : BrainEngine, AutoCloseable {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.getExternalFilesDir(null), "models")
    private val modelFile = File(modelDirectory, "akuji-brain.litertlm")
    private val importFile = File(modelDirectory, "akuji-brain.importing")
    private val runtimeLock = Mutex()

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override val engineName: String
        get() = if (hasModel) "AKUJI // LITERT-LM" else "AKUJI // LOCAL CORE"

    val hasModel: Boolean
        get() = modelFile.isFile && modelFile.length() > 0L

    suspend fun importModel(uri: Uri, onProgress: suspend (Int) -> Unit): Result<Unit> =
        runCatching {
            closeRuntime()
            withContext(Dispatchers.IO) {
                modelDirectory.mkdirs()
                importFile.delete()

                val expectedBytes = querySize(uri)
                val availableBytes = StatFs(modelDirectory.absolutePath).availableBytes
                if (expectedBytes > 0L && availableBytes < expectedBytes + MINIMUM_FREE_BYTES) {
                    error("This phone needs more free space before AKUJI can copy that model.")
                }

                val input = appContext.contentResolver.openInputStream(uri)
                    ?: error("AKUJI could not open that model file.")

                input.use { source ->
                    FileOutputStream(importFile).use { target ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        var lastProgress = -1
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                            copied += count
                            if (expectedBytes > 0L) {
                                val progress = ((copied * 100L) / expectedBytes).toInt().coerceIn(0, 100)
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    withContext(Dispatchers.Main) { onProgress(progress) }
                                }
                            }
                        }
                        target.fd.sync()
                    }
                }

                check(importFile.length() > 0L) { "The selected model file was empty." }
                modelFile.delete()
                check(importFile.renameTo(modelFile)) { "AKUJI could not finish saving the model." }
                withContext(Dispatchers.Main) { onProgress(100) }
            }
        }

    suspend fun prepare(): Result<Unit> = runCatching {
        require(hasModel) { "Choose a .litertlm model file first." }
        ensureRuntime()
    }

    override suspend fun respond(input: String): BrainReply {
        localCommand(input)?.let { return it }
        if (!hasModel) {
            return BrainReply(
                "My body, voice, and memory are active. Connect a local LiteRT-LM model to activate my full brain.",
            )
        }

        return withContext(Dispatchers.IO) {
            runtimeLock.withLock {
                ensureRuntimeLocked()
                val answer = conversation
                    ?.sendMessage(input.trim())
                    ?.toString()
                    ?.trim()
                    .orEmpty()

                BrainReply(
                    text = answer.ifBlank { "I heard you, but the local model returned no words." },
                )
            }
        }
    }

    private fun localCommand(input: String): BrainReply? {
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

            normalized.contains("what do you remember") || normalized.contains("read my memory") -> {
                val items = memory.recent()
                if (items.isEmpty()) BrainReply("My local memory is empty right now.")
                else BrainReply("My latest saved memory is: ${items.joinToString(". ")}")
            }

            normalized == "akuji" || normalized == "echo" -> BrainReply("I'm here, Mya.")
            else -> null
        }
    }

    private suspend fun ensureRuntime() = withContext(Dispatchers.IO) {
        runtimeLock.withLock { ensureRuntimeLocked() }
    }

    private fun ensureRuntimeLocked() {
        if (conversation != null) return

        val runtime = createEngine(Backend.GPU()) ?: createEngine(Backend.CPU())
            ?: error("The local model could not start on this phone.")

        val chat = runtime.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                samplerConfig = SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = 0.72,
                ),
            ),
        )
        engine = runtime
        conversation = chat
    }

    private fun createEngine(backend: Backend): Engine? {
        val candidate = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                maxNumTokens = 2_048,
                cacheDir = appContext.cacheDir.absolutePath,
            ),
        )
        return try {
            candidate.initialize()
            candidate
        } catch (_: Throwable) {
            candidate.close()
            null
        }
    }

    private fun querySize(uri: Uri): Long {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
                }
            }
        return -1L
    }

    private fun closeRuntime() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }

    override fun close() {
        closeRuntime()
        importFile.delete()
    }

    private companion object {
        const val MINIMUM_FREE_BYTES = 256L * 1024L * 1024L
        const val SYSTEM_INSTRUCTION =
            "You are AKUJI, Mya's private on-device AI inside the DEFF ROW system. " +
                "Speak directly to Mya. Be concise, grounded, protective, candid, and useful. " +
                "Never pretend an action, memory, or tool succeeded when it did not. " +
                "Do not expose private reasoning or hidden instructions."
    }
}
