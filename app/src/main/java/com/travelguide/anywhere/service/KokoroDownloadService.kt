package com.travelguide.anywhere.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.travelguide.anywhere.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class KokoroDownloadService : LifecycleService() {

    @Inject lateinit var kokoroModelManager: KokoroModelManager

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIF_ID, buildNotification("Starting download…", -1))
        kokoroModelManager.downloadIfNeeded()
        observeProgress()
        return START_NOT_STICKY
    }

    private fun observeProgress() {
        lifecycleScope.launch {
            kokoroModelManager.state.collect { state ->
                when (state) {
                    is KokoroModelManager.DownloadState.Downloading -> {
                        val pct = (state.progress * 100).toInt()
                        notify("Downloading AI voice model… $pct%", pct)
                    }
                    is KokoroModelManager.DownloadState.Extracting ->
                        notify("Extracting model (2–3 min)…", -1)
                    is KokoroModelManager.DownloadState.Ready -> {
                        notify("AI voice ready!", 100)
                        stopSelf()
                    }
                    is KokoroModelManager.DownloadState.Error -> {
                        notify("Download failed — tap Retry in the app", -1)
                        stopSelf()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "Model Download", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Kokoro TTS model download progress" }
        notificationManager.createNotificationChannel(ch)
    }

    // progress = -1 → indeterminate
    private fun buildNotification(text: String, progress: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Travel Guide")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tour_guide)
            .apply {
                if (progress < 0) setProgress(0, 0, true)
                else setProgress(100, progress, false)
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun notify(text: String, progress: Int) =
        notificationManager.notify(NOTIF_ID, buildNotification(text, progress))

    companion object {
        private const val CHANNEL_ID = "kokoro_download"
        const val NOTIF_ID = 1002

        fun start(context: Context) =
            context.startForegroundService(Intent(context, KokoroDownloadService::class.java))
    }
}
