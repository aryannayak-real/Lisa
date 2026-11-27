package com.lisaai.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams a GGUF file to disk without pulling it into memory.
 *
 * Hugging Face answers with a redirect to a signed CDN URL, so redirects are followed
 * manually for both http and https targets.
 */
object ModelDownloader {

    private const val MAX_REDIRECTS = 6

    fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        val connection = openFollowingRedirects(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("Server returned HTTP $code")

            val total = connection.contentLengthLong
            val temp = File(target.parentFile, target.name + ".part")

            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0L) {
                            onProgress((written.toDouble() / total).toFloat().coerceIn(0f, 1f))
                        }
                    }
                }
            }

            if (temp.length() <= 0L) error("Downloaded file is empty")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            onProgress(1f)
        } finally {
            connection.disconnect()
        }
    }

    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Lisa/0.1")
            }
            connection.connect()

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) error("Redirect without a Location header")
                current = URL(current, location)
                return@repeat
            }
            return connection
        }
        error("Too many redirects")
    }
}
