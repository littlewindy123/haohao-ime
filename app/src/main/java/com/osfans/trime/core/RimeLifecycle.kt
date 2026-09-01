/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// Adapted from https://github.com/fcitx5-android/fcitx5-android/blob/364afb44dcf0d9e3db3d43a21a32601b2190cbdf/app/src/main/java/org/fcitx/fcitx5/android/core/FcitxLifecycle.kt
package com.osfans.trime.core

import androidx.annotation.StringRes
import com.osfans.trime.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

class RimeLifecycleRegistry : RimeLifecycle {

    private val observers = ConcurrentLinkedQueue<RimeLifecycleObserver>()

    override fun addObserver(observer: RimeLifecycleObserver) {
        observers.add(observer)
    }

    override fun removeObserver(observer: RimeLifecycleObserver) {
        observers.remove(observer)
    }

    override val currentState: RimeLifecycle.State
        get() = internalState

    private var internalState = RimeLifecycle.State.STOPPED

    override val lifecycleScope: CoroutineScope = RimeLifecycleScope(this)

    @Synchronized
    fun emitState(state: RimeLifecycle.State) {
        when (state) {
            RimeLifecycle.State.STARTING -> {
                checkAtState(RimeLifecycle.State.STOPPED)
                internalState = RimeLifecycle.State.STARTING
            }
            RimeLifecycle.State.READY -> {
                checkAtState(RimeLifecycle.State.STARTING)
                internalState = RimeLifecycle.State.READY
            }
            RimeLifecycle.State.STOPPING -> {
                check(internalState == RimeLifecycle.State.STARTING || internalState == RimeLifecycle.State.READY) {
                    "Currently not at STARTING or READY! Actual state is $internalState"
                }
                internalState = RimeLifecycle.State.STOPPING
            }
            RimeLifecycle.State.STOPPED -> {
                checkAtState(RimeLifecycle.State.STOPPING)
                internalState = RimeLifecycle.State.STOPPED
            }
        }
        notifyObservers(state)
    }

    @Synchronized
    fun emitStartupFailed() {
        checkAtState(RimeLifecycle.State.STARTING)
        internalState = RimeLifecycle.State.STOPPED
        notifyObservers(RimeLifecycle.State.STOPPED)
    }

    private fun notifyObservers(state: RimeLifecycle.State) {
        observers.forEach { observer ->
            runCatching { observer.onChanged(state) }
                .onFailure { Timber.e(it, "Rime lifecycle observer failed at %s", state) }
        }
    }

    private fun checkAtState(state: RimeLifecycle.State) = takeIf { (internalState == state) }
        ?: throw IllegalStateException("Currently not at $state! Actual state is $internalState")
}

enum class RimeRuntimeState {
    PREPARING,
    READY,
    FAILED,
}

internal enum class RimePreparationPhase {
    DATA_SYNCHRONIZATION,
    NATIVE_INITIALIZATION,
    MAINTENANCE_DEPLOYMENT,
    SCHEMA_ACTIVATION,
    THEME_INITIALIZATION,
}

@get:StringRes
internal val RimePreparationPhase.statusTextRes: Int
    get() =
        when (this) {
            RimePreparationPhase.DATA_SYNCHRONIZATION -> R.string.rime_runtime_phase_data
            RimePreparationPhase.NATIVE_INITIALIZATION -> R.string.rime_runtime_phase_native
            RimePreparationPhase.MAINTENANCE_DEPLOYMENT -> R.string.rime_runtime_phase_maintenance
            RimePreparationPhase.SCHEMA_ACTIVATION -> R.string.rime_runtime_phase_schema
            RimePreparationPhase.THEME_INITIALIZATION -> R.string.rime_runtime_phase_theme
        }

internal enum class RimeFailureCode {
    INSUFFICIENT_SPACE,
    DATA_CORRUPTION,
    NATIVE_STARTUP,
    MAINTENANCE_FAILURE,
    MAINTENANCE_TIMEOUT,
    SCHEMA_MISSING,
    THEME_FAILURE,
    UNKNOWN,
    ;

