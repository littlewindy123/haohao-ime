/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Commit metadata captured when a command is queued, without moving its editor hooks off main. */
internal data class RimeCommitContext(
    val inputSessionId: Long,
    val sentence: CommitSentence?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RimeCommitContext>
}

internal suspend fun <T> withRimeCommitContext(
    inputSessionId: Long,
    sentence: CommitSentence?,
    block: suspend () -> T,
): T = withContext(RimeCommitContext(inputSessionId, sentence)) { block() }

/**
 * Apply command metadata in the same engine dispatch as the native operation. A later presentation
 * flush runs outside the command scope and must retain the last native operation's metadata.
 */
internal suspend inline fun <T> runOnRimeDispatcher(
    dispatcher: CoroutineDispatcher,
    crossinline applyCommitContext: (RimeCommitContext) -> Unit,
    crossinline block: suspend () -> T,
): T = withContext(dispatcher) {
    coroutineContext[RimeCommitContext]?.let { applyCommitContext(it) }
    block()
}
