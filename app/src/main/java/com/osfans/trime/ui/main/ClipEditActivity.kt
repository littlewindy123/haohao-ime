/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import com.osfans.trime.R
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.databinding.ActivityClipEditBinding
import com.osfans.trime.util.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import splitties.systemservices.inputMethodManager
import timber.log.Timber

class ClipEditActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private var beanId: Int = -1
    private lateinit var editText: EditText
    private var clipType: String? = null
    private lateinit var saveButton: Button
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes.gravity = Gravity.TOP
        val binding =
            ActivityClipEditBinding.inflate(layoutInflater).apply {
                editText = clipEditText
                saveButton = clipEditOk
                saveButton.isEnabled = false
                clipEditCancel.setOnClickListener { finish() }
                clipEditOk.setOnClickListener { finishEditing() }
            }
        setContentView(binding.root)
        inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        processIntent(intent)
    }

    private fun finishEditing() {
        val str = editText.editableText.toString()
        if (str.isBlank()) {
            editText.error = getString(R.string.ime_phrase_required)
            return
        }
        if (saving || clipType == null) return
        saving = true
        saveButton.isEnabled = false
        scope.launch {
            try {
                when (clipType) {
                    FROM_CLIPBOARD -> ClipboardHelper.updateText(beanId, str)
                    FROM_COLLECTION -> if (beanId < 0) {
                        CollectionHelper.insert(DatabaseBean(text = str))
                    } else {
                        CollectionHelper.updateText(beanId, str)
                    }
                }
                finish()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                toast(R.string.ime_save_failed)
                saving = false
                saveButton.isEnabled = true
            }
        }
    }

    private fun setBean(bean: DatabaseBean) {
        beanId = bean.id
        editText.setText(bean.text)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        scope.launch {
            intent.run {
                val clipType = intent.getStringExtra(CLIP_TYPE) ?: return@launch
                val beanId = getIntExtra(BEAN_ID, -1)
                this@ClipEditActivity.clipType = clipType
                if (clipType == FROM_COLLECTION && beanId < 0 && CollectionHelper.isAvailable) {
                    this@ClipEditActivity.beanId = -1
                    editText.setHint(R.string.ime_phrase_hint)
                    saveButton.isEnabled = true
                    return@launch
                }
                when (clipType) {
                    FROM_CLIPBOARD -> ClipboardHelper.get(beanId)
                    FROM_COLLECTION -> CollectionHelper.get(beanId)
                    else -> null
                }?.also {
                    this@ClipEditActivity.clipType = clipType
                    setBean(it)
                    saveButton.isEnabled = true
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val BEAN_ID = "id"
        const val CLIP_TYPE = "clip_type"
        const val FROM_CLIPBOARD = "from_clipboard"
        const val FROM_COLLECTION = "from_collection"
    }
}
