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
import android.os.Bundle
import android.util.Log
import androidx.core.os.bundleOf
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.mkckr0.audio_share_app.model.canStartForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlin.time.Duration.Companion.seconds

/**
 * Unified playback startup coordinator that handles background service startup
 * with proper system restriction awareness and result feedback.
 *
 * This coordinator centralizes the logic for starting playback from different
 * entry points (boot, quick settings tile, foreground) while:
 * - Checking system restrictions (battery optimization, app visibility)
 * - Providing structured result feedback
 * - Handling failure scenarios gracefully
 * - Maintaining consistent behavior across all entry points
 */
object PlaybackCoordinator {

    private const val TAG = "PlaybackCoordinator"

    /**
     * Represents the source of the playback start request
     */
    enum class StartSource {
        BOOT,           // System boot completed
        QS_TILE,        // Quick settings tile clicked
        FOREGROUND      // Normal foreground interaction
    }

    /**
     * Represents the result of a playback start attempt
     */
    sealed class StartResult {
        data class Success(val source: StartSource) : StartResult()
        data class Failure(val source: StartSource, val reason: FailureReason) : StartResult()
    }

    /**
     * Represents the reason for a startup failure
     */
    enum class FailureReason {
        SYSTEM_RESTRICTION,     // Cannot start foreground service due to system restrictions
        CONTROLLER_ERROR,       // Failed to create or connect MediaController
        PLAYBACK_ERROR,         // Failed to start playback
        CANCELLED               // Request was cancelled
    }

    /**
     * Callback interface for receiving startup results
     */
    interface ResultCallback {
        fun onResult(result: StartResult)
    }

    /**
     * Attempts to start playback with system restriction awareness.
     *
     * @param context The context used to start the service
     * @param source The source of the start request
     * @param callback Optional callback for receiving the result
     * @return The start result
     */
    suspend fun startPlayback(
        context: Context,
        source: StartSource,
        callback: ResultCallback? = null
    ): StartResult {
        Log.d(TAG, "startPlayback from source: $source")

        return try {
            // Check system restrictions based on source
            val canStart = when (source) {
                StartSource.BOOT -> {
                    // Boot service runs in background context, check restrictions
                    context.canStartForegroundService()
                }
                StartSource.QS_TILE -> {
                    // QS tile is considered a user interaction, but still check
                    context.canStartForegroundService()
                }
                StartSource.FOREGROUND -> {
                    // Foreground interactions typically have fewer restrictions
                    true
                }
            }

            if (!canStart) {
                Log.w(TAG, "Cannot start foreground service from source: $source")
                StartResult.Failure(source, FailureReason.SYSTEM_RESTRICTION).also {
                    callback?.onResult(it)
                }
            } else {
                // Create MediaController and start playback
                val sessionToken = SessionToken(
                    context,
                    ComponentName(context, PlaybackService::class.java)
                )

                val mediaController = try {
                    MediaController.Builder(context, sessionToken)
                        .setConnectionHints(bundleOf("src" to source.name))
                        .buildAsync()
                        .await()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create MediaController", e)
                    StartResult.Failure(source, FailureReason.CONTROLLER_ERROR).also {
                        callback?.onResult(it)
                    }
                    return it
                }

                // Start playback
                try {
                    mediaController.play()
                    Log.d(TAG, "Playback started successfully from source: $source")

                    // Wait briefly to ensure service is started
                    delay(1.seconds)

                    StartResult.Success(source).also {
                        callback?.onResult(it)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start playback", e)
                    StartResult.Failure(source, FailureReason.PLAYBACK_ERROR).also {
                        callback?.onResult(it)
                    }
                } finally {
                    mediaController.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during playback start", e)
            StartResult.Failure(source, FailureReason.CONTROLLER_ERROR).also {
                callback?.onResult(it)
            }
        }
    }

    /**
     * Checks if playback can be started from the given source without actually starting it.
     *
     * @param context The context to check restrictions
     * @param source The source to check for
     * @return true if playback can be started, false otherwise
     */
    fun canStartPlayback(context: Context, source: StartSource): Boolean {
        return when (source) {
            StartSource.BOOT, StartSource.QS_TILE -> context.canStartForegroundService()
            StartSource.FOREGROUND -> true
        }
    }
}
