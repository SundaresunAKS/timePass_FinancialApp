package com.timepass.financialapp.backup

import com.timepass.financialapp.R

/**
 * Every way a backup or restore can fail, with the message the user sees.
 *
 * Modelled as exceptions rather than a result type because the failure can surface from
 * deep inside the network or parsing layer, and each case needs a different recovery: sign
 * in again, free up Drive space, pick a new folder, retry later.
 */
sealed class BackupError(
    val messageRes: Int,
    val detail: String? = null,
    /** True when retrying later is likely to succeed, which WorkManager acts on. */
    val retryable: Boolean = false
) : Exception(detail) {

    object NoNetwork : BackupError(R.string.backup_err_no_network, retryable = true)

    /** Token expired or revoked; the user must re-authorise. */
    object NeedsReauth : BackupError(R.string.backup_err_needs_reauth)

    object NotSignedIn : BackupError(R.string.backup_err_not_signed_in)

    object DriveStorageFull : BackupError(R.string.backup_err_storage_full)

    /** 403 for reasons other than quota, e.g. the scope was not granted. */
    object PermissionDenied : BackupError(R.string.backup_err_permission)

    /** 429 or a 403 userRateLimitExceeded. */
    object RateLimited : BackupError(R.string.backup_err_rate_limited, retryable = true)

    object FolderMissing : BackupError(R.string.backup_err_folder_missing)

    object BackupFileMissing : BackupError(R.string.backup_err_file_missing)

    class CorruptBackup(reason: String) :
        BackupError(R.string.backup_err_corrupt, reason)

    class Server(code: Int) :
        BackupError(R.string.backup_err_server, "HTTP $code", retryable = code >= 500)

    class Unknown(reason: String?) : BackupError(R.string.backup_err_unknown, reason)

    companion object {
        /**
         * Maps a Drive HTTP failure onto a case. Drive reports quota exhaustion and rate
         * limiting with the same 403 status, distinguished only by the reason string.
         */
        fun fromHttp(code: Int, body: String?): BackupError {
            val reason = body.orEmpty()
            return when {
                code == 401 -> NeedsReauth
                code == 403 && reason.contains("storageQuotaExceeded", true) -> DriveStorageFull
                code == 403 && reason.contains("RateLimit", true) -> RateLimited
                code == 403 && reason.contains("userRateLimitExceeded", true) -> RateLimited
                code == 403 -> PermissionDenied
                code == 404 -> BackupFileMissing
                code == 429 -> RateLimited
                else -> Server(code)
            }
        }
    }
}
