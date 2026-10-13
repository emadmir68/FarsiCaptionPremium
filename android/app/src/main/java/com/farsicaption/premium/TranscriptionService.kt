package com.farsicaption.premium

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class TranscriptionService : Service() {
    companion object {
        const val ACTION_START = "com.farsicaption.premium.START_TRANSCRIPTION"
        const val EXTRA_URI = "uri"
        const val EXTRA_MAX_WORDS = "max_words"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_JOB_ID = "job_id"
        const val KEY_JOB_ID = "job_id"
        const val KEY_URI = "source_uri"
        const val PREFS = "transcription_job"
        const val KEY_STATE = "state"
        const val KEY_STATUS = "status"
        const val KEY_RESULT = "result"
        const val STATE_RUNNING = "running"
        const val STATE_DONE = "done"
        const val STATE_ERROR = "error"
        private const val CHANNEL_ID = "transcription"
        private const val NOTIFICATION_ID = 41
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "تشخیص گفتار", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: return START_NOT_STICKY
        val maxWords = intent.getIntExtra(EXTRA_MAX_WORDS, 5)
        val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "fa"
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return START_NOT_STICKY
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_JOB_ID, jobId)
            .putString(KEY_URI, uri.toString())
            .putString(KEY_STATE, STATE_RUNNING)
            .putString(KEY_STATUS, "در حال آماده‌سازی تشخیص گفتار…")
            .remove(KEY_RESULT).commit()
        startForeground(NOTIFICATION_ID, notification("در حال آماده‌سازی…"))

        scope.launch {
            runCatching {
                LocalCaptionEngine(applicationContext).transcribe(
                    uri = uri,
                    maxWords = maxWords,
                    language = language,
                    onStatus = { status ->
                        updateState(jobId, STATE_RUNNING, status)
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification(status))
                    },
                )
            }.onSuccess { result ->
                val array = JSONArray()
                result.captions.forEach { c ->
                    array.put(JSONObject().put("text", c.text).put("start", c.start).put("end", c.end))
                }
                if (isCurrentJob(jobId)) getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_STATE, STATE_DONE)
                    .putString(KEY_STATUS, "زیرنویس آماده شد • ${result.captions.size} قطعه")
                    .putString(KEY_RESULT, array.toString()).apply()
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("زیرنویس آماده شد"))
            }.onFailure { e ->
                updateState(jobId, STATE_ERROR, e.message ?: "خطا در تشخیص گفتار")
            }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
        return START_REDELIVER_INTENT
    }

    private fun isCurrentJob(jobId: String): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_JOB_ID, null) == jobId

    private fun updateState(jobId: String, state: String, status: String) {
        if (!isCurrentJob(jobId)) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STATE, state).putString(KEY_STATUS, status).apply()
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("Farsi Caption")
        .setContentText(text)
        .setOngoing(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATE, "") == STATE_RUNNING)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
