package com.deeproot.app.ui.components

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/** App-wide one-shot messages (e.g. "Completed · 1h 52m studied" after leaving the Focus screen). */
@Singleton
class MessageCenter @Inject constructor() {
    private val channel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = channel.receiveAsFlow()

    fun post(message: String) {
        channel.trySend(message)
    }
}
