package com.farsicaption.premium
internal object ModelPolicy {
    fun useTurbo(totalBytes: Long, availableBytes: Long, lowMemory: Boolean): Boolean =
        !lowMemory && totalBytes >= 6_000_000_000L && availableBytes >= 3_000_000_000L
}
