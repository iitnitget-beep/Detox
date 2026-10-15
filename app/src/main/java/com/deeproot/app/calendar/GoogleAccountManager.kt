package com.deeproot.app.calendar

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.deeproot.app.R
import com.deeproot.app.data.auth.AuthRepository
import com.deeproot.app.data.auth.LinkResult
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Result of asking Google for a Calendar access token. */
sealed interface CalendarAuthorization {
    data class Granted(val accessToken: String) : CalendarAuthorization

    /** The user must see Google's consent / account screen (launch it with an IntentSenderRequest). */
    data class NeedsConsent(val pendingIntent: PendingIntent) : CalendarAuthorization

    data class Failed(val message: String) : CalendarAuthorization
}

/**
 * Google identity for the app:
 * - "Sign in with Google" via Credential Manager, linked to the anonymous Firebase user (keeps all progress);
 * - Calendar authorization (calendar.readonly) via Google Identity Services AuthorizationClient.
 *
 * The OAuth web client id comes from google-services.json (R.string.default_web_client_id) — nothing is hard-coded.
 */
@Singleton
class GoogleAccountManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
) {

    /** Shows the Google account picker and links the chosen account to the current Firebase user. */
    suspend fun signInWithGoogle(activity: Activity): LinkResult {
        val option = GetSignInWithGoogleOption.Builder(context.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = CredentialManager.create(activity).getCredential(activity, request)
        val credential = response.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            return auth.linkOrSignIn(GoogleAuthProvider.getCredential(google.idToken, null))
        }
        throw IllegalStateException("Unexpected credential type: ${credential.type}")
    }

    /**
     * Asks for calendar.readonly. Returns the token directly when it was granted before (works in the background);
     * otherwise a PendingIntent for the consent screen (only usable from an Activity).
     */
    suspend fun authorizeCalendar(activity: Activity? = null): CalendarAuthorization = try {
        val client = if (activity != null) Identity.getAuthorizationClient(activity) else Identity.getAuthorizationClient(context)
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(CALENDAR_READONLY))).build()
        val result = client.authorize(request).await()
        val pendingIntent = result.pendingIntent
        val token = result.accessToken
        when {
            result.hasResolution() && pendingIntent != null -> CalendarAuthorization.NeedsConsent(pendingIntent)
            token != null -> CalendarAuthorization.Granted(token)
            else -> CalendarAuthorization.Failed("Google did not return an access token")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Calendar authorization failed", e)
        CalendarAuthorization.Failed(e.message ?: "Google authorization failed")
    }

    /** Reads the token after the consent screen returned. */
    fun tokenFromConsentResult(data: Intent?): String? = try {
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
    } catch (e: Exception) {
        Log.w(TAG, "Consent was not granted", e)
        null
    }

    /** Drops a cached access token that the Calendar API rejected (401) so the next request gets a fresh one. */
    suspend fun clearToken(token: String) {
        withContext(Dispatchers.IO) {
            runCatching { GoogleAuthUtil.clearToken(context, token) }.onFailure { Log.w(TAG, "clearToken failed", it) }
        }
    }

    companion object {
        private const val TAG = "GoogleAccountManager"
        const val CALENDAR_READONLY = "https://www.googleapis.com/auth/calendar.readonly"
    }
}
