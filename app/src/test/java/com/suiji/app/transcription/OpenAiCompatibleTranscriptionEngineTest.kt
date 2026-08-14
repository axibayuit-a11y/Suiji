package com.suiji.app.transcription

import com.suiji.app.model.CloudTranscriptionConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class OpenAiCompatibleTranscriptionEngineTest {
    @Test
    fun sendsMultipartAudioAndParsesTranscript() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"text\":\"测试转录成功\"}"))
        server.start()

        val directory = Files.createTempDirectory("suiji-transcription-test").toFile()
        val audio = directory.resolve("sample.m4a").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            val result = OpenAiCompatibleTranscriptionEngine().transcribe(
                audio,
                CloudTranscriptionConfig(
                    enabled = true,
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                    apiKey = "test-key",
                    model = "gpt-4o-transcribe"
                )
            )
            assertEquals("测试转录成功", result.getOrThrow().text)
            val request = server.takeRequest()
            val body = request.body.readString(Charsets.ISO_8859_1)
            assertEquals("/v1/audio/transcriptions", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            assertTrue(body.contains("name=\"model\""))
            assertTrue(body.contains("gpt-4o-transcribe"))
            assertTrue(body.contains("filename=\"sample.m4a\""))
        } finally {
            server.shutdown()
            audio.delete()
            directory.delete()
        }
    }

    @Test
    fun requestsAndFormatsSpeakerDiarization() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {"segments":[
                  {"speaker":"speaker_0","start":0.2,"end":1.1,"text":"你好。"},
                  {"speaker":"speaker_1","start":1.2,"end":2.8,"text":"你好，请坐。"}
                ]}
                """.trimIndent()
            )
        )
        server.start()

        val directory = Files.createTempDirectory("suiji-diarization-test").toFile()
        val audio = directory.resolve("sample.m4a").apply { writeBytes(byteArrayOf(5, 6, 7)) }
        try {
            val result = OpenAiCompatibleTranscriptionEngine().transcribe(
                audio,
                CloudTranscriptionConfig(
                    enabled = true,
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                    apiKey = "test-key",
                    model = "gpt-4o-transcribe-diarize",
                    speakerDiarization = true
                )
            )
            val transcription = result.getOrThrow()
            assertEquals("speaker_0: 你好。\nspeaker_1: 你好，请坐。", transcription.text)
            assertEquals(200L, transcription.segments.first().startMs)
            assertEquals(2_800L, transcription.segments.last().endMs)
            val body = server.takeRequest().body.readString(Charsets.ISO_8859_1)
            assertTrue(body.contains("diarized_json"))
            assertTrue(body.contains("chunking_strategy"))
        } finally {
            server.shutdown()
            audio.delete()
            directory.delete()
        }
    }
}
