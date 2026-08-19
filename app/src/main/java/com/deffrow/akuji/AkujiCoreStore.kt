package com.deffrow.akuji

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

data class CoreImportResult(
    val fileCount: Int,
    val characterCount: Int,
)

/**
 * Durable, private storage for AKUJI's user-supplied identity/core files.
 *
 * The original files never need to remain in Downloads or Drive after import.
 * Their readable contents are copied into AKUJI's internal app storage. Only a
 * small identity section and query-relevant passages are sent to the model so a
 * large archive cannot overflow Gemma's 2K-token context window.
 */
class AkujiCoreStore(context: Context) {
    private val appContext = context.applicationContext
    private val coreDirectory = File(appContext.filesDir, "akuji_core")
    private val activeCore = File(coreDirectory, "active_core.txt")
    private val previousCore = File(coreDirectory, "previous_core.txt")
    private val archiveCore = File(coreDirectory, "searchable_archive.txt")

    init {
        if (
            !archiveCore.isFile &&
            previousCore.isFile &&
            previousCore.length() >= ARCHIVE_FILE_THRESHOLD_BYTES
        ) {
            coreDirectory.mkdirs()
            previousCore.copyTo(archiveCore, overwrite = true)
        }
    }

    val hasCore: Boolean
        get() = activeCore.isFile && activeCore.length() > 0L

