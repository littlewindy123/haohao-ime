/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.bilingual

import com.osfans.trime.util.appContext
import timber.log.Timber
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal fun interface CandidateTranslationRepository {
    fun lookup(text: String): String?
}

internal class BinaryCandidateTranslationRepository private constructor(
    source: ByteBuffer,
    private val entryCount: Int,
    private val indexOffset: Int,
    private val dataOffset: Int,
) : CandidateTranslationRepository {
    private val buffer = source.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)

    override fun lookup(text: String): String? {
        val query = text.encodeToByteArray()
        var low = 0
        var high = entryCount - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val comparison = compareKey(middle, query)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return readValue(middle)
            }
        }
        return null
    }

    private fun compareKey(
        entryIndex: Int,
        query: ByteArray,
    ): Int {
        val recordOffset = indexOffset + entryIndex * INDEX_RECORD_SIZE
        val keyOffset = buffer.getInt(recordOffset)
        val keyLength = buffer.getInt(recordOffset + Int.SIZE_BYTES)
        val commonLength = minOf(keyLength, query.size)
        for (index in 0 until commonLength) {
            val dictionaryByte = buffer.get(dataOffset + keyOffset + index).toInt() and 0xff
            val queryByte = query[index].toInt() and 0xff
            val comparison = dictionaryByte.compareTo(queryByte)
            if (comparison != 0) return comparison
        }
        return keyLength.compareTo(query.size)
    }

    private fun readValue(entryIndex: Int): String {
        val recordOffset = indexOffset + entryIndex * INDEX_RECORD_SIZE
        val valueOffset = buffer.getInt(recordOffset + Int.SIZE_BYTES * 2)
        val valueLength = buffer.getInt(recordOffset + Int.SIZE_BYTES * 3)
        val bytes = ByteArray(valueLength)
        val valueBuffer = buffer.duplicate()
        valueBuffer.position(dataOffset + valueOffset)
        valueBuffer.get(bytes)
        return bytes.decodeToString()
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val EXPECTED_RELEASE = "2026-08-24"
        private const val FIXED_HEADER_SIZE = 28
        private const val INDEX_RECORD_SIZE = 16
        private val MAGIC = byteArrayOf('H'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte(), 0)

        fun load(
            onFailure: (Throwable) -> Unit = {},
            bufferProvider: () -> ByteBuffer,
        ): CandidateTranslationRepository = runCatching {
            create(bufferProvider())
        }.getOrElse { error ->
            onFailure(error)
            CandidateTranslationRepository { null }
        }

        private fun create(source: ByteBuffer): BinaryCandidateTranslationRepository {
            val buffer = source.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.limit() >= FIXED_HEADER_SIZE) { "Dictionary header is truncated" }
            val magic = ByteArray(MAGIC.size).also(buffer::get)
            require(magic.contentEquals(MAGIC)) { "Unsupported dictionary magic" }
            require(buffer.int == FORMAT_VERSION) { "Unsupported dictionary format version" }
            val releaseLength = buffer.int
            val entryCount = buffer.int
            val indexOffset = buffer.int
            val dataOffset = buffer.int
            require(releaseLength in 1..256) { "Invalid release length" }
            require(entryCount >= 0) { "Invalid entry count" }
            require(indexOffset == FIXED_HEADER_SIZE + releaseLength) { "Invalid index offset" }
            require(dataOffset.toLong() == indexOffset.toLong() + entryCount.toLong() * INDEX_RECORD_SIZE) {
                "Invalid data offset"
            }
            require(dataOffset in indexOffset..buffer.limit()) { "Dictionary index exceeds file bounds" }
            val releaseBytes = ByteArray(releaseLength).also(buffer::get)
            require(releaseBytes.decodeToString() == EXPECTED_RELEASE) { "Unexpected dictionary release" }
            validateRecords(buffer, entryCount, indexOffset, dataOffset)
            return BinaryCandidateTranslationRepository(buffer, entryCount, indexOffset, dataOffset)
        }

        private fun validateRecords(
            buffer: ByteBuffer,
            entryCount: Int,
            indexOffset: Int,
            dataOffset: Int,
        ) {
            var previousRecordOffset = -1
            repeat(entryCount) { index ->
                val recordOffset = indexOffset + index * INDEX_RECORD_SIZE
                val keyOffset = buffer.getInt(recordOffset)
                val keyLength = buffer.getInt(recordOffset + Int.SIZE_BYTES)
                val valueOffset = buffer.getInt(recordOffset + Int.SIZE_BYTES * 2)
                val valueLength = buffer.getInt(recordOffset + Int.SIZE_BYTES * 3)
                requireRange(buffer, dataOffset, keyOffset, keyLength)
                requireRange(buffer, dataOffset, valueOffset, valueLength)
                if (previousRecordOffset >= 0) {
                    require(compareStoredKeys(buffer, previousRecordOffset, recordOffset, dataOffset) < 0) {
                        "Dictionary keys are not strictly sorted"
                    }
                }
                previousRecordOffset = recordOffset
            }
        }

        private fun requireRange(
            buffer: ByteBuffer,
            dataOffset: Int,
            offset: Int,
            length: Int,
        ) {
            require(offset >= 0 && length >= 0) { "Negative dictionary offset or length" }
            val end = dataOffset.toLong() + offset.toLong() + length.toLong()
            require(end <= buffer.limit()) { "Dictionary record exceeds file bounds" }
        }

        private fun compareStoredKeys(
            buffer: ByteBuffer,
            leftRecordOffset: Int,
            rightRecordOffset: Int,
            dataOffset: Int,
        ): Int {
            val leftOffset = buffer.getInt(leftRecordOffset)
            val leftLength = buffer.getInt(leftRecordOffset + Int.SIZE_BYTES)
            val rightOffset = buffer.getInt(rightRecordOffset)
            val rightLength = buffer.getInt(rightRecordOffset + Int.SIZE_BYTES)
            val commonLength = minOf(leftLength, rightLength)
            for (index in 0 until commonLength) {
                val left = buffer.get(dataOffset + leftOffset + index).toInt() and 0xff
                val right = buffer.get(dataOffset + rightOffset + index).toInt() and 0xff
                val comparison = left.compareTo(right)
                if (comparison != 0) return comparison
            }
            return leftLength.compareTo(rightLength)
        }
    }
}

internal object OfflineCandidateTranslationRepository : CandidateTranslationRepository {
    private const val ASSET_PATH = "bilingual_zh_en.hhdict"

    private val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BinaryCandidateTranslationRepository.load(
            bufferProvider = {
                appContext.assets.openFd(ASSET_PATH).use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                        channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            descriptor.startOffset,
                            descriptor.length,
                        )
                    }
                }
            },
            onFailure = { error ->
                Timber.e(error, "Failed to map offline bilingual candidate dictionary")
            },
        )
    }

    override fun lookup(text: String): String? = delegate.lookup(text)
}
