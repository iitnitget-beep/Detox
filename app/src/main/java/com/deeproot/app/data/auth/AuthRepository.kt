package com.deeproot.app.data.auth

import android.util.Log
import com.deeproot.app.di.ApplicationScope
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class AuthUser(
    val uid: String,
    val isAnonymous: Boolean,
    val email: String?,
    val displayName: String?,
)

/** Outcome of linking a Google account to the current (anonymous) user. */
sealed interface LinkResult {
    /** Same uid, now permanent. */
    data class Linked(val user: AuthUser) : LinkResult

    /** The Google account already had a Lecture Tracker user; we switched to it (uid changed). */
    data class SwitchedAccount(val user: AuthUser) : LinkResult
}

/**
 * Firebase Auth wrapper. Every install starts with an anonymous account so the app works immediately; the user can
 * later link Google (Settings → Account) to keep their data across reinstalls and devices.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    @ApplicationScope scope: CoroutineScope,
) {
    private val signInMutex = Mutex()

    val user: StateFlow<AuthUser?> = callbackFlow {
        // The ID-token listener also fires when an anonymous account is linked to Google (auth-state does not).
        val listener = FirebaseAuth.IdTokenListener { trySend(it.currentUser?.toAuthUser()) }
        auth.addIdTokenListener(listener)
        awaitClose { auth.removeIdTokenListener(listener) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, auth.currentUser?.toAuthUser())

    val currentUid: String? get() = auth.currentUser?.uid

    val currentUser: AuthUser? get() = auth.currentUser?.toAuthUser()

    /** Returns the current uid, signing in anonymously first if needed. Requires network on first launch. */
    suspend fun ensureSignedIn(): String = signInMutex.withLock {
        auth.currentUser?.uid ?: run {
            val result = auth.signInAnonymously().await()
            val uid = result.user?.uid ?: throw IllegalStateException("Anonymous sign-in returned no user")
            Log.i(TAG, "Signed in anonymously")
            uid
        }
    }

    /**
     * Upgrades the anonymous account with a Google credential. If that Google account is already linked to another
     * Firebase user, signs in to that user instead (the caller re-uploads local data under the new uid).
     */
    suspend fun linkOrSignIn(credential: AuthCredential): LinkResult {
        val current = auth.currentUser
        if (current == null) {
            val user = auth.signInWithCredential(credential).await().user ?: throw IllegalStateException("No user")
            return LinkResult.SwitchedAccount(user.toAuthUser())
        }
        return try {
            val user = current.linkWithCredential(credential).await().user ?: current
            user.reload().await()
            LinkResult.Linked((auth.currentUser ?: user).toAuthUser())
        } catch (e: FirebaseAuthUserCollisionException) {
            val user = auth.signInWithCredential(e.updatedCredential ?: credential).await().user
                ?: throw IllegalStateException("No user after collision sign-in")
            LinkResult.SwitchedAccount(user.toAuthUser())
        }
    }

    private fun FirebaseUser.toAuthUser(): AuthUser {
        val googleProfile = providerData.firstOrNull { it.providerId == "google.com" }
        return AuthUser(
            uid = uid,
            isAnonymous = isAnonymous,
            email = email ?: googleProfile?.email,
            displayName = displayName ?: googleProfile?.displayName,
        )
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
