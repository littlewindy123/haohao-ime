/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.unrolled

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.daemon.RimeSession
import timber.log.Timber

data class UnrolledCandidateItem(
    val globalIndex: Int,
    val candidate: CandidateProto,
    val presentationVersion: Long = 0L,
)

private fun Int.isRareCjkCodePoint(): Boolean = this in 0x3400..0x4DBF ||
    this in 0x20000..0x2A6DF ||
    this in 0x2A700..0x2B73F ||
    this in 0x2B740..0x2B81F ||
    this in 0x2B820..0x2CEAF ||
    this in 0x2CEB0..0x2EBEF ||
    this in 0x2EBF0..0x2EE5F ||
    this in 0x30000..0x3134F ||
    this in 0x31350..0x323AF ||
    this in 0x323B0..0x3347F ||
    this in 0xF900..0xFAFF ||
    this in 0x2F800..0x2FA1F

private fun String.containsRareCjkCodePoint(): Boolean {
    var offset = 0
    while (offset < length) {
        val codePoint = codePointAt(offset)
        if (codePoint.isRareCjkCodePoint()) return true
        offset += Character.charCount(codePoint)
    }
    return false
}

internal fun Array<CandidateProto>.toDisplayableUnrolledCandidates(
    startIndex: Int,
    presentationVersion: Long = 0L,
): List<UnrolledCandidateItem> = mapIndexedNotNull { relativeIndex, candidate ->
    candidate
        .takeUnless { it.text.containsRareCjkCodePoint() }
        ?.let { UnrolledCandidateItem(startIndex + relativeIndex, it, presentationVersion) }
}

class CandidatesPagingSource(
    val rime: RimeSession,
    val total: Int,
    val offset: Int,
    val presentationVersion: Long = 0L,
) : PagingSource<Int, UnrolledCandidateItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, UnrolledCandidateItem> {
        // use candidate index for key, null means load from beginning (including offset)
        val startIndex = params.key ?: offset
        val pageSize = params.loadSize
        Timber.d("getCandidates(offset=$startIndex, limit=$pageSize)")
        val candidates =
            rime.runOnReady {
                getCandidates(startIndex, pageSize)
            }
        val displayableCandidates =
            candidates.toDisplayableUnrolledCandidates(startIndex, presentationVersion)
        val prevKey = if (startIndex >= pageSize) startIndex - pageSize else null
        val nextKey = if (total > 0) {
            if (startIndex + pageSize + 1 >= total) null else startIndex + pageSize
        } else {
            if (candidates.size < pageSize) null else startIndex + pageSize
        }
        return LoadResult.Page(displayableCandidates, prevKey, nextKey)
    }

    // always reload from beginning
    override fun getRefreshKey(state: PagingState<Int, UnrolledCandidateItem>) = null
}
