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

import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for [AudioResourceManager].
 *
 * These tests guard against the reconnection-leak bug where repeated
 * disconnect/reconnect cycles left orphan AudioTrack and LoudnessEnhancer
 * instances alive, accumulating audio sessions and stale playback state.
 */
class AudioResourceManagerTest {

    private lateinit var manager: AudioResourceManager

    @Before
    fun setUp() {
        manager = AudioResourceManager()
    }

    // ------------------------------------------------------------------ //
    //  Idempotency — the single most important property                  //
    // ------------------------------------------------------------------ //

    @Test
    fun `release on empty manager is a safe no-op`() {
        // Must not throw even though no resources have been assigned.
        manager.release()
        manager.release() // double-release also safe
        assertNull(manager.audioTrack)
        assertNull(manager.loudnessEnhancer)
    }

    // ------------------------------------------------------------------ //
    //  assign + release — basic lifecycle                                //
    // ------------------------------------------------------------------ //

    @Test
    fun `release tears down AudioTrack and LoudnessEnhancer`() {
        val track = mockk<AudioTrack>(relaxed = true)
        val enhancer = mockk<LoudnessEnhancer>(relaxed = true)

        manager.assign(track, enhancer)
        manager.release()

        // LoudnessEnhancer released first (it depends on the audio session)
        verifyOrder {
            enhancer.release()
            track.pause()
            track.flush()
            track.release()
        }

        assertNull(manager.audioTrack)
        assertNull(manager.loudnessEnhancer)
    }

    @Test
    fun `release works without LoudnessEnhancer`() {
        val track = mockk<AudioTrack>(relaxed = true)

        manager.assign(track)
        manager.release()

        verifyOrder {
            track.pause()
            track.flush()
            track.release()
        }

        assertNull(manager.audioTrack)
        assertNull(manager.loudnessEnhancer)
    }

    // ------------------------------------------------------------------ //
    //  Double-release after assign — must not call release() twice       //
    // ------------------------------------------------------------------ //

    @Test
    fun `double release after assign does not double-release resources`() {
        val track = mockk<AudioTrack>(relaxed = true)
        val enhancer = mockk<LoudnessEnhancer>(relaxed = true)

        manager.assign(track, enhancer)
        manager.release()
        manager.release() // second call must be a no-op

        verify(exactly = 1) { track.release() }
        verify(exactly = 1) { enhancer.release() }
        verify(exactly = 1) { track.pause() }
        verify(exactly = 1) { track.flush() }
    }

    // ------------------------------------------------------------------ //
    //  Reconnect-leak scenario — the original bug                        //
    // ------------------------------------------------------------------ //

    @Test
    fun `simulated reconnection cycle releases old resources before new ones`() {
        // First connection
        val track1 = mockk<AudioTrack>(relaxed = true)
        val enhancer1 = mockk<LoudnessEnhancer>(relaxed = true)
        manager.assign(track1, enhancer1)

        // Simulate onError → releaseAudioResources()
        manager.release()

        // Verify first connection resources were released
        verify(exactly = 1) { track1.pause() }
        verify(exactly = 1) { track1.flush() }
        verify(exactly = 1) { track1.release() }
        verify(exactly = 1) { enhancer1.release() }

        // Second connection (reconnect)
        val track2 = mockk<AudioTrack>(relaxed = true)
        val enhancer2 = mockk<LoudnessEnhancer>(relaxed = true)
        manager.assign(track2, enhancer2)

        // Simulate another onError → releaseAudioResources()
        manager.release()

        // Verify second connection resources were released
        verify(exactly = 1) { track2.pause() }
        verify(exactly = 1) { track2.flush() }
        verify(exactly = 1) { track2.release() }
        verify(exactly = 1) { enhancer2.release() }

        // And first connection was NOT released again
        clearMocks(track1, enhancer1, answers = false)
        verify(exactly = 0) { track1.release() }
        verify(exactly = 0) { enhancer1.release() }
    }

    @Test
    fun `rapid reconnect cycles do not accumulate leaked resources`() {
        val tracks = mutableListOf<AudioTrack>()
        val enhancers = mutableListOf<LoudnessEnhancer>()

        // Simulate 5 rapid reconnect cycles (like the original bug scenario)
        repeat(5) {
            val track = mockk<AudioTrack>(relaxed = true)
            val enhancer = mockk<LoudnessEnhancer>(relaxed = true)
            tracks.add(track)
            enhancers.add(enhancer)

            // This is what onReceiveAudioFormat does: release old, then assign new
            manager.release()
            manager.assign(track, enhancer)
        }

        // Final cleanup (handleRelease or handleStop)
        manager.release()

        // Every track and enhancer must have been released exactly once
        tracks.forEachIndexed { index, track ->
            verify(exactly = 1) { track.pause() }
            verify(exactly = 1) { track.flush() }
            verify(exactly = 1) { track.release() }
        }
        enhancers.forEachIndexed { index, enhancer ->
            verify(exactly = 1) { enhancer.release() }
        }

        assertNull(manager.audioTrack)
        assertNull(manager.loudnessEnhancer)
    }

    // ------------------------------------------------------------------ //
    //  assign without prior release — old resources are NOT auto-freed   //
    //  (caller is responsible for calling release() before assign())     //
    // ------------------------------------------------------------------ //

    @Test
    fun `assign replaces references without auto-releasing old ones`() {
        val track1 = mockk<AudioTrack>(relaxed = true)
        val track2 = mockk<AudioTrack>(relaxed = true)

        manager.assign(track1)
        // Intentionally NOT calling release() before second assign
        manager.assign(track2)

        // track1 was NOT released (caller responsibility)
        verify(exactly = 0) { track1.release() }

        // Only track2 is held now
        manager.release()
        verify(exactly = 1) { track2.release() }
        verify(exactly = 0) { track1.release() }
    }
}
