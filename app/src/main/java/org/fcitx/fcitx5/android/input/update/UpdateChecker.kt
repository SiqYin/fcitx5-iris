/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 SiqYin
 */
package org.fcitx.fcitx5.android.input.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 更新检测：查 GitHub Releases 最新 tag，与当前 versionName 比较。
 *
 * 语义（对应用户要求「同一版本只提醒一次」）：
 *  - 每次调出输入法检测一次；没更新就静默。
 *  - 有更新且这一版还没提醒过 → 返回 [Result]，由调用方发一条系统通知。
 *  - **通知一旦弹出就立刻记下该版本号**（[markNotified]）：此后无论用户点了
 *    「更新」、点掉通知、还是把通知划掉不管，只要最新版仍是这一版都不再提醒；
 *    只有发布了更高版本才会再次提醒。
 */
object UpdateChecker {

    /** 仓库坐标：写死，避免被降级攻击改指向 */
    private const val OWNER = "SiqYin"
    private const val REPO = "fcitx5-iris"

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 「更新」按钮打开的页面（latest，不写死版本号） */
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases/latest"

    data class Result(val latestVersion: String, val pageUrl: String)

    /**
     * 检测是否有新版本。
     *
     * @return 需要提醒时返回 [Result]；无更新、这一版已提醒过、或网络失败时返回 null。
     */
    suspend fun checkForUpdate(): Result? = withContext(Dispatchers.IO) {
        val current = currentVersionName()
        if (current.isEmpty()) return@withContext null
        val latest = runCatching { fetchLatestTag() }.getOrElse {
            // 网络不可用属正常情况（没网、被墙），静默
            Timber.d(it, "UpdateChecker: fetch failed")
            return@withContext null
        }
        if (latest.isEmpty()) return@withContext null
        if (!isNewer(latest, current)) return@withContext null
        // 这一版已提醒过 → 不再打扰（无论用户当时是否处理过）
        val notified = AppPrefs.getInstance().internal.updateNotifiedFor.getValue()
        if (notified == latest) {
            Timber.d("UpdateChecker: $latest already notified, stay silent")
            return@withContext null
        }
        Timber.i("UpdateChecker: $current -> $latest available")
        Result(latest, RELEASES_PAGE)
    }

    /**
     * 当前安装的 versionName（形如 `1.0.0` 或 `1.0.0-iris-wugniu`）。
     *
     * 取 PackageManager 而不是 BuildConfig：前者就是 APK 清单里的值，
     * 与 Release tag 严格对应，且不受构建期 git 状态影响。
     */
    private fun currentVersionName(): String = runCatching {
        appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .versionName
            .orEmpty()
            .trim()
    }.getOrElse { "" }

    /**
     * 记下「已经提醒过这一版」。
     *
     * 必须在**通知成功弹出后**立即调用：这样即使通知被用户划掉、
     * 或用户压根没理，也不会在下一次调出键盘时重复提醒。
     */
    fun markNotified(version: String) {
        AppPrefs.getInstance().internal.updateNotifiedFor.setValue(version)
    }

    /**
     * 取最新 release 的 tag（去掉开头的 v）。
     *
     * 只用 /releases/latest：它天然跳过 draft 与 prerelease，
     * 也不会像 /releases 那样把历史版本按时间混在一起。
     */
    private fun fetchLatestTag(): String {
        val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "IrisKeyboard/${currentVersionName()}")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Timber.d("UpdateChecker: HTTP ${conn.responseCode}")
                return ""
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val tag = JSONObject(body).optString("tag_name", "")
            return tag.trim().removePrefix("v").removePrefix("V")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 语义化版本比较：`a` 是否比 `b` 新。
     *
     * 按 `.` 分段逐段比数字；非数字段（如 `1.0.0-rc1`）退化成字符串比较，
     * 保证不会因为段数不同而漏判（1.0.1 > 1.0）。
     */
    internal fun isNewer(a: String, b: String): Boolean {
        val pa = a.split('.', '-', '+')
        val pb = b.split('.', '-', '+')
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val xa = pa.getOrNull(i) ?: "0"
            val xb = pb.getOrNull(i) ?: "0"
            val na = xa.toIntOrNull()
            val nb = xb.toIntOrNull()
            val cmp = if (na != null && nb != null) na.compareTo(nb) else xa.compareTo(xb)
            if (cmp != 0) return cmp > 0
        }
        return false
    }
}
