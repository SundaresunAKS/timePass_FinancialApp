package com.timepass.financialapp.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The linked account's name and address.
 *
 * Fetched from the OpenID userinfo endpoint rather than read off the sign-in result: the
 * authorization flow returns a token and, at best, an email, and the account's *name* is
 * only available by spending that token. One small GET is cheaper than pulling in the
 * Google API client library for a single field.
 */
data class GoogleProfile(val email: String?, val name: String?) {

    /** First name where there is one, for a greeting that reads like a person wrote it. */
    val givenName: String? get() = name?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }

    companion object {

        private const val USERINFO = "https://www.googleapis.com/oauth2/v3/userinfo"
        private const val TIMEOUT_MS = 15_000

        /**
         * Returns null rather than throwing on any failure.
         *
         * The profile is decoration: a backup must not fail because a greeting could not be
         * personalised, and the scope may legitimately have been declined.
         */
        suspend fun fetch(accessToken: String): GoogleProfile? = withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(USERINFO).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                }

                connection.use { c ->
                    if (c.responseCode !in 200..299) return@runCatching null
                    val json = JSONObject(c.inputStream.bufferedReader().readText())
                    GoogleProfile(
                        email = json.optString("email").takeIf { it.isNotEmpty() },
                        name = json.optString("name").takeIf { it.isNotEmpty() }
                            ?: json.optString("given_name").takeIf { it.isNotEmpty() }
                    )
                }
            }.getOrNull()
        }

        private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
            try {
                block(this)
            } finally {
                disconnect()
            }
    }
}
