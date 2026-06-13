package io.github.mkckr0.audio_share_app

import io.github.mkckr0.audio_share_app.pb.Client.AudioFormat
import io.github.mkckr0.audio_share_app.pb.Client.FormatConstraints
import io.github.mkckr0.audio_share_app.service.NetClient
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for format compatibility negotiation logic.
 * Tests the client-side pickCompatibleFormat utility that mirrors
 * server-side compute_compatible_format.
 */
class FormatCompatTest {

    // ── Helper builders ──────────────────────────────────────────────

    private fun audioFormat(
        encoding: AudioFormat.Encoding,
        channels: Int,
        sampleRate: Int
    ): AudioFormat = AudioFormat.newBuilder()
        .setEncoding(encoding)
        .setChannels(channels)
        .setSampleRate(sampleRate)
        .build()

    private fun constraints(
        preferredEncodings: List<AudioFormat.Encoding>,
        maxChannels: Int = 0,
        apiLevel: Int = 0
    ): FormatConstraints {
        val builder = FormatConstraints.newBuilder()
            .addAllPreferredEncodings(preferredEncodings)
        if (maxChannels > 0) builder.maxChannels = maxChannels
        if (apiLevel > 0) builder.apiLevel = apiLevel
        return builder.build()
    }

    // ── No conversion needed ─────────────────────────────────────────

    @Test
    fun pickCompatibleFormat_sameEncoding_noChange() {
        val server = audioFormat(AudioFormat.Encoding.ENCODING_PCM_16BIT, 2, 48000)
        val caps = constraints(
            listOf(
                AudioFormat.Encoding.ENCODING_PCM_FLOAT,
                AudioFormat.Encoding.ENCODING_PCM_16BIT,
                AudioFormat.Encoding.ENCODING_PCM_8BIT
            ),
            maxChannels = 2
        )
        val result = NetClient.pickCompatibleFormat(server, caps)
        assertEquals(AudioFormat.Encoding.ENCODING_PCM_16BIT, result.encoding)
        assertEquals(2, result.channels)
        assertEquals(48000, result.sampleRate)
    }

    // ── Encoding fallback ────────────────────────────────────────────

    @Test
    fun pickCompatibleFormat_24bitToFloat() {
        val server = audioFormat(AudioFormat.Encoding.ENCODING_PCM_24BIT, 2, 48000)
        val caps = constraints(
            listOf(
                AudioFormat.Encoding.ENCODING_PCM_FLOAT,
                AudioFormat.Encoding.ENCODING_PCM_16BIT
            ),
            maxChannels = 2
        )
        val result = NetClient.pickCompatibleFormat(server, caps)
        // 24-bit not in preferred list → fallback to first preferred (FLOAT)
        assertEquals(AudioFormat.Encoding.ENCODING_PCM_FLOAT, result.encoding)
    }

    @Test
    fun pickCompatibleFormat_32bitTo16bit() {
        val server = audioFormat(AudioFormat.Encoding.ENCODING_PCM_32BIT, 2, 48000)
        val caps = constraints(
            listOf(AudioFormat.Encoding.ENCODING_PCM_16BIT),
            maxChannels = 2
        )
        val result = NetClient.pickCompatibleFormat(server, caps)
        assertEquals(AudioFormat.Encoding.ENCODING_PCM_16BIT, result.encoding)
    }

    @Test
    fun pickCompatibleFormat_floatTo16bit() {
        val server = audioFormat(AudioFormat.Encoding.ENCODING_PCM_FLOAT, 2, 44100)
        val caps = constraints(
            listOf(AudioFormat.Encoding.ENCODING_PCM_16BIT),
            maxChannels = 2
        )
        val result = NetClient.pickCompatibleFormat(server, caps)
        assertEquals(AudioFormat.Encoding.ENCODING_PCM_16BIT, result.encoding)
    }

    // ── Channel clamping ─────────────────────────────────────────────

    @Test
    fun pickCompatibleFormat_clampChannels() {
        val server = audioFormat(AudioFormat.Encoding.ENCODING_PCM_16BIT, 6, 48000)
        val caps = constraints(
            listOf(AudioFormat.Encoding.ENCODING_PCM_16BIT),
            maxChannels = 2
        )
        val result = NetClient.pickCompatibleFormat(server, caps)
        assertEquals(AudioFormat.Encoding.ENCODING_PCM_16BIT, result.encoding)
        assertEquals(2, result.channels)
    }

    @Test
    fun pickCompatibleFormat_noClampWhenUnderLimit() {
        val server = audioFormat(AudioFormat.Encoding.ENCODING_PCM_16BIT, 2, 48000)
        val caps = constraints(
            listOf(AudioFormat.Encoding.ENCODING_PCM_16BIT),
            maxChannels = 8
        )
        val result = NetClient.pickCompatibleFormat(server, caps)
        assertEquals(2, result.channels)
    }

    // ── Combined: encoding + channel fallback ────────────────────────

    @Test
    fun pickCompatibleFormat_bothEncodingAndChannels() {
        val server = audioFormat(AudioFormat.Encoding.ENCODING_PCM_24BIT, 8, 192000)
        val caps = constraints(
            listOf(AudioFormat.Encoding.ENCODING_PCM_16BIT),
            maxChannels = 2
        )
        val result = NetClient.pickCompatibleFormat(server, caps)
        assertEquals(AudioFormat.Encoding.ENCODING_PCM_16BIT, result.encoding)
        assertEquals(2, result.channels)
        assertEquals(192000, result.sampleRate) // sample rate preserved
    }

    // ── Empty constraints fallback ───────────────────────────────────

    @Test
    fun pickCompatibleFormat_emptyConstraints_fallbackTo16bit() {
        val server = audioFormat(AudioFormat.Encoding.ENCODING_PCM_24BIT, 6, 48000)
        val caps = FormatConstraints.getDefaultInstance() // empty
        val result = NetClient.pickCompatibleFormat(server, caps)
        // Empty preferred list → fallback to ENCODING_PCM_16BIT
        assertEquals(AudioFormat.Encoding.ENCODING_PCM_16BIT, result.encoding)
    }

    // ── getDeviceConstraints sanity check ────────────────────────────

    @Test
    fun getDeviceConstraints_alwaysIncludes16bitAnd8bit() {
        val caps = NetClient.getDeviceConstraints()
        assertTrue(caps.preferredEncodingsList.contains(AudioFormat.Encoding.ENCODING_PCM_16BIT))
        assertTrue(caps.preferredEncodingsList.contains(AudioFormat.Encoding.ENCODING_PCM_8BIT))
    }

    @Test
    fun getDeviceConstraints_hasMaxChannels() {
        val caps = NetClient.getDeviceConstraints()
        assertTrue(caps.hasMaxChannels())
        assertEquals(2, caps.maxChannels)
    }

    @Test
    fun getDeviceConstraints_firstPreferredIsBest() {
        val caps = NetClient.getDeviceConstraints()
        // First preferred encoding should be the highest quality
        assertTrue(caps.preferredEncodingsCount > 0)
        val first = caps.getPreferredEncodings(0)
        assertTrue(
            first == AudioFormat.Encoding.ENCODING_PCM_FLOAT ||
            first == AudioFormat.Encoding.ENCODING_PCM_32BIT
        )
    }
}
