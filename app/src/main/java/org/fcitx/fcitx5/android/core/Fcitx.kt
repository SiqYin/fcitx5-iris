/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.os.Build
import org.fcitx.fcitx5.android.BuildConfig
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.ImmutableGraph
import org.fcitx.fcitx5.android.utils.Locales
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Do not use this class directly, accessing fcitx via daemon instead
 */
class Fcitx(private val context: Context) : FcitxAPI, FcitxLifecycleOwner {

    private val lifecycleRegistry = FcitxLifecycleRegistry()

    override val eventFlow = eventFlow_.asSharedFlow()

    override val isReady
        get() = lifecycle.currentState == FcitxLifecycle.State.READY

    override var inputMethodEntryCached =
        InputMethodEntry(context.getString(R.string._not_available_))
        private set

    override var statusAreaActionsCached: Array<Action> = emptyArray()
        private set

    override var clientPreeditCached = FormattedText.Empty
        private set

    override var inputPanelCached = FcitxEvent.InputPanelEvent.Data()
        private set

    // TODO: custom log rule
    override fun setLogRule(verbose: Boolean) {
        setupLogStream(verbose)
    }

    // the computation is delayed to the first call of [getAddonReverseDependencies]
    private var addonGraph: ImmutableGraph<String, FcitxAPI.AddonDep>? = null

    private val addonReverseDependencies =
        mutableMapOf<String, List<Pair<String, FcitxAPI.AddonDep>>>()

    override fun getAddonReverseDependencies(addon: String) =
        (addonGraph ?: run { computeAddonGraph().also { addonGraph = it } }).let { graph ->
            addonReverseDependencies.computeIfAbsent(addon)
            {
                graph.bfs(it) { level, _, dep ->
                    // stop when the direct child is an optional dependency
                    dep == FcitxAPI.AddonDep.Required
                            || (level == 1 && dep == FcitxAPI.AddonDep.Optional)
                }
            }
        }

    override fun translate(str: String, domain: String) = getFcitxTranslation(domain, str)

    override suspend fun save() = withFcitxContext { saveFcitxState() }
    override suspend fun reloadConfig() = withFcitxContext { reloadFcitxConfig() }

    override suspend fun sendKey(
        key: String,
        states: UInt,
        code: Int,
        up: Boolean,
        timestamp: Int
    ) =
        withFcitxContext { sendKeyToFcitxString(key, states.toInt(), code, up, timestamp) }

    override suspend fun sendKey(c: Char, states: UInt, code: Int, up: Boolean, timestamp: Int) =
        withFcitxContext { sendKeyToFcitxChar(c, states.toInt(), code, up, timestamp) }

    override suspend fun sendKey(sym: Int, states: UInt, code: Int, up: Boolean, timestamp: Int) =
        withFcitxContext { sendKeySymToFcitx(sym, states.toInt(), code, up, timestamp) }

    override suspend fun sendKey(
        sym: KeySym,
        states: KeyStates,
        code: Int,
        up: Boolean,
        timestamp: Int
    ) =
        withFcitxContext { sendKeySymToFcitx(sym.sym, states.toInt(), code, up, timestamp) }

    override suspend fun select(idx: Int): Boolean = withFcitxContext { selectCandidate(idx) }
    override suspend fun isEmpty(): Boolean = withFcitxContext { isInputPanelEmpty() }
    override suspend fun reset() = withFcitxContext { resetInputContext() }
    override suspend fun moveCursor(position: Int) = withFcitxContext { repositionCursor(position) }
    override suspend fun availableIme() =
        withFcitxContext { availableInputMethods() ?: emptyArray() }

    override suspend fun enabledIme() =
        withFcitxContext { listInputMethods() ?: emptyArray() }

    override suspend fun setEnabledIme(array: Array<String>) =
        withFcitxContext { setEnabledInputMethods(array) }

    override suspend fun toggleIme() = withFcitxContext { toggleInputMethod() }
    override suspend fun activateIme(ime: String) = withFcitxContext { setInputMethod(ime) }
    override suspend fun enumerateIme(forward: Boolean) =
        withFcitxContext { nextInputMethod(forward) }

    override suspend fun currentIme() =
        withFcitxContext { inputMethodStatus() ?: inputMethodEntryCached }

