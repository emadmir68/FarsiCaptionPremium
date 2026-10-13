package com.farsicaption.premium
import org.junit.Assert.*
import org.junit.Test
class JobAndModelTest {
    @Test fun wrongVideoIsRejected() { assertFalse(JobIdentity("a","video1").matches("a","video2")) }
    @Test fun wrongJobIsRejected() { assertFalse(JobIdentity("a","video1").matches("b","video1")) }
    @Test fun retranscribingSameUriIsNewJob() { val gate=ResultDelivery(); assertTrue(gate.consume(JobIdentity("b","video1"),"b","video1",true)); assertFalse(gate.consume(JobIdentity("b","video1"),"a","video1",true)) }
    @Test fun blankIdentityCannotMatch() { assertFalse(JobIdentity("","").matches("","")) }
    @Test fun resultDeliveredOncePreservesEdits() { val gate=ResultDelivery(); val id=JobIdentity("a","v"); assertTrue(gate.consume(id,"a","v",true)); assertFalse(gate.consume(id,"a","v",true)) }
    @Test fun recreationKeepsConsumedState() { assertFalse(ResultDelivery("a").consume(JobIdentity("a","v"),"a","v",true)) }
    @Test fun runningResultNotConsumed() { val gate=ResultDelivery(); val id=JobIdentity("a","v"); assertFalse(gate.consume(id,"a","v",false)); assertTrue(gate.consume(id,"a","v",true)) }
    @Test fun turboRequiresMemoryHeadroom() { assertTrue(ModelPolicy.useTurbo(8_000_000_000L,4_000_000_000L,false)); assertFalse(ModelPolicy.useTurbo(4_000_000_000L,4_000_000_000L,false)); assertFalse(ModelPolicy.useTurbo(8_000_000_000L,2_000_000_000L,false)); assertFalse(ModelPolicy.useTurbo(8_000_000_000L,4_000_000_000L,true)) }
    @Test fun persistenceRetainsWordTiming() { val c=CaptionItem("یک دو",1.0,3.0,listOf(TimedWord("یک",1.0,2.0),TimedWord("دو",2.0,3.0))); assertEquals(listOf(c),CaptionJson.decode(CaptionJson.encode(listOf(c)))) }
}
