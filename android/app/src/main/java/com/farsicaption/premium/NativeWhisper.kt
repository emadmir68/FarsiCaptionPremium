package com.farsicaption.premium
internal object NativeWhisper {
    init { System.loadLibrary("farsicaption") }
    external fun load(path: String, melBins: Int, decoderLayers: Int): Long
    external fun transcribe(handle: Long, audioPath: String, vadPath: String, language: String, threads: Int): ByteArray
    external fun cancel(handle: Long)
    external fun release(handle: Long)
}
