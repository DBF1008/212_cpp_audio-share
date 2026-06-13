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
 * Owns at most one resource of type [T] and guarantees it is released exactly
 * once whenever it is replaced or cleared.
 *
 * The audio resources ([android.media.AudioTrack] and its
 * [android.media.audiofx.LoudnessEnhancer]) used to be overwritten on every
 * reconnect without releasing the previous native instances, leaking audio
 * sessions and effects across retries and mixing in stale state. Routing them
 * through a slot turns the teardown into a single, idempotent operation:
 *
 *  - [set] releases the previous occupant *before* storing the new one, so a
 *    reconnect can never leave an orphaned resource behind.
 *  - [clear] is safe to call any number of times; once empty it is a no-op.
 *  - a release that throws still clears the reference, so a failed teardown
 *    cannot leave stale state that a later retry would trip over.
 *
 * Not thread-safe: every audio callback and player command runs on the media
 * session's main [android.os.Looper], so all access is single-threaded.
 *
 * @param release how to tear down a held resource; invoked at most once per value.
 */
class ResourceSlot<T>(private val release: (T) -> Unit) {

    var value: T? = null
        private set

    /** Store [value], releasing whatever was held before (if anything). */
    fun set(value: T?) {
        clear()
        this.value = value
    }

    /** Release the held resource, if any. Idempotent. */
    fun clear() {
        val current = value ?: return
        // Drop the reference first so the resource is gone even if release()
        // throws — a failed teardown must not leave a dangling instance that a
        // later clear()/set() would touch again.
        value = null
        try {
            release(current)
        } catch (e: Exception) {
            // Best effort: the reference is already cleared above.
        }
    }
}
