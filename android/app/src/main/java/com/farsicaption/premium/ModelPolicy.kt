package com.farsicaption.premium
internal enum class ModelTier { TURBO, TURBO_COMPACT, SMALL, PERSIAN_TURBO }
internal object ModelPolicy {
    fun useTurbo(totalBytes: Long, availableBytes: Long, lowMemory: Boolean): Boolean =
        !lowMemory && totalBytes >= 6_000_000_000L && availableBytes >= 3_000_000_000L
    fun select(mode: String, totalBytes: Long, availableBytes: Long, lowMemory: Boolean, language: String = "fa"): ModelTier = when {
        mode == "fast" -> ModelTier.TURBO_COMPACT
        mode == "compact" -> ModelTier.TURBO_COMPACT
        mode == "persian" && language == "fa" -> ModelTier.PERSIAN_TURBO
        useTurbo(totalBytes, availableBytes, lowMemory) -> ModelTier.TURBO
        else -> ModelTier.TURBO_COMPACT
    }
}
