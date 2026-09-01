/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** A single-consumer, unbounded commit stream; unlike UI state, committed text may not conflate. */
internal class LosslessRimeCommitFlow {
    private val commits = Channel<RimeCommitEvent>(capacity = Channel.UNLIMITED)
    val flow: Flow<RimeCommitEvent> = commits.receiveAsFlow()

    fun publish(
        commit: CommitProto,
        inputSessionId: Long,
    ): Boolean = commit.text.isNullOrEmpty() ||
        commits.trySend(RimeCommitEvent(commit, inputSessionId)).isSuccess
}