    fun import(uris: List<Uri>): Result<CoreImportResult> = runCatching {
        require(uris.isNotEmpty()) { "No AKUJI core file was selected." }

        val sections = mutableListOf<String>()
        var totalCharacters = 0

        uris.forEach { uri ->
            val name = queryName(uri).ifBlank { "AKUJI core file" }
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                input.readLimited(MAX_IMPORT_BYTES)
            } ?: error("AKUJI could not open $name.")

            if (name.endsWith(".zip", ignoreCase = true)) {
                ZipInputStream(bytes.inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (
                            !entry.isDirectory &&
                            !entry.name.endsWith(".zip", ignoreCase = true) &&
                            isReadableCoreFile(entry.name)
                        ) {
                            val text = zip.readLimited(MAX_FILE_BYTES).decodeToString().cleanCoreText()
                            if (text.isNotBlank()) {
                                totalCharacters += text.length
                                check(totalCharacters <= MAX_CORE_CHARACTERS) {
                                    "That core bundle is too large for the on-device model."
                                }
                                sections += section(entry.name, text)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } else {
                require(isReadableCoreFile(name)) {
                    "Choose AKUJI's TXT, JSON, MD, JS, HTML, YAML, XML, or ZIP core file."
                }
                val text = bytes.decodeToString().cleanCoreText()
                if (text.isNotBlank()) {
                    totalCharacters += text.length
                    check(totalCharacters <= MAX_CORE_CHARACTERS) {
                        "Those core files are too large for the on-device model."
                    }
                    sections += section(name, text)
                }
            }
        }

        require(sections.isNotEmpty()) { "No readable AKUJI core text was found." }
        coreDirectory.mkdirs()

        val newCore = File(coreDirectory, "active_core.importing")
        newCore.writeText(sections.joinToString("\n\n"))
        if (activeCore.isFile) {
            previousCore.delete()
            activeCore.copyTo(previousCore, overwrite = true)
            if (activeCore.length() >= ARCHIVE_FILE_THRESHOLD_BYTES) {
                activeCore.copyTo(archiveCore, overwrite = true)
            }
        }
        check(newCore.renameTo(activeCore)) { "AKUJI could not finish saving her core." }
        if (activeCore.length() >= ARCHIVE_FILE_THRESHOLD_BYTES) {
            activeCore.copyTo(archiveCore, overwrite = true)
        }

        CoreImportResult(
            fileCount = sections.size,
            characterCount = totalCharacters,
        )
    }

    fun identityText(): String = if (!hasCore) {
        ""
    } else {
        activeCore.readText().take(MAX_IDENTITY_CHARACTERS)
    }

    fun relevantText(query: String): String {
        if (!hasCore) return ""

        val terms = searchTerms(query)
        if (terms.isEmpty()) return ""

        val activeText = activeCore.readText()
        val searchable = buildString {
            append(activeText.drop(MAX_IDENTITY_CHARACTERS))
            if (
                archiveCore.isFile &&
                archiveCore.length() > 0L &&
                archiveCore.length() != activeCore.length()
            ) {
                append("\n\n")
                append(archiveCore.readText())
            }
        }
        if (searchable.isBlank()) return ""

        val ranked = chunks(searchable)
            .mapIndexed { index, chunk ->
                val lower = chunk.lowercase()
                val hits = terms.sumOf { term ->
                    Regex("\\b${Regex.escape(term)}\\b").findAll(lower).count()
                }
                RankedChunk(chunk = chunk, score = hits, index = index)
            }
            .filter { it.score > 0 }
            .sortedWith(compareByDescending<RankedChunk> { it.score }.thenBy { it.index })

        val selected = StringBuilder()
        for (match in ranked.take(MAX_RELEVANT_CHUNKS)) {
            val remaining = MAX_RELEVANT_CHARACTERS - selected.length
            if (remaining <= 0) break
            if (selected.isNotEmpty()) selected.append("\n\n")
            selected.append(match.chunk.take(remaining))
        }
        return selected.toString().trim()
    }

    private fun queryName(uri: Uri): String {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun section(name: String, text: String): String =
        "===== AKUJI CORE FILE: ${name.substringAfterLast('/')} =====\n$text"

    private fun String.cleanCoreText(): String =
        replace("\u0000", "").trim()

    private fun isReadableCoreFile(name: String): Boolean {
        val lower = name.lowercase()
        return READABLE_EXTENSIONS.any(lower::endsWith)
    }

    private fun java.io.InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            check(total <= limit) { "That AKUJI core file is too large." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun chunks(text: String): List<String> {
        val paragraphs = text
            .split(Regex("\\n\\s*\\n"))
            .map(String::trim)
            .filter(String::isNotBlank)

        val result = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString().trim()
                current.clear()
            }
        }

        paragraphs.forEach { paragraph ->
            if (paragraph.length > MAX_CHUNK_CHARACTERS) {
                flush()
                paragraph.chunked(MAX_CHUNK_CHARACTERS).forEach(result::add)
            } else if (current.length + paragraph.length + 2 > MAX_CHUNK_CHARACTERS) {
                flush()
                current.append(paragraph)
            } else {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(paragraph)
            }
        }
        flush()
        return result
    }

    private fun searchTerms(query: String): Set<String> = Regex("[a-zA-Z0-9']+")
        .findAll(query.lowercase())
        .map { it.value.trim('\'') }
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .toSet()

    private data class RankedChunk(
        val chunk: String,
        val score: Int,
        val index: Int,
    )

    private companion object {
        const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_CORE_CHARACTERS = 1_000_000
        const val ARCHIVE_FILE_THRESHOLD_BYTES = 12_000L
        const val MAX_IDENTITY_CHARACTERS = 3_400
        const val MAX_RELEVANT_CHARACTERS = 900
        const val MAX_RELEVANT_CHUNKS = 2
        const val MAX_CHUNK_CHARACTERS = 700
        val READABLE_EXTENSIONS = listOf(
            ".txt", ".json", ".md", ".js", ".html", ".yaml", ".yml", ".xml", ".zip",
        )
        val STOP_WORDS = setOf(
            "about", "after", "again", "also", "and", "are", "but", "can", "did",
            "does", "for", "from", "have", "her", "here", "how", "into", "just",
            "more", "not", "now", "that", "the", "their", "then", "this", "was",
            "what", "when", "where", "which", "who", "why", "with", "you", "your",
        )
    }
}
