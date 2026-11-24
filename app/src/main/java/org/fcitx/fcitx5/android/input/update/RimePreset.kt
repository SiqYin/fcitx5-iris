/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 SiqYin
 */
package org.fcitx.fcitx5.android.input.update

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * 首次启动时把 APK 内的预编译产物「认领」为已部署，并写入默认方案。
 *
 * ## 背景：Rime 为什么要「部署」
 *
 * librime 启动时若判定 workspace 有改动，会排 `workspace_update`，
 * 把四个方案的全部词典重新编译一遍。搜狗表有 104 万词条，在手机上重编要几分钟到几十分钟，
 * 并且极易 OOM——这就是「加载不出来 / 遇到了一个错误」的由来。
 *
 * APK 里已经带着构建机上编好的 `table/prism/reverse` 产物（`usr/share/rime-data/build/`）。
 * `workspace_update` 里的 `DictCompiler` 会按 `dict_file_checksum` 比对：
 * 命中就**直接复用**已有产物，不重编。所以只要有产物在，部署就是秒级。
 *
 * ## 本类的职责
 *
 * 在 fcitx 启动**之前**（`Fcitx.nativeStartup` 里、`startupFcitx` 之前）把
 * `user_data/rime/user.yaml` 准备好：
 *
 *  - `var/last_build_time` 写成**将来**的时间，让 `detect_modifications` 直接判定「无改动」，
 *    连那次秒级部署都不发生；
 *  - `var/previously_selected_schema` 写入本发行版的默认方案（switcher 启动时据此选方案）。
 *
 * 只有在 buildinfo 变化（首次安装 / 覆盖升级换了词库 / 换了发行版）时才重写，
 * 用户之后自己切换方案不会被覆盖。
 *
 * **这里绝不做任何编译或 deploy 调用。**
 */
object RimePreset {

    private const val BUILDINFO_ASSET = "usr/share/rime-data/build/buildinfo.txt"

    /** 预置标记文件名（放在 rime 用户目录里） */
    private const val MARKER = ".iris_buildinfo"

    /**
     * 预置 user.yaml。
     *
     * @param defaultScheme 本发行版的默认方案 id（BuildConfig.DEFAULT_SCHEME）
     * @param dataDir DataManager 同步后的 assets 根目录（其下有 usr/share/rime-data/build）
     * @param rimeUserDir Rime 用户目录（<extData>/data/rime）
     * @return true 表示这次真的重写了预置
     */
    fun prepare(
        defaultScheme: String,
        dataDir: File,
        rimeUserDir: File
    ): Boolean {
        val buildinfo = runCatching {
            appContext.assets.open(BUILDINFO_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: run {
            Timber.w("RimePreset: no buildinfo in assets, skip preset")
            return false
        }
        val stamp = sha256Prefix(buildinfo)

        val marker = File(rimeUserDir, MARKER)
        // 已预置过同一份词库，且默认方案没变 → 什么都不做
        if (marker.exists() && marker.readText() == markerContent(stamp, defaultScheme)) {
            Timber.d("RimePreset: already prepared for $stamp/$defaultScheme")
            return false
        }

        val prebuiltDir = File(dataDir, "usr/share/rime-data/build")
        if (!prebuiltDir.isDirectory) {
            // 没有预编译产物（本地直接构建）：交给 Rime 常规部署
            Timber.w("RimePreset: prebuilt dir missing, fall back to normal deployment")
            return false
        }

        // 用户自己放过 yaml（自行改过方案）：不覆盖他的设置
        val hasCustom = rimeUserDir.listFiles { f ->
            f.isFile && f.name.endsWith(".yaml") &&
                    f.name != "user.yaml" && f.name != "installation.yaml"
        }?.isNotEmpty() ?: false
        if (hasCustom) {
            Timber.i("RimePreset: custom yaml present, leave user settings alone")
            return false
        }

        rimeUserDir.mkdirs()
        // 注意：**不删** user_data/build。
        //
        // 早先这里是 deleteRecursively()，理由是「手机端旧产物会遮蔽 APK 里的新词库」
        // （staging_dir 优先于 prebuilt_data_dir）。但删掉后 staging 为空，
        // 一旦后续有任何路径触发 workspace_update，DictCompiler 在 staging 找不到
        // 产物、只能从 .dict.yaml 源码重编 —— 104 万条搜狗表在手机上就是几分钟起步。
        //
        // 保留旧产物则相反：checksum 不一致时 DictCompiler 会重建（rebuild_table=true），
        // 一致时直接复用。两种情况都不会因为「文件不存在」而走全量源码编译。

        // last_build_time 取「将来」：
        // DetectModifications 的判据是 last_modified > last_build_time，
        // 而 last_modified 是 shared/user 顶层 *.yaml 的 mtime ——那段 yaml 由 DataManager
        // 在启动时解压，mtime 就是解压那一刻，必然晚于「now」。
        // 写 now 会导致判定「有改动」→ 排 workspace_update（虽然能按 checksum 复用到秒级，
        // 但没必要）。写 now+60 可以稳定跳过。
        // 这不影响用户手动点「部署」：deploy() 走 rimeStart(true) 不做检测，直接复用产物。
        val future = System.currentTimeMillis() / 1000 + 60
        File(rimeUserDir, "user.yaml").writeText(
            buildString {
                append("var:\n")
                append("  last_build_time: ").append(future).append('\n')
                append("  previously_selected_schema: ").append(defaultScheme).append('\n')
            }
        )
        marker.writeText(markerContent(stamp, defaultScheme))
        // 预置成功 = 已经「部署」过了：librime 的 detect_modifications 会直接判定无改动，
        // 一次编译都不会发生。同步把自动部署的 stamp 也写上，避免 RimeAutoDeploy
        // 再主动跑一轮 deploy()（那会 finalize 引擎并排 workspace_update）。
        AppPrefs.getInstance().internal.rimeDeployedStamp.setValue(stamp)
        Timber.i("RimePreset: prepared (buildinfo=$stamp, scheme=$defaultScheme)")
        true
    }

    private fun markerContent(stamp: String, scheme: String) = "$stamp/$scheme"

    private fun sha256Prefix(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(16)
    }
}
