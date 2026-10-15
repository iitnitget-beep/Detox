package com.deeproot.app.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Called by repositories after every local write. Requests an immediate in-process push (fast path while the app is
 * running) and enqueues a [SyncWorker] so the change is uploaded even if the process is killed right away.
 */
@Singleton
class SyncTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    fun requestPush() {
        _requests.tryEmit(Unit)
        SyncWorker.enqueue(context)
    }
}
