/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime

import android.app.Application
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.osfans.trime.core.RimeRuntimeDiagnostics
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.footprints.InputFootprints
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.receiver.RimeIntentReceiver
import com.osfans.trime.util.isNightMode
import com.osfans.trime.worker.BackgroundSyncWork
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import timber.log.Timber

/**
 * Custom Application class.
 * Application class will only be created once when the app run,
 * so you can init a "global" class here, whose methods serve other
 * classes everywhere.
 */
class TrimeApplication : Application() {
    val coroutineScope = MainScope() + CoroutineName("TrimeApplication")

    private val rimeIntentReceiver = RimeIntentReceiver()

    private fun registerBroadcastReceiver() {
        val intentFilter =
            IntentFilter().apply {
                addAction(RimeIntentReceiver.ACTION_DEPLOY)
                addAction(RimeIntentReceiver.ACTION_SYNC_USER_DATA)
            }
        ContextCompat.registerReceiver(
            this,
            rimeIntentReceiver,
            intentFilter,
            PERMISSION_TEST_INPUT_METHOD,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashSummaryHandler()
        if (BuildConfig.DEBUG) {
            Timber.plant(
                object : Timber.DebugTree() {
                    override fun createStackElementTag(element: StackTraceElement): String = "${super.createStackElementTag(element)}|${element.fileName}:${element.lineNumber}"

                    override fun log(
                        priority: Int,
                        tag: String?,
                        message: String,
                        t: Throwable?,
                    ) {
                        super.log(
                            priority,
                            "[${Thread.currentThread().name}] ${tag?.substringBefore('|')}",
                            "${tag?.substringAfter('|')}] $message",
                            t,
                        )
                    }
                },
            )
        } else {
            Timber.plant(
                object : Timber.Tree() {
                    override fun log(
                        priority: Int,
                        tag: String?,
                        message: String,
                        t: Throwable?,
                    ) {
                        if (priority < Log.INFO) return
                        Log.println(priority, "[${Thread.currentThread().name}]", message)
                    }
                },
            )
        }
        val sharedPreferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val appPrefs = AppPrefs.initDefault(sharedPreferences)
        // record last pid for crash logs
        appPrefs.internal.pid.apply {
            val currentPid = Process.myPid()
            lastPid = getValue()
            Timber.d("Last pid is $lastPid. Set it to current pid: $currentPid")
            setValue(currentPid)
        }
        initializeOptionalModule("clipboard") { ClipboardHelper.init(applicationContext) }
        initializeOptionalModule("collection") { CollectionHelper.init(applicationContext) }
        initializeOptionalModule("input-footprints") { InputFootprints.init(applicationContext) }
        registerBroadcastReceiver()
        startWorkManager()
    }

    private fun installCrashSummaryHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { RimeRuntimeDiagnostics.recordCrash(error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    private inline fun initializeOptionalModule(
        name: String,
        initializer: () -> Unit,
    ) {
        try {
            initializer()
        } catch (error: Exception) {
            Timber.e(error, "Optional module disabled: %s", name)
            RimeRuntimeDiagnostics.recordOptionalModuleFailure(name, error)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            ColorManager.onSystemNightModeChange(newConfig.isNightMode())
        } catch (e: Exception) {
            Timber.w(e, "Something wrong on configuration changed")
        }
    }

    private fun startWorkManager() {
        coroutineScope.launch {
            BackgroundSyncWork.start(applicationContext)
        }
    }

    companion object {
        private var instance: TrimeApplication? = null
        private var lastPid: Int? = null

        fun getInstance() = instance ?: throw IllegalStateException("Trime application is not created!")

        fun getLastPid() = lastPid

        /**
         * This permission is requested by com.android.shell, makes it possible to start
         * deploy from `adb shell am` command:
         * ```sh
         * adb shell am broadcast -a com.osfans.trime.action.DEPLOY
         * ```
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.0.0_r1/packages/Shell/AndroidManifest.xml#67
         *
         * other candidate: android.permission.TEST_INPUT_METHOD requires Android 14
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/packages/Shell/AndroidManifest.xml#628
         */
        const val PERMISSION_TEST_INPUT_METHOD = "android.permission.READ_INPUT_STATE"
    }
}
