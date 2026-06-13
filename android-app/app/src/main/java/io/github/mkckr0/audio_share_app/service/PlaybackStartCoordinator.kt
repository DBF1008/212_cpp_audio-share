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

/**
 * Identifies which entry point is trying to start playback. Each source carries everything
 * [PlaybackStartCoordinator] needs to make a uniform decision, so the gate no longer drifts between
 * callers.
 *
 * @param key the value reported as the `"src"` connection hint to [PlaybackService]. Replaces the
 *   ad-hoc strings the entry points used to pass, giving one shared taxonomy.
 * @param isForeground whether the caller is inherently in the foreground. A foreground caller may
 *   always start a foreground service, so the background-start restriction does not apply to it.
 * @param canRedirectToForeground whether, when a background start is blocked, this entry point can
 *   instead bring the app to the foreground (e.g. the quick settings tile) rather than give up.
 */
enum class PlaybackStartSource(
    val key: String,
    val isForeground: Boolean,
    val canRedirectToForeground: Boolean,
) {
    BOOT(key = "BootService", isForeground = false, canRedirectToForeground = false),
    QS_TILE(key = "QsTileService", isForeground = false, canRedirectToForeground = true),
    APP_START(key = "MainActivity", isForeground = true, canRedirectToForeground = false),
    USER_ACTION(key = "HomeScreen", isForeground = true, canRedirectToForeground = false),
}

/**
 * What the coordinator decided a caller should do. The Android-specific execution lives in
 * `PlaybackLauncher`; this type only expresses intent so the decision can be reasoned about and
 * unit-tested in isolation.
 */
sealed interface PlaybackStartDecision {
    /** The system allows it (or the caller is in the foreground): start playback now. */
    data object Proceed : PlaybackStartDecision

    /** A background start is blocked, but this entry point can bring the app to the foreground. */
    data object RedirectToForeground : PlaybackStartDecision

    /** A background start is blocked and cannot be redirected: do not start. */
    data object Reject : PlaybackStartDecision
}

/**
 * The observable outcome of a start attempt, reported by `PlaybackLauncher` and surfaced through
 * `PlaybackStartReporter`.
 */
sealed interface PlaybackStartResult {
    val source: PlaybackStartSource

    /** Playback was requested on the controller. */
    data class Started(override val source: PlaybackStartSource) : PlaybackStartResult

    /** Playback was not started; the app was (or should be) brought to the foreground instead. */
    data class Redirected(override val source: PlaybackStartSource) : PlaybackStartResult

    /** The system disallowed a background foreground-service start and it could not be redirected. */
    data class Blocked(override val source: PlaybackStartSource, val reason: String) : PlaybackStartResult

    /** A start was attempted but threw. */
    data class Failed(override val source: PlaybackStartSource, val reason: String) : PlaybackStartResult
}

/**
 * Single source of truth for "given who is asking and whether the system currently allows a
 * background foreground-service start, what should happen?".
 *
 * Deliberately free of Android dependencies so the whole decision matrix can be unit-tested
 * directly (see `PlaybackStartCoordinatorTest`). Every entry point routes through this so boot
 * auto-start, the quick settings tile and normal foreground starts can no longer drift apart.
 */
object PlaybackStartCoordinator {

    fun decide(
        source: PlaybackStartSource,
        canStartForegroundService: Boolean,
    ): PlaybackStartDecision {
        if (source.isForeground || canStartForegroundService) {
            return PlaybackStartDecision.Proceed
        }
        return if (source.canRedirectToForeground) {
            PlaybackStartDecision.RedirectToForeground
        } else {
            PlaybackStartDecision.Reject
        }
    }
}
