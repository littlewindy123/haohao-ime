/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhrasePersistenceTest {
    @Test
    fun phrasesSurviveReopenAndEmitEditsAndDeletesWithoutChangingTheSchema() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "phrase-verification-${System.nanoTime()}.db"
        var database = Room.databaseBuilder(context, Database::class.java, name).build()
        try {
            val id = database.databaseDao().insert(DatabaseBean(text = "Fixed test phrase"))
            val bean = requireNotNull(database.databaseDao().get(id))
            database.close()
            database = Room.databaseBuilder(context, Database::class.java, name).build()
            val dao = database.databaseDao()
            assertEquals("Fixed test phrase", dao.observeBeans().first().single().text)
            dao.updateText(bean.id, "Edited test phrase")
            assertEquals("Edited test phrase", dao.observeBeans().first().single().text)
            dao.updatePinned(bean.id, true)
            dao.insert(DatabaseBean(text = "Unpinned test phrase"))
            dao.deleteAllUnpinned()
            assertEquals(bean.id, dao.observeBeans().first().single().id)
            dao.delete(bean.id)
            assertTrue(dao.observeBeans().first().isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
