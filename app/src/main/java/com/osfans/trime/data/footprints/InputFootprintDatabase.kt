/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.footprints

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [InputFootprintEntity::class], version = 1, exportSchema = false)
abstract class InputFootprintDatabase : RoomDatabase() {
    internal abstract fun inputFootprintDao(): InputFootprintDao
}
