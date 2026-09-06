/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.DEFAULT_THEME_ID
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.main.footprints.WordLearningActivity
import com.osfans.trime.util.navigateWithAnim
import splitties.dimensions.dp

class HaoHaoHomeFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View = HaoHaoHomeView(
        requireContext(),
        navigate = { findNavController().navigateWithAnim(it) },
        tryKeyboard = { (requireActivity() as MainActivity).showTestInputPanel() },
        review = { WordLearningActivity.openReview(requireContext(), daily = true) },
        selectPalette = { id ->
            val theme = ThemeManager.activeThemeOrNull
            val scheme = theme?.colorSchemes?.find { it.id == id }
            if (ThemeManager.prefs.selectedTheme.getValue() == DEFAULT_THEME_ID && scheme != null) {
                ColorManager.setColorScheme(scheme)
                true
            } else {
                findNavController().navigateWithAnim(NavigationRoute.Theme)
                false
            }
        },
        selectedPalette = ThemeManager.prefs.normalModeColor.getValue(),
    ).apply {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}

/** Release-facing home: four useful destinations, a real theme chooser, no engine diagnostics. */
internal class HaoHaoHomeView(
    context: Context,
    navigate: (NavigationRoute) -> Unit,
    tryKeyboard: () -> Unit,
    review: () -> Unit,
    selectPalette: (String) -> Boolean,
    selectedPalette: String,
) : ScrollView(context) {
    private val ink = color(R.color.haohao_cocoa)
    private val secondary = color(R.color.haohao_cocoa_secondary)
    private val surface = color(R.color.haohao_surface)
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun rounded(fill: Int, radius: Int = 20, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(2), it) }
    }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun text(res: Int, size: Float = 16f, bold: Boolean = false) = TextView(context).apply {
        setText(res)
        textSize = size
        setTextColor(ink)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
    }
    private fun clickable(view: View, action: () -> Unit) {
        view.isClickable = true
        view.isFocusable = true
        view.setOnClickListener { action() }
    }

    init {
        isFillViewport = true
        clipToPadding = false
        setBackgroundColor(color(R.color.haohao_page_background))
        val content = column().apply { setPadding(dp(20), dp(12), dp(20), dp(24)) }
        addView(content, ViewGroup.LayoutParams(-1, -2))
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text(R.string.app_name_release, 26f, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_haohao_more_vert_24)
                imageTintList = ColorStateList.valueOf(ink)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                contentDescription = context.getString(R.string.home_settings)
                clickable(this) { navigate(NavigationRoute.AllSettings) }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        content.addView(heading)
        val wide = resources.configuration.screenWidthDp >= 600
        val primary = if (wide) column() else content
        val secondaryColumn = if (wide) column() else content
        if (wide) {
            content.addView(
                LinearLayout(context).apply {
                    addView(primary, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
                    addView(secondaryColumn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }

        val hero = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(color(R.color.haohao_brand_header), 24)
            setPadding(dp(20), dp(20), dp(4), dp(20))
            minimumHeight = dp(156)
        }
        val heroWords = column()
        heroWords.addView(
            text(R.string.trime_app_slogan, 19f, true).apply {
                setTextColor(color(R.color.haohao_header_foreground))
                setLineSpacing(dp(3).toFloat(), 1f)
            },
        )
        heroWords.addView(
            text(R.string.home_try, 15f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(color(R.color.haohao_on_honey))
                minHeight = dp(48)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = rounded(color(R.color.haohao_honey), 14)
                clickable(this, tryKeyboard)
            },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) },
        )
        hero.addView(heroWords, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.haohao_golden_foreground)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(dp(110), dp(124)),
        )
        primary.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        val learning = LinearLayout(context)
        fun learningCard(title: Int, icon: Int, fill: Int, action: () -> Unit): View = column().apply {
            background = rounded(fill)
            setPadding(dp(16), dp(18), dp(16), dp(18))
            addView(
                ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(ink)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(26), dp(26)),
            )
            addView(text(title, 17f, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            contentDescription = context.getString(title)
            clickable(this, action)
        }
        learning.addView(learningCard(R.string.home_words, R.drawable.ic_baseline_book_24, surface) { navigate(NavigationRoute.InputFootprints) }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(6) })
        learning.addView(learningCard(R.string.home_review, R.drawable.ic_baseline_star_24, color(R.color.haohao_selection_surface), review), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(6) })
        primary.addView(learning, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val themesTitle = text(R.string.home_theme, 18f, true).apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
        }
        clickable(themesTitle) { navigate(NavigationRoute.Theme) }
        secondaryColumn.addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(themesTitle, LinearLayout.LayoutParams(0, -2, 1f))
                addView(
                    text(R.string.home_all_themes, 13f).apply {
                        gravity = Gravity.CENTER
                        minHeight = dp(48)
                        setPadding(dp(12), 0, dp(4), 0)
                        clickable(this) { navigate(NavigationRoute.Theme) }
                    },
                    LinearLayout.LayoutParams(-2, -2),
                )
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) },
        )
        val palettes = listOf(
            HomePalette("default", R.string.home_mint, 0xffe7e2d8.toInt(), 0xffdcece2.toInt()),
            HomePalette("haohao_mist", R.string.home_mist, 0xffe0e7ee.toInt(), 0xffcfdfeb.toInt()),
            HomePalette("haohao_apricot", R.string.home_apricot, 0xffeaded3.toInt(), 0xffefd4bf.toInt()),
            HomePalette("haohao_graphite", R.string.home_graphite, 0xffdfe1e3.toInt(), 0xffcbd0d5.toInt()),
        )
        val paletteRow = LinearLayout(context)
        val cards = mutableListOf<Pair<HomePalette, View>>()
        fun highlight(id: String) {
            cards.forEach { (palette, card) ->
                val selected = id == palette.id || id == "${palette.id}_dark" || palette.id == "default" && id == "haohao_dark"
                card.isSelected = selected
                card.background = rounded(surface, 14, if (selected) color(R.color.haohao_cocoa) else null)
                card.contentDescription = context.getString(if (selected) R.string.home_theme_selected else R.string.home_theme_select, context.getString(palette.title))
            }
        }
        palettes.forEach { palette ->
            val card = column().apply {
                setPadding(dp(5), dp(5), dp(5), dp(5))
                addView(ThemeThumbnail(context, palette), LinearLayout.LayoutParams(-1, dp(68)))
                addView(
                    text(palette.title, 13f).apply {
                        gravity = Gravity.CENTER
                        minHeight = dp(40)
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
                clickable(this) { if (selectPalette(palette.id)) highlight(palette.id) }
            }
            cards.add(palette to card)
            paletteRow.addView(card, LinearLayout.LayoutParams(dp(104), -2).apply { marginEnd = dp(8) })
        }
        highlight(selectedPalette)
        secondaryColumn.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(paletteRow)
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        val settings = column().apply {
            background = rounded(surface)
            setPadding(0, dp(4), 0, dp(4))
        }
        fun destination(title: Int, icon: Int, route: NavigationRoute) {
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(12), dp(8))
                minimumHeight = dp(64)
            }
            row.addView(
                ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(secondary)
                },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) },
            )
            row.addView(text(title), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_baseline_arrow_right_24)
                    imageTintList = ColorStateList.valueOf(secondary)
                },
                LinearLayout.LayoutParams(dp(24), dp(24)),
            )
            clickable(row) { navigate(route) }
            settings.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        destination(R.string.home_input_preferences, R.drawable.ic_baseline_keyboard_24, NavigationRoute.InputPreferences)
        destination(R.string.home_translation, R.drawable.ic_haohao_translate_24, NavigationRoute.LanguageSettings)
        destination(R.string.ime_common_phrases, R.drawable.ic_clipboard_24, NavigationRoute.CommonPhrases)
        destination(R.string.home_settings, R.drawable.ic_baseline_tune_24, NavigationRoute.AllSettings)
        secondaryColumn.addView(settings, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
    }
}

internal data class HomePalette(val id: String, val title: Int, val background: Int, val function: Int)

private class ThemeThumbnail(context: Context, private val palette: HomePalette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = palette.background
        canvas.drawRoundRect(0f, 0f, w, h, dp(9).toFloat(), dp(9).toFloat(), paint)
        val gap = w * .025f
        val keyW = (w - gap * 9) / 8
        val keyH = (h - gap * 5) / 4
        for (row in 0..2) {
            for (col in 0..7) {
                val left = gap + col * (keyW + gap)
                val top = gap + row * (keyH + gap)
                paint.color = if (row == 2 && (col == 0 || col == 7)) palette.function else Color.WHITE
                canvas.drawRoundRect(left, top, left + keyW, top + keyH, gap, gap, paint)
            }
        }
        paint.color = palette.function
        canvas.drawRoundRect(gap, gap + 3 * (keyH + gap), w - gap, h - gap, gap, gap, paint)
    }
}
