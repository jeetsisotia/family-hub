package com.familyhub.app.core.security

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Fingerprint / face / screen-lock checks for the app lock and for viewing ID numbers. */
object AppLock {
    private const val PREFS = "family_hub_security"
    private const val KEY_LOCK = "app_lock"

    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCK, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOCK, on).apply()
    }

    /** True when the phone has a fingerprint, face unlock, PIN, pattern or password set up. */
    fun canUse(context: Context): Boolean {
        val ok = try {
            BiometricManager.from(context).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Exception) {
            false
        }
        if (ok) return true
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceSecure == true
    }

    /**
     * Shows the phone's fingerprint / screen-lock prompt. If the phone has no lock at all,
     * there is nothing to check against, so [onSuccess] runs straight away.
     */
    fun authenticate(
        context: Context,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFail: (String) -> Unit
    ) {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            onFail("Could not open the fingerprint prompt.")
            return
        }
        if (!canUse(activity)) {
            onSuccess()
            return
        }
        try {
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        onFail(errString.toString())
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(AUTH)
                .build()
            prompt.authenticate(info)
        } catch (e: Exception) {
            onFail(e.message ?: "Fingerprint check is not available.")
        }
    }
}

fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c != null) {
        if (c is FragmentActivity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}
