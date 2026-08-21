package com.deffrow.akuji

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AkujiBridgeClient(context: Context) {
    data class BridgeStatus(
        val authenticated: Boolean,
        val operatorMode: String,
        val executionEnabled: Boolean,
        val availableTools: List<String>,
    )

    data class OperatorResult(
        val ok: Boolean,
        val executed: Boolean,
        val tool: String,
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
                operatorMode = json.optString("operator_mode", "unknown"),
                executionEnabled = json.optBoolean("execution_enabled", false),
                availableTools = json.optJSONArray("available_tools").toStringList(),
            )
        }
    }

    suspend fun runOperatorTool(
        tool: String,
        argumentsJson: String = "{}",
        dryRun: Boolean = true,
    ): Result<OperatorResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanTool = tool.trim().lowercase()
            require(cleanTool.isNotBlank()) { "Operator tool cannot be empty." }
            require(cleanTool.length <= 120) { "Operator tool name is too long." }

            val arguments = argumentsJson.trim().ifBlank { "{}" }
            val argumentsObject = JSONObject(arguments)
            val config = requireConfig()
            val payload = JSONObject()
                .put("tool", cleanTool)
                .put("arguments", argumentsObject)
                .put("dry_run", dryRun)

            val response = request(
                method = "POST",
                url = "${config.baseUrl}/v1/operator/tool",
                bearerToken = config.bearerToken,
                jsonBody = payload.toString(),
            )

            val json = JSONObject(response.body)
            OperatorResult(
                ok = json.optBoolean("ok", false),
                executed = json.optBoolean("executed", false),
                tool = json.optString("tool", cleanTool),
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

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private data class HttpResponse(
        val statusCode: Int,
        val body: String,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 120_000
    }
}
