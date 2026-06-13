/*
 *    Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.github.mkckr0.audio_share_app.service

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PlaybackCoordinator.
 *
 * These tests verify:
 * - System restriction checking logic
 * - Start source handling
 * - Result callback invocation
 * - Failure reason reporting
 */
class PlaybackCoordinatorTest {

    private lateinit var mockContext: Context

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockkObject(PlaybackCoordinator)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `StartSource enum has all expected values`() {
        val sources = PlaybackCoordinator.StartSource.values()
        assertEquals(3, sources.size)
        assertTrue(sources.contains(PlaybackCoordinator.StartSource.BOOT))
        assertTrue(sources.contains(PlaybackCoordinator.StartSource.QS_TILE))
        assertTrue(sources.contains(PlaybackCoordinator.StartSource.FOREGROUND))
    }

    @Test
    fun `FailureReason enum has all expected values`() {
        val reasons = PlaybackCoordinator.FailureReason.values()
        assertEquals(4, reasons.size)
        assertTrue(reasons.contains(PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION))
        assertTrue(reasons.contains(PlaybackCoordinator.FailureReason.CONTROLLER_ERROR))
        assertTrue(reasons.contains(PlaybackCoordinator.FailureReason.PLAYBACK_ERROR))
        assertTrue(reasons.contains(PlaybackCoordinator.FailureReason.CANCELLED))
    }

    @Test
    fun `StartResult Success contains correct source`() {
        val result = PlaybackCoordinator.StartResult.Success(
            PlaybackCoordinator.StartSource.BOOT
        )
        assertEquals(PlaybackCoordinator.StartSource.BOOT, result.source)
    }

    @Test
    fun `StartResult Failure contains correct source and reason`() {
        val result = PlaybackCoordinator.StartResult.Failure(
            source = PlaybackCoordinator.StartSource.QS_TILE,
            reason = PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION
        )
        assertEquals(PlaybackCoordinator.StartSource.QS_TILE, result.source)
        assertEquals(PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION, result.reason)
    }

    @Test
    fun `StartResult sealed class pattern matching works correctly`() {
        val successResult: PlaybackCoordinator.StartResult =
            PlaybackCoordinator.StartResult.Success(PlaybackCoordinator.StartSource.FOREGROUND)

        val failureResult: PlaybackCoordinator.StartResult =
            PlaybackCoordinator.StartResult.Failure(
                PlaybackCoordinator.StartSource.BOOT,
                PlaybackCoordinator.FailureReason.CONTROLLER_ERROR
            )

        // Test pattern matching
        when (successResult) {
            is PlaybackCoordinator.StartResult.Success -> {
                assertEquals(PlaybackCoordinator.StartSource.FOREGROUND, successResult.source)
            }
            is PlaybackCoordinator.StartResult.Failure -> {
                fail("Expected Success result")
            }
        }

        when (failureResult) {
            is PlaybackCoordinator.StartResult.Success -> {
                fail("Expected Failure result")
            }
            is PlaybackCoordinator.StartResult.Failure -> {
                assertEquals(PlaybackCoordinator.StartSource.BOOT, failureResult.source)
                assertEquals(
                    PlaybackCoordinator.FailureReason.CONTROLLER_ERROR,
                    failureResult.reason
                )
            }
        }
    }

    @Test
    fun `canStartPlayback returns true for FOREGROUND source`() {
        // Foreground source should always be allowed
        every { mockContext.getSystemService(any()) } returns null

        val canStart = PlaybackCoordinator.canStartPlayback(
            mockContext,
            PlaybackCoordinator.StartSource.FOREGROUND
        )
        assertTrue("FOREGROUND source should always allow playback", canStart)
    }

    @Test
    fun `ResultCallback interface can be implemented`() {
        var callbackInvoked = false
        var receivedResult: PlaybackCoordinator.StartResult? = null

        val callback = object : PlaybackCoordinator.ResultCallback {
            override fun onResult(result: PlaybackCoordinator.StartResult) {
                callbackInvoked = true
                receivedResult = result
            }
        }

        val testResult = PlaybackCoordinator.StartResult.Success(
            PlaybackCoordinator.StartSource.BOOT
        )
        callback.onResult(testResult)

        assertTrue("Callback should be invoked", callbackInvoked)
        assertNotNull("Result should be received", receivedResult)
        assertEquals(testResult, receivedResult)
    }

    @Test
    fun `ResultCallback can handle failure results`() {
        var receivedReason: PlaybackCoordinator.FailureReason? = null

        val callback = object : PlaybackCoordinator.ResultCallback {
            override fun onResult(result: PlaybackCoordinator.StartResult) {
                if (result is PlaybackCoordinator.StartResult.Failure) {
                    receivedReason = result.reason
                }
            }
        }

        val testResult = PlaybackCoordinator.StartResult.Failure(
            source = PlaybackCoordinator.StartSource.QS_TILE,
            reason = PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION
        )
        callback.onResult(testResult)

        assertEquals(
            "Should receive SYSTEM_RESTRICTION reason",
            PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION,
            receivedReason
        )
    }

    @Test
    fun `StartSource values can be used in when expression`() {
        val sources = PlaybackCoordinator.StartSource.values()

        sources.forEach { source ->
            val description = when (source) {
                PlaybackCoordinator.StartSource.BOOT -> "Boot"
                PlaybackCoordinator.StartSource.QS_TILE -> "Quick Settings"
                PlaybackCoordinator.StartSource.FOREGROUND -> "Foreground"
            }
            assertNotNull("Each source should have a description", description)
        }
    }

    @Test
    fun `FailureReason values can be used in when expression`() {
        val reasons = PlaybackCoordinator.FailureReason.values()

        reasons.forEach { reason ->
            val description = when (reason) {
                PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION -> "System restriction"
                PlaybackCoordinator.FailureReason.CONTROLLER_ERROR -> "Controller error"
                PlaybackCoordinator.FailureReason.PLAYBACK_ERROR -> "Playback error"
                PlaybackCoordinator.FailureReason.CANCELLED -> "Cancelled"
            }
            assertNotNull("Each reason should have a description", description)
        }
    }

    @Test
    fun `StartResult data class equality works correctly`() {
        val result1 = PlaybackCoordinator.StartResult.Success(
            PlaybackCoordinator.StartSource.BOOT
        )
        val result2 = PlaybackCoordinator.StartResult.Success(
            PlaybackCoordinator.StartSource.BOOT
        )
        val result3 = PlaybackCoordinator.StartResult.Success(
            PlaybackCoordinator.StartSource.QS_TILE
        )

        assertEquals("Same results should be equal", result1, result2)
        assertNotEquals("Different results should not be equal", result1, result3)
    }

    @Test
    fun `Failure result data class equality works correctly`() {
        val result1 = PlaybackCoordinator.StartResult.Failure(
            source = PlaybackCoordinator.StartSource.BOOT,
            reason = PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION
        )
        val result2 = PlaybackCoordinator.StartResult.Failure(
            source = PlaybackCoordinator.StartSource.BOOT,
            reason = PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION
        )
        val result3 = PlaybackCoordinator.StartResult.Failure(
            source = PlaybackCoordinator.StartSource.BOOT,
            reason = PlaybackCoordinator.FailureReason.CONTROLLER_ERROR
        )

        assertEquals("Same failure results should be equal", result1, result2)
        assertNotEquals("Different failure results should not be equal", result1, result3)
    }
}
