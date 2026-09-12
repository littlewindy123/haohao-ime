/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.os.Bundle
import android.text.Editable
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EditorConnectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun autofillBeforeThemeInitializationDoesNotCrash() = onMain {
        // Run this suite with the system IME selected, before starting the regression IME.
        assertFalse("Use a fresh test process without an active regression IME", ThemeManager.isInitialized)
        val preference = AppPrefs.defaultInstance().general.inlineSuggestions
        val previous = preference.getValue()
        preference.setValue(true)
        try {
            val editor = EditText(instrumentation.targetContext)
            assertNull(service(connection(editor)).onCreateInlineSuggestionsRequest(Bundle()))
        } finally {
            preference.setValue(previous)
        }
    }

    @Test
    fun clearingPartialExtractedSelectionUsesDocumentCoordinates() = onMain {
        val editor = EditText(instrumentation.targetContext)
        editor.setText("x".repeat(100) + "甲，乙。尾")
        editor.setSelection(101, 103)
        val connection = extractedConnection(editor, "甲，乙。尾", 1, 3)
        service(connection).clearTextSelection()
        assertEquals(103, editor.selectionStart)
        assertEquals(103, editor.selectionEnd)
    }

    @Test
    fun sectionNavigationHandlesPartialDocumentsAndTextBoundaries() = onMain {
        val preference = AppPrefs.defaultInstance().keyboard.hookCtrlLR
        val previous = preference.getValue()
        preference.setValue(true)
        try {
            for ((text, start, right, left) in listOf(
                NavigationCase("甲，乙。尾", 2, 3, 1),
                NavigationCase("甲，乙。尾", 1, 3, 0),
                NavigationCase("甲，乙。尾", 5, 5, 3),
                NavigationCase("plain", 2, 5, 0),
                NavigationCase("plain", 0, 5, 0),
                NavigationCase("", 0, 0, 0),
            )) {
                val editor = EditText(instrumentation.targetContext)
                editor.setText("x".repeat(100) + text)
                val service = service(extractedConnection(editor, text, start, start))
                assertEquals(true, service.hookKeyboard(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON))
                assertEquals(100 + right, editor.selectionEnd)
                assertEquals(true, service.hookKeyboard(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON))
                assertEquals(100 + left, editor.selectionEnd)
            }
        } finally {
            preference.setValue(previous)
        }
    }

    @Test
    fun rejectedCompositionDoesNotTurnTheNextEmptySnapshotIntoADeletion() = onMain {
        val editor = EditText(instrumentation.targetContext)
        editor.setText("keep selected text")
        editor.selectAll()
        var updates = 0
        val connection = object : BaseInputConnection(editor, true) {
            override fun getEditable(): Editable = editor.editableText
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                updates++
                return if (updates == 1) false else super.setComposingText(text, newCursorPosition)
            }
        }
        val service = service(connection)
        service.updateComposingText("rejected")
        service.updateComposingText("")
        assertEquals("keep selected text", editor.text.toString())
        assertEquals(1, updates)
    }

    @Test
    fun replacingASelectionPreservesItsPrefixIncludingSurrogatePairs() = onMain {
        for (reverse in listOf(false, true)) {
            val editor = EditText(instrumentation.targetContext)
            editor.setText("前🙂旧词后")
            if (reverse) editor.setSelection(5, 3) else editor.setSelection(3, 5)
            val connection = connection(editor)
            val service = service(connection)
            service.updateComposingText("n")
            assertEquals("前🙂n后", editor.text.toString())
            service.updateComposingText("ni")
            assertEquals("前🙂ni后", editor.text.toString())
            connection.commitText("你", 1)
            service.updateComposingText("")
            assertEquals("前🙂你后", editor.text.toString())
        }
    }

    @Test
    fun replacingWholeTextAndCancellingCompositionKeepSurroundingText() = onMain {
        val editor = EditText(instrumentation.targetContext)
        editor.setText("旧词")
        editor.selectAll()
        val service = service(connection(editor))
        service.updateComposingText("new")
        assertEquals("new", editor.text.toString())
        service.updateComposingText("")
        assertEquals("", editor.text.toString())
        editor.setText("前后")
        editor.setSelection(1)
        service.updateComposingText("draft")
        service.updateComposingText("")
        assertEquals("前后", editor.text.toString())
    }

    @Test
    fun emptySnapshotDoesNotReplaceAnUnrelatedSelection() = onMain {
        val editor = EditText(instrumentation.targetContext)
        editor.setText("前旧词后")
        editor.setSelection(1, 3)
        service(connection(editor)).updateComposingText("")
        assertEquals("前旧词后", editor.text.toString())
        assertEquals(1, editor.selectionStart)
        assertEquals(3, editor.selectionEnd)
    }

    @Test
    fun failedEditorUpdateAlwaysEndsItsBatch() = onMain {
        val editor = EditText(instrumentation.targetContext)
        var batches = 0
        val connection = object : BaseInputConnection(editor, true) {
            override fun beginBatchEdit(): Boolean {
                batches++
                return true
            }
            override fun endBatchEdit(): Boolean {
                batches--
                return true
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = throw IllegalStateException("synthetic editor failure")
        }
        assertThrows(IllegalStateException::class.java) { service(connection).updateComposingText("draft") }
        assertEquals(0, batches)
    }

    private fun connection(editor: EditText): InputConnection = object : BaseInputConnection(editor, true) {
        override fun getEditable(): Editable = editor.editableText
    }

    private data class NavigationCase(val text: String, val start: Int, val right: Int, val left: Int)

    private fun extractedConnection(editor: EditText, text: String, start: Int, end: Int): InputConnection = object : BaseInputConnection(editor, true) {
        override fun getEditable(): Editable = editor.editableText
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = ExtractedText().apply {
            this.text = text
            startOffset = 100
            selectionStart = start
            selectionEnd = end
        }
    }

    private fun service(connection: InputConnection) = object : TrimeInputMethodService() {
        override fun getCurrentInputConnection(): InputConnection = connection
    }

    private fun onMain(block: () -> Unit) {
        check(instrumentation.targetContext.packageName.endsWith(".regression"))
        instrumentation.runOnMainSync(block)
    }
}
