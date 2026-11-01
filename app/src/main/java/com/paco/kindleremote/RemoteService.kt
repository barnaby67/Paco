package com.paco.kindleremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder

/**
 * Keeps the Kindle connection alive in the background and listens for the
 * volume buttons through a media session, so page turns keep working when
 * the app isn't on screen.
 */
class RemoteService : Service() {

    private lateinit var remote: KindleRemote
    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        remote = KindleRemote(this) { setStatus(it) }
        remote.start()

        session = MediaSession(this, "KindleRemote").apply {
            setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction < 0) remote.nextPage() else if (direction > 0) remote.previousPage()
                }
            })
            setPlaybackState(
                PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build()
            )
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session.release()
        remote.stop()
        instance = null
        setStatus("Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("remote", "Kindle remote", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RemoteService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "remote")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Kindle remote is on")
            .setContentText("Volume buttons turn pages")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "stop"
        var instance: RemoteService? = null
            private set
        var status = "Stopped"
            private set
        var statusListener: ((String) -> Unit)? = null

        fun nextPage() = instance?.remote?.nextPage()
        fun previousPage() = instance?.remote?.previousPage()

        private fun setStatus(s: String) {
            status = s
            statusListener?.invoke(s)
        }
    }
}
