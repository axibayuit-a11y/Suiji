package com.suiji.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdatePolicyTest {
    @Test
    fun comparesSemanticVersionsNumerically() {
        assertTrue(ReleaseUpdatePolicy.isNewer("0.10.0", "0.9.9"))
        assertTrue(ReleaseUpdatePolicy.isNewer("v1.0.1", "1.0.0"))
        assertFalse(ReleaseUpdatePolicy.isNewer("0.8.0", "0.8.0"))
        assertFalse(ReleaseUpdatePolicy.isNewer("0.7.9", "0.8.0"))
    }

    @Test
    fun prefersArm64AndFallsBackToUniversal() {
        val arm64 = ReleaseAsset("Suiji-v0.8.0-arm64.apk", "arm", 10)
        val universal = ReleaseAsset("Suiji-v0.8.0-universal.apk", "all", 20)
        assertEquals(
            arm64,
            ReleaseUpdatePolicy.selectAsset(listOf("arm64-v8a", "armeabi-v7a"), listOf(universal, arm64))
        )
        assertEquals(
            universal,
            ReleaseUpdatePolicy.selectAsset(listOf("x86_64"), listOf(universal, arm64))
        )
        assertNull(ReleaseUpdatePolicy.selectAsset(listOf("x86_64"), listOf(arm64)))
    }
}
