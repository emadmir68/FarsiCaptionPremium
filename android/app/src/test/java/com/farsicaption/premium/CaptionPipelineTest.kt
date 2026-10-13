package com.farsicaption.premium
import org.junit.Assert.*
import org.junit.Test
class CaptionPipelineTest {
    private fun build(vararg s: AsrSegment) = CaptionPipeline.build(s.toList(), 10.0, 2, "fa")
    @Test fun independentPrefixRepeatSurvives() { assertEquals(listOf("سلام", "سلام دوست"), build(AsrSegment("سلام",0.0,1.0),AsrSegment("سلام دوست",2.0,3.0)).map { it.text }) }
    @Test fun adjacentRepetitionSurvives() { assertEquals(2, build(AsrSegment("نه",0.0,1.0), AsrSegment("نه",1.0,2.0)).size) }
    @Test fun growingSnapshotReplacesSameInterval() { val r=build(AsrSegment("سلام",0.0,1.0),AsrSegment("سلام دوست",0.0,2.0)); assertEquals(1,r.size); assertEquals("سلام دوست",r[0].text) }
    @Test fun exactDuplicateIntervalAppearsOnce() { val s=AsrSegment("سلام",0.0,1.0); assertEquals(1,build(s,s).size) }
    @Test fun musicMarkerCreatesNoCaption() { assertTrue(build(AsrSegment("[music]",0.0,1.0)).isEmpty()) }
    @Test fun silenceHasNoFallbackCaption() { assertTrue(build().isEmpty()) }
    @Test fun clampsAndKeepsMonotonicIntervals() { val r=build(AsrSegment("اول",-1.0,2.0),AsrSegment("دوم",1.0,20.0)); assertEquals(0.0,r[0].start,0.0); assertEquals(2.0,r[1].start,0.0); assertEquals(10.0,r[1].end,0.0) }
    @Test fun invalidIntervalsAreRejected() { assertTrue(build(AsrSegment("الف",Double.NaN,1.0),AsrSegment("ب",3.0,2.0),AsrSegment("ج",11.0,12.0)).isEmpty()) }
    @Test fun noWordsMeansNoInventedAlignment() { val r=build(AsrSegment("یک دو سه چهار",1.0,8.0)); assertEquals(1,r.size); assertTrue(r[0].words.isEmpty()); assertEquals(8.0,r[0].end,0.0) }
    @Test fun nativeWordGapIsRetained() { val r=build(AsrSegment("یک دو سه چهار",0.0,9.0,listOf(TimedWord("یک",1.0,2.0),TimedWord("دو",2.0,3.0),TimedWord("سه",6.0,7.0),TimedWord("چهار",7.0,8.0)))); assertEquals(2,r.size); assertEquals(3.0,r[0].end,0.0); assertEquals(6.0,r[1].start,0.0) }
    @Test fun invalidWordTimesFallBackToNativeSegment() { val r=build(AsrSegment("یک دو",1.0,4.0,listOf(TimedWord("یک",0.0,2.0),TimedWord("دو",2.0,3.0)))); assertTrue(r[0].words.isEmpty()); assertEquals(1.0,r[0].start,0.0) }
    @Test fun silenceBetweenNativeSegmentsStaysBlank() { val r=build(AsrSegment("اول",1.0,2.0),AsrSegment("دوم",7.0,8.0)); assertEquals(2.0,r[0].end,0.0); assertEquals(7.0,r[1].start,0.0) }
}
