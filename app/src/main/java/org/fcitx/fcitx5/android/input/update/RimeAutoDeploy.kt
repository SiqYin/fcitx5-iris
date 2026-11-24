/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 SiqYin
 */
package org.fcitx.fcitx5.android.input.update

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.security.MessageDigest

/**
 * Rime 部署的「是否需要执行」判定与标记。
 *
 * ## 为什么要自动部署
 *
 * APK 里带着预编译产物，正常情况下 librime 的 detect_modifications 会判定「无改动」，
 * 一次编译都不发生。但只要预置没能生效（首次安装的时序、用户改过 yaml、
 * 覆盖升级换了词库），Rime 就会停在 maintenance 状态 —— 键盘弹出却打不了字，
 * 必须用户自己去菜单里点一次「部署」。这一步本类负责替用户做掉。
 *
 * ## 为什么必然是秒级
 *
 * deploy() → rimeStart(true) → workspace_update → 每个 schema 走 SchemaUpdate
 * → DictCompiler::Compile()。后者先算源 `*.dict.yaml` 的 dict_file_checksum，
 * 与 `table.bin` 里记录的值比对：**一致就 reuse existing table，不重建**；
 * prism / reverse 同理。所以只要 APK 里同时有产物和与之匹配的源文件，
 * 这一轮就是「比对 checksum + 加载」，一秒内结束。
 *
 * （1.0.0 之所以在设备上从头编 104 万条：APK 装的搜狗表是上游原文件、无拼音码列，
 *   而产物是按 CI 补码后的文件编的 → checksum 永远不一致。已由 CMakeLists
 *   安装 `rime-wugniu-suwu/build-src/` 下的补码版本修正。）
 *
 * ## 调用时机
 *
 * 只在 **fcitx 启动完成、还没有输入视图挂着** 时调用（`Fcitx.nativeStartup` 末尾）。
 * deploy() 内部会 finalize 引擎，若在键盘显示期间执行，用户会看到「遇到了一个错误」。
 */
object RimeAutoDeploy {

    private const val BUILDINFO_ASSET = "usr/share/rime-data/build/buildinfo.txt"

    /** 本次启动已经算过的 stamp，避免同一进程内重复判定 */
    @Volatile
    private var pendingStamp: String? = null

    /**
     * 当前 APK 的词库是否需要（重新）部署。
     *
     * 预置成功时 [RimePreset] 已经把 stamp 写好了，这里会返回 false —— 也就是说
     * 正常安装/升级根本不会走到 deploy()，键盘一弹出就能打字。
     * 只有预置没能生效（用户改过 yaml、产物缺失等）才会真正跑一轮部署兜底。
     */
    fun shouldDeploy(): Boolean {
        val stamp = runCatching { currentStamp() }.getOrNull() ?: return false
        if (stamp.isEmpty()) return false
        pendingStamp = stamp
        val done = AppPrefs.getInstance().internal.rimeDeployedStamp.getValue() == stamp
        if (!done) Timber.i("RimeAutoDeploy: preset did not apply, will deploy for $stamp")
        return !done
    }

    /** 部署成功后记录 stamp，同一份词库下次启动不再重复 */
    fun markDeployed() {
        val stamp = pendingStamp ?: return
        AppPrefs.getInstance().internal.rimeDeployedStamp.setValue(stamp)
        pendingStamp = null
        Timber.i("RimeAutoDeploy: marked deployed for $stamp")
    }

    private fun currentStamp(): String {
        val text = appContext.assets.open(BUILDINFO_ASSET).bufferedReader().use { it.readText() }
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(16)
    }
}
