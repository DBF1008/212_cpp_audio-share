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
import android.os.Build
import io.github.mkckr0.audio_share_app.pb.Client

/**
 * Chooses an [android.media.AudioTrack] configuration the device can actually
 * play for a source format reported by the server, falling back when the source
 * encoding or channel layout is not natively playable:
 *
 *  - 24/32-bit integer PCM is kept only on API >= 31 (Android S), otherwise it
 *    is down-converted to 16-bit.
 *  - Channel counts that have no valid CHANNEL_OUT mask are downmixed to stereo.
 *  - The sample rate is preserved (no resampling); an invalid one is rejected.
 *
 * This is the authoritative, device-aware fallback (the shared server capture
 * stream cannot be transcoded per client). It is pure logic — [sdkInt] is passed
 * explicitly rather than read from Build.VERSION.SDK_INT — so it is unit-testable.
 * [AudioPlayer] adds a second safety layer: if building the negotiated track
 * fails on a specific device it retries with [safeFallback].
 */
object AudioFormatNegotiator {

    data class PlaybackFormat(
        val encoding: Int,
        val channelMask: Int,
        val channelCount: Int,
        val sampleRate: Int,
    )

    data class Result(
        val playback: PlaybackFormat,
        val converter: PcmConverter,
        /** True when incoming PCM must be converted before being written. */
        val converted: Boolean,
    )

    class UnsupportedFormatException(message: String) : Exception(message)

    fun negotiate(
        sourceEncoding: Client.AudioFormat.Encoding,
        sourceChannels: Int,
        sampleRate: Int,
        sdkInt: Int,
    ): Result {
        if (sampleRate <= 0) {
            throw UnsupportedFormatException("invalid sample rate: $sampleRate")
        }
        if (sourceChannels <= 0) {
            throw UnsupportedFormatException("invalid channel count: $sourceChannels")
        }

        // ENCODING_PCM_24BIT_PACKED and ENCODING_PCM_32BIT both require API 31.
        val extendedPcm = sdkInt >= Build.VERSION_CODES.S

        // The Android encoding to keep if the source bit-depth is natively
        // playable, else null (meaning: down-convert to 16-bit).
        val keepEncoding: Int? = when (sourceEncoding) {
            Client.AudioFormat.Encoding.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
            Client.AudioFormat.Encoding.ENCODING_PCM_8BIT -> AudioFormat.ENCODING_PCM_8BIT
            Client.AudioFormat.Encoding.ENCODING_PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
            Client.AudioFormat.Encoding.ENCODING_PCM_24BIT ->
                if (extendedPcm) AudioFormat.ENCODING_PCM_24BIT_PACKED else null
            Client.AudioFormat.Encoding.ENCODING_PCM_32BIT ->
                if (extendedPcm) AudioFormat.ENCODING_PCM_32BIT else null
            else -> throw UnsupportedFormatException("unsupported source encoding: $sourceEncoding")
        }

        val sourceMask = channelMaskFor(sourceChannels)
        val needDownmix = sourceMask == AudioFormat.CHANNEL_INVALID

        return if (keepEncoding != null && !needDownmix) {
            // Source is directly playable: no conversion.
            Result(
                playback = PlaybackFormat(keepEncoding, sourceMask, sourceChannels, sampleRate),
                converter = PcmConverter.IDENTITY,
                converted = false,
            )
        } else {
            // Fall back to 16-bit PCM; downmix to stereo only if the layout was
            // unrepresentable, otherwise keep the (valid) channel layout.
            val targetChannels = if (needDownmix) STEREO_CHANNELS else sourceChannels
            val targetMask = if (needDownmix) AudioFormat.CHANNEL_OUT_STEREO else sourceMask
            Result(
                playback = PlaybackFormat(
                    AudioFormat.ENCODING_PCM_16BIT, targetMask, targetChannels, sampleRate
                ),
                converter = PcmConverter.toS16(sourceEncoding, sourceChannels, targetChannels),
                converted = true,
            )
        }
    }

    /**
     * A guaranteed-playable configuration (16-bit / stereo / source rate) used by
     * [AudioPlayer] if the negotiated track is rejected by a particular device.
     */
    fun safeFallback(
        sourceEncoding: Client.AudioFormat.Encoding,
        sourceChannels: Int,
        sampleRate: Int,
    ): Result {
        if (sampleRate <= 0) {
            throw UnsupportedFormatException("invalid sample rate: $sampleRate")
        }
        if (sourceChannels <= 0) {
            throw UnsupportedFormatException("invalid channel count: $sourceChannels")
        }
        return Result(
            playback = PlaybackFormat(
                AudioFormat.ENCODING_PCM_16BIT,
                AudioFormat.CHANNEL_OUT_STEREO,
                STEREO_CHANNELS,
                sampleRate,
            ),
            converter = PcmConverter.toS16(sourceEncoding, sourceChannels, STEREO_CHANNELS),
            converted = true,
        )
    }

    /** The CHANNEL_OUT mask for [channels], or CHANNEL_INVALID if not representable. */
    fun channelMaskFor(channels: Int): Int = when (channels) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
        4 -> AudioFormat.CHANNEL_OUT_QUAD
        5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        7 -> AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_BACK_CENTER
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> AudioFormat.CHANNEL_INVALID
    }

    /**
     * The encodings/limits this device can render, sent to the server (see proto
     * message PlaybackCapabilities) so it can log an actionable diagnostic.
     */
    fun buildPlaybackCapabilities(sdkInt: Int): Client.PlaybackCapabilities {
        val builder = Client.PlaybackCapabilities.newBuilder()
            .addSupportedEncodings(Client.AudioFormat.Encoding.ENCODING_PCM_8BIT)
            .addSupportedEncodings(Client.AudioFormat.Encoding.ENCODING_PCM_16BIT)
            .addSupportedEncodings(Client.AudioFormat.Encoding.ENCODING_PCM_FLOAT)
        if (sdkInt >= Build.VERSION_CODES.S) {
            builder.addSupportedEncodings(Client.AudioFormat.Encoding.ENCODING_PCM_24BIT)
            builder.addSupportedEncodings(Client.AudioFormat.Encoding.ENCODING_PCM_32BIT)
        }
        return builder
            .setMaxChannels(MAX_CHANNELS)
            .setMaxSampleRate(MAX_SAMPLE_RATE)
            .build()
    }

    private const val STEREO_CHANNELS = 2
    private const val MAX_CHANNELS = 8
    private const val MAX_SAMPLE_RATE = 192000
}
