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

import android.media.AudioFormat
import io.github.mkckr0.audio_share_app.pb.Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [AudioFormatNegotiator]. These reference Android
 * AudioFormat constants (compile-time `static final int`, so they inline) and
 * never call AudioTrack, so they run on the local JVM without an emulator.
 */
class AudioFormatNegotiatorTest {

    private val enc = Client.AudioFormat.Encoding

    // API levels: 30 = Android 11 (no 24/32-bit PCM), 31 = Android 12 (Build.VERSION_CODES.S).
    private val sdkLegacy = 30
    private val sdkS = 31

    @Test
    fun float_isKeptOnAllSupportedApis() {
        for (sdk in intArrayOf(23, sdkLegacy, sdkS)) {
            val r = AudioFormatNegotiator.negotiate(enc.ENCODING_PCM_FLOAT, 2, 48000, sdk)
            assertEquals(AudioFormat.ENCODING_PCM_FLOAT, r.playback.encoding)
            assertEquals(AudioFormat.CHANNEL_OUT_STEREO, r.playback.channelMask)
            assertEquals(2, r.playback.channelCount)
            assertEquals(48000, r.playback.sampleRate)
            assertFalse(r.converted)
        }
    }

    @Test
    fun pcm16_isKeptNatively() {
        val r = AudioFormatNegotiator.negotiate(enc.ENCODING_PCM_16BIT, 2, 44100, sdkLegacy)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, r.playback.encoding)
        assertFalse(r.converted)
    }

    @Test
    fun pcm8_isKeptNatively_mono() {
        val r = AudioFormatNegotiator.negotiate(enc.ENCODING_PCM_8BIT, 1, 48000, sdkLegacy)
        assertEquals(AudioFormat.ENCODING_PCM_8BIT, r.playback.encoding)
        assertEquals(AudioFormat.CHANNEL_OUT_MONO, r.playback.channelMask)
        assertFalse(r.converted)
    }

    @Test
    fun pcm24_keptOnApi31_downConvertedBelow() {
        val onS = AudioFormatNegotiator.negotiate(enc.ENCODING_PCM_24BIT, 2, 48000, sdkS)
        assertEquals(AudioFormat.ENCODING_PCM_24BIT_PACKED, onS.playback.encoding)
        assertFalse(onS.converted)

        val legacy = AudioFormatNegotiator.negotiate(enc.ENCODING_PCM_24BIT, 2, 48000, sdkLegacy)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, legacy.playback.encoding)
        assertTrue(legacy.converted)
    }

    @Test
    fun pcm32_keptOnApi31_downConvertedBelow() {
        val onS = AudioFormatNegotiator.negotiate(enc.ENCODING_PCM_32BIT, 2, 48000, sdkS)
        assertEquals(AudioFormat.ENCODING_PCM_32BIT, onS.playback.encoding)
        assertFalse(onS.converted)

        val legacy = AudioFormatNegotiator.negotiate(enc.ENCODING_PCM_32BIT, 2, 48000, sdkLegacy)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, legacy.playback.encoding)
        assertTrue(legacy.converted)
    }

    @Test
    fun multichannelLayoutIsPreservedWhenValid() {
        // 6 channels -> 5.1 mask; bit depth still down-converted on legacy.
        val r = AudioFormatNegotiator.negotiate(enc.ENCODING_PCM_32BIT, 6, 48000, sdkLegacy)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, r.playback.encoding)
        assertEquals(AudioFormat.CHANNEL_OUT_5POINT1, r.playback.channelMask)
        assertEquals(6, r.playback.channelCount)
        assertTrue(r.converted)
    }

    @Test
    fun invalidChannelLayoutIsDownmixedToStereo() {
        // 9 channels has no CHANNEL_OUT mask -> downmix to stereo, force 16-bit.
        val r = AudioFormatNegotiator.negotiate(enc.ENCODING_PCM_16BIT, 9, 48000, sdkS)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, r.playback.encoding)
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO, r.playback.channelMask)
        assertEquals(2, r.playback.channelCount)
        assertTrue(r.converted)
    }

    @Test
    fun floatWithExoticLayoutDownmixesAndDropsToS16() {
        val r = AudioFormatNegotiator.negotiate(enc.ENCODING_PCM_FLOAT, 10, 48000, sdkS)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, r.playback.encoding)
        assertEquals(2, r.playback.channelCount)
        assertTrue(r.converted)
    }

    @Test
    fun invalidInputsThrow() {
        assertThrows(AudioFormatNegotiator.UnsupportedFormatException::class.java) {
            AudioFormatNegotiator.negotiate(enc.ENCODING_PCM_16BIT, 0, 48000, sdkS)
        }
        assertThrows(AudioFormatNegotiator.UnsupportedFormatException::class.java) {
            AudioFormatNegotiator.negotiate(enc.ENCODING_PCM_16BIT, 2, 0, sdkS)
        }
        assertThrows(AudioFormatNegotiator.UnsupportedFormatException::class.java) {
            AudioFormatNegotiator.negotiate(enc.ENCODING_INVALID, 2, 48000, sdkS)
        }
    }

    @Test
    fun safeFallbackIsAlways16BitStereo() {
        val r = AudioFormatNegotiator.safeFallback(enc.ENCODING_PCM_FLOAT, 6, 96000)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, r.playback.encoding)
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO, r.playback.channelMask)
        assertEquals(2, r.playback.channelCount)
        assertEquals(96000, r.playback.sampleRate)
        assertTrue(r.converted)
    }

    @Test
    fun capabilitiesExcludeHighBitDepthBelowApi31() {
        val caps = AudioFormatNegotiator.buildPlaybackCapabilities(sdkLegacy)
        val list = caps.supportedEncodingsList
        assertTrue(list.contains(enc.ENCODING_PCM_8BIT))
        assertTrue(list.contains(enc.ENCODING_PCM_16BIT))
        assertTrue(list.contains(enc.ENCODING_PCM_FLOAT))
        assertFalse(list.contains(enc.ENCODING_PCM_24BIT))
        assertFalse(list.contains(enc.ENCODING_PCM_32BIT))
        assertEquals(8, caps.maxChannels)
        assertEquals(192000, caps.maxSampleRate)
    }

    @Test
    fun capabilitiesIncludeHighBitDepthOnApi31() {
        val caps = AudioFormatNegotiator.buildPlaybackCapabilities(sdkS)
        val list = caps.supportedEncodingsList
        assertTrue(list.contains(enc.ENCODING_PCM_24BIT))
        assertTrue(list.contains(enc.ENCODING_PCM_32BIT))
    }
}
