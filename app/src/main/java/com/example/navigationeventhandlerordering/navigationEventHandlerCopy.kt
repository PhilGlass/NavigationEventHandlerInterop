package com.example.navigationeventhandlerordering

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventCallback
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

@Composable
internal fun NavigationEventHandler(
    enabled: () -> Boolean = { true },
    onBack: suspend (progress: Flow<NavigationEvent>) -> Unit
) {
    // ensure we don't re-register callbacks when onBack changes
    val currentOnBack by rememberUpdatedState(onBack)
    val navEventScope = rememberCoroutineScope()

    val navEventCallBack = remember {
        NavigationEventHandlerCallback(enabled, navEventScope, currentOnBack)
    }

    // we want to use the same callback, but ensure we adjust the variable on recomposition
    SideEffect {
        navEventCallBack.currentOnBack = currentOnBack
        navEventCallBack.onBackScope = navEventScope
    }

    LaunchedEffect(enabled) { navEventCallBack.setIsEnabled(enabled()) }

    val navEventDispatcher =
        checkNotNull(LocalView.current.findViewTreeNavigationEventDispatcherOwner())
            .navigationEventDispatcher

    DisposableEffect(navEventDispatcher) {
        navEventDispatcher.addCallback(navEventCallBack)

        onDispose { navEventCallBack.remove() }
    }
}

private class OnBackInstance(
    scope: CoroutineScope,
    var isPredictiveBack: Boolean,
    onBack: suspend (progress: Flow<NavigationEvent>) -> Unit,
    callback: NavigationEventCallback
) {
    val channel =
        Channel<NavigationEvent>(capacity = BUFFERED, onBufferOverflow = BufferOverflow.SUSPEND)
    val job =
        scope.launch {
            if (callback.isEnabled) {
                var completed = false
                onBack(channel.consumeAsFlow().onCompletion { completed = true })
                check(completed) { "You must collect the progress flow" }
            }
        }

    fun send(backEvent: NavigationEvent) = channel.trySend(backEvent)

    // idempotent if invoked more than once
    fun close() = channel.close()

    fun cancel() {
        channel.cancel(CancellationException("navEvent cancelled"))
        job.cancel()
    }
}

private class NavigationEventHandlerCallback(
    enabled: () -> Boolean,
    var onBackScope: CoroutineScope,
    var currentOnBack: suspend (progress: Flow<NavigationEvent>) -> Unit,
) : NavigationEventCallback(enabled()) {
    private var onBackInstance: OnBackInstance? = null
    private var isActive = false

    fun setIsEnabled(enabled: Boolean) {
        // We are disabling a callback that was enabled.
        if (!enabled && !isActive && isEnabled) {
            onBackInstance?.cancel()
        }
        isEnabled = enabled
    }

    override fun onEventStarted(event: NavigationEvent) {
        // in case the previous onBackInstance was started by a normal back gesture
        // we want to make sure it's still cancelled before we start a predictive
        // back gesture
        onBackInstance?.cancel()
        if (isEnabled) {
            onBackInstance = OnBackInstance(onBackScope, true, currentOnBack, this)
        }
        isActive = true
    }

    override fun onEventProgressed(event: NavigationEvent) {
        onBackInstance?.send(event)
    }

    override fun onEventCompleted() {
        // handleOnBackPressed could be called by regular back to restart
        // a new back instance. If this is the case (where current back instance
        // was NOT started by handleOnBackStarted) then we need to reset the previous
        // regular back.
        onBackInstance?.apply {
            if (!isPredictiveBack) {
                cancel()
                onBackInstance = null
            }
        }
        if (onBackInstance == null) {
            onBackInstance = OnBackInstance(onBackScope, false, currentOnBack, this)
        }

        // finally, we close the channel to ensure no more events can be sent
        // but let the job complete normally
        onBackInstance?.close()
        onBackInstance?.isPredictiveBack = false
        isActive = false
    }

    override fun onEventCancelled() {
        // cancel will purge the channel of any sent events that are yet to be received
        onBackInstance?.cancel()
        onBackInstance?.isPredictiveBack = false
        isActive = false
    }
}
