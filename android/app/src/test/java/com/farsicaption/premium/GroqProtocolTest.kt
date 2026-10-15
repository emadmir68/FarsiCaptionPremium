package com.farsicaption.premium

import org.junit.Assert.*
import org.junit.Test

class GroqProtocolTest {
    private val speech = """{"text":"سلام خوبی","words":[{"word":"سلام","start":1.0,"end":1.4},{"word":"خوبی","start":1.5,"end":2.0}]}"""
    @Test fun realWordTimingReachesExistingCaptionPipeline() {
        val result = CaptionPipeline.build(GroqProtocol.parse(speech, 180.0, "fa"),180.0,5,"fa").single()
        assertEquals("سلام خوبی",result.text); assertEquals(1.0,result.start,0.0); assertEquals(2.0,result.end,0.0)
        assertEquals(2,result.words.size)
    }
    @Test fun threeMinuteClipNeedsOnlyOneUpload() {
        assertEquals(listOf(AudioChunk(0.0,180.0,0.0,180.0)),GroqProtocol.chunks(180.0))
    }
    @Test fun longFilesHaveContinuousOwnershipAndBoundedUploads() {
        val chunks=GroqProtocol.chunks(1000.0)
        assertEquals(0.0,chunks.first().start,0.0); assertEquals(1000.0,chunks.last().end,0.0)
        chunks.zipWithNext().forEach { (a,b) -> assertEquals(a.end,b.start,0.0) }
        chunks.forEach { assertTrue((it.uploadEnd-it.uploadStart)*32000+44 < GroqProtocol.MAX_FILE_BYTES) }
    }
    @Test fun overlapIsOwnedOnceWhileGenuineRepetitionRemains() {
        val chunks=GroqProtocol.chunks(300.0)
        val first=listOf(AsrSegment("سلام سلام",239.3,240.6,listOf(TimedWord("سلام",239.3,239.7),TimedWord("سلام",240.2,240.6))))
        val second=listOf(AsrSegment("سلام سلام",.3,1.6,listOf(TimedWord("سلام",.3,.7),TimedWord("سلام",1.2,1.6))))
        val owned=GroqProtocol.owned(first,chunks[0])+GroqProtocol.owned(second,chunks[1])
        val words=owned.flatMap { it.words }
        assertEquals(2,words.size); assertEquals(239.3,words[0].start,0.00001); assertEquals(240.2,words[1].start,0.00001)
    }
    @Test fun validSegmentsRemainWhenProviderOmitsWordAlignment() {
        val json="""{"text":"سلام خوبی","segments":[{"text":"سلام خوبی","start":10.0,"end":12.0}]}"""
        val caption=CaptionPipeline.build(GroqProtocol.parse(json,20.0,"fa"),20.0,2,"fa").single()
        assertEquals(10.0,caption.start,0.0); assertEquals(12.0,caption.end,0.0); assertTrue(caption.words.isEmpty())
    }
    @Test(expected=IllegalArgumentException::class) fun textWithoutTimingIsRejected() { GroqProtocol.parse("""{"text":"سلام"}""",20.0,"fa") }
    @Test(expected=IllegalArgumentException::class) fun invalidIntervalsAreRejected() {
        GroqProtocol.parse("""{"text":"سلام","segments":[{"text":"سلام","start":4,"end":3}]}""",20.0,"fa")
    }
    @Test fun silenceCreatesNoCaption() { assertTrue(GroqProtocol.parse("""{"text":"","segments":[],"words":[]}""",10.0,"fa").isEmpty()) }
    @Test fun providerRoundingAtAudioEndKeepsWordAlignment() {
        val json="""{"text":"سلام خوبی","words":[{"word":"سلام","start":1.0,"end":1.4},{"word":"خوبی","start":1.5,"end":2.05}]}"""
        val caption=CaptionPipeline.build(GroqProtocol.parse(json,2.0,"fa"),2.0,5,"fa").single()
        assertEquals(2,caption.words.size); assertEquals(2.0,caption.end,0.0)
    }
    @Test fun arabicNeverPassesThroughPersianNormalizer() {
        val json="""{"text":"كيف حالك","words":[{"word":"كيف","start":1,"end":2},{"word":"حالك","start":2,"end":3}]}"""
        assertEquals("كيف حالك",CaptionPipeline.build(GroqProtocol.parse(json,5.0,"ar"),5.0,5,"ar").single().text)
    }
    @Test fun wordTextMismatchDoesNotDiscardSegmentText() {
        val json="""{"text":"سلام دوست من","segments":[{"text":"سلام دوست من","start":1,"end":3,"words":[{"word":"سلام","start":1,"end":2}]}]}"""
        val owned=GroqProtocol.owned(GroqProtocol.parse(json,5.0,"fa"),GroqProtocol.chunks(5.0).single())
        assertEquals("سلام دوست من",owned.single().text); assertTrue(owned.single().words.isEmpty())
    }
}