    override suspend fun getGlobalConfig() = withFcitxContext {
        getFcitxGlobalConfig() ?: RawConfig()
    }

    override suspend fun setGlobalConfig(config: RawConfig) = withFcitxContext {
        setFcitxGlobalConfig(config)
    }

    override suspend fun getAddonConfig(addon: String) = withFcitxContext {
        getFcitxAddonConfig(addon) ?: RawConfig()
    }

    override suspend fun setAddonConfig(addon: String, config: RawConfig) = withFcitxContext {
        setFcitxAddonConfig(addon, config)
    }

    override suspend fun getAddonSubConfig(addon: String, path: String) = withFcitxContext {
        getFcitxAddonSubConfig(addon, path) ?: RawConfig()
    }

    override suspend fun setAddonSubConfig(addon: String, path: String, config: RawConfig) =
        withFcitxContext { setFcitxAddonSubConfig(addon, path, config) }

    override suspend fun getImConfig(key: String) = withFcitxContext {
        getFcitxInputMethodConfig(key) ?: RawConfig()
    }

    override suspend fun setImConfig(key: String, config: RawConfig) = withFcitxContext {
        setFcitxInputMethodConfig(key, config)
    }

    override suspend fun addons() = withFcitxContext { getFcitxAddons() ?: emptyArray() }
    override suspend fun setAddonState(name: Array<String>, state: BooleanArray) =
        withFcitxContext { setFcitxAddonState(name, state) }

    override suspend fun triggerQuickPhrase() = withFcitxContext { triggerQuickPhraseInput() }
    override suspend fun triggerUnicode() = withFcitxContext { triggerUnicodeInput() }
    private suspend fun setClipboard(string: String, password: Boolean = false) =
        withFcitxContext { setFcitxClipboard(string, password) }

    override suspend fun focus(focus: Boolean) = withFcitxContext { focusInputContext(focus) }
    override suspend fun focusOutIn() = withFcitxContext { focusInputContextOutIn() }
    override suspend fun activate(uid: Int, pkgName: String) =
        withFcitxContext { activateInputContext(uid, pkgName) }

    override suspend fun deactivate(uid: Int) = withFcitxContext { deactivateInputContext(uid) }
    override suspend fun setCapFlags(flags: CapabilityFlags) =
        withFcitxContext { setCapabilityFlags(flags.toLong()) }

    override suspend fun statusArea(): Array<Action> =
        withFcitxContext { getFcitxStatusAreaActions() ?: emptyArray() }

    override suspend fun activateAction(id: Int) =
        withFcitxContext { activateUserInterfaceAction(id) }

    override suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord> =
        withFcitxContext { getFcitxCandidates(offset, limit) ?: emptyArray() }

    override suspend fun getCandidateActions(idx: Int): Array<CandidateAction> =
        withFcitxContext { getFcitxCandidateActions(idx) ?: emptyArray() }

    override suspend fun triggerCandidateAction(idx: Int, actionIdx: Int) =
        withFcitxContext { triggerFcitxCandidateAction(idx, actionIdx) }

    override suspend fun setCandidatePagingMode(mode: Int) =
        withFcitxContext { setFcitxCandidatePagingMode(mode) }

    override suspend fun offsetCandidatePage(delta: Int) =
        withFcitxContext { offsetFcitxCandidatePage(delta) }

    override suspend fun triggerCandidateListTabAction(id: Int) =
        withFcitxContext { triggerFcitxCandidateListTabAction(id) }

    /**
     * 触发 Rime 的一轮部署（等价于用户点「部署」）。
     *
     * fcitx5-rime 的 RimeEngine::setSubConfig 把 path == "deploy" 映射到 deploy()，
     * 后者 finalize + rimeStart(true)，即全量强制部署（kRebuild）。
     * 走 [withFcitxContext] 保证在 fcitx 线程执行，不阻塞 UI。
     */
    override suspend fun deployRime() = withFcitxContext {
        setFcitxAddonSubConfig(RIME_ADDON, "deploy", RawConfig())
    }

    init {
        if (lifecycle.currentState != FcitxLifecycle.State.STOPPED)
            throw IllegalAccessException("Fcitx5 has already been created!")
    }


