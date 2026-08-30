/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import android.os.SystemClock
import com.osfans.trime.BuildConfig
import com.osfans.trime.data.base.DEFAULT_SCHEMA_ID
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.base.DataSyncStats
import com.osfans.trime.data.opencc.OpenCCDictManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.InlinePreeditMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Rime JNI and instance methods
 *
 * @see [librime](https://github.com/rime/librime)
 */
class Rime :
    RimeApi,
    RimeLifecycleOwner {
    private val lifecycleRegistry = RimeLifecycleRegistry()
    override val lifecycle get() = lifecycleRegistry

    override val messageFlow = messageFlow_.asSharedFlow()

    override val isReady: Boolean
        get() = lifecycle.currentState == RimeLifecycle.State.READY

    private val mutableRuntimeState = MutableStateFlow(RimeRuntimeState.PREPARING)
    val runtimeState: StateFlow<RimeRuntimeState> = mutableRuntimeState.asStateFlow()

    @Volatile
    var lastFailure: String? = null
        private set

    @Volatile
    internal var lastDataPreparationMillis: Long = -1L
        private set

    @Volatile
    internal var lastNativeStartupMillis: Long = -1L
        private set

    @Volatile
    internal var lastDataSyncStats: DataSyncStats? = null
        private set

    override var schemaCached = RimeSchema(".default")
        private set

    override var statusCached = StatusProto()
        private set

    override var compositionCached = CompositionProto()
        private set

    override var hasMenu: Boolean = false
        private set

    override var paging: Boolean = false
        private set

    private val dispatcher =
        RimeDispatcher(
            object : RimeDispatcher.RimeController {
                override fun nativeStartup() {
                    startRime(startupFullCheck)
                    lifecycleRegistry.emitState(RimeLifecycle.State.READY)
                    mutableRuntimeState.value = RimeRuntimeState.READY
                    lastFailure = null
                }

                override fun nativeStartupFailed(error: Throwable) {
                    runCatching(::exitRime)
                    lastFailure = error.message ?: error.javaClass.simpleName
                    lifecycleRegistry.emitStartupFailed()
                    mutableRuntimeState.value = RimeRuntimeState.FAILED
                    unregisterRimeMessageHandler(::handleRimeMessage)
                }

                override fun nativeFinalize() {
                    exitRime()
                }
            },
        )

    private val inlinePreeditMode by AppPrefs.defaultInstance().general.inlinePreeditMode
    private val showAsciiSwitchTips by AppPrefs.defaultInstance().general.asciiSwitchTips

    private var asciiSwitchTipsJob: Job? = null
    private var isNullInputType = true
    private var lastAsciiTipsText = ""
    private var pagingMode = false

    @Volatile
    private var startupFullCheck = false

    init {
        if (lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            throw IllegalStateException("Rime has already been created!")
        }
    }

    private suspend inline fun <T> withRimeContext(crossinline block: suspend () -> T): T = withContext(dispatcher) {
        block()
    }

    override suspend fun isEmpty(): Boolean = withRimeContext {
        getCurrentRimeSchema() == ".default" // 無方案
    }

    override suspend fun deploy() = withRimeContext {
        exitRime()
        startRime(true)
    }

    override suspend fun updateConfig() = withRimeContext {
        exitRime()
        startRime(false)
    }

    override suspend fun syncUserData(): Boolean = withRimeContext {
        syncRimeUserData()
    }

    override suspend fun processKey(
        value: Int,
        modifiers: UInt,
        isVirtual: Boolean,
    ): Boolean = withRimeContext {
        processKeyInner(value, modifiers.toInt(), isVirtual)
    }

    override suspend fun processKey(
        value: KeyValue,
        modifiers: KeyModifiers,
        isVirtual: Boolean,
    ): Boolean = withRimeContext {
        processKeyInner(value.value, modifiers.toInt(), isVirtual)
    }

    override suspend fun simulateKeySequence(sequence: String): Boolean = withRimeContext {
        Timber.d("simulateKeySequence: $sequence")
        if (simulateRimeKeySequence(sequence)) {
            val commit = getRimeCommit()
            val input = getRimeRawInput()
            if (!commit.text.isNullOrEmpty() || input.isNotEmpty()) {
                emitResponse(commit)
                true
            } else {
                emitResponse(CommitProto(sequence))
                false
            }
        } else {
            false
        }.also { Timber.d("simulateKeySequence ${if (it) "success" else "failed"}") }
    }

    override suspend fun selectCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        selectRimeCandidate(idx, global).also { emitResponse() }
    }

    override suspend fun deleteCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        deleteRimeCandidate(idx, global).also { emitResponse() }
    }

    override suspend fun changeCandidatePage(backward: Boolean): Boolean = withRimeContext {
        changeRimeCandidatePage(backward).also { emitResponse() }
    }

    override suspend fun moveCursorPos(position: Int) = withRimeContext {
        setRimeCaretPos(position)
        emitResponse()
    }

    override suspend fun availableSchemata(): Array<SchemaItem> = withRimeContext { getAvailableRimeSchemaList() }

    override suspend fun enabledSchemata(): Array<SchemaItem> = withRimeContext { getSelectedRimeSchemaList() }

    override suspend fun setEnabledSchemata(schemaIds: Array<String>) = withRimeContext {
        val selected = selectRimeSchemas(arrayOf(DEFAULT_SCHEMA_ID))
        enforceSimplifiedSchema()
        selected
    }

    override suspend fun selectedSchemata(): Array<SchemaItem> = withRimeContext { getRimeSchemaList() }

    override suspend fun selectedSchemaId(): String = withRimeContext { getCurrentRimeSchema() }

    override suspend fun selectSchema(schemaId: String) = withRimeContext {
        val selected = selectRimeSchema(DEFAULT_SCHEMA_ID)
        enforceSimplifiedSchema()
        selected
    }

    override suspend fun currentSchema(): RimeSchema = withRimeContext {
        RimeSchema(getCurrentRimeSchema())
    }

    override suspend fun commitComposition(): Boolean = withRimeContext { commitRimeComposition().also { if (it) emitResponse() } }

    override suspend fun clearComposition() = withRimeContext {
        clearRimeComposition()
        emitResponse()
    }

    override suspend fun getRawInput(): String = withRimeContext {
        getRimeRawInput()
    }

    override suspend fun setRuntimeOption(
        option: String,
        value: Boolean,
    ): Unit = withRimeContext {
        setRimeOption(option, if (option == SIMPLIFIED_OPTION) true else value)
    }

    override suspend fun getRuntimeOption(option: String): Boolean = withRimeContext {
        getRimeOption(option)
    }

    override suspend fun setNullInputType(value: Boolean) = withRimeContext {
        isNullInputType = value
    }

    override suspend fun getCandidates(
        startIndex: Int,
        limit: Int,
    ): Array<CandidateProto> = withRimeContext {
        getRimeCandidates(startIndex, limit)
    }

    override suspend fun setCandidatePagingMode(enabled: Boolean) = withRimeContext {
        pagingMode = enabled
        emitResponse()
    }

    private fun startRime(fullCheck: Boolean) {
        val dataStartedAt = SystemClock.elapsedRealtime()
        lastDataSyncStats = null
        val syncStats = try {
            DataManager.sync()
        } finally {
            lastDataPreparationMillis = SystemClock.elapsedRealtime() - dataStartedAt
        }
        lastDataSyncStats = syncStats
        Timber.i(
            "Rime data prepared in %d ms: copied=%d, bytes=%d, reused=%s",
            lastDataPreparationMillis,
            syncStats.copiedFiles,
            syncStats.copiedBytes,
            syncStats.reusedPrebuilt,
        )
        val sharedDataDir = DataManager.sharedDataDir.absolutePath
        val userDataDir = DataManager.userDataDir.absolutePath
        Timber.d(
            """
            Starting rime with:
            sharedDataDir: $sharedDataDir
            userDataDir: $userDataDir
            fullCheck: $fullCheck
            """.trimIndent(),
        )
        val nativeStartedAt = SystemClock.elapsedRealtime()
        try {
            startupRime(sharedDataDir, userDataDir, BuildConfig.BUILD_VERSION_NAME, fullCheck)
            enforceSimplifiedSchema()
        } finally {
            lastNativeStartupMillis = SystemClock.elapsedRealtime() - nativeStartedAt
            Timber.i("Rime native startup finished in %d ms", lastNativeStartupMillis)
        }
    }

    private fun enforceSimplifiedSchema() {
        val currentSchema = getCurrentRimeSchema()
        if (currentSchema != DEFAULT_SCHEMA_ID && !selectRimeSchema(DEFAULT_SCHEMA_ID)) {
            error("Required simplified schema is unavailable: $DEFAULT_SCHEMA_ID")
        }
        setRimeOption(SIMPLIFIED_OPTION, true)
        val status = getRimeStatus()
        if (status.schemaId != DEFAULT_SCHEMA_ID || !getRimeOption(SIMPLIFIED_OPTION)) {
            error("Failed to activate HaoHao simplified schema")
        }
        statusCached = status
        schemaCached = RimeSchema(DEFAULT_SCHEMA_ID)
    }

    private fun processKeyInner(value: Int, modifiers: Int, isVirtual: Boolean): Boolean {
        lastAsciiTipsText = asciiTipsText(getRimeStatus())
        val handled = processRimeKey(value, modifiers)
        emitResponse()
        if (!handled) {
            handleRimeMessage(
                10, // RimeMessage.MessageType.Key,
                arrayOf(value, modifiers, isVirtual),
            )
        }
        return handled
    }

    private fun asciiTipsText(status: StatusProto): String = when {
        status.isAsciiMode -> "En"
        status.schemaName.isNotEmpty() && !status.schemaName.startsWith('.') ->
            status.schemaName.take(2)
        else -> ""
    }

    private fun emitResponse(commit: CommitProto? = null) {
        val response = getRimeResponse(pagingMode)
        handleRimeMessage(4, arrayOf(commit ?: response.commit))
        handlePreedit(response.composition)
        if (response.composition.length <= 0 && lastAsciiTipsText != asciiTipsText(response.status)) {
            showAsciiSwitchTips(response.status)
        }
        when (val candidates = response.candidates) {
            is Candidates.Paged -> handleRimeMessage(7, arrayOf(candidates))
            is Candidates.Bulk -> handleRimeMessage(9, arrayOf(candidates))
        }
        handleRimeMessage(8, arrayOf(response.status))
    }

    private fun handlePreedit(composition: CompositionProto) {
        val mode = if (isNullInputType) {
            InlinePreeditMode.DISABLE
        } else {
            inlinePreeditMode
        }
        val inlinePreedit = when (mode) {
            InlinePreeditMode.DISABLE -> ""
            InlinePreeditMode.COMPOSING_TEXT -> composition.preedit ?: ""
            InlinePreeditMode.COMMIT_TEXT_PREVIEW -> composition.commitTextPreview ?: ""
        }
        val composition = if (mode == InlinePreeditMode.COMPOSING_TEXT) {
            CompositionProto()
        } else {
            composition
        }
        handleRimeMessage(5, arrayOf(inlinePreedit))
        handleRimeMessage(6, arrayOf(composition))
    }

    private fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                if (it.data.id != DEFAULT_SCHEMA_ID) {
                    enforceSimplifiedSchema()
                    return
                }
                statusCached = getRimeStatus()
                schemaCached = RimeSchema(it.data.id)
            }
            is RimeMessage.OptionMessage -> {
                if (it.data.option == SIMPLIFIED_OPTION && !it.data.value) {
                    setRimeOption(SIMPLIFIED_OPTION, true)
                    statusCached = getRimeStatus()
                    return
                }
                // Option change won't trigger response update
                val status = getRimeStatus()
                statusCached = status
                updateSchemaCached(status)
                if (it.data.option == "ascii_mode") {
                    showAsciiSwitchTips(status)
                }
            }
            is RimeMessage.DeployMessage -> {
                when (it.data) {
                    RimeMessage.DeployMessage.State.Start -> {
                        mutableRuntimeState.value = RimeRuntimeState.PREPARING
                        OpenCCDictManager.buildOpenCCDict()
                    }
                    RimeMessage.DeployMessage.State.Success -> {
                        enforceSimplifiedSchema()
                        mutableRuntimeState.value = RimeRuntimeState.READY
                        lastFailure = null
                    }
                    RimeMessage.DeployMessage.State.Failure -> {
                        lastFailure = "Rime deployment failed"
                        mutableRuntimeState.value = RimeRuntimeState.FAILED
                    }
                }
            }
            is RimeMessage.CompositionMessage -> {
                val composition = it.data
                compositionCached = composition
            }
            is RimeMessage.PagedCandidatesMessage -> {
                val paged = it.data
                paging = paged.hasPrevPage
                hasMenu = paged.candidates.isNotEmpty()
            }
            is RimeMessage.BulkCandidatesMessage -> {
                hasMenu = it.data.candidates.isNotEmpty()
            }
            is RimeMessage.StatusMessage -> {
                statusCached = it.data
                updateSchemaCached(it.data)
            }
            else -> {}
        }
    }

    private fun updateSchemaCached(status: StatusProto) {
        val (schemaId, schemaName) = status
        // Engine response update won't send SchemaMessage, but usually update RimeStatus
        if (schemaId != schemaCached.schemaId) {
            schemaCached = RimeSchema(schemaId)
            // notify downstream consumers that schema has changed
            messageFlow_.tryEmit(
                RimeMessage.SchemaMessage(
                    SchemaItem(schemaId, schemaName),
                ),
            )
        }
    }

    private fun showAsciiSwitchTips(status: StatusProto) {
        if (!showAsciiSwitchTips) return
        val tipsText = asciiTipsText(status)
        if (tipsText.isEmpty()) return

        lastAsciiTipsText = tipsText

        val tips = CompositionProto(tipsText)
        messageFlow_.tryEmit(RimeMessage.CompositionMessage(tips))
        compositionCached = tips
        asciiSwitchTipsJob?.cancel()
        asciiSwitchTipsJob = lifecycleScope.launch {
            delay(1000L)
            val ctx = getRimeContext()
            handleRimeMessage(6, arrayOf(ctx.composition))
        }
    }

    fun startup(fullCheck: Boolean = false) {
        if (lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            Timber.w("Skip starting rime: not at stopped state!")
            return
        }
        startupFullCheck = fullCheck
        lastFailure = null
        mutableRuntimeState.value = RimeRuntimeState.PREPARING
        registerRimeMessageHandler(::handleRimeMessage)
        lifecycleRegistry.emitState(RimeLifecycle.State.STARTING)
        dispatcher.start()
    }

    fun finalize() {
        if (lifecycle.currentState != RimeLifecycle.State.READY) {
            Timber.w("Skip stopping rime: not at ready state!")
            return
        }
        lifecycleRegistry.emitState(RimeLifecycle.State.STOPPING)
        Timber.i("Rime finalize()")
        dispatcher.stop().let {
            if (it.isNotEmpty()) {
                Timber.w("${it.size} job(s) didn't get a chance to run!")
            }
        }
        lifecycleRegistry.emitState(RimeLifecycle.State.STOPPED)
        unregisterRimeMessageHandler(::handleRimeMessage)
    }

    fun markFailed(error: Throwable) {
        lastFailure = error.message ?: error.javaClass.simpleName
        mutableRuntimeState.value = RimeRuntimeState.FAILED
    }

    companion object {
        private const val SIMPLIFIED_OPTION = "zh_simp"
        private val messageFlow_ =
            MutableSharedFlow<RimeMessage<*>>(
                extraBufferCapacity = 15,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        private val rimeMessageHandlers = ArrayList<(RimeMessage<*>) -> Unit>()

        init {
            System.loadLibrary("rime_jni")
        }

        // init
        @JvmStatic
        external fun startupRime(
            sharedDir: String,
            userDir: String,
            versionName: String,
            fullCheck: Boolean,
        )

        @JvmStatic
        external fun exitRime()

        @JvmStatic
        external fun deployRimeSchemaFile(schemaFile: String): Boolean

        @JvmStatic
        external fun deployRimeConfigFile(
            fileName: String,
            versionKey: String,
        ): Boolean

        @JvmStatic
        external fun syncRimeUserData(): Boolean

        // input
        @JvmStatic
        external fun processRimeKey(
            keycode: Int,
            mask: Int,
        ): Boolean

        @JvmStatic
        external fun commitRimeComposition(): Boolean

        @JvmStatic
        external fun clearRimeComposition()

        // output
        @JvmStatic
        external fun getRimeCommit(): CommitProto

        @JvmStatic
        external fun getRimeContext(): ContextProto

        @JvmStatic
        external fun getRimeStatus(): StatusProto

        // runtime options
        @JvmStatic
        external fun setRimeOption(
            option: String,
            value: Boolean,
        )

        @JvmStatic
        external fun getRimeOption(option: String): Boolean

        @JvmStatic
        external fun getRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getCurrentRimeSchema(): String

        @JvmStatic
        external fun selectRimeSchema(schemaId: String): Boolean

        // testing
        @JvmStatic
        external fun simulateRimeKeySequence(keySequence: String): Boolean

        @JvmStatic
        external fun getRimeRawInput(): String

        @JvmStatic
        external fun getRimeCaretPos(): Int

        @JvmStatic
        external fun setRimeCaretPos(caretPos: Int)

        @JvmStatic
        external fun selectRimeCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun deleteRimeCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun changeRimeCandidatePage(backward: Boolean): Boolean

        @JvmStatic
        external fun getAvailableRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getSelectedRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun selectRimeSchemas(schemaIds: Array<String>): Boolean

        @JvmStatic
        external fun getRimeCandidates(
            startIndex: Int,
            limit: Int,
        ): Array<CandidateProto>

        @JvmStatic
        external fun getRimeResponse(pagingMode: Boolean): RimeResponse

        @JvmStatic
        fun handleRimeMessage(
            type: Int,
            params: Array<Any>,
        ) {
            val message = RimeMessage.nativeCreate(type, params)
            Timber.d("Handling $message")
            rimeMessageHandlers.forEach { it.invoke(message) }
            messageFlow_.tryEmit(message)
        }

        private fun registerRimeMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            if (rimeMessageHandlers.contains(handler)) return
            rimeMessageHandlers.add(handler)
        }

        private fun unregisterRimeMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            rimeMessageHandlers.remove(handler)
        }
    }
}
