package com.lvyoubao.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 后台常驻前台服务（设置 → 桌面与后台 → 「后台常驻运行」开关控制）：
 * 常驻通知 + START_STICKY，让进程不被系统随手回收（灵动模式随身设备场景：随手拿起来就要能用）。
 * 开机后由 BootReceiver 视开关决定是否拉起。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTI_ID = 1001
        const val ACTION_STOP = "com.lvyoubao.app.action.KEEP_ALIVE_STOP"

        /** 启动常驻前台服务（幂等） */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, KeepAliveService::class.java),
            )
        }

        /** 停止常驻服务（开关关闭时调用） */
        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台常驻", NotificationManager.IMPORTANCE_MIN).apply {
                description = "保持旅行宝在后台持续运行"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("旅行宝运行中")
            .setContentText("正在后台待命，点按打开")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        // specialUse 类型仅 34+ 有效；低版本直接普通 startForeground
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTI_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTI_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
