package com.tracklesstelemetry.sdk

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Result of an HTTP send operation.
 *
 * @param statusCode HTTP status code, or -1 for network errors
 * @param isNetworkError true if the request failed due to a network error or timeout
 */
internal data class SendResult(
    val statusCode: Int,
    val isNetworkError: Boolean = false,
)

/**
 * HTTP client using [java.net.HttpURLConnection] — zero dependencies.
 *
 * Hard 10-second total timeout:
 * - connectTimeout = 5000ms
 * - readTimeout = 5000ms
 * This caps total request time at 10 seconds.
 */
internal object HttpClient {

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /**
     * Send an event payload to the ingest endpoint via POST.
     *
     * @param endpoint Ingest URL
     * @param apiKey API key in `tl_*` format
     * @param payload Event payload to send
     * @return [SendResult] with status code or network error indicator
     */
    fun send(endpoint: String, apiKey: String, payload: EventPayload): SendResult {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(endpoint)
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Api-Key", apiKey)
            }

            // Write request body
            val body = payload.toJsonString()
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }

            // Read response code
            val statusCode = connection.responseCode
            SendResult(statusCode = statusCode)
        } catch (_: Throwable) {
            SendResult(statusCode = -1, isNetworkError = true)
        } finally {
            connection?.disconnect()
        }
    }
}
