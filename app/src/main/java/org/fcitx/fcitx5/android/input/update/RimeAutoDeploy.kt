/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 SiqYin
 */
package org.fcitx.fcitx5.android.input.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.security.MessageDigest

/**
 * 首次调出输入法时自动部署。
 *
 * 为什么需要它：APK 里带着在构建机上预编译好的 rime 产物，正常路径下
 * `Fcitx.prepareRimePrebuiltDeployment()` 已经让 librime 判定「无修改」，
 * 一次编译都不发生。但只要下面任一情况出现，Rime 就会停在
 * maintenance（部署中）状态、键盘出字前必须先点一次「部署」：
 *
 *  - 用户第一次在系统设置里勾选本输入法（覆盖安装后 user_data 是全新的）；
 *  - 用户放过自定义 yaml，走的是常规部署路径；
 *  - 覆盖升级换了词库（buildinfo.txt 变了）。
 *
 * 这里在**键盘第一次被调出**时判断一次「当前 APK 的 buildinfo 是否已部署过」，
 * 没部署过就自己触发一轮，用户不需要点任何东西。
 *
 * 判据用 [AppPrefs.Internal.rimeDeployedStamp]（上一次成功自动部署时的 buildinfo 摘要）
 * 而不是每次启动都跑：部署本身要一两秒，不能每次弹键盘都做。
 */
object RimeAutoDeploy {

    /** buildinfo.txt 在 assets 里的路径（与 Fcitx.prepareRimePrebuiltDeployment 一致） */
    private const val BUILDINFO_ASSET = "usr/share/rime-data/build/buildinfo.txt"

    /**
     * 若本 APK 的词库还没部署过，就跑一轮部署。
     *
     * 需要在 fcitx 已就绪后调用（键盘调出时必然已就绪）。
     *
     * @return true 表示这次真的执行了部署。
     */
    suspend fun ensureDeployed(fcitx: FcitxAPI): Boolean = withContext(Dispatchers.IO) {
        val stamp = runCatching { currentStamp() }.getOrNull()
        if (stamp.isNullOrEmpty()) {
            // APK 内没有预编译产物（本地直接构建），交给 Rime 的常规部署路径
            Timber.d("RimeAutoDeploy: no buildinfo in APK, skip")
            return@withContext false
        }
        val prefs = AppPrefs.getInstance().internal
        if (prefs.rimeDeployedStamp.getValue() == stamp) {
            Timber.d("RimeAutoDeploy: already deployed for $stamp")
            return@withContext false
        }
        Timber.i("RimeAutoDeploy: deploying for $stamp")
        runCatching { fcitx.deployRime() }
            .onSuccess {
                prefs.rimeDeployedStamp.setValue(stamp)
                Timber.i("RimeAutoDeploy: done")
            }
            .onFailure {
                // 失败就不记标记，下次调出再试
                Timber.w(it, "RimeAutoDeploy: deploy failed")
            }
        prefs.rimeDeployedStamp.getValue() == stamp
    }

    /**
     * 当前 APK 内 buildinfo.txt 的摘要。
     *
     * buildinfo.txt 里含 schema 哈希、librime 版本与构建时间戳，
     * 覆盖升级换词库时必然变化，正好当版本戳用。
     */
    private fun currentStamp(): String {
        val text = appContext.assets.open(BUILDINFO_ASSET)
            .bufferedReader().use { it.readText() }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
