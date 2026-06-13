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

import io.github.mkckr0.audio_share_app.pb.Client
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure-JVM unit tests for [PcmConverter]'s byte-level PCM conversions. */
class PcmConverterTest {

    private val enc = Client.AudioFormat.Encoding

    private fun leBuffer(capacity: Int) = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)

    private fun readShorts(buf: ByteBuffer): ShortArray {
        val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(b.remaining() / 2)
        for (i in out.indices) out[i] = b.short
        return out
    }

    @Test
    fun identityReturnsSameBuffer() {
        val src = leBuffer(8).apply { putShort(1); putShort(2); putShort(3); putShort(4); flip() }
        assertSame(src, PcmConverter.IDENTITY.convert(src))
    }

    @Test
    fun float32ToS16() {
        val src = leBuffer(16).apply {
            putFloat(1.0f); putFloat(-1.0f); putFloat(0.5f); putFloat(0.0f); flip()
        }
        val out = PcmConverter.toS16(enc.ENCODING_PCM_FLOAT, 1, 1).convert(src)
        assertArrayEquals(shortArrayOf(32767, -32767, 16383, 0), readShorts(out))
    }

    @Test
    fun int32ToS16_keepsTopBits() {
        val src = leBuffer(12).apply {
            putInt(0x7FFFFFFF); putInt(Int.MIN_VALUE); putInt(0x00010000); flip()
        }
        val out = PcmConverter.toS16(enc.ENCODING_PCM_32BIT, 1, 1).convert(src)
        assertArrayEquals(shortArrayOf(32767, -32768, 1), readShorts(out))
    }

    @Test
    fun packed24ToS16_keepsTopBits() {
        // 24-bit packed little-endian samples: 0x7FFFFF, 0x800000, 0x000100.
        val src = leBuffer(9).apply {
            put(
                byteArrayOf(
                    0xFF.toByte(), 0xFF.toByte(), 0x7F,
                    0x00, 0x00, 0x80.toByte(),
                    0x00, 0x01, 0x00,
                )
            )
            flip()
        }
        val out = PcmConverter.toS16(enc.ENCODING_PCM_24BIT, 1, 1).convert(src)
        assertArrayEquals(shortArrayOf(32767, -32768, 1), readShorts(out))
    }

    @Test
    fun unsigned8ToS16_centersAt128() {
        val src = leBuffer(3).apply { put(byteArrayOf(255.toByte(), 128.toByte(), 0)); flip() }
        val out = PcmConverter.toS16(enc.ENCODING_PCM_8BIT, 1, 1).convert(src)
        assertArrayEquals(shortArrayOf(32512, 0, -32768), readShorts(out))
    }

    @Test
    fun downmixSurroundToStereo_takesFirstTwoChannels() {
        // one 4-channel 16-bit frame -> stereo (first two channels)
        val src = leBuffer(8).apply { putShort(100); putShort(200); putShort(300); putShort(400); flip() }
        val out = PcmConverter.toS16(enc.ENCODING_PCM_16BIT, 4, 2).convert(src)
        assertArrayEquals(shortArrayOf(100, 200), readShorts(out))
    }

    @Test
    fun downmixStereoToMono_averages() {
        val src = leBuffer(4).apply { putShort(100); putShort(300); flip() }
        val out = PcmConverter.toS16(enc.ENCODING_PCM_16BIT, 2, 1).convert(src)
        assertArrayEquals(shortArrayOf(200), readShorts(out))
    }

    @Test
    fun sampleConversionPreservesChannelCount() {
        // 32-bit stereo -> 16-bit stereo (one frame), channels unchanged.
        val src = leBuffer(8).apply { putInt(0x7FFFFFFF); putInt(0x00010000); flip() }
        val out = PcmConverter.toS16(enc.ENCODING_PCM_32BIT, 2, 2).convert(src)
        assertArrayEquals(shortArrayOf(32767, 1), readShorts(out))
    }

    @Test
    fun trailingPartialFrameIsIgnored() {
        // 3 shorts as 16-bit stereo = 1.5 frames -> only the full frame is emitted.
        val src = leBuffer(6).apply { putShort(10); putShort(20); putShort(30); flip() }
        val out = PcmConverter.toS16(enc.ENCODING_PCM_16BIT, 2, 2).convert(src)
        assertArrayEquals(shortArrayOf(10, 20), readShorts(out))
    }

    @Test
    fun convertDoesNotConsumeSourceBuffer() {
        val src = leBuffer(8).apply { putShort(1); putShort(2); putShort(3); putShort(4); flip() }
        val before = src.position()
        PcmConverter.toS16(enc.ENCODING_PCM_16BIT, 2, 1).convert(src)
        assertEquals(before, src.position())
    }
}
