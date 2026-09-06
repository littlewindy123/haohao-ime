/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.osfans.trime.R
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import splitties.dimensions.dp

class CommonPhrasesFragment : Fragment() {
    private var list: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.haohao_page_background))
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        val entries = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        list = entries
        root.addView(ScrollView(context).apply { addView(entries) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(
            label(R.string.ime_add_phrase).apply {
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.haohao_preview_badge_background)
                isEnabled = CollectionHelper.isAvailable
                setOnClickListener { AppUtils.launchClipEdit(context, -1, ClipEditActivity.FROM_COLLECTION) }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        return root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        if (!CollectionHelper.isAvailable) {
            list?.addView(label(R.string.ime_clip_unavailable))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CollectionHelper.observeBeans().catch {
                    list?.removeAllViews()
                    list?.addView(label(R.string.ime_clip_unavailable))
                }.collect { phrases ->
                    val entries = list ?: return@collect
                    entries.removeAllViews()
                    if (phrases.isEmpty()) entries.addView(label(R.string.ime_phrase_empty))
                    phrases.forEach { phrase ->
                        val row = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
                        row.addView(
                            label(R.string.ime_common_phrases).apply {
                                text = phrase.text
                                setOnClickListener { AppUtils.launchClipEdit(context, phrase.id, ClipEditActivity.FROM_COLLECTION) }
                            },
                            LinearLayout.LayoutParams(0, -2, 1f),
                        )
                        row.addView(
                            android.widget.ImageButton(requireContext()).apply {
                                setImageResource(R.drawable.ic_haohao_more_vert_24)
                                imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.haohao_cocoa))
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                contentDescription = getString(R.string.ime_item_actions)
                                setOnClickListener { anchor ->
                                    PopupMenu(context, anchor).apply {
                                        menu.add(R.string.edit).setOnMenuItemClickListener {
                                            AppUtils.launchClipEdit(context, phrase.id, ClipEditActivity.FROM_COLLECTION)
                                            true
                                        }
                                        menu.add(R.string.delete).setOnMenuItemClickListener {
                                            AlertDialog.Builder(requireContext()).setMessage(R.string.ime_phrase_delete_prompt)
                                                .setNegativeButton(R.string.cancel, null)
                                                .setPositiveButton(R.string.delete) { _, _ -> viewLifecycleOwner.lifecycleScope.launch { CollectionHelper.delete(phrase.id) } }
                                                .show()
                                            true
                                        }
                                        show()
                                    }
                                }
                            },
                            LinearLayout.LayoutParams(requireContext().dp(48), requireContext().dp(48)),
                        )
                        entries.addView(row)
                    }
                }
            }
        }
    }

    private fun label(res: Int) = TextView(requireContext()).apply {
        setText(res)
        setTextColor(ContextCompat.getColor(context, R.color.haohao_cocoa))
        textSize = 16f
        minHeight = dp(56)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(14), dp(12), dp(14))
    }

    override fun onDestroyView() {
        list = null
        super.onDestroyView()
    }
}
