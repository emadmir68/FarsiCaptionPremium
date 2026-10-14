package com.farsicaption.premium
import org.junit.Assert.*
import org.junit.Test
class VideoRegressionTest {
    @Test fun lowFreeMemoryStillSelectsTurbo() { assertEquals(ModelTier.TURBO_COMPACT, ModelPolicy.select("quality",8_000_000_000L,2_000_000_000L,false)) }
    @Test fun lowMemoryDoesNotSilentlyChooseSmall() { assertEquals(ModelTier.TURBO_COMPACT, ModelPolicy.select("quality",4_000_000_000L,900_000_000L,true)) }
    @Test fun smallRequiresExplicitChoice() { assertEquals(ModelTier.SMALL, ModelPolicy.select("fast",8_000_000_000L,4_000_000_000L,false)) }
    @Test fun compactChoiceDoesNotLoadFullModel() { assertEquals(ModelTier.TURBO_COMPACT, ModelPolicy.select("compact",8_000_000_000L,4_000_000_000L,false)) }
    @Test fun fullGenericTurboRemainsAvailableWithHeadroom() { assertEquals(ModelTier.TURBO, ModelPolicy.select("quality",8_000_000_000L,4_000_000_000L,false)) }
    @Test fun persianOptionUsesTheMeasuredModelEvenWithMoreMemory() { assertEquals(ModelTier.PERSIAN_TURBO, ModelPolicy.select("persian",8_000_000_000L,4_000_000_000L,false,"fa")) }
    @Test fun persianLowMemoryDoesNotSilentlySelectSmall() { assertEquals(ModelTier.PERSIAN_TURBO, ModelPolicy.select("persian",4_000_000_000L,900_000_000L,true,"fa")) }
    @Test fun arabicNeverUsesPersianAdaptation() { assertEquals(ModelTier.TURBO, ModelPolicy.select("quality",8_000_000_000L,4_000_000_000L,false,"ar")) }
    @Test fun arabicLowMemoryRetainsGenericTurbo() { assertEquals(ModelTier.TURBO_COMPACT, ModelPolicy.select("quality",4_000_000_000L,900_000_000L,true,"ar")) }
    @Test fun arabicFastChoiceRemainsExplicit() { assertEquals(ModelTier.SMALL, ModelPolicy.select("fast",8_000_000_000L,4_000_000_000L,false,"ar")) }
    @Test fun switchingToArabicAfterPersianOptionUsesGenericModel() { assertEquals(ModelTier.TURBO, ModelPolicy.select("persian",8_000_000_000L,4_000_000_000L,false,"ar")) }
    @Test fun persianModelIsNotSelectedForUnknownLanguage() { assertEquals(ModelTier.TURBO, ModelPolicy.select("persian",8_000_000_000L,4_000_000_000L,false,"auto")) }
    @Test fun unknownModeRetainsQualityDefault() { assertEquals(ModelTier.TURBO_COMPACT, ModelPolicy.select("legacy",8_000_000_000L,2_000_000_000L,false)) }
    @Test fun previewAndExportShareRelativeFontSize() { val value=78f; assertEquals(CaptionStyle.fontPixels(value,1920f)/1920f,CaptionStyle.fontPixels(value,640f)/640f,0.000001f) }
    @Test fun maximumSliderFontIsActuallyExported() { assertEquals(110f,CaptionStyle.fontPixels(110f,1920f),0f) }
    @Test fun previewAndExportShareRelativeMargin() { assertEquals(40f,CaptionStyle.marginPixels(120f,640f),0f) }
    @Test fun dragUsesDisplayedFrameHeight() { assertEquals(180f,CaptionStyle.dragMargin(120f,-20f,640f),0.001f) }
    @Test fun sixWordsDoNotBecomeFivePlusOne() {
        val words=(0..5).map { TimedWord("واژه$it",it.toDouble(),it+0.8) }
        val out=CaptionPipeline.build(listOf(AsrSegment(words.joinToString(" "){it.text},0.0,6.0,words)),6.0,5,"fa")
        assertEquals(listOf(3,3),out.map{it.words.size});assertEquals(3.0,out[1].start,0.0);assertEquals(5.8,out[1].end,0.0)
    }
    @Test fun pauseIsNotFilledInsideOneCaption() {
        val words=listOf(TimedWord("اول",0.0,0.4),TimedWord("دوم",2.0,2.4))
        val out=CaptionPipeline.build(listOf(AsrSegment("اول دوم",0.0,3.0,words)),3.0,5,"fa")
        assertEquals(2,out.size);assertEquals(0.4,out[0].end,0.0);assertEquals(2.0,out[1].start,0.0)
    }
}
