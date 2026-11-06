package dev.junjo.eztranscribe

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** What the progress notification shows while a job keeps the app awake. */
data class ForegroundWork(val title: String, val text: String, val percent: Int?)

/**
 * Keeps the app running while a transcription is under way, so leaving the app does not freeze it:
 * without this, Android's cached-app freezer suspended a Whisper run the moment the screen went to
 * another app. It does no work itself and exists only while TranscriptionHub has a job running.
 */
class TranscriptionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val work = current ?: ForegroundWork("Transcribing a voice note", "Starting", null)
        // Not ServiceCompat.startForeground: on Android 17 it took its Android 14 path, which strips
        // the media-processing type it does not know, and Android refused a service of type "none".
        startForeground(NOTIFICATION_ID, progressNotification(this, work), serviceType())
        // Nothing to redo if the system kills it: the job's state lived in the same process.
        return START_NOT_STICKY
    }

    /** Android 15's limit for media processing; reaching it means something has hung. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service time limit reached; stopping")
        stopSelf()
    }

    companion object {
        private const val TAG = "TranscriptionService"
        private const val CHANNEL_PROGRESS = "transcription"
        private const val CHANNEL_DONE = "transcription_done"
        private const val NOTIFICATION_ID = 1
        private const val DONE_NOTIFICATION_ID = 2

        @Volatile
        private var current: ForegroundWork? = null
        private var running = false

        /**
         * Starts, updates or stops the service to match [work]. Called on the main thread with
         * every change; null means nothing is running, so the service goes away at once.
         */
        fun sync(context: Context, work: ForegroundWork?, announceWhenDone: Boolean) {
            val wasRunning = running
            current = work
            if (work == null) {
                if (wasRunning) {
                    context.stopService(Intent(context, TranscriptionService::class.java))
                    running = false
                    if (announceWhenDone) postDone(context)
                }
                return
            }
            ensureChannels(context)
            if (!wasRunning) {
                // Started from a tap in the app, which is when Android allows it.
                ContextCompat.startForegroundService(context, Intent(context, TranscriptionService::class.java))
                running = true
            } else {
                notify(context, NOTIFICATION_ID, progressNotification(context, work))
            }
        }

        private fun serviceType(): Int = when {
            // The type made for this: transcoding, transcription and other long media jobs.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }

        private fun progressNotification(context: Context, work: ForegroundWork): Notification =
            NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(work.title)
                .setContentText(work.text + (work.percent?.let { " · $it%" } ?: ""))
                .setProgress(100, work.percent ?: 0, work.percent == null)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openApp(context))
                .build()

        /** Only when no screen is showing: the transcript itself stays in the app, not the shade. */
        private fun postDone(context: Context) {
            val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Transcript ready")
                .setContentText("Tap to read it.")
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(openApp(context))
                .build()
            notify(context, DONE_NOTIFICATION_ID, notification)
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Without the notification permission, posting is skipped; the service still runs. */
        private fun notify(context: Context, id: Int, notification: Notification) {
            try {
                NotificationManagerCompat.from(context).notify(id, notification)
            } catch (e: SecurityException) {
                Log.d(TAG, "notifications not allowed", e)
            }
        }

        private fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Transcription in progress", NotificationManager.IMPORTANCE_LOW),
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Transcript ready", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}