    override val lifecycle: FcitxLifecycle
        get() = lifecycleRegistry

    private companion object JNI {

        /** Rime 引擎的输入法唯一名（inputmethod/rime.conf 的 Addon=rime + key） */
        private const val PREBUILT_CHINESE_ENGINE = "rime"

        /** Rime addon 的唯一名（addon/rime.conf） */
        private const val RIME_ADDON = "rime"

        /** 英文键盘的唯一名（androidkeyboard 的 listInputMethods() 固定值） */
        private const val FALLBACK_LATIN_ENGINE = "keyboard-us"

        /**
         * called from native-lib
         */
        @Suppress("unused")
        @JvmStatic
        fun showToast(s: String) {
            ContextCompat.getMainExecutor(appContext).execute {
                appContext.toast(s)
            }
        }

        private val eventFlow_ =
            MutableSharedFlow<FcitxEvent<*>>(
                extraBufferCapacity = 15,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )

        // we may need to modify the list during iteration
        // eg. remove the "first run" listener after first ReadyEvent
        private val fcitxEventHandlers = CopyOnWriteArrayList<(FcitxEvent<*>) -> Unit>()

        init {
            System.loadLibrary("native-lib")
        }

        @JvmStatic
        external fun setupLogStream(verbose: Boolean)

        @JvmStatic
        external fun startupFcitx(
            locale: String,
            appData: String,
            appLib: String,
            extData: String,
            extCache: String,
            extDomains: Array<String>
        )

        @JvmStatic
        external fun getFcitxTranslation(domain: String, str: String): String

        @JvmStatic
        external fun exitFcitx()

        @JvmStatic
        external fun saveFcitxState()

        @JvmStatic
        external fun reloadFcitxConfig()

        @JvmStatic
        external fun sendKeyToFcitxString(
            key: String,
            state: Int,
            code: Int,
            up: Boolean,
            timestamp: Int
        )

        @JvmStatic
        external fun sendKeyToFcitxChar(c: Char, state: Int, code: Int, up: Boolean, timestamp: Int)

        @JvmStatic
        external fun sendKeySymToFcitx(sym: Int, state: Int, code: Int, up: Boolean, timestamp: Int)

        @JvmStatic
        external fun selectCandidate(idx: Int): Boolean

        @JvmStatic
        external fun isInputPanelEmpty(): Boolean

        @JvmStatic
        external fun resetInputContext()

        @JvmStatic
        external fun repositionCursor(position: Int)

        @JvmStatic
        external fun toggleInputMethod()

        @JvmStatic
        external fun nextInputMethod(forward: Boolean)

        @JvmStatic
        external fun listInputMethods(): Array<InputMethodEntry>?

        @JvmStatic
        external fun inputMethodStatus(): InputMethodEntry?

        @JvmStatic
        external fun setInputMethod(ime: String)

        @JvmStatic
        external fun availableInputMethods(): Array<InputMethodEntry>?

        @JvmStatic
        external fun setEnabledInputMethods(array: Array<String>)

        @JvmStatic
        external fun ensureInputMethodGroup(preferred: Array<String>, defaultIme: String): Boolean

        @JvmStatic
        external fun getFcitxGlobalConfig(): RawConfig?

        @JvmStatic
        external fun getFcitxAddonConfig(addon: String): RawConfig?

        @JvmStatic
        external fun getFcitxAddonSubConfig(addon: String, path: String): RawConfig?

        @JvmStatic
        external fun getFcitxInputMethodConfig(im: String): RawConfig?

        @JvmStatic
        external fun setFcitxGlobalConfig(config: RawConfig)

        @JvmStatic
        external fun setFcitxAddonConfig(addon: String, config: RawConfig)

        @JvmStatic
        external fun setFcitxAddonSubConfig(addon: String, path: String, config: RawConfig)

        @JvmStatic
        external fun setFcitxInputMethodConfig(im: String, config: RawConfig)

        @JvmStatic
        external fun getFcitxAddons(): Array<AddonInfo>?

        @JvmStatic
        external fun setFcitxAddonState(name: Array<String>, state: BooleanArray)

        @JvmStatic
        external fun triggerQuickPhraseInput()

        @JvmStatic
        external fun triggerUnicodeInput()

        @JvmStatic
        external fun setFcitxClipboard(string: String, password: Boolean)

        @JvmStatic
        external fun focusInputContext(focus: Boolean)

        @JvmStatic
        external fun focusInputContextOutIn()

        @JvmStatic
        external fun activateInputContext(uid: Int, pkgName: String)

        @JvmStatic
        external fun deactivateInputContext(uid: Int)

        @JvmStatic
        external fun setCapabilityFlags(flags: Long)

        @JvmStatic
        external fun getFcitxStatusAreaActions(): Array<Action>?

        @JvmStatic
        external fun activateUserInterfaceAction(id: Int)

        @JvmStatic
        external fun getFcitxCandidates(offset: Int, limit: Int): Array<CandidateWord>?

        @JvmStatic
        external fun getFcitxCandidateActions(idx: Int): Array<CandidateAction>?

        @JvmStatic
        external fun triggerFcitxCandidateAction(idx: Int, actionIdx: Int)

        @JvmStatic
        external fun setFcitxCandidatePagingMode(mode: Int)

        @JvmStatic
        external fun offsetFcitxCandidatePage(delta: Int)

        @JvmStatic
        external fun triggerFcitxCandidateListTabAction(id: Int)

        @JvmStatic
        external fun loopOnce()

        @JvmStatic
        external fun scheduleEmpty()

        /**
         * Called from native-lib
         */
        @Suppress("unused")
        @JvmStatic
        fun handleFcitxEvent(type: Int, params: Array<Any>) {
            val event = FcitxEvent.create(type, params)
            Timber.d("Handling $event")
            fcitxEventHandlers.forEach { it.invoke(event) }
            eventFlow_.tryEmit(event)
        }

        // will be called in fcitx main thread
        private fun onFirstRun() {
            Timber.i("onFirstRun")
        }

        /**
         * register a [FcitxEvent] handler that will fire before events go into [eventFlow_]
         */
        private fun registerFcitxEventHandler(handler: (FcitxEvent<*>) -> Unit) {
            if (fcitxEventHandlers.contains(handler)) return
            fcitxEventHandlers.add(handler)
        }

        private fun unregisterFcitxEventHandler(handler: (FcitxEvent<*>) -> Unit) {
            fcitxEventHandlers.remove(handler)
        }

    }

