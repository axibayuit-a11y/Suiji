package com.suiji.app.speaker

/** Converts anonymous LS-EEND slots into continuous labels in first-appearance order. */
object SpeakerLabelNormalizer {
    fun mappingByFirstAppearance(speakerIds: Iterable<String>): Map<String, String> {
        val labels = linkedMapOf<String, String>()
        speakerIds.forEach { speakerId ->
            labels.getOrPut(speakerId) { "speaker_${labels.size}" }
        }
        return labels
    }
}
