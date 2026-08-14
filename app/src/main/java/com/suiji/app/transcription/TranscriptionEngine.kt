package com.suiji.app.transcription

import com.suiji.app.model.CloudTranscriptionConfig
import com.suiji.app.model.TranscriptionResult
import java.io.File

interface TranscriptionEngine {
    fun transcribe(audioFile: File, config: CloudTranscriptionConfig): Result<TranscriptionResult>
}