    private val dispatcher = FcitxDispatcher(object : FcitxDispatcher.FcitxController {
        override fun nativeStartup() {
            DataManager.sync()
            prepareRimePrebuiltDeployment()
            val locale = Locales.fcitxLocale
            val dataDir = DataManager.dataDir.absolutePath
            val plugins = DataManager.getLoadedPlugins()
            val nativeLibDir = StringBuilder(context.applicationInfo.nativeLibraryDir)
            val extDomains = arrayListOf<String>()
            plugins.forEach {
                if (it.nativeLibraryDir.isNotBlank()) {
                    nativeLibDir.append(':')
                    nativeLibDir.append(it.nativeLibraryDir)
                }
                it.domain?.let { d ->
                    extDomains.add(d)
                }
            }
            Timber.d(
                """
               Starting fcitx with:
               locale=$locale
               dataDir=$dataDir
               nativeLibDir=$nativeLibDir
               extDomains=${extDomains.joinToString()}
            """.trimIndent()
            )
            with(FcitxApplication.getInstance().directBootAwareContext) {
                startupFcitx(
                    locale,
                    dataDir,
                    nativeLibDir.toString(),
                    (getExternalFilesDir(null) ?: filesDir).absolutePath,
                    (externalCacheDir ?: cacheDir).absolutePath,
                    extDomains.toTypedArray()
                )
            }
            applyDefaultInputMethodGroup()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                lifecycle.launchWhenReady {
                    SubtypeManager.syncWith(enabledIme())
                }
            }
        }

        override fun nativeLoopOnce() {
            loopOnce()
        }

        override fun nativeScheduleEmpty() {
            scheduleEmpty()
        }

        override fun nativeExit() {
            exitFcitx()
        }

    })

    private suspend inline fun <T> withFcitxContext(crossinline block: suspend () -> T): T =
        withContext(dispatcher) {
            block()
        }

    @Keep
    private val onClipboardUpdate = ClipboardManager.OnClipboardUpdateListener {
        lifecycle.launchWhenReady { setClipboard(it.text, it.sensitive) }
    }

    private fun computeAddonGraph() = runBlocking {
        addons().flatMap { a ->
            a.dependencies.map {
                ImmutableGraph.Edge(it, a.uniqueName, FcitxAPI.AddonDep.Required)
            } + a.optionalDependencies.map {
                ImmutableGraph.Edge(it, a.uniqueName, FcitxAPI.AddonDep.Optional)
            }
        }.let { ImmutableGraph(it) }
    }

    private var firstRun by AppPrefs.getInstance().internal.firstRun

    /**
     * 免手动部署：APK 内自带 rime_deployer 预编译产物（usr/share/rime-data/build/）。
     *
     * librime 启动时的修改检测（DetectModifications）只比较 shared/user 数据目录
     * 顶层 *.yaml 的 mtime 与 user_data/user.yaml 里的 var/last_build_time；
     * 预置产物 + 预写 user.yaml 即可让它一次编译都不发生，安装激活后开箱即用。
     * 同时清掉手机端旧部署产物（user_data/build 优先于 prebuilt 加载，不清会遮蔽新词库）。
     *
     * 兜底：APK 内无产物 / 产物未同步落盘 / 用户放了自定义 yaml 时，
     * 不做任何干预，让 Rime 走常规部署路径（与上游行为一致）。
     */
    private fun prepareRimePrebuiltDeployment() {
        val current = runCatching {
            appContext.assets.open("usr/share/rime-data/build/buildinfo.txt")
                .bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        val extContext = FcitxApplication.getInstance().directBootAwareContext
        val extData = extContext.getExternalFilesDir(null) ?: extContext.filesDir
        val rimeUserDir = File(extData, "data/rime")
        val marker = File(rimeUserDir, ".iris_buildinfo")
        if (marker.exists() && marker.readText() == current) return
        val prebuiltDir = File(DataManager.dataDir, "usr/share/rime-data/build")
        if (!prebuiltDir.isDirectory) {
            Timber.w("Rime prebuilt dir missing after sync; fallback to normal deployment")
            return
        }
        val hasCustom = rimeUserDir.listFiles { f -> f.isFile && f.name.endsWith(".yaml") }
            ?.any { it.name != "user.yaml" && it.name != "installation.yaml" } ?: false
        if (hasCustom) {
            Timber.i("Rime custom yaml found; fallback to normal deployment")
            return
        }
        rimeUserDir.mkdirs()
        File(rimeUserDir, "build").deleteRecursively()
        // last_build_time 必须取「当前时间」而不是「未来时间」：
        // DetectModifications 的比较是 last_modified > last_build_time，写未来值虽然也能
        // 压住首次自动部署，但之后用户点「部署」时 librime 仍认为 workspace 是最新的，
        // 部署变成空操作（0.1.0 用户反馈「重新部署也没有任何效果」即由此而来）。
        // 取当前时间即可：shared/user 顶层 yaml 的 mtime 都早于它 → 不触发自动部署；
        // 用户手动部署走的是强制路径，不受此值影响。
        val nowSeconds = System.currentTimeMillis() / 1000
        File(rimeUserDir, "user.yaml").writeText(buildUserYaml(nowSeconds))
        marker.writeText(current)
        Timber.i("Rime prebuilt deployment refreshed: ${current.trim().lineSequence().firstOrNull()}")
    }

    /**
     * 生成 user.yaml。
     *
     * 除 `var/last_build_time` 外还写入 `var/previously_selected_schema`：
     * 两个发行版（吴语版 / 西安话版）内容完全一致，唯一区别就是这里的默认方案。
     * librime 启动时按这个键恢复上次使用的方案，因此首次安装即落在各自方案上。
     *
     * 只在首次写入（user.yaml 不存在或被本方法覆盖）时生效：
     * 用户之后自己切换方案，librime 会把新的选择写回同一文件。
     */
    private fun buildUserYaml(nowSeconds: Long): String = buildString {
        append("var:\n")
        append("  last_build_time: ").append(nowSeconds).append('\n')
        append("  previously_selected_schema: ").append(BuildConfig.DEFAULT_SCHEME).append('\n')
    }

    /**
     * 默认输入法：Rime（keyboard-us 英文键盘保留为用户可选，但不是默认）。
     *
     * 首次启动时 fcitx5 会按 FocusGroup 的 XKB 布局 buildDefaultGroup()，
     * 组内第一项固定是 keyboard-<layout>（安卓上无 X11，回落成 keyboard-us），
     * 于是默认输入法变成英文键盘、Rime 根本不在组里——用户必须手动添加才能用。
     * 这里在 startup 之后把组补齐成 [rime, keyboard-us] 并把 DefaultIM 设为 rime。
     *
     * 幂等：已符合预期时不写 profile；用户后续自行增删输入法不会被覆盖
     * （只有组内缺少 rime 或 DefaultIM 不是 rime 时才修正）。
     */
    private fun applyDefaultInputMethodGroup() {
        if (PREBUILT_CHINESE_ENGINE !in availableEngines()) return
        val changed = ensureInputMethodGroup(
            arrayOf(PREBUILT_CHINESE_ENGINE, FALLBACK_LATIN_ENGINE),
            PREBUILT_CHINESE_ENGINE
        )
        if (changed) {
            Timber.i("Default input method group updated: $PREBUILT_CHINESE_ENGINE first")
        }
    }

    private fun availableEngines(): Set<String> =
        runCatching { availableInputMethods()?.map { it.uniqueName }?.toSet() ?: emptySet() }
            .getOrElse { emptySet() }

    private fun handleFirstRunReadyEvent(event: FcitxEvent<*>) {
        if (event is FcitxEvent.ReadyEvent && firstRun) {
            firstRun = false
            // this method runs in same thread with `startupFcitx`
            // block it will also block fcitx
            onFirstRun()
            unregisterFcitxEventHandler(::handleFirstRunReadyEvent)
        }
    }

    private fun handleFcitxEvent(event: FcitxEvent<*>) {
        when (event) {
            is FcitxEvent.ReadyEvent -> lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_READY)
            is FcitxEvent.IMChangeEvent -> inputMethodEntryCached = event.data
            is FcitxEvent.StatusAreaEvent -> {
                val (actions, im) = event.data
                statusAreaActionsCached = actions
                // Engine subMode update won't trigger IMChangeEvent, but usually updates StatusArea
                if (im != inputMethodEntryCached) {
                    inputMethodEntryCached = im
                    // notify downstream consumers that engine subMode has changed
                    eventFlow_.tryEmit(FcitxEvent.IMChangeEvent(im))
                }
            }
            is FcitxEvent.ClientPreeditEvent -> clientPreeditCached = event.data
            is FcitxEvent.InputPanelEvent -> inputPanelCached = event.data
            else -> {}
        }
    }

    fun start() {
        if (lifecycle.currentState != FcitxLifecycle.State.STOPPED) {
            Timber.w("Skip starting fcitx: not at stopped state!")
            return
        }
        if (firstRun) {
            registerFcitxEventHandler(::handleFirstRunReadyEvent)
        }
        registerFcitxEventHandler(::handleFcitxEvent)
        lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_START)
        ClipboardManager.addOnUpdateListener(onClipboardUpdate)
        DataManager.addOnNextSyncedCallback {
            FcitxPluginServices.connectAll()
        }
        setupLogStream(AppPrefs.getInstance().internal.verboseLog.getValue())
        dispatcher.start()
    }

    fun stop() {
        if (lifecycle.currentState != FcitxLifecycle.State.READY) {
            Timber.w("Skip stopping fcitx: not at ready state!")
            return
        }
        lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_STOP)
        Timber.i("Fcitx stop()")
        ClipboardManager.removeOnUpdateListener(onClipboardUpdate)
        FcitxPluginServices.disconnectAll()
        dispatcher.stop().let {
            if (it.isNotEmpty())
                Timber.w("${it.size} job(s) didn't get a chance to run!")
        }
        lifecycleRegistry.postEvent(FcitxLifecycle.Event.ON_STOPPED)
        unregisterFcitxEventHandler(::handleFcitxEvent)
        // clear addon graph
        addonGraph = null
        addonReverseDependencies.clear()
    }

}