    val retryable: Boolean
        get() = this in setOf(NATIVE_STARTUP, MAINTENANCE_FAILURE, MAINTENANCE_TIMEOUT, THEME_FAILURE)
}

internal data class RimeRuntimeSnapshot(
    val attemptId: Long = 0L,
    val phase: RimePreparationPhase = RimePreparationPhase.DATA_SYNCHRONIZATION,
    val startedAtMillis: Long = 0L,
    val elapsedMillis: Long = 0L,
    val autoRetryCount: Int = 0,
    val failureCode: RimeFailureCode? = null,
    val failureMessage: String? = null,
    val phaseDurationsMillis: Map<RimePreparationPhase, Long> = emptyMap(),
)

internal enum class RimeRecoveryAction {
    IGNORE,
    RETRY,
    STOP,
}

internal class RimePreparationController(
    private val maxAutoRetries: Int = 1,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableRuntimeState = MutableStateFlow(RimeRuntimeState.PREPARING)
    val runtimeState: StateFlow<RimeRuntimeState> = mutableRuntimeState.asStateFlow()

    private val mutableSnapshot = MutableStateFlow(RimeRuntimeSnapshot())
    val snapshot: StateFlow<RimeRuntimeSnapshot> = mutableSnapshot.asStateFlow()

    private var attemptSequence = 0L
    private var retryCount = 0
    private var phaseStartedAtMillis = 0L
    private val phaseDurations = linkedMapOf<RimePreparationPhase, Long>()

    @Synchronized
    fun beginAttempt(autoRetry: Boolean): Long {
        retryCount = if (autoRetry) (retryCount + 1).coerceAtMost(maxAutoRetries) else 0
        attemptSequence += 1L
        val now = clock()
        phaseStartedAtMillis = now
        phaseDurations.clear()
        mutableRuntimeState.value = RimeRuntimeState.PREPARING
        mutableSnapshot.value =
            RimeRuntimeSnapshot(
                attemptId = attemptSequence,
                phase = RimePreparationPhase.DATA_SYNCHRONIZATION,
                startedAtMillis = now,
                autoRetryCount = retryCount,
            )
        return attemptSequence
    }

    @Synchronized
    fun invalidateCurrentAttempt(): Long {
        retryCount = 0
        attemptSequence += 1L
        val now = clock()
        phaseStartedAtMillis = now
        phaseDurations.clear()
        mutableRuntimeState.value = RimeRuntimeState.PREPARING
        mutableSnapshot.value =
            RimeRuntimeSnapshot(
                attemptId = attemptSequence,
                phase = RimePreparationPhase.DATA_SYNCHRONIZATION,
                startedAtMillis = now,
            )
        return attemptSequence
    }

    @Synchronized
    fun advance(
        attemptId: Long,
        phase: RimePreparationPhase,
    ): Boolean {
        if (!isCurrent(attemptId) || mutableRuntimeState.value == RimeRuntimeState.FAILED) return false
        if (mutableSnapshot.value.phase == phase) return true
        val now = clock()
        recordCurrentPhase(now)
        phaseStartedAtMillis = now
        mutableSnapshot.value =
            mutableSnapshot.value.copy(
                phase = phase,
                elapsedMillis = elapsed(now),
                failureCode = null,
                failureMessage = null,
                phaseDurationsMillis = phaseDurations.toMap(),
            )
        return true
    }

    @Synchronized
    fun markReady(attemptId: Long): Boolean {
        if (!isCurrent(attemptId) || mutableRuntimeState.value == RimeRuntimeState.FAILED) return false
        val now = clock()
        recordCurrentPhase(now)
        mutableRuntimeState.value = RimeRuntimeState.READY
        mutableSnapshot.value =
            mutableSnapshot.value.copy(
                elapsedMillis = elapsed(now),
                failureCode = null,
                failureMessage = null,
                phaseDurationsMillis = phaseDurations.toMap(),
            )
        return true
    }

    @Synchronized
    fun finishCurrentPhase(attemptId: Long): Boolean {
        if (!isCurrent(attemptId) || mutableRuntimeState.value == RimeRuntimeState.FAILED) return false
        val now = clock()
        recordCurrentPhase(now)
        phaseStartedAtMillis = now
        mutableSnapshot.value =
            mutableSnapshot.value.copy(
                elapsedMillis = elapsed(now),
                phaseDurationsMillis = phaseDurations.toMap(),
            )
        return true
    }

    @Synchronized
    fun fail(
        attemptId: Long,
        code: RimeFailureCode,
        message: String,
    ): RimeRecoveryAction {
        if (!isCurrent(attemptId) || mutableRuntimeState.value == RimeRuntimeState.FAILED) {
            return RimeRecoveryAction.IGNORE
        }
        val now = clock()
        recordCurrentPhase(now)
        mutableRuntimeState.value = RimeRuntimeState.FAILED
        mutableSnapshot.value =
            mutableSnapshot.value.copy(
                elapsedMillis = elapsed(now),
                failureCode = code,
                failureMessage = message,
                phaseDurationsMillis = phaseDurations.toMap(),
            )
        return if (code.retryable && retryCount < maxAutoRetries) {
            RimeRecoveryAction.RETRY
        } else {
            RimeRecoveryAction.STOP
        }
    }

    @Synchronized
    fun isCurrent(attemptId: Long): Boolean = attemptId != 0L && mutableSnapshot.value.attemptId == attemptId

    @Synchronized
    fun isPreparing(attemptId: Long): Boolean = isCurrent(attemptId) && mutableRuntimeState.value == RimeRuntimeState.PREPARING

    private fun recordCurrentPhase(now: Long) {
        val phase = mutableSnapshot.value.phase
        phaseDurations[phase] = (phaseDurations[phase] ?: 0L) + (now - phaseStartedAtMillis).coerceAtLeast(0L)
    }

    private fun elapsed(now: Long): Long = (now - mutableSnapshot.value.startedAtMillis).coerceAtLeast(0L)
}

