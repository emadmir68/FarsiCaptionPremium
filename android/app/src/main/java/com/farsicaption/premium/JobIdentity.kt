package com.farsicaption.premium
internal data class JobIdentity(val jobId: String, val sourceUri: String) {
    fun matches(otherId: String?, otherUri: String?): Boolean =
        jobId.isNotBlank() && sourceUri.isNotBlank() && jobId == otherId && sourceUri == otherUri
}
internal class ResultDelivery(initialConsumedJob: String? = null) {
    private var consumedJob: String? = initialConsumedJob
    fun consume(expected: JobIdentity, actualId: String?, actualUri: String?, done: Boolean): Boolean {
        if (!done || !expected.matches(actualId, actualUri) || consumedJob == actualId) return false
        consumedJob = actualId
        return true
    }
}
