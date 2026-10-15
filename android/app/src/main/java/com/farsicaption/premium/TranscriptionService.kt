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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class TranscriptionService : Service() {
    companion object {
        const val ACTION_START = "com.farsicaption.premium.START_TRANSCRIPTION"
        const val ACTION_CANCEL = "com.farsicaption.premium.CANCEL_TRANSCRIPTION"
        const val EXTRA_URI = "uri"
        const val EXTRA_MAX_WORDS = "max_words"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_MODE = "model_mode"
        const val EXTRA_JOB_ID = "job_id"
        const val KEY_JOB_ID = "job_id"
        const val KEY_URI = "source_uri"
        const val PREFS = "transcription_job"
        const val KEY_STATE = "state"
        const val KEY_STATUS = "status"
        const val KEY_RESULT = "result"
        const val KEY_PROGRESS = "model_progress"
        const val STATE_RUNNING = "running"
        const val STATE_DONE = "done"
        const val STATE_ERROR = "error"
        private const val CHANNEL_ID = "transcription"
        private const val NOTIFICATION_ID = 41
        private val nativeGate = Mutex()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activeJob: Job? = null
    @Volatile private var activeEngine: LocalCaptionEngine? = null
    @Volatile private var activeOnlineEngine: GroqCaptionEngine? = null
    @Volatile private var activeIdentity: JobIdentity? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "تشخیص گفتار", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val identity = activeIdentity
            if (identity != null && identity.jobId == intent.getStringExtra(EXTRA_JOB_ID)) {
                TranscriptionJobStore.update(this, identity, STATE_ERROR, "تشخیص لغو شد؛ زیرنویس قبلی حفظ شد")
                activeEngine?.cancel(); activeOnlineEngine?.cancel(); activeJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: return START_NOT_STICKY
        val maxWords = intent.getIntExtra(EXTRA_MAX_WORDS, 5)
        val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "fa"
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "quality"
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return START_NOT_STICKY
        val identity = JobIdentity(jobId, uri.toString())
        if (!TranscriptionJobStore.matches(this, identity)) return START_NOT_STICKY
        if (activeIdentity == identity && activeJob?.isActive == true) return START_REDELIVER_INTENT
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATE, "") != STATE_RUNNING) {
            stopSelf(startId); return START_NOT_STICKY
        }
        activeEngine?.cancel(); activeOnlineEngine?.cancel(); activeJob?.cancel()
        activeIdentity = identity
        startForeground(NOTIFICATION_ID, notification("در حال آماده‌سازی…"))
        activeJob = scope.launch {
            try {
                nativeGate.withLock {
                    if (!TranscriptionJobStore.matches(applicationContext, identity)) return@withLock
                    val report: (String) -> Unit = { status ->
                        if (TranscriptionJobStore.update(applicationContext, identity, STATE_RUNNING, status)) {
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
                        }
                    }
                    val progress: (Int) -> Unit = { TranscriptionJobStore.progress(applicationContext, identity, it) }
                    val result = if (GroqMode.isOnline(mode)) {
                        val engine = GroqCaptionEngine(applicationContext)
                        activeOnlineEngine = engine
                        try { engine.transcribe(uri, maxWords, language, mode, report, progress) }
                        finally { if (activeOnlineEngine === engine) activeOnlineEngine = null }
                    } else {
                        val engine = LocalCaptionEngine(applicationContext)
                        activeEngine = engine
                        try { engine.transcribe(uri, maxWords, language, mode, report, progress) }
                        finally { if (activeEngine === engine) activeEngine = null }
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val array = CaptionJson.encode(result.captions)
                    val message = (if (result.captions.isEmpty()) "گفتاری پیدا نشد؛ زیرنویسی اضافه نشد" else "زیرنویس آماده شد • ${result.captions.size} قطعه") + " • ${result.engineLabel}"
                    if (TranscriptionJobStore.update(applicationContext, identity, STATE_DONE, message, array.toString())) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = e.message ?: "خطا در تشخیص گفتار"
                if (TranscriptionJobStore.update(applicationContext, identity, STATE_ERROR, message)) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
                }
            }
            finally {
                if (activeIdentity == identity) { stopForeground(STOP_FOREGROUND_DETACH); stopSelf(startId) }
            }
        }
        return START_REDELIVER_INTENT
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
        activeEngine?.cancel()
        activeOnlineEngine?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
