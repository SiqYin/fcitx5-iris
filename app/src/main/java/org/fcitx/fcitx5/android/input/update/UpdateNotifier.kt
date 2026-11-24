/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 SiqYin
 */
package org.fcitx.fcitx5.android.input.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.notificationManager

/**
 * 更新提醒：以**系统通知**形式弹出（不再用键盘内的对话框）。
 *
 * 通知下方带一个「更新」按钮，点击直接跳 Release 最新版页面；
 * 点通知本体同样跳转。用户点过「稍后」后同一版本不再提示，见 [UpdateChecker]。
 */
object UpdateNotifier {

    private const val CHANNEL_ID = "app-update"

    private const val NOTIFY_ID = 0x1715

    /** 弹出（或替换）更新通知 */
    fun show(context: Context, latestVersion: String, pageUrl: String) {
        createChannel(context)

        val pageIntent = Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val contentIntent = PendingIntent.getActivity(
            context, 0, pageIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 「更新」按钮：requestCode 与内容点击区分开，否则会被当成同一个 PendingIntent
        val actionIntent = PendingIntent.getActivity(
            context, 1, pageIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_sync_24)
            .setContentTitle(context.getText(R.string.update_available_title))
            .setContentText(context.getString(R.string.update_available_message, latestVersion))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.update_available_message, latestVersion))
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .addAction(0, context.getText(R.string.update_now), actionIntent)
            .setAutoCancel(true)
            .build()
            .let { context.notificationManager.notify(NOTIFY_ID, it) }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getText(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getText(R.string.update_channel_description).toString()
            }
            context.notificationManager.createNotificationChannel(channel)
        }
    }

    /** 需要通知权限时用 appContext 判断（IME 进程与主进程共享同一份权限状态） */
    fun canNotify(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }.getOrElse { false }
}
