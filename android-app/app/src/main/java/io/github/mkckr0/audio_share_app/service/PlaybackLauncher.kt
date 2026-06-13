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

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import io.github.mkckr0.audio_share_app.model.canStartForegroundService

private const val TAG = "PlaybackLauncher"

/**
 * Process-wide, observable record of the most recent playback start attempt.
 *
 * Mirrors the [AudioPlayer.message] idiom (a Compose [mutableStateOf] in the service layer) so any
 * Compose UI can read [lastResult] directly, and every attempt is logged under a single tag so a
 * "nothing happened" report can be diagnosed without guessing which entry point was used.
 */
object PlaybackStartReporter {

    var lastResult: PlaybackStartResult? by mutableStateOf<PlaybackStartResult?>(null)
        private set

    fun report(result: PlaybackStartResult) {
        lastResult = result
        when (result) {
            is PlaybackStartResult.Started ->
                Log.i(TAG, "start[${result.source}] -> Started")
            is PlaybackStartResult.Redirected ->
                Log.i(TAG, "start[${result.source}] -> Redirected to foreground")
            is PlaybackStartResult.Blocked ->
                Log.w(TAG, "start[${result.source}] -> Blocked: ${result.reason}")
            is PlaybackStartResult.Failed ->
                Log.e(TAG, "start[${result.source}] -> Failed: ${result.reason}")
        }
    }
}

/**
 * The unified background-start coordination path. Every entry point — boot auto-start, the quick
 * settings tile, app start and the in-app play button — funnels its `play()` through here so they
 * share one permission/visibility gate ([Context.canStartForegroundService]), one result type, one
 * failure report and one state-sync step. This is what stops the entry points from drifting apart.
 *
 * The caller owns the [controller]'s lifecycle; this function only decides and acts on it. When the
 * result is [PlaybackStartResult.Redirected] the caller is responsible for actually bringing the app
 * to the foreground (e.g. the tile's `startActivityAndCollapse`), since that step is entry-point
 * specific and cannot be performed generically.
 */
fun startPlayback(
    context: Context,
    source: PlaybackStartSource,
    controller: MediaController,
): PlaybackStartResult {
    val canStart = context.canStartForegroundService()
    Log.d(TAG, "startPlayback source=$source canStartForegroundService=$canStart")

    val result = when (PlaybackStartCoordinator.decide(source, canStart)) {
        PlaybackStartDecision.Proceed -> try {
            controller.play()
            PlaybackStartResult.Started(source)
        } catch (e: Exception) {
            PlaybackStartResult.Failed(source, e.message ?: e.javaClass.simpleName)
        }

        PlaybackStartDecision.RedirectToForeground ->
            PlaybackStartResult.Redirected(source)

        PlaybackStartDecision.Reject ->
            PlaybackStartResult.Blocked(source, "background foreground-service start not allowed")
    }

    PlaybackStartReporter.report(result)

    // State sync: ask the system to refresh the quick settings tile so its on/off state reflects
    // reality after this attempt (it must not be left showing "active" when the start was blocked).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        TileService.requestListeningState(
            context,
            ComponentName(context, QsTileService::class.java)
        )
    }

    return result
}
