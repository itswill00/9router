package com.ninerouter.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.ninerouter.android.data.Exposure
import com.ninerouter.android.data.Store
import com.ninerouter.android.ui.tr
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
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            GatewayState.error.value = "Foreground service failed: ${e.message ?: e.javaClass.simpleName}"
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REBIND && gateway != null) {
            gateway?.stop()
            gateway?.start()
            if (GatewayState.running.value != true) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        if (gateway == null) gateway = Gateway(applicationContext)
        gateway?.start()
        if (GatewayState.running.value != true) {
            // Port conflict or bind failure: do not keep a sticky foreground
            // service that advertises an endpoint nobody can reach.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
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
            .setContentTitle(tr("9Router gateway"))
            .setContentText(
                if (Exposure.enabled(Store.state.settings)) tr("Remote access on · port ${GatewayState.PORT}")
                else tr("Local endpoint ${GatewayState.BASE_URL}"),
            )
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ninerouter_gateway"
        private const val NOTIFICATION_ID = 20128
        private const val ACTION_REBIND = "com.ninerouter.android.REBIND"

        /** Starts the gateway in the configured exposure mode. Safe to call repeatedly. */
        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                GatewayState.error.value = "Cannot start gateway: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }

        /** Stops the service, then starts a fresh instance after the old socket closes. */
        fun restart(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                start(context)
            }, 400)
        }

        /**
         * Rebinds the listener inside the running service. Exposure changes must
         * not restart the service from the background (Android 12+ blocks it).
         */
        fun rebind(context: Context) {
            if (GatewayState.running.value != true) {
                start(context)
                return
            }
            val intent = Intent(context, GatewayService::class.java).setAction(ACTION_REBIND)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                GatewayState.error.value = "Cannot rebind gateway: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
}
