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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Regression tests for [ResourceSlot], the single-owner that fixes the audio
 * resource leak during reconnection: the [android.media.AudioTrack] /
 * [android.media.audiofx.LoudnessEnhancer] used to be overwritten on every
 * reconnect without releasing the previous instances.
 */
class ResourceSlotTest {

    /** Stand-in for a native resource (AudioTrack / LoudnessEnhancer). */
    private class FakeResource(val id: Int, private val throwOnRelease: Boolean = false) {
        var releaseCount = 0
            private set

        fun release() {
            ++releaseCount
            if (throwOnRelease) {
                throw IllegalStateException("release of resource $id failed")
            }
        }
    }

    private fun newSlot(): ResourceSlot<FakeResource> = ResourceSlot { it.release() }

    @Test
    fun set_releasesPreviousBeforeStoringNew() {
        val slot = newSlot()
        val first = FakeResource(1)
        val second = FakeResource(2)

        slot.set(first)
        assertSame(first, slot.value)
        assertEquals(0, first.releaseCount)

        slot.set(second)
        assertEquals("previous resource must be released exactly once on replace", 1, first.releaseCount)
        assertSame(second, slot.value)
        assertEquals("the newly stored resource must not be released", 0, second.releaseCount)
    }

    @Test
    fun clear_releasesCurrentAndIsIdempotent() {
        val slot = newSlot()
        val resource = FakeResource(1)
        slot.set(resource)

        slot.clear()
        assertNull(slot.value)
        assertEquals(1, resource.releaseCount)

        // A second clear must not touch the already-released resource.
        slot.clear()
        assertNull(slot.value)
        assertEquals("clear() must be idempotent", 1, resource.releaseCount)
    }

    @Test
    fun clear_onEmptySlot_isNoOp() {
        val slot = newSlot()
        slot.clear()
        assertNull(slot.value)
    }

    @Test
    fun set_null_releasesPreviousAndEmptiesSlot() {
        val slot = newSlot()
        val resource = FakeResource(1)
        slot.set(resource)

        slot.set(null)
        assertEquals(1, resource.releaseCount)
        assertNull(slot.value)
    }

    @Test
    fun failingRelease_stillClearsSlot_andDoesNotRethrow() {
        val slot = newSlot()
        val bad = FakeResource(1, throwOnRelease = true)
        slot.set(bad)

        // A throwing teardown must not propagate ...
        slot.clear()
        // ... and must still have cleared the reference, so no stale state leaks.
        assertNull(slot.value)
        assertEquals(1, bad.releaseCount)

        // A later teardown must not retry releasing the failed resource.
        slot.clear()
        assertEquals("failed resource must not be released again", 1, bad.releaseCount)
    }

    @Test
    fun failingRelease_onReplace_doesNotBlockStoringNewResource() {
        val slot = newSlot()
        val bad = FakeResource(1, throwOnRelease = true)
        val good = FakeResource(2)
        slot.set(bad)

        // Replacing a resource whose release throws must still install the new one.
        slot.set(good)
        assertEquals(1, bad.releaseCount)
        assertSame(good, slot.value)
        assertEquals(0, good.releaseCount)
    }

    @Test
    fun repeatedReconnects_releaseEveryPreviousResourceExactlyOnce() {
        // Simulates onReceiveAudioFormat() running once per reconnect: each new
        // AudioTrack is stored and the previous one must be released exactly once,
        // with only the latest left live. Direct regression for the leak where
        // reconnects accumulated orphaned AudioTrack / LoudnessEnhancer instances.
        val slot = newSlot()
        val created = mutableListOf<FakeResource>()

        repeat(10) { i ->
            val resource = FakeResource(i)
            created += resource
            slot.set(resource)
        }

        // Every superseded resource was released exactly once (none leaked).
        created.dropLast(1).forEach {
            assertEquals("resource ${it.id} leaked across reconnects", 1, it.releaseCount)
        }

        // The most recent resource is still live.
        val last = created.last()
        assertSame(last, slot.value)
        assertEquals(0, last.releaseCount)

        // Final teardown releases the last one too, idempotently.
        slot.clear()
        assertEquals(1, last.releaseCount)
        slot.clear()
        assertEquals(1, last.releaseCount)
        assertNull(slot.value)
    }
}
