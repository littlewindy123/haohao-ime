package com.osfans.trime.ui.main.footprints

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.osfans.trime.R
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.footprints.LearningBackup
import com.osfans.trime.data.footprints.LearningBackupCodec
import com.osfans.trime.data.footprints.LearningBackupStore
import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Explicit document access; selected files are never executed or used as database paths. */
class LearningBackupActivity : AppCompatActivity() {
    class Operation : ViewModel() {
        internal var imported: LearningBackup? = null
        var busy = false
        var message = 0
        var exportReady = false
        val changes = MutableStateFlow(0L)
    }
    private val operation by lazy { ViewModelProvider(this)[Operation::class.java] }
    private val backup by lazy { LearningBackupStore(InputFootprints.store, InputFootprints.rollbackFile(this)) }
    private val exportFile get() = File(cacheDir, "learning-export.json")
    private val importFile get() = File(cacheDir, "learning-import.json")
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var imported: LearningBackup?
        get() = operation.imported
        set(value) {
            operation.imported = value
        }
    private var busy: Boolean
        get() = operation.busy
        set(value) {
            operation.busy = value
        }
    private var message: Int
        get() = operation.message
        set(value) {
            operation.message = value
        }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            exportFile.delete()
        } else {
            launchTask(R.string.learning_backup_export_failed) {
                withContext(Dispatchers.IO) {
                    require(exportFile.isFile)
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { output ->
                        exportFile.inputStream().use { it.copyTo(output) }
                        output.flush()
                    }
                    exportFile.delete()
                }
                message = R.string.learning_backup_exported
            }
        }
    }
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            launchTask {
                imported = null
                importFile.delete()
                imported = withContext(Dispatchers.IO) {
                    val data = requireNotNull(contentResolver.openInputStream(uri)).use(LearningBackupCodec::read)
                    importFile.writeBytes(LearningBackupCodec.encode(data))
                    data
                }
                message = 0
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            when (AppPrefs.defaultInstance().advanced.uiMode.getValue()) {
                AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.haohao_page_background))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this) { if (!busy) finish() }
        if (!InputFootprints.isAvailable) {
            finish()
            return
        }
        lifecycleScope.launch {
            operation.changes.collect {
                render()
                if (operation.exportReady && !busy) {
                    operation.exportReady = false
                    export.launch("haohao-study-${SimpleDateFormat("yyyyMMdd-HHmm",Locale.ROOT).format(Date())}.json")
                }
            }
        }
        if (!busy && imported == null && savedInstanceState?.getBoolean("preview") == true && importFile.isFile) {
            launchTask {
                imported = withContext(Dispatchers.IO) { importFile.inputStream().use(LearningBackupCodec::read) }
            }
        } else {
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("preview", imported != null)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) {
            importFile.delete()
            exportFile.delete()
        }
        super.onDestroy()
    }

    private fun render() {
        root.removeAllViews()
        val toolbar = Toolbar(this).apply {
            setTitle(R.string.learning_backup)
            setTitleTextColor(color(R.color.haohao_cocoa))
            navigationIcon = DrawerArrowDrawable(this@LearningBackupActivity).apply {
                progress = 1f
                color = color(R.color.haohao_cocoa)
            }
            navigationContentDescription = getString(R.string.words_back)
            setNavigationOnClickListener { if (!busy) finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(28))
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        label(getString(R.string.learning_backup_note), 17f)
        label(getString(R.string.learning_backup_limit), 14f)
        if (message != 0) label(getString(message), 18f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        if (busy) {
            label(getString(R.string.learning_backup_working), 18f)
            return
        }
        button(R.string.learning_backup_export) {
            launchTask(R.string.learning_backup_export_failed) {
                withContext(Dispatchers.IO) { exportFile.writeBytes(LearningBackupCodec.encode(backup.snapshot())) }
                operation.exportReady = true
            }
        }
        button(R.string.learning_backup_import) { pick.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
        imported?.let { data ->
            label(getString(R.string.learning_backup_preview), 24f)
            label(getString(R.string.learning_backup_summary, DateFormat.getDateTimeInstance().format(Date(data.exportedAt)), data.words.size, data.sentences.size, data.events.size, data.practiceEvents.size), 16f)
            label(getString(R.string.wordbooks_backup_counts, data.books.size, data.memberships.size), 16f)
            button(R.string.learning_backup_full) { confirm(R.string.learning_backup_full, R.string.learning_backup_full_note) { restore(data, false) } }
            button(R.string.learning_backup_merge) { confirm(R.string.learning_backup_merge, R.string.learning_backup_merge_note) { restore(data, true) } }
        }
        if (backup.hasRollback()) {
            button(R.string.learning_backup_rollback) {
                confirm(R.string.learning_backup_rollback, R.string.learning_backup_rollback_note) {
                    launchTask {
                        withContext(Dispatchers.IO) { backup.rollBack() }
                        message = R.string.learning_backup_restored
                        imported = null
                        importFile.delete()
                    }
                }
            }
        }
    }

    private fun restore(data: LearningBackup, merge: Boolean) = launchTask {
        withContext(Dispatchers.IO) { backup.restore(data, merge) }
        imported = null
        importFile.delete()
        message = if (merge) R.string.learning_backup_merged else R.string.learning_backup_restored
    }

    private fun confirm(title: Int, text: Int, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(text).setNegativeButton(R.string.learning_backup_cancel, null)
            .setPositiveButton(R.string.learning_backup_confirm) { _, _ -> action() }.show()
    }

    private fun launchTask(error: Int = R.string.learning_backup_failed, task: suspend () -> Unit) {
        if (busy) return
        busy = true
        render()
        operation.viewModelScope.launch {
            try {
                task()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = when (e.message) {
                    "backup_too_large" -> R.string.learning_backup_too_large
                    "backup_version" -> R.string.learning_backup_version
                    else -> error
                }
            } finally {
                busy = false
                operation.changes.value++
            }
        }
    }

    private fun label(text: String, size: Float): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color(R.color.haohao_cocoa))
        setLineSpacing(dp(3).toFloat(), 1f)
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }
    private fun button(title: Int, action: () -> Unit) {
        val view = AppCompatButton(this).apply {
            setText(title)
            isAllCaps = false
            textSize = 16f
            minHeight = dp(56)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(color(R.color.haohao_cocoa))
            setBackgroundResource(R.drawable.haohao_segment_background)
            setOnClickListener { if (!busy) action() }
        }
        content.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
