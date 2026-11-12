package com.kingsman.tanpurakings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media.session.MediaButtonReceiver

/**
 * Foreground service that keeps the process alive while the tanpura drone
 * is playing in the background.
 *
 * Without a foreground service, Android's process-death policy silences
 * audio within ~60 s (or immediately on aggressive OEMs like Xiaomi /
 * Samsung with battery optimisation enabled) of the app leaving the
 * foreground, even though MediaPlayer holds a PARTIAL_WAKE_LOCK.
 * A running foreground service prevents that process death entirely.
 *
 * The notification now uses [MediaNotificationCompat.MediaStyle] which:
 *   • Shows the now-playing card on the lock screen.
 *   • Wires up the Stop action so it appears on the lock screen.
 *   • Routes hardware media-button events through the MediaSession.
 */
class AudioPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID      = "tanpura_playback_channel"
        const val NOTIFICATION_ID = 101

        /**
         * True between startForegroundService() and this service's
         * onStartCommand(). Calling stopService() in that window crashes with
         * ForegroundServiceDidNotStartInTimeException, so AudioManager skips
         * stopService() and the service stops itself once it is running.
         * Only touched on the main thread.
         */
        var startPending = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Enter the foreground immediately: if stopService() races ahead of
        // onStartCommand() (note toggled on/off quickly), Android 12+ throws
        // ForegroundServiceDidNotStartInTimeException unless startForeground()
        // was already called.
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always satisfy the startForegroundService() contract first.
        startForeground(NOTIFICATION_ID, buildNotification())
        startPending = false

        // Route media-button intents (notification Stop, headset) to the session.
        AudioManager.handleMediaButtonIntent(intent)

        // Nothing playing (note stopped before we started, or a headset button
        // woke a dead process) — don't leave a stale notification behind.
        if (!AudioManager.needsPlaybackService) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        // Tapping the notification brings the user back to the main screen.
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action shown on the lock screen / notification shade.
        val stopIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this, android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Tanpura Kings")
            .setContentText("Drone is playing in the background")
            .setContentIntent(openApp)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopIntent
            )
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Attach the MediaSession token so the system knows this notification
        // is the "now playing" card — shows lock-screen artwork, note name, etc.
        AudioManager.mediaSessionToken?.let { token ->
            builder.setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(0)   // show Stop in compact view
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopIntent)
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tanpura Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while the tanpura drone is playing in the background"
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }
}
