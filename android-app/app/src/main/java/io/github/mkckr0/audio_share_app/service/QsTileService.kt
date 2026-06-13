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

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionToken
import io.github.mkckr0.audio_share_app.service.PlaybackService.Companion.ACTION_STOP_SERVICE
import io.github.mkckr0.audio_share_app.ui.MainActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@RequiresApi(Build.VERSION_CODES.N)
class QsTileService : TileService() {

    private val tag = QsTileService::class.simpleName

    private val scope = MainScope()

    override fun onCreate() {
        Log.d(tag, "onCreate")
        super.onCreate()
    }

    override fun onDestroy() {
        Log.d(tag, "onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(tag, "onBind")
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(tag, "onUnbind")
        return super.onUnbind(intent)
    }

    override fun onStartListening() {
        Log.d(tag, "onStartListening")
        super.onStartListening()
        withMediaController {
            toggleState(playWhenReady)
        }
    }

    override fun onStopListening() {
        Log.d(tag, "onStopListening")
        super.onStopListening()
    }

    override fun onClick() {
        Log.d(tag, "onClick")
        super.onClick()
        withMediaController {
            if (playWhenReady) {
                // Stop playback
                sendCustomCommand(SessionCommand(ACTION_STOP_SERVICE, Bundle.EMPTY), Bundle.EMPTY)
                delay(1.seconds)
            } else {
                // Start playback via coordinator
                scope.launch {
                    val result = PlaybackCoordinator.startPlayback(
                        context = this@QsTileService,
                        source = PlaybackCoordinator.StartSource.QS_TILE,
                        callback = object : PlaybackCoordinator.ResultCallback {
                            override fun onResult(result: PlaybackCoordinator.StartResult) {
                                Log.d(tag, "Playback start result: $result")
                            }
                        }
                    )

                    when (result) {
                        is PlaybackCoordinator.StartResult.Success -> {
                            Log.d(tag, "Playback started successfully from QS tile")
                            delay(1.seconds)
                        }
                        is PlaybackCoordinator.StartResult.Failure -> {
                            Log.w(tag, "Failed to start playback: ${result.reason}")
                            handleStartFailure(result.reason)
                        }
                    }
                }
            }
        }
    }

    private fun toggleState(isRunning: Boolean) {
        Log.d(tag, "toggleState: $isRunning")
        qsTile.apply {
            state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (state == Tile.STATE_ACTIVE) "On" else "Off"
            }
        }.updateTile()
    }

    /**
     * Handles playback start failures by providing appropriate user feedback.
     *
     * @param reason The reason for the failure
     */
    private fun handleStartFailure(reason: PlaybackCoordinator.FailureReason) {
        when (reason) {
            PlaybackCoordinator.FailureReason.SYSTEM_RESTRICTION -> {
                Log.w(tag, "System restriction prevents foreground service start")
                // Open MainActivity to allow user to start playback from foreground
                openMainActivity()
            }
            PlaybackCoordinator.FailureReason.CONTROLLER_ERROR -> {
                Log.e(tag, "Failed to create MediaController")
                // Keep tile in inactive state, user can retry
            }
            PlaybackCoordinator.FailureReason.PLAYBACK_ERROR -> {
                Log.e(tag, "Failed to start playback")
                // Keep tile in inactive state, user can retry
            }
            PlaybackCoordinator.FailureReason.CANCELLED -> {
                Log.d(tag, "Playback start was cancelled")
            }
        }
    }

    /**
     * Opens MainActivity to allow user to start playback from foreground context.
     */
    private fun openMainActivity() {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    @OptIn(UnstableApi::class)
    private fun withMediaController(block: suspend MediaController.() -> Unit) {
        scope.launch {
            val sessionToken =
                SessionToken(this@QsTileService, ComponentName(this@QsTileService, PlaybackService::class.java))
            val mediaController = MediaController.Builder(this@QsTileService, sessionToken)
                .setConnectionHints(bundleOf("src" to "QsTileService"))
                .setListener(object : MediaController.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        super.onDisconnected(controller)
                        Log.d(tag, "onDisconnected")
                    }

                    override fun onError(controller: MediaController, sessionError: SessionError) {
                        super.onError(controller, sessionError)
                        Log.d(tag, "onError $sessionError")
                    }
                })
                .buildAsync().await()
            block.invoke(mediaController)
            mediaController.release()
        }
    }
}