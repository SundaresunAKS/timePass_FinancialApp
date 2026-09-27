package com.timepass.financialapp.mail

import android.util.Log
import com.timepass.financialapp.backup.BackupError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.Base64

/**
 * The two Gmail endpoints the import needs -- search and fetch -- called over HTTPS, for
 * the same reason DriveClient avoids google-api-client.
 *
 * Read-only by construction: nothing here can send, change or delete mail.
 */
class GmailClient {

    private companion object {
        const val TAG = "GmailClient"
        const val API = "https://gmail.googleapis.com/gmail/v1/users/me"
        const val TIMEOUT_MS = 30_000
        const val PAGE_SIZE = 100
    }

    data class Message(
        val id: String,
        val from: String,
        val subject: String,
        /** When Gmail received it, in epoch millis. */
        val receivedAt: Long,
        /** Plain text of the body, converted from HTML where that is all there is. */
        val body: String
    )

    /**
     * Ids of the messages matching a Gmail search, newest first, up to [limit].
     *
     * Only ids come back, so skipping the messages already imported costs nothing.
     */
    suspend fun search(token: String, query: String, limit: Int): List<String> =
        withContext(Dispatchers.IO) {
            val ids = mutableListOf<String>()
            var pageToken: String? = null
            do {
                val url = buildString {
                    append("$API/messages?maxResults=$PAGE_SIZE&q=${query.urlEncode()}")
                    pageToken?.let { append("&pageToken=${it.urlEncode()}") }
                }
                val page = JSONObject(get(token, url))
                page.optJSONArray("messages")?.let { array ->
                    for (i in 0 until array.length()) ids += array.getJSONObject(i).getString("id")
                }
                pageToken = page.optString("nextPageToken").takeIf { it.isNotEmpty() }
            } while (pageToken != null && ids.size < limit)
            ids.take(limit)
        }

    suspend fun fetch(token: String, id: String): Message = withContext(Dispatchers.IO) {
        val json = JSONObject(get(token, "$API/messages/$id?format=full"))
        val payload = json.getJSONObject("payload")

        val headers = payload.optJSONArray("headers")
        fun header(name: String): String {
            if (headers == null) return ""
            for (i in 0 until headers.length()) {
                val h = headers.getJSONObject(i)
                if (h.optString("name").equals(name, ignoreCase = true)) return h.optString("value")
            }
            return ""
        }

        val parts = Parts()
        collectParts(payload, parts)

        // HTML first: the plain part of a bank alert is often an afterthought -- "view this
        // email in a browser", or the HTML with its tags stripped and its stylesheet left
        // in. Plain text is the fallback when the HTML carries no amount.
        val html = parts.html?.let(MailParser::htmlToText)
        val body = html?.takeIf { MailParser.mentionsAmount(it) }
            ?: parts.plain?.let(MailParser::plainToText)
            ?: html.orEmpty()

        Message(
            id = json.getString("id"),
            from = header("From"),
            subject = header("Subject"),
            receivedAt = json.optString("internalDate").toLongOrNull() ?: 0L,
            body = body
        )
    }

    private class Parts {
        var plain: String? = null
        var html: String? = null
    }

    /** Walks a MIME tree, keeping the first text/plain and text/html bodies found. */
    private fun collectParts(part: JSONObject, out: Parts) {
        val mime = part.optString("mimeType").lowercase()
        val data = part.optJSONObject("body")?.optString("data").orEmpty()

        if (data.isNotEmpty()) {
            val text = decode(data, charsetOf(part))
            when {
                mime == "text/plain" && out.plain == null -> out.plain = text
                mime == "text/html" && out.html == null -> out.html = text
            }
        }

        val children = part.optJSONArray("parts") ?: return
        for (i in 0 until children.length()) collectParts(children.getJSONObject(i), out)
    }

    /** Gmail undoes the transfer encoding but not the charset, which the part declares. */
    private fun charsetOf(part: JSONObject): Charset {
        val headers = part.optJSONArray("headers") ?: return Charsets.UTF_8
        for (i in 0 until headers.length()) {
            val h = headers.getJSONObject(i)
            if (!h.optString("name").equals("Content-Type", ignoreCase = true)) continue
            val name = Regex("""charset="?([^";\s]+)""", RegexOption.IGNORE_CASE)
                .find(h.optString("value"))?.groupValues?.get(1) ?: break
            return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        }
        return Charsets.UTF_8
    }

    /** Gmail bodies are base64url, with or without padding. */
    private fun decode(data: String, charset: Charset): String =
        String(Base64.getUrlDecoder().decode(data.trim()), charset)

    // ------------------------------------------------------------------ http

    private fun get(token: String, url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val code = connection.responseCode
            if (code in 200..299) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            }
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
            Log.w(TAG, "GET ${url.substringBefore('?')} -> $code: ${error?.take(300)}")
            throw BackupError.fromHttp(code, error)
        } catch (e: UnknownHostException) {
            Log.w(TAG, "GET ${url.substringBefore('?')} failed: no network", e)
            throw BackupError.NoNetwork
        } catch (e: IOException) {
            Log.w(TAG, "GET ${url.substringBefore('?')} failed", e)
            throw BackupError.NoNetwork
        } finally {
            connection.disconnect()
        }
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
}
