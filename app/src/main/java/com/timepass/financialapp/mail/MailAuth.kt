package com.timepass.financialapp.mail

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.timepass.financialapp.backup.BackupError
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Read-only Gmail authorization, following the same AuthorizationClient flow as DriveAuth.
 *
 * Asked for separately from Drive rather than folded into the backup consent: reading
 * someone's mail is a much bigger ask than writing one file to their Drive, and a user who
 * only wants backups should never see it.
 */
class MailAuth(context: Context) {

    companion object {
        const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    }

    sealed class AuthState {
        data class Authorized(val accessToken: String, val email: String?) : AuthState()
        data class NeedsConsent(val pendingIntent: PendingIntent) : AuthState()
    }

    private val client = Identity.getAuthorizationClient(context)

    private val request: AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GMAIL_READONLY_SCOPE), Scope("email")))
            .build()

    suspend fun authorize(): AuthState = suspendCancellableCoroutine { cont ->
        client.authorize(request)
            .addOnSuccessListener { cont.resume(it.toState()) }
            .addOnFailureListener { cont.resumeWithException(BackupError.Unknown(it.message)) }
    }

    fun resultFromIntent(data: Intent?): AuthState =
        client.getAuthorizationResultFromIntent(data).toState()

    /** A token without showing UI, for background syncs; null means consent is needed. */
    suspend fun silentToken(): String? = runCatching {
        when (val state = authorize()) {
            is AuthState.Authorized -> state.accessToken
            is AuthState.NeedsConsent -> null
        }
    }.getOrNull()

    private fun AuthorizationResult.toState(): AuthState {
        val resolution = pendingIntent
        return if (hasResolution() && resolution != null) {
            AuthState.NeedsConsent(resolution)
        } else {
            val token = accessToken ?: throw BackupError.NeedsReauth
            AuthState.Authorized(token, toGoogleSignInAccount()?.email)
        }
    }
}
