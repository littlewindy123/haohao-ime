/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.footprints

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = InputFootprintEntity.TABLE_NAME)
data class InputFootprintEntity(
    @PrimaryKey val text: String,
    val lastUsedAt: Long? = null,
    val useCount: Int = 0,
    val favorite: Boolean = false,
    val favoritedAt: Long? = null,
) {
    companion object {
        const val TABLE_NAME = "input_footprints"
    }
}
