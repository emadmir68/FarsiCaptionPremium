package com.farsicaption.premium
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
class CaptionAssTest {
    private val text="این متن نمونه برای بررسی خوانایی زیرنویس است"
    private fun build(width: Int,height: Int,size: Int = 78) = CaptionAss.build(width,height,listOf(CaptionItem(text,1.25,3.5)),"luxury",size,120,"cream","none")
    @Test fun persianDeviceLocaleStillWritesAsciiTimes() {
        val old=Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("fa-IR")); assertTrue(build(1080,1920).contains("Dialogue: 0,0:00:01.25,0:00:03.50,")) }
        finally { Locale.setDefault(old) }
    }
    @Test fun portraitUsesUnclippedFontAndWraps() {
        val ass=build(1080,1920,110)
        assertTrue(ass.contains("PlayResX: 1080\nPlayResY: 1920\nWrapStyle: 0"))
        assertTrue(ass.contains("Style: Premium,Vazirmatn,110,"))
        val dir=File("build/reports/render-fixtures").apply { mkdirs() }
        File(dir,"portrait.ass").writeText(build(1080,1920))
        File(dir,"portrait-large.ass").writeText(ass)
    }
    @Test fun landscapeScalesToActualHeight() {
        val ass=build(1920,1080)
        assertTrue(ass.contains("PlayResX: 1920\nPlayResY: 1080"))
        assertTrue(ass.contains("Style: Premium,Vazirmatn,43,"))
        File("build/reports/render-fixtures").mkdirs()
        File("build/reports/render-fixtures/landscape.ass").writeText(ass)
    }
}
