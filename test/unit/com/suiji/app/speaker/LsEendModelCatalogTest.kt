package com.suiji.app.speaker

import com.suiji.app.model.LsEendModelId
import com.suiji.app.model.LsEendRuntimeProfile
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LsEendModelCatalogTest {
    @Test
    fun downloadableModelHasImmutableIntegrityMetadata() {
        val descriptor = LsEendModelManager.catalog.single()

        assertEquals(LsEendModelId.GENERIC_1_8, descriptor.id)
        assertEquals(44_947_938L, descriptor.modelBytes)
        assertEquals(64, descriptor.sha256.length)
        assertTrue(descriptor.sha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals("https", URI(descriptor.downloadUrl).scheme)
        assertEquals("github.com", URI(descriptor.downloadUrl).host)
        assertEquals(8, descriptor.maxSpeakers)
        assertEquals(LsEendRuntimeProfile.STREAMING_1_8_V1, descriptor.runtimeProfile)
    }
}
