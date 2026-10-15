package com.farsicaption.premium
import android.content.Context
/** Identity checks and writes are one serialized transaction. */
internal object TranscriptionJobStore {
    @Synchronized fun start(context: Context, identity: JobIdentity) {
        context.getSharedPreferences(TranscriptionService.PREFS, Context.MODE_PRIVATE).edit()
            .putString(TranscriptionService.KEY_JOB_ID, identity.jobId).putString(TranscriptionService.KEY_URI, identity.sourceUri)
            .putString(TranscriptionService.KEY_STATE, TranscriptionService.STATE_RUNNING)
            .putString(TranscriptionService.KEY_STATUS, "در حال آماده‌سازی تشخیص گفتار…")
            .putInt(TranscriptionService.KEY_PROGRESS, 0).remove(TranscriptionService.KEY_RESULT).commit()
    }
    @Synchronized fun matches(context: Context, identity: JobIdentity): Boolean {
        val p = context.getSharedPreferences(TranscriptionService.PREFS, Context.MODE_PRIVATE)
        return identity.matches(p.getString(TranscriptionService.KEY_JOB_ID, null), p.getString(TranscriptionService.KEY_URI, null))
    }
    @Synchronized fun update(context: Context, identity: JobIdentity, state: String, status: String, result: String? = null): Boolean {
        if (!matches(context, identity)) return false
        // A late upload callback must not turn a cancelled/finished job back into running.
        if (state == TranscriptionService.STATE_RUNNING && context.getSharedPreferences(TranscriptionService.PREFS, Context.MODE_PRIVATE)
                .getString(TranscriptionService.KEY_STATE, "") != TranscriptionService.STATE_RUNNING) return false
        val editor = context.getSharedPreferences(TranscriptionService.PREFS, Context.MODE_PRIVATE).edit()
            .putString(TranscriptionService.KEY_STATE, state).putString(TranscriptionService.KEY_STATUS, status)
        if (result != null) editor.putString(TranscriptionService.KEY_RESULT, result)
        editor.commit()
        return true
    }
    @Synchronized fun progress(context: Context, identity: JobIdentity, progress: Int) {
        if (matches(context, identity)) context.getSharedPreferences(TranscriptionService.PREFS, Context.MODE_PRIVATE)
            .edit().putInt(TranscriptionService.KEY_PROGRESS, progress.coerceIn(0, 100)).apply()
    }
    @Synchronized fun detach(context: Context) {
        context.getSharedPreferences(TranscriptionService.PREFS, Context.MODE_PRIVATE).edit()
            .remove(TranscriptionService.KEY_JOB_ID).remove(TranscriptionService.KEY_URI)
            .remove(TranscriptionService.KEY_STATE).remove(TranscriptionService.KEY_RESULT).commit()
    }
}
