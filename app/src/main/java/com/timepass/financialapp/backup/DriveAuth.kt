package com.timepass.financialapp.backup

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google account selection and Drive scope authorization.
 *
 * ## Why AuthorizationClient rather than Credential Manager
 *
 * Credential Manager solves *authentication* -- proving who the user is, returning an ID
 * token. It does not grant OAuth scopes, so it cannot by itself produce a token that the
 * Drive API will accept. Getting `drive.file` requires an *authorization* flow, and
 * [AuthorizationClient] is the current, non-deprecated API for that; it replaces the
 * scope-requesting half of the old GoogleSignInClient.
 *
 * It also shows the accounts already on the phone and returns the chosen one, which is the
 * behaviour the account picker was wanted for. Pairing it with Credential Manager would add
 * a second consent screen and would additionally require a **web** OAuth client id, on top
 * of the Android one Drive already needs.
 */
class DriveAuth(private val context: Context) {

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }

    /**
     * Either a usable token, or an intent the caller must launch to let the user pick an
     * account and consent.
     */
    sealed class AuthState {
        data class Authorized(val accessToken: String, val email: String?) : AuthState()
        data class NeedsConsent(val pendingIntent: PendingIntent) : AuthState()
    }

    private val client = Identity.getAuthorizationClient(context)

    private val request: AuthorizationRequest =
        AuthorizationRequest.builder()
            // "email" and "profile" are requested purely so the drawer and the backup
            // screen can name the linked account; drive.file on its own returns neither.
            .setRequestedScopes(
                listOf(Scope(DRIVE_FILE_SCOPE), Scope("email"), Scope("profile"))
            )
            .build()

    /**
     * Asks for the Drive scope. Returns [AuthState.Authorized] outright when consent was
     * already granted, which is the path every automatic backup takes.
     */
    suspend fun authorize(): AuthState = suspendCancellableCoroutine { cont ->
        client.authorize(request)
            .addOnSuccessListener { result ->
                cont.resume(result.toState())
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(BackupError.Unknown(e.message))
            }
    }

    /** Completes the flow after the consent intent returns. */
    fun resultFromIntent(data: android.content.Intent?): AuthState {
        val result: AuthorizationResult = client.getAuthorizationResultFromIntent(data)
        return result.toState()
    }

    /**
     * A token without ever showing UI, for background work. Null means the user must
     * re-authorise interactively.
     */
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
            val token = accessToken
                ?: throw BackupError.NeedsReauth
            AuthState.Authorized(token, toGoogleSignInAccount()?.email)
        }
    }

    /**
     * Forgets the granted scope. Drive data is untouched -- this only disconnects the app,
     * matching what the user expects from "sign out".
     */
    suspend fun signOut() = suspendCancellableCoroutine<Unit> { cont ->
        Identity.getSignInClient(context).signOut()
            .addOnCompleteListener { cont.resume(Unit) }
    }
}
