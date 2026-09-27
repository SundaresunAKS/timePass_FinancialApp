package com.timepass.financialapp.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.timepass.financialapp.R

/**
 * The optional lock in front of the app and its figures.
 *
 * Wraps [BiometricPrompt] so the two callers -- unlocking the app at launch, and revealing
 * the dashboard totals -- do not each repeat the executor, the prompt builder and the
 * result plumbing.
 *
 * DEVICE_CREDENTIAL sits alongside BIOMETRIC_WEAK deliberately: a phone with no sensor, or
 * one whose owner has not enrolled a fingerprint, can still use the PIN or pattern. Without
 * it the setting would simply be unavailable on those devices.
 */
object AppLock {

    private const val ALLOWED =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Why the lock cannot be turned on, or null when it can. */
    fun unavailableReason(context: Context): Int? =
        when (BiometricManager.from(context).canAuthenticate(ALLOWED)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> R.string.biometric_none_enrolled
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> R.string.biometric_no_hardware
            else -> R.string.biometric_unavailable
        }

    fun isAvailable(context: Context): Boolean = unavailableReason(context) == null

    /**
     * Shows the system prompt.
     *
     * @param onFailed runs when the user cancelled or the system refused; a single wrong
     *   finger does not end the attempt, so this is only the final outcome.
     */
    fun prompt(
        activity: FragmentActivity,
        titleRes: Int,
        subtitleRes: Int,
        onSuccess: () -> Unit,
        onFailed: (CharSequence?) -> Unit = {}
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    onFailed(message)
                }
            }
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(titleRes))
                .setSubtitle(activity.getString(subtitleRes))
                // No negative button is allowed once DEVICE_CREDENTIAL is in the set; the
                // prompt supplies its own "Use PIN" affordance instead.
                .setAllowedAuthenticators(ALLOWED)
                .build()
        )
    }
}
