package org.claudevoice.mobile.app

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
import org.claudevoice.mobile.core.PhoneAgent
import org.claudevoice.mobile.core.TailnetState

/**
 * The one foreground service (ADR-CVM-003): it keeps the agent listening for the hub on the
 * tailnet address, following Tailscale as it comes and goes. It decides nothing; the core does.
 * It runs only once the owner has confirmed the phone's name, since before that there is no
 * agent. Turning it off comes with the plugin switches; until then, force-stopping the app does.
 */
class ListenerService : Service() {
    private var agent: PhoneAgent? = null
    private var watcher: TailnetWatcher? = null

    override fun onCreate() {
        super.onCreate()
        foreground(NOTICE, notice())
        val found = (application as App).agent()
        if (found == null) {
            stopSelf()
            return
        }
        agent = found
        watcher = TailnetWatcher(this) { found.follow(it) }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watcher?.stop()
        // Closed as if the network went, not stopped for good: the agent lives as long as the
        // app, and a service started again later must be able to listen again.
        agent?.follow(TailnetState.NoNetwork)
        super.onDestroy()
    }

    private fun notice(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.listener_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.listener_running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun foreground(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }

    companion object {
        private const val CHANNEL = "listener"
        private const val NOTICE = 1

        /** Starts the listener, from the app in the foreground, as Android 12 and later require. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, ListenerService::class.java))
        }
    }
}
