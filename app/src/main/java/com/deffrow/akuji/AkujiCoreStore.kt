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
 * Their readable contents are copied into AKUJI's internal app storage and are
 * injected into each new local-model conversation.
 */
class AkujiCoreStore(context: Context) {
    private val appContext = context.applicationContext
    private val coreDirectory = File(appContext.filesDir, "akuji_core")
    private val activeCore = File(coreDirectory, "active_core.txt")
    private val previousCore = File(coreDirectory, "previous_core.txt")

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
        }
        check(newCore.renameTo(activeCore)) { "AKUJI could not finish saving her core." }

        CoreImportResult(
            fileCount = sections.size,
            characterCount = totalCharacters,
        )
    }

    fun promptText(): String = if (!hasCore) {
        ""
    } else {
        activeCore.readText().take(MAX_PROMPT_CHARACTERS)
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

    private companion object {
        const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_CORE_CHARACTERS = 1_000_000
        const val MAX_PROMPT_CHARACTERS = 24_000
        val READABLE_EXTENSIONS = listOf(
            ".txt", ".json", ".md", ".js", ".html", ".yaml", ".yml", ".xml", ".zip",
        )
    }
}
