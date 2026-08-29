// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.setup

import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.osfans.trime.R
import com.osfans.trime.databinding.ActivitySetupBinding
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.util.appContext
import com.osfans.trime.util.createNotificationChannel
import com.osfans.trime.util.toast
import splitties.systemservices.notificationManager

class SetupActivity : FragmentActivity() {
    private lateinit var binding: ActivitySetupBinding
    private lateinit var viewPager: ViewPager2
    private var lastKnownDone = BooleanArray(SetupPage.entries.size)
    private var statePollAttempts = 0
    private val statePollRunnable = object : Runnable {
        override fun run() {
            if (!::binding.isInitialized || isFinishing) return
            syncCurrentStepAndAdvance()
            statePollAttempts += 1
            if (SetupPage.hasUndonePage() && statePollAttempts < STATE_POLL_MAX_ATTEMPTS) {
                binding.root.postDelayed(this, STATE_POLL_INTERVAL_MILLIS)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "setup"
        private const val NOTIFY_ID = 87463
        private const val STATE_POLL_INTERVAL_MILLIS = 200L
        private const val STATE_POLL_MAX_ATTEMPTS = 15

        fun shouldSetup() = SetupPage.hasUndonePage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySetupBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val sysBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(
                sysBars.left,
                sysBars.top,
                sysBars.right,
                sysBars.bottom,
            )
            windowInsets
        }
        setContentView(binding.root)
        setupSystemBars()
        setupSkipAction()

        viewPager = binding.viewpager
        viewPager.adapter = Adapter()
        viewPager.isUserInputEnabled = false
        viewPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    binding.progressText.text =
                        getString(
                            R.string.setup__progress,
                            SetupFlow.progressStep(position),
                            SetupPage.entries.size,
                        )
                    currentFragment()?.sync()
                }
            },
        )

        val doneStates = readDoneStates()
        lastKnownDone = doneStates.toBooleanArray()
        viewPager.currentItem = SetupFlow.firstUndoneIndex(doneStates) ?: SetupPage.entries.lastIndex
        binding.progressText.text =
            getString(
                R.string.setup__progress,
                SetupFlow.progressStep(viewPager.currentItem),
                SetupPage.entries.size,
            )

        createNotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.setup_channel),
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::binding.isInitialized) {
            scheduleStateSync()
        }
    }

    override fun onPause() {
        if (SetupPage.hasUndonePage()) {
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_trime_status)
                .setContentTitle(getText(R.string.trime_app_name))
                .setContentText(getText(R.string.setup__notify_hint))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, javaClass),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setAutoCancel(true)
                .build()
                .let { notificationManager.notify(NOTIFY_ID, it) }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        notificationManager.cancel(NOTIFY_ID)
        if (::binding.isInitialized) {
            scheduleStateSync()
        }
    }

    internal fun startTyping() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SHOW_TEST_INPUT, true),
        )
        finish()
    }

    internal fun showEnableStep() {
        viewPager.setCurrentItem(SetupPage.Enable.ordinal, true)
        toast(R.string.setup__enable_before_select)
    }

    private fun setupSkipAction() {
        binding.skipButton.setOnClickListener {
            val dialog =
                AlertDialog
                    .Builder(this)
                    .setIcon(R.mipmap.ic_app_icon)
                    .setTitle(R.string.setup__skip_dialog_title)
                    .setMessage(R.string.setup__skip_hint)
                    .setPositiveButton(R.string.setup__skip_hint_yes) { _, _ -> finish() }
                    .setNegativeButton(R.string.setup__skip_hint_no, null)
                    .show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                ContextCompat.getColor(this, R.color.haohao_honey_pressed),
            )
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(
                ContextCompat.getColor(this, R.color.haohao_cocoa),
            )
        }
    }

    private fun setupSystemBars() {
        val isNightMode =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = ContextCompat.getColor(this, R.color.haohao_brand_header)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.haohao_page_background)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = !isNightMode
        }
    }

    private fun readDoneStates() = SetupPage.entries.map { it.isDone() }

    private fun syncCurrentStepAndAdvance() {
        val position = viewPager.currentItem
        val doneStates = readDoneStates()
        val wasDone = lastKnownDone[position]
        val isDone = doneStates[position]
        currentFragment()?.sync()
        val nextIndex =
            SetupFlow.nextIndexAfterSync(
                currentIndex = position,
                wasDone = wasDone,
                isDone = isDone,
                doneStates = doneStates,
            )
        lastKnownDone = doneStates.toBooleanArray()
        if (nextIndex != null && nextIndex != position) {
            viewPager.setCurrentItem(nextIndex, true)
        }
    }

    private fun scheduleStateSync() {
        binding.root.removeCallbacks(statePollRunnable)
        statePollAttempts = 0
        binding.root.post(statePollRunnable)
    }

    override fun onDestroy() {
        if (::binding.isInitialized) binding.root.removeCallbacks(statePollRunnable)
        super.onDestroy()
    }

    private fun currentFragment() = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}") as? SetupFragment

    private inner class Adapter : FragmentStateAdapter(this) {
        override fun getItemCount(): Int = SetupPage.entries.size

        override fun createFragment(position: Int): Fragment = SetupFragment().apply {
            arguments = bundleOf("page" to SetupPage.entries[position])
        }
    }
}
