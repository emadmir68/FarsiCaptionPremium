package com.farsicaption.premium
import org.junit.Assert.*
import org.junit.Test

class LandscapeCaptionTest {
    @Test fun landscapeIsReadableAt1080p() { assertEquals(81f, CaptionStyle.fontPixels(90f,1920f,1080f),0.001f) }
    @Test fun portraitKeepsItsExistingSize() { assertEquals(78f, CaptionStyle.fontPixels(78f,1080f,1920f),0.001f) }
    @Test fun previewMatchesLandscapeExportAtAnyScale() {
        assertEquals(CaptionStyle.fontPixels(90f,1920f,1080f)/1920f,CaptionStyle.fontPixels(90f,360f,202.5f)/360f,0.00001f)
    }
    @Test fun preferencesAreIndependent() {
        assertEquals(42f,CaptionStyle.selectedFont(42f,120f,1080f,1920f),0f)
        assertEquals(120f,CaptionStyle.selectedFont(42f,120f,1920f,1080f),0f)
    }
    @Test fun squareRetainsPortraitPreference() { assertEquals(78f,CaptionStyle.selectedFont(78f,90f,1080f,1080f),0f) }
    @Test fun maximumLandscapeSliderSurvivesExport() {
        val ass=CaptionAss.build(1920,1080,listOf(CaptionItem("متن آزمایشی",0.0,2.0)),"luxury",150,120,"cream","none")
        assertTrue(ass.contains("Style: Premium,Vazirmatn,135,"))
    }
    @Test fun ultrawideDoesNotGrowWithoutLimit() { assertEquals(81f,CaptionStyle.fontPixels(90f,4000f,1080f),0.001f) }
}
