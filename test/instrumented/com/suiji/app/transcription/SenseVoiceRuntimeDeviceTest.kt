package com.suiji.app.transcription

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suiji.app.model.RecordingLanguage
import com.suiji.app.speaker.InstalledLsEendModel
import com.suiji.app.speaker.LsEendModelManager
import com.suiji.app.speaker.RealtimeSpeakerDiarizer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Device smoke test for the native runtime shared by SenseVoice and LS-EEND. */
@RunWith(AndroidJUnit4::class)
class SenseVoiceRuntimeDeviceTest {
    @Test(timeout = 15 * 60 * 1_000L)
    fun downloadedSenseVoiceAndLsEendCanCreateSessionsTogether() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val transcriptionManager = LocalModelManager(context)
        val transcriptionDescriptor = LocalModelManager.catalog.first()
        if (!transcriptionManager.isInstalled(transcriptionDescriptor)) {
            transcriptionManager.downloadAndInstall(transcriptionDescriptor) {}
        }

        val speakerDescriptor = LsEendModelManager.catalog.single()
        val speakerModel = File(context.cacheDir, "runtime-test-lseend.onnx")
        instrumentation.context.assets.open("lseend-streaming-1-8spk.onnx").use { input ->
            speakerModel.outputStream().buffered().use(input::copyTo)
        }

        try {
            RealtimeSpeakerDiarizer(InstalledLsEendModel(speakerDescriptor, speakerModel)).use {
                diarizer ->
                // Keep the speaker session alive while sherpa creates the ASR session. This is the
                // same process-level runtime combination used by a live recording.
                diarizer.accept(FloatArray(8_000))
                val engine = SenseVoiceLocalTranscriptionEngine(transcriptionManager, context)
                val recognizer = engine.createRecognizer(
                    transcriptionDescriptor,
                    RecordingLanguage.CHINESE
                )
                try {
                    assertNotNull(engine.transcribeSamples(recognizer, FloatArray(16_000)))
                } finally {
                    recognizer.release()
                }
            }
        } finally {
            speakerModel.delete()
        }
    }
}
