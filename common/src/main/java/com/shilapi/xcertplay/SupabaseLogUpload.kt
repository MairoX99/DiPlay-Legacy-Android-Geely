package com.shilapi.xcertplay

import java.net.HttpURLConnection
import java.net.URL

/**
 * One-shot upload of a report the user just generated.
 * Nothing here runs unless [post] is called.
 */
internal object SupabaseLogUpload {
    const val MAX_BYTES = 1024 * 1024

    fun enabled(url: String, key: String): Boolean =
        url.startsWith("https://") && url.contains("supabase.co") && key.isNotBlank()

    /** Last [maxBytes] of UTF-8, starting on a character boundary and a line when one fits. */
    fun tailUtf8(text: String, maxBytes: Int = MAX_BYTES): ByteArray {
        val full = text.toByteArray(Charsets.UTF_8)
        if (full.size <= maxBytes) return full
        var start = full.size - maxBytes
        while (start < full.size && (full[start].toInt() and 0xC0) == 0x80) start++
        var newline = start
        while (newline < full.size && full[newline] != '\n'.code.toByte()) newline++
        if (newline < full.size - 1) start = newline + 1
        return full.copyOfRange(start, full.size)
    }

    fun post(url: String, key: String, objectName: String, body: ByteArray) {
        check(enabled(url, key)) { "Supabase upload is not configured" }
        check(body.size in 1..MAX_BYTES) { "Report is ${body.size} bytes; the cap is $MAX_BYTES" }
        val safeName = objectName.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '-'
        }.joinToString("").ifBlank { "diplay.txt" }
        val endpoint = URL(url.trimEnd('/') + "/storage/v1/object/logs/$safeName")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("apikey", key)
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val message = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val reason = when {
                    message.contains("Bucket not found") || message.contains("NoSuchBucket") ->
                        "Supabase 里还没有名为 logs 的存储位置。在 Storage 新建私有 bucket logs，单文件限制 1048576 字节，并允许 anon 写入。"
                    else -> message.ifBlank { "HTTP $code" }
                }
                error(reason.take(400))
            }
        } finally {
            connection.disconnect()
        }
    }
}
