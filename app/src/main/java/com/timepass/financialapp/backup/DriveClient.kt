package com.timepass.financialapp.backup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * The five Drive endpoints this app needs, called directly over HTTPS.
 *
 * Deliberately not google-api-client: that library pulls in several megabytes of
 * transitive dependencies and its own HTTP stack to wrap what is a handful of REST calls.
 *
 * Every call takes an access token rather than holding one, so token refresh stays the
 * caller's concern and a 401 can be retried with a fresh token.
 */
class DriveClient {

    private companion object {
        const val TAG = "DriveClient"
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val TIMEOUT_MS = 30_000
    }

    data class DriveFile(val id: String, val name: String, val size: Long, val modifiedTime: String?)

    /** Creates a folder in the root of My Drive and returns its id. */
    suspend fun createFolder(token: String, name: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("name", name)
            put("mimeType", FOLDER_MIME)
        }
        val response = request(
            token = token,
            method = "POST",
            url = "$API/files?fields=id",
            contentType = "application/json",
            body = body.toString().toByteArray()
        )
        JSONObject(response).getString("id")
    }

    /**
     * Confirms a saved folder still exists and is not in the trash.
     *
     * Drive keeps returning metadata for trashed items, so `trashed` must be checked
     * explicitly -- otherwise backups would upload into a folder the user cannot see.
     */
    suspend fun folderExists(token: String, folderId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = request(
                token = token,
                method = "GET",
                url = "$API/files/$folderId?fields=id,trashed"
            )
            !JSONObject(response).optBoolean("trashed", false)
        } catch (e: BackupError.BackupFileMissing) {
            false
        } catch (e: BackupError) {
            if (e is BackupError.Server && e.detail == "HTTP 404") false else throw e
        }
    }

    /**
     * Finds a folder this app created, by name.
     *
     * Needed after a reinstall, when the saved folder id is gone. The drive.file scope
     * grants access to the OAuth client rather than the installation, so a folder created
     * by a previous install of the same app is still visible here -- which is what makes
     * "sign in and find my backup" work at all.
     */
    suspend fun findFolderByName(token: String, name: String): DriveFile? =
        withContext(Dispatchers.IO) {
            // A quote inside the name would otherwise terminate the query string.
            val escaped = name.replace("'", "\\'")
            val query = "mimeType = '$FOLDER_MIME' and name = '$escaped' and trashed = false"
            val url = "$API/files?q=${query.urlEncode()}" +
                "&fields=files(id,name,size,modifiedTime)&pageSize=10"
            val files = JSONObject(request(token, "GET", url)).optJSONArray("files")
            if (files == null || files.length() == 0) return@withContext null
            files.getJSONObject(0).toDriveFile()
        }

    /** Finds [name] inside [folderId], or null. Used to adopt a backup made on another device. */
    suspend fun findFile(token: String, folderId: String, name: String): DriveFile? =
        withContext(Dispatchers.IO) {
            val query = "'$folderId' in parents and name = '$name' and trashed = false"
            val url = "$API/files?q=${query.urlEncode()}" +
                "&fields=files(id,name,size,modifiedTime)&pageSize=1"
            val files = JSONObject(request(token, "GET", url)).optJSONArray("files")
            if (files == null || files.length() == 0) return@withContext null
            files.getJSONObject(0).toDriveFile()
        }

    suspend fun getFile(token: String, fileId: String): DriveFile? = withContext(Dispatchers.IO) {
        try {
            val url = "$API/files/$fileId?fields=id,name,size,modifiedTime,trashed"
            val o = JSONObject(request(token, "GET", url))
            if (o.optBoolean("trashed", false)) null else o.toDriveFile()
        } catch (e: BackupError.BackupFileMissing) {
            null
        }
    }

    /** Creates the backup file inside [folderId] and returns its id. */
    suspend fun createFile(
        token: String,
        folderId: String,
        name: String,
        content: ByteArray,
        mimeType: String
    ): String = withContext(Dispatchers.IO) {
        val boundary = "----financialapp${System.currentTimeMillis()}"
        val metadata = JSONObject().apply {
            put("name", name)
            put("parents", org.json.JSONArray().put(folderId))
        }
        val payload = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata.toString())
            append("\r\n--$boundary\r\n")
            append("Content-Type: $mimeType\r\n\r\n")
        }.toByteArray() + content + "\r\n--$boundary--\r\n".toByteArray()

        val response = request(
            token = token,
            method = "POST",
            url = "$UPLOAD/files?uploadType=multipart&fields=id",
            contentType = "multipart/related; boundary=$boundary",
            body = payload
        )
        JSONObject(response).getString("id")
    }

    /** Overwrites an existing file's contents, keeping its id so no duplicate appears. */
    suspend fun updateFile(
        token: String,
        fileId: String,
        content: ByteArray,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        request(
            token = token,
            method = "PATCH",
            url = "$UPLOAD/files/$fileId?uploadType=media&fields=id",
            contentType = mimeType,
            body = content
        )
        Unit
    }

    suspend fun download(token: String, fileId: String): String = withContext(Dispatchers.IO) {
        request(token, "GET", "$API/files/$fileId?alt=media")
    }

    // ------------------------------------------------------------------ http

    private fun request(
        token: String,
        method: String,
        url: String,
        contentType: String? = null,
        body: ByteArray? = null
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            // PATCH is not in HttpURLConnection's allowed set; the override header is the
            // documented way to reach it without swapping in a whole HTTP library.
            if (method == "PATCH") {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                requestMethod = method
            }
            setRequestProperty("Authorization", "Bearer $token")
            contentType?.let { setRequestProperty("Content-Type", it) }
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doInput = true
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }

        try {
            body?.let { connection.outputStream.use { out -> out.write(it) } }

            val code = connection.responseCode
            if (code in 200..299) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            }

            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
            Log.w(TAG, "$method $url -> $code: ${error?.take(300)}")
            throw BackupError.fromHttp(code, error)
        } catch (e: UnknownHostException) {
            throw BackupError.NoNetwork
        } catch (e: IOException) {
            // A dropped connection is indistinguishable from being offline here, and both
            // are worth retrying.
            throw BackupError.NoNetwork
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toDriveFile() = DriveFile(
        id = getString("id"),
        name = optString("name"),
        size = optString("size").toLongOrNull() ?: 0L,
        modifiedTime = optString("modifiedTime").takeIf { it.isNotEmpty() }
    )

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
}
