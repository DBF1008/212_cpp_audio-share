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

import io.github.mkckr0.audio_share_app.service.PlaybackStartCoordinator
import io.github.mkckr0.audio_share_app.service.PlaybackStartDecision
import io.github.mkckr0.audio_share_app.service.PlaybackStartSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the unified background-start decision matrix. Every playback entry point routes through
 * [PlaybackStartCoordinator.decide], so these cases guard against the behavior drift that previously
 * existed between boot auto-start, the quick settings tile and normal foreground starts.
 */
class PlaybackStartCoordinatorTest {

    private fun decide(source: PlaybackStartSource, canStart: Boolean) =
        PlaybackStartCoordinator.decide(source, canStart)

    // --- Full decision matrix: every source x {canStartForegroundService = true, false} ---

    @Test
    fun boot_whenAllowed_proceeds() {
        assertEquals(PlaybackStartDecision.Proceed, decide(PlaybackStartSource.BOOT, true))
    }

    @Test
    fun boot_whenBlocked_rejects() {
        // Regression: boot used to call play() unconditionally, which fails on Android 15+ when a
        // background foreground-service start is not allowed. It must now be rejected, not proceed.
        assertEquals(PlaybackStartDecision.Reject, decide(PlaybackStartSource.BOOT, false))
    }

    @Test
    fun qsTile_whenAllowed_proceeds() {
        assertEquals(PlaybackStartDecision.Proceed, decide(PlaybackStartSource.QS_TILE, true))
    }

    @Test
    fun qsTile_whenBlocked_redirectsToForeground() {
        assertEquals(
            PlaybackStartDecision.RedirectToForeground,
            decide(PlaybackStartSource.QS_TILE, false)
        )
    }

    @Test
    fun appStart_proceedsRegardlessOfGate() {
        assertEquals(PlaybackStartDecision.Proceed, decide(PlaybackStartSource.APP_START, true))
        assertEquals(PlaybackStartDecision.Proceed, decide(PlaybackStartSource.APP_START, false))
    }

    @Test
    fun userAction_proceedsRegardlessOfGate() {
        assertEquals(PlaybackStartDecision.Proceed, decide(PlaybackStartSource.USER_ACTION, true))
        assertEquals(PlaybackStartDecision.Proceed, decide(PlaybackStartSource.USER_ACTION, false))
    }

    // --- Taxonomy guards: lock the source flags so the matrix can't silently drift ---

    @Test
    fun foregroundSources_proceedEvenWhenBackgroundStartBlocked() {
        PlaybackStartSource.entries.filter { it.isForeground }.forEach { source ->
            assertEquals(
                "foreground source $source must always proceed",
                PlaybackStartDecision.Proceed,
                decide(source, false),
            )
        }
    }

    @Test
    fun backgroundSources_areGatedWhenBlocked() {
        PlaybackStartSource.entries.filterNot { it.isForeground }.forEach { source ->
            assertNotEquals(
                "background source $source must be gated when a background start is blocked",
                PlaybackStartDecision.Proceed,
                decide(source, false),
            )
        }
    }

    @Test
    fun onlyQsTile_canRedirectToForeground() {
        PlaybackStartSource.entries.forEach { source ->
            assertEquals(
                "only QS_TILE may redirect to the foreground",
                source == PlaybackStartSource.QS_TILE,
                source.canRedirectToForeground,
            )
        }
    }

    @Test
    fun sourceKeys_areUniqueAndNotBlank() {
        val keys = PlaybackStartSource.entries.map { it.key }
        assertTrue("connection-hint keys must not be blank", keys.none { it.isBlank() })
        assertEquals("connection-hint keys must be unique", keys.size, keys.toSet().size)
    }
}
