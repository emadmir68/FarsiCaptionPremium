package com.farsicaption.premium

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class GroqClientTest {
    private fun audio()=File.createTempFile("test-speech-",".wav").apply { writeBytes(ByteArray(128)) }
    @Test fun sendsAudioAndExplicitLanguageWithRealTimestampsRequested()= runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"text\":\"\",\"segments\":[]}"))
            val file=audio()
            try {
                GroqClient(endpoint=server.url("/audio/transcriptions").toString()).transcribe(file,"test-personal-key","ar",GroqMode.QUALITY)
                val req=server.takeRequest(2,TimeUnit.SECONDS)!!
                assertEquals("Bearer test-personal-key",req.getHeader("Authorization"))
                val body=req.body.readUtf8()
                for(value in listOf("whisper-large-v3","name=\"language\"\r\n\r\nar","verbose_json","timestamp_granularities[]","word","segment","speech.wav")) assertTrue(value,body.contains(value))
                assertFalse(body.contains("source.mp4")); assertEquals(1,server.requestCount)
            } finally { file.delete() }
        }
    }
    @Test fun invalidKeyErrorDoesNotExposeServerEchoOrKey()= runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("secret-that-must-not-be-displayed"))
            val file=audio()
            try {
                val failure=runCatching { GroqClient(endpoint=server.url("/").toString()).transcribe(file,"test-key","fa",GroqMode.TURBO) }.exceptionOrNull()!!
                assertTrue(failure.message!!.contains("کلید")); assertFalse(failure.message!!.contains("secret")); assertEquals(1,server.requestCount)
            } finally { file.delete() }
        }
    }
    @Test fun exhaustedQuotaDoesNotLoopOrSwitchToPaidProvider()= runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","86400"))
            val file=audio()
            try {
                val e=runCatching { GroqClient(endpoint=server.url("/").toString()).transcribe(file,"test-key","fa",GroqMode.TURBO) }.exceptionOrNull()!!
                assertTrue(e.message!!.contains("سهمیه")); assertEquals(1,server.requestCount)
            } finally { file.delete() }
        }
    }
    @Test fun shortRetryAfterIsRespectedAndBounded()= runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","1"))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","1"))
            val file=audio(); val started=System.nanoTime()
            try {
                assertTrue(runCatching { GroqClient(endpoint=server.url("/").toString()).transcribe(file,"test-key","fa",GroqMode.TURBO) }.isFailure)
                assertEquals(2,server.requestCount); assertTrue(System.nanoTime()-started>=1_000_000_000L)
            } finally { file.delete() }
        }
    }
    @Test fun redirectsNeverForwardThePersonalKey()= runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.enqueue(MockResponse().setResponseCode(307).setHeader("Location",second.url("/steal")))
            val file=audio()
            try {
                assertTrue(runCatching { GroqClient(endpoint=first.url("/").toString()).transcribe(file,"test-key","fa",GroqMode.TURBO) }.isFailure)
                assertEquals(0,second.requestCount)
            } finally { file.delete() }
        } }
    }
    @Test fun cancelStopsAnInFlightRequest()= runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client=GroqClient(endpoint=server.url("/").toString()); val file=audio()
            try {
                val job=async(Dispatchers.IO) { runCatching { client.transcribe(file,"test-key","fa",GroqMode.TURBO) } }
                assertNotNull(server.takeRequest(3,TimeUnit.SECONDS)); client.cancel()
                val error=withTimeout(3000) { job.await() }.exceptionOrNull()
                assertTrue(error is CancellationException)
            } finally { client.cancel(); file.delete() }
        }
    }
}
