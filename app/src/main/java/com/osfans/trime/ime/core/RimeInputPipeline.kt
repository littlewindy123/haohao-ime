/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal sealed class RimeInputCommand(
    val sequence: Long,
    val enqueuedAtNanos: Long,
    val operation: suspend () -> Unit,
    val completion: CompletableDeferred<Unit>? = null,
) {
    class Key(
        sequence: Long,
        enqueuedAtNanos: Long,
        operation: suspend () -> Unit,
    ) : RimeInputCommand(sequence, enqueuedAtNanos, operation)

    class Barrier(
        sequence: Long,
        enqueuedAtNanos: Long,
        operation: suspend () -> Unit,
        completion: CompletableDeferred<Unit>? = null,
    ) : RimeInputCommand(sequence, enqueuedAtNanos, operation, completion)
}

internal data class TypingPerformanceSnapshot(
    val queueDepth: Int = 0,
    val maximumQueueDepth: Int = 0,
    val processedKeyCount: Int = 0,
    val lastQueueWaitMicros: Long = 0,
    val maximumQueueWaitMicros: Long = 0,
    val lastNativeProcessingMicros: Long = 0,
    val maximumNativeProcessingMicros: Long = 0,
    val lastPresentationBuildMicros: Long = 0,
    val maximumPresentationBuildMicros: Long = 0,
    val lastCandidateModelBuildMicros: Long = 0,
    val maximumCandidateModelBuildMicros: Long = 0,
    val discardedPresentationSnapshots: Int = 0,
    val staleCandidateSelections: Int = 0,
) {
    fun diagnosticText(): String = buildString {
        appendLine("Typing queue: depth=$queueDepth, max=$maximumQueueDepth, keys=$processedKeyCount")
        appendLine("Typing queue wait: last=${lastQueueWaitMicros}us, max=${maximumQueueWaitMicros}us")
        appendLine(
            "Typing native: last=${lastNativeProcessingMicros}us, max=${maximumNativeProcessingMicros}us",
        )
        appendLine(
            "Typing presentation: last=${lastPresentationBuildMicros}us, " +
                "max=${maximumPresentationBuildMicros}us, " +
                "coalesced=$discardedPresentationSnapshots, staleSelections=$staleCandidateSelections",
        )
        append(
            "Typing candidate model: last=${lastCandidateModelBuildMicros}us, " +
                "max=${maximumCandidateModelBuildMicros}us",
        )
    }.trimEnd()
}

internal object TypingPerformanceMonitor {
    private val mutableSnapshot = MutableStateFlow(TypingPerformanceSnapshot())
    val snapshot: StateFlow<TypingPerformanceSnapshot> = mutableSnapshot.asStateFlow()

    fun update(value: TypingPerformanceSnapshot) {
        mutableSnapshot.value = value
    }

    fun reset() {
        mutableSnapshot.value = TypingPerformanceSnapshot()
    }
}

internal fun isCurrentPresentation(
    expectedVersion: Long,
    currentVersion: Long,
): Boolean = expectedVersion == currentVersion

internal fun isCurrentInputSession(
    commitSessionId: Long,
    activeSessionId: Long,
): Boolean = commitSessionId == activeSessionId

/**
 * A single-consumer command pipeline for Rime input.
 *
 * Key commands are never dropped and are processed in enqueue order. Consecutive keys are allowed
 * to share one presentation refresh; barriers split batches so candidate selection, paging and
 * editor changes always observe all preceding keys.
 */
