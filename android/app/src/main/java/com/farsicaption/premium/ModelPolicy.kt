package com.farsicaption.premium
internal enum class ModelTier { TURBO, TURBO_COMPACT, SMALL }
internal object ModelPolicy {
    fun useTurbo(totalBytes: Long, availableBytes: Long, lowMemory: Boolean): Boolean =
        !lowMemory && totalBytes >= 6_000_000_000L && availableBytes >= 3_000_000_000L
    fun select(mode: String, totalBytes: Long, availableBytes: Long, lowMemory: Boolean): ModelTier = when {
        mode == "fast" -> ModelTier.SMALL
        mode == "compact" -> ModelTier.TURBO_COMPACT
        useTurbo(totalBytes, availableBytes, lowMemory) -> ModelTier.TURBO
        else -> ModelTier.TURBO_COMPACT
    }
}
