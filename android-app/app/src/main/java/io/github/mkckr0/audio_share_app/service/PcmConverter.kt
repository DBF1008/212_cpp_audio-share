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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts raw PCM packets from the format the server sends into a format the
 * client's [android.media.AudioTrack] can accept, when the negotiated playback
 * format differs from the source (see [AudioFormatNegotiator]).
 *
 * Pure logic (no Android framework dependency) so it is unit-testable. All
 * buffers are little-endian. Input packets are frame-aligned (the server splits
 * UDP frames on `block_align`), and any trailing partial frame is ignored
 * defensively.
 *
 * Every non-identity converter produces 16-bit signed PCM, because 16-bit is
 * universally playable on Android and is the only target the fallback path ever
 * needs (bit-depth down-conversion and/or channel downmix).
 */
interface PcmConverter {

    /** Convert one source buffer; the returned buffer is positioned for reading. */
    fun convert(src: ByteBuffer): ByteBuffer

    companion object {
        /** Passes the buffer through unchanged (source already playable as-is). */
        val IDENTITY: PcmConverter = IdentityConverter

        /**
         * A converter that reads [sourceEncoding]/[sourceChannels] PCM and emits
         * 16-bit PCM with [targetChannels] channels (folding channels if needed).
         */
        fun toS16(
            sourceEncoding: Client.AudioFormat.Encoding,
            sourceChannels: Int,
            targetChannels: Int,
        ): PcmConverter {
            require(sourceChannels > 0) { "sourceChannels must be > 0" }
            require(targetChannels > 0) { "targetChannels must be > 0" }
            return ToS16Converter(sourceEncoding, sourceChannels, targetChannels)
        }

        fun bytesPerSample(encoding: Client.AudioFormat.Encoding): Int = when (encoding) {
            Client.AudioFormat.Encoding.ENCODING_PCM_8BIT -> 1
            Client.AudioFormat.Encoding.ENCODING_PCM_16BIT -> 2
            Client.AudioFormat.Encoding.ENCODING_PCM_24BIT -> 3
            Client.AudioFormat.Encoding.ENCODING_PCM_32BIT -> 4
            Client.AudioFormat.Encoding.ENCODING_PCM_FLOAT -> 4
            else -> throw IllegalArgumentException("unknown encoding: $encoding")
        }
    }
}

private object IdentityConverter : PcmConverter {
    override fun convert(src: ByteBuffer): ByteBuffer = src
}

private class ToS16Converter(
    private val sourceEncoding: Client.AudioFormat.Encoding,
    private val sourceChannels: Int,
    private val targetChannels: Int,
) : PcmConverter {

    private val srcSampleBytes = PcmConverter.bytesPerSample(sourceEncoding)
    private val srcFrameBytes = srcSampleBytes * sourceChannels
    private val dstFrameBytes = BYTES_PER_S16_SAMPLE * targetChannels

    override fun convert(src: ByteBuffer): ByteBuffer {
        // Read via absolute gets on a duplicate so the caller's buffer is untouched.
        val input = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = input.position()
        val frames = input.remaining() / srcFrameBytes

        val out = ByteBuffer.allocate(frames * dstFrameBytes).order(ByteOrder.LITTLE_ENDIAN)
        val frame = ShortArray(sourceChannels)

        for (f in 0 until frames) {
            val frameOffset = base + f * srcFrameBytes
            for (ch in 0 until sourceChannels) {
                frame[ch] = readSampleAsShort(input, frameOffset + ch * srcSampleBytes)
            }
            writeFolded(out, frame)
        }

        out.flip()
        return out
    }

    private fun readSampleAsShort(buf: ByteBuffer, idx: Int): Short = when (sourceEncoding) {
        // Android ENCODING_PCM_8BIT is unsigned; center at 128, scale to 16-bit.
        Client.AudioFormat.Encoding.ENCODING_PCM_8BIT -> {
            val u = buf.get(idx).toInt() and 0xFF
            ((u - 128) shl 8).toShort()
        }

        Client.AudioFormat.Encoding.ENCODING_PCM_16BIT -> buf.getShort(idx)

        // 24-bit packed little-endian (3 bytes, byte[2] is the signed MSB):
        // keep the top 16 bits.
        Client.AudioFormat.Encoding.ENCODING_PCM_24BIT -> {
            val b1 = buf.get(idx + 1).toInt() and 0xFF
            val b2 = buf.get(idx + 2).toInt() // signed high byte
            ((b2 shl 8) or b1).toShort()
        }

        // 32-bit little-endian (byte[3] is the signed MSB): keep the top 16 bits.
        Client.AudioFormat.Encoding.ENCODING_PCM_32BIT -> {
            val b2 = buf.get(idx + 2).toInt() and 0xFF
            val b3 = buf.get(idx + 3).toInt() // signed high byte
            ((b3 shl 8) or b2).toShort()
        }

        // 32-bit float in [-1, 1]; clamp and scale.
        Client.AudioFormat.Encoding.ENCODING_PCM_FLOAT -> {
            val v = buf.getFloat(idx)
            val clamped = if (v > 1f) 1f else if (v < -1f) -1f else v
            (clamped * S16_MAX_F).toInt().toShort()
        }

        else -> 0
    }

    private fun writeFolded(out: ByteBuffer, frame: ShortArray) {
        when {
            // No channel change: copy through.
            targetChannels == sourceChannels -> {
                for (ch in 0 until sourceChannels) {
                    out.putShort(frame[ch])
                }
            }
            // Downmix to mono: average all source channels.
            targetChannels == 1 -> {
                var sum = 0
                for (ch in 0 until sourceChannels) {
                    sum += frame[ch]
                }
                out.putShort((sum / sourceChannels).toShort())
            }
            // Downmix to stereo (or any N < source): take the first target channels.
            else -> {
                for (ch in 0 until targetChannels) {
                    out.putShort(if (ch < sourceChannels) frame[ch] else 0)
                }
            }
        }
    }

    private companion object {
        const val BYTES_PER_S16_SAMPLE = 2
        const val S16_MAX_F = 32767f
    }
}
