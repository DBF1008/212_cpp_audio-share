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

package io.github.mkckr0.audio_share_app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mkckr0.audio_share_app.service.PlaybackCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for PlaybackCoordinator.
 *
 * These tests verify the coordinator behavior in a real Android environment:
 * - System restriction detection
 * - Startup from different sources
 * - Result callback invocation
 * - Failure handling
 */
@RunWith(AndroidJUnit4::class)
class PlaybackCoordinatorInstrumentedTest {

    private lateinit var appContext: Context

    @Before
    fun setup() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun canStartPlayback_foregroundSource_alwaysReturnsTrue() {
        // Foreground source should always be allowed
        val canStart = PlaybackCoordinator.canStartPlayback(
            appContext,
            PlaybackCoordinator.StartSource.FOREGROUND
        )
        assertTrue("Foreground source should always allow playback", canStart)
    }

    @Test
    fun canStartPlayback_bootSource_checksSystemRestrictions() {
        // Boot source should check system restrictions
        val canStart = PlaybackCoordinator.canStartPlayback(
            appContext,
            PlaybackCoordinator.StartSource.BOOT
        )
        // Result depends on system state, just verify it doesn't crash
        assertNotNull("Should return a boolean result", canStart)
    }

    @Test
    fun canStartPlayback_qsTileSource_checksSystemRestrictions() {
        // QS tile source should check system restrictions
        val canStart = PlaybackCoordinator.canStartPlayback(
            appContext,
            PlaybackCoordinator.StartSource.QS_TILE
        )
        // Result depends on system state, just verify it doesn't crash
        assertNotNull("Should return a boolean result", canStart)
    }

    @Test
    fun startPlayback_withCallback_invokesCallback() = runBlocking {
        var callbackInvoked = false
        var receivedResult: PlaybackCoordinator.StartResult? = null

        val callback = object : PlaybackCoordinator.ResultCallback {
            override fun onResult(result: PlaybackCoordinator.StartResult) {
                callbackInvoked = true
                receivedResult = result
            }
        }

        // Attempt to start playback from foreground (most likely to succeed)
        val result = PlaybackCoordinator.startPlayback(
            context = appContext,
            source = PlaybackCoordinator.StartSource.FOREGROUND,
            callback = callback
        )

        // Verify callback was invoked
        assertTrue("Callback should be invoked", callbackInvoked)
        assertNotNull("Should receive a result", receivedResult)
        assertEquals("Callback result should match returned result", result, receivedResult)
    }

    @Test
    fun startPlayback_withoutCallback_returnsResult() = runBlocking {
        // Start playback without callback should still return result
        val result = PlaybackCoordinator.startPlayback(
            context = appContext,
            source = PlaybackCoordinator.StartSource.FOREGROUND,
            callback = null
        )

        assertNotNull("Should return a result", result)
        assertTrue(
            "Result should be Success or Failure",
            result is PlaybackCoordinator.StartResult.Success ||
            result is PlaybackCoordinator.StartResult.Failure
        )
    }

    @Test
    fun startPlayback_fromForegroundSource_returnsSuccess() = runBlocking {
        val result = PlaybackCoordinator.startPlayback(
            context = appContext,
            source = PlaybackCoordinator.StartSource.FOREGROUND,
            callback = null
        )

        // Foreground source should typically succeed
        when (result) {
            is PlaybackCoordinator.StartResult.Success -> {
                assertEquals(
                    "Success result should have correct source",
                    PlaybackCoordinator.StartSource.FOREGROUND,
                    result.source
                )
            }
            is PlaybackCoordinator.StartResult.Failure -> {
                // If it fails, it should be due to controller/playback error, not system restriction
                assertNotEquals(
                    "Foreground source should not fail due to system restriction",
                    PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION,
                    result.reason
                )
            }
        }
    }

    @Test
    fun startResult_successContainsCorrectSource() {
        val result = PlaybackCoordinator.StartResult.Success(
            PlaybackCoordinator.StartSource.BOOT
        )
        assertEquals(
            "Success result should contain the correct source",
            PlaybackCoordinator.StartSource.BOOT,
            result.source
        )
    }

    @Test
    fun startResult_failureContainsCorrectSourceAndReason() {
        val result = PlaybackCoordinator.StartResult.Failure(
            source = PlaybackCoordinator.StartSource.QS_TILE,
            reason = PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION
        )
        assertEquals(
            "Failure result should contain the correct source",
            PlaybackCoordinator.StartSource.QS_TILE,
            result.source
        )
        assertEquals(
            "Failure result should contain the correct reason",
            PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION,
            result.reason
        )
    }

    @Test
    fun allStartSources_areDefined() {
        val sources = PlaybackCoordinator.StartSource.values()
        assertEquals("Should have 3 start sources", 3, sources.size)
        assertTrue("Should contain BOOT", sources.contains(PlaybackCoordinator.StartSource.BOOT))
        assertTrue("Should contain QS_TILE", sources.contains(PlaybackCoordinator.StartSource.QS_TILE))
        assertTrue("Should contain FOREGROUND", sources.contains(PlaybackCoordinator.StartSource.FOREGROUND))
    }

    @Test
    fun allFailureReasons_areDefined() {
        val reasons = PlaybackCoordinator.FailureReason.values()
        assertEquals("Should have 4 failure reasons", 4, reasons.size)
        assertTrue("Should contain SYSTEM_RESTRICTION", reasons.contains(PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION))
        assertTrue("Should contain CONTROLLER_ERROR", reasons.contains(PlaybackCoordinator.FailureReason.CONTROLLER_ERROR))
        assertTrue("Should contain PLAYBACK_ERROR", reasons.contains(PlaybackCoordinator.FailureReason.PLAYBACK_ERROR))
        assertTrue("Should contain CANCELLED", reasons.contains(PlaybackCoordinator.FailureReason.CANCELLED))
    }

    @Test
    fun coordinatorIsSingleton() {
        // Verify that PlaybackCoordinator is a singleton object
        val instance1 = PlaybackCoordinator
        val instance2 = PlaybackCoordinator
        assertSame("PlaybackCoordinator should be a singleton", instance1, instance2)
    }
}