internal class RimeInputPipeline(
    scope: CoroutineScope,
    private val flushPresentation: suspend () -> Unit,
    private val onKeyQueued: () -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val commands = Channel<RimeInputCommand>(capacity = Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private val nextSequence = AtomicLong(0)
    private val pendingCount = AtomicInteger(0)
    private val maximumQueueDepth = AtomicInteger(0)
    private val processedKeyCount = AtomicInteger(0)
    private val lastQueueWaitMicros = AtomicLong(0)
    private val maximumQueueWaitMicros = AtomicLong(0)
    private val lastNativeProcessingMicros = AtomicLong(0)
    private val maximumNativeProcessingMicros = AtomicLong(0)
    private val lastPresentationBuildMicros = AtomicLong(0)
    private val maximumPresentationBuildMicros = AtomicLong(0)
    private val lastCandidateModelBuildMicros = AtomicLong(0)
    private val maximumCandidateModelBuildMicros = AtomicLong(0)
    private val discardedPresentationSnapshots = AtomicInteger(0)
    private val staleCandidateSelections = AtomicInteger(0)
    private val mutablePerformanceSnapshot = MutableStateFlow(TypingPerformanceSnapshot())
    val performanceSnapshot: StateFlow<TypingPerformanceSnapshot> = mutablePerformanceSnapshot.asStateFlow()

    private val worker: Job = scope.launch { consumeCommands() }

    fun postKey(operation: suspend () -> Unit): Boolean {
        onKeyQueued()
        return enqueue(
            RimeInputCommand.Key(
                sequence = nextSequence.incrementAndGet(),
                enqueuedAtNanos = clockNanos(),
                operation = operation,
            ),
        )
    }

    fun postBarrier(operation: suspend () -> Unit): Boolean = enqueue(
        RimeInputCommand.Barrier(
            sequence = nextSequence.incrementAndGet(),
            enqueuedAtNanos = clockNanos(),
            operation = operation,
        ),
    )

    suspend fun awaitIdle() {
        val completion = CompletableDeferred<Unit>()
        val accepted =
            enqueue(
                RimeInputCommand.Barrier(
                    sequence = nextSequence.incrementAndGet(),
                    enqueuedAtNanos = clockNanos(),
                    operation = {},
                    completion = completion,
                ),
            )
        check(accepted) { "Rime input pipeline is closed" }
        completion.await()
    }

    fun recordStaleCandidateSelection() {
        staleCandidateSelections.incrementAndGet()
        publishSnapshot()
    }

    fun recordCandidateModelBuild(elapsedNanos: Long) {
        val elapsedMicros = nanosToMicros(elapsedNanos)
        lastCandidateModelBuildMicros.set(elapsedMicros)
        maximumCandidateModelBuildMicros.updateMaximum(elapsedMicros)
        publishSnapshot()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        commands.close()
        worker.cancel()
    }

    private fun enqueue(command: RimeInputCommand): Boolean {
        val depth = pendingCount.incrementAndGet()
        maximumQueueDepth.updateMaximum(depth)
        val result = commands.trySend(command)
        if (result.isFailure) {
            pendingCount.decrementAndGet()
            command.completion?.cancel()
            publishSnapshot()
            return false
        }
        return true
    }

    private suspend fun consumeCommands() {
        var deferredCommand: RimeInputCommand? = null
        try {
            while (currentCoroutineContext().isActive) {
                val command = deferredCommand ?: commands.receiveCatching().getOrNull() ?: break
                deferredCommand = null
                if (command is RimeInputCommand.Key) {
                    var current: RimeInputCommand.Key = command
                    var batchSize = 0
                    while (true) {
                        execute(current)
                        batchSize += 1
                        when (val next = commands.tryReceive().getOrNull()) {
                            is RimeInputCommand.Key -> current = next
                            else -> {
                                deferredCommand = next
                                break
                            }
                        }
                    }
                    if (batchSize > 1) {
                        discardedPresentationSnapshots.addAndGet(batchSize - 1)
                    }
                    flushPresentationSafely()
                    publishSnapshot()
                } else {
                    execute(command)
                }
            }
        } finally {
            deferredCommand?.let(::cancelPendingCommand)
            while (true) {
                val command = commands.tryReceive().getOrNull() ?: break
                cancelPendingCommand(command)
            }
            publishSnapshot()
        }
    }

    private fun cancelPendingCommand(command: RimeInputCommand) {
        pendingCount.decrementAndGet()
        command.completion?.cancel()
    }

    private suspend fun execute(command: RimeInputCommand) {
        val startedAt = clockNanos()
        val queueWaitMicros = nanosToMicros(startedAt - command.enqueuedAtNanos)
        var failure: Throwable? = null
        try {
            command.operation()
        } catch (error: CancellationException) {
            failure = error
            throw error
        } catch (error: Throwable) {
            failure = error
            onFailure(error)
        } finally {
            val elapsedMicros = nanosToMicros(clockNanos() - startedAt)
            pendingCount.decrementAndGet()
            lastQueueWaitMicros.set(queueWaitMicros)
            maximumQueueWaitMicros.updateMaximum(queueWaitMicros)
            if (command is RimeInputCommand.Key) {
                processedKeyCount.incrementAndGet()
                lastNativeProcessingMicros.set(elapsedMicros)
                maximumNativeProcessingMicros.updateMaximum(elapsedMicros)
            } else {
                publishSnapshot()
            }
            if (failure == null) {
                command.completion?.complete(Unit)
            } else {
                command.completion?.completeExceptionally(failure)
            }
        }
    }

    private suspend fun flushPresentationSafely() {
        val startedAt = clockNanos()
        try {
            flushPresentation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onFailure(error)
        } finally {
            val elapsedMicros = nanosToMicros(clockNanos() - startedAt)
            lastPresentationBuildMicros.set(elapsedMicros)
            maximumPresentationBuildMicros.updateMaximum(elapsedMicros)
        }
    }

    private fun publishSnapshot() {
        val updated =
            TypingPerformanceSnapshot(
                queueDepth = pendingCount.get().coerceAtLeast(0),
                maximumQueueDepth = maximumQueueDepth.get(),
                processedKeyCount = processedKeyCount.get(),
                lastQueueWaitMicros = lastQueueWaitMicros.get(),
                maximumQueueWaitMicros = maximumQueueWaitMicros.get(),
                lastNativeProcessingMicros = lastNativeProcessingMicros.get(),
                maximumNativeProcessingMicros = maximumNativeProcessingMicros.get(),
                lastPresentationBuildMicros = lastPresentationBuildMicros.get(),
                maximumPresentationBuildMicros = maximumPresentationBuildMicros.get(),
                lastCandidateModelBuildMicros = lastCandidateModelBuildMicros.get(),
                maximumCandidateModelBuildMicros = maximumCandidateModelBuildMicros.get(),
                discardedPresentationSnapshots = discardedPresentationSnapshots.get(),
                staleCandidateSelections = staleCandidateSelections.get(),
            )
        mutablePerformanceSnapshot.value = updated
        TypingPerformanceMonitor.update(updated)
    }

    private fun nanosToMicros(value: Long): Long = value.coerceAtLeast(0) / NANOS_PER_MICROSECOND

    private companion object {
        const val NANOS_PER_MICROSECOND = 1_000L
    }
}

private fun AtomicInteger.updateMaximum(candidate: Int) {
    var previous = get()
    while (candidate > previous && !compareAndSet(previous, candidate)) {
        previous = get()
    }
}

private fun AtomicLong.updateMaximum(candidate: Long) {
    var previous = get()
    while (candidate > previous && !compareAndSet(previous, candidate)) {
        previous = get()
    }
}
