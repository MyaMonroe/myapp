package com.deffrow.akuji

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AkujiBridgeClient(context: Context) {
    data class BridgeStatus(
        val authenticated: Boolean,
        val harnessConfigured: Boolean,
        val executionEnabled: Boolean,
    )

    data class HarnessResult(
        val ok: Boolean,
        val statusCode: Int?,
        val result: String?,
        val message: String?,
    )

    private val configStore = AkujiBridgeConfigStore(context.applicationContext)

    suspend fun getStatus(): Result<BridgeStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val config = requireConfig()
            val response = request(
                method = "GET",
                url = "${config.baseUrl}/v1/status",
                bearerToken = config.bearerToken,
            )
            val json = JSONObject(response.body)
            BridgeStatus(
                authenticated = json.optBoolean("authenticated", false),
                harnessConfigured = json.optBoolean("harness_configured", false),
                executionEnabled = json.optBoolean("execution_enabled", false),
            )
        }
    }

    suspend fun runHarnessTask(
        instruction: String,
        context: String? = null,
        dryRun: Boolean = true,
    ): Result<HarnessResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanInstruction = instruction.trim()
            require(cleanInstruction.isNotBlank()) { "Harness instruction cannot be empty." }
            require(cleanInstruction.length <= 12_000) { "Harness instruction is too long." }
            require(context == null || context.length <= 24_000) { "Harness context is too long." }

            val config = requireConfig()
            val payload = JSONObject()
                .put("instruction", cleanInstruction)
                .put("context", context ?: JSONObject.NULL)
                .put("dry_run", dryRun)

            val response = request(
                method = "POST",
                url = "${config.baseUrl}/v1/harness/task",
                bearerToken = config.bearerToken,
                jsonBody = payload.toString(),
            )

            val json = JSONObject(response.body)
            HarnessResult(
                ok = json.optBoolean("ok", false),
                statusCode = if (json.isNull("status_code")) null else json.optInt("status_code"),
                result = json.opt("result")?.let { value ->
                    if (value == JSONObject.NULL) null else value.toString()
                },
                message = json.optString("message").takeIf { it.isNotBlank() },
            )
        }
    }

    fun saveConfiguration(baseUrl: String, bearerToken: String) {
        configStore.save(baseUrl = baseUrl, bearerToken = bearerToken)
    }

    fun clearConfiguration() {
        configStore.clear()
    }

    fun isConfigured(): Boolean = configStore.load() != null

    private fun requireConfig(): AkujiBridgeConfigStore.BridgeConfig =
        configStore.load() ?: error("AKUJI bridge is not configured on this device.")

    private fun request(
        method: String,
        url: String,
        bearerToken: String,
        jsonBody: String? = null,
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("Accept", "application/json")

            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        return try {
            if (jsonBody != null) {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(jsonBody)
                }
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (statusCode !in 200..299) {
                val detail = runCatching {
                    JSONObject(body).optString("detail").takeIf { it.isNotBlank() }
                }.getOrNull()
                throw IOException(detail ?: "AKUJI bridge returned HTTP $statusCode.")
            }

            HttpResponse(statusCode = statusCode, body = body)
        } finally {
            connection.disconnect()
        }
    }

    private data class HttpResponse(
        val statusCode: Int,
        val body: String,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
