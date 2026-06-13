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

/**
 * Holds and manages the lifecycle of [AudioTrack] and [LoudnessEnhancer].
 *
 * The single most important guarantee of this class is that [release] is
 * **idempotent**: calling it when no resources exist is a no-op, and calling
 * it multiple times in a row never double-releases.  This makes it safe to
 * invoke at every lifecycle transition (reconnect, stop, pause, release)
 * without worrying about ordering or duplicate calls.
 */
internal class AudioResourceManager {

    var audioTrack: AudioTrack? = null
        private set

    var loudnessEnhancer: LoudnessEnhancer? = null
        private set

    /**
     * Assign newly-created audio resources.  Any previously held resources
     * are **not** released automatically — callers must invoke [release]
     * before [assign] if they want to avoid leaks (the typical pattern is
     * `release(); assign(newTrack, newEnhancer)`).
     */
    fun assign(track: AudioTrack, enhancer: LoudnessEnhancer? = null) {
        audioTrack = track
        loudnessEnhancer = enhancer
    }

    /**
     * Idempotent teardown: releases [LoudnessEnhancer] first (it depends on
     * the audio session of the [AudioTrack]), then pauses, flushes and
     * releases the [AudioTrack].  Both references are nullified so that
     * subsequent calls are safe no-ops.
     */
    fun release() {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        audioTrack?.let {
            it.pause()
            it.flush()
            it.release()
        }
        audioTrack = null
    }
}