class RimeUnavailableException(
    message: String,
) : IllegalStateException(message)

interface RimeLifecycle {
    val currentState: State
    val lifecycleScope: CoroutineScope

    fun addObserver(observer: RimeLifecycleObserver)
    fun removeObserver(observer: RimeLifecycleObserver)

    enum class State {
        STARTING,
        READY,
        STOPPING,
        STOPPED,
    }
}

interface RimeLifecycleOwner {
    val lifecycle: RimeLifecycle
}

val RimeLifecycleOwner.lifecycleScope get() = lifecycle.lifecycleScope

fun interface RimeLifecycleObserver {
    fun onChanged(value: RimeLifecycle.State)
}

class RimeLifecycleScope(
    val lifecycle: RimeLifecycle,
    override val coroutineContext: CoroutineContext = SupervisorJob(),
) : CoroutineScope,
    RimeLifecycleObserver {
    init {
        lifecycle.addObserver(this)
    }

    override fun onChanged(value: RimeLifecycle.State) {
        if (lifecycle.currentState >= RimeLifecycle.State.STOPPING) {
            coroutineContext.cancelChildren()
        }
    }
}

suspend fun <T> RimeLifecycle.whenAtState(
    state: RimeLifecycle.State,
    block: suspend CoroutineScope.() -> T,
): T = if (state == currentState) {
    block(lifecycleScope)
} else {
    StateDelegate(this, state).run(block)
}

suspend inline fun <T> RimeLifecycle.whenReady(
    noinline block: suspend CoroutineScope.() -> T,
) = whenAtState(RimeLifecycle.State.READY, block)

private class StateDelegate(
    val lifecycle: RimeLifecycle,
    val state: RimeLifecycle.State,
) {
    private val observer = RimeLifecycleObserver {
        if (lifecycle.currentState == state) {
            continuation?.resume(Unit)
        }
    }

    init {
        lifecycle.addObserver(observer)
    }

    private var continuation: Continuation<Unit>? = null

    suspend fun <T> run(block: suspend CoroutineScope.() -> T): T {
        suspendCancellableCoroutine { continuation = it }
        lifecycle.removeObserver(observer)
        return block(lifecycle.lifecycleScope)
    }
}
