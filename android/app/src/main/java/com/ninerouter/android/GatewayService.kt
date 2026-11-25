package com.ninerouter.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.ninerouter.android.server.Gateway
import com.ninerouter.android.server.GatewayState

class GatewayService : Service() {
    private var gateway: Gateway? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        gateway = Gateway(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        gateway?.start()
        return START_STICKY
    }

    override fun onDestroy() {
        gateway?.stop()
        gateway = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_description) }
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("9Router gateway")
            .setContentText("Local endpoint ${GatewayState.BASE_URL}")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "ninerouter_gateway"
        const val NOTIFICATION_ID = 20128
    }
}
