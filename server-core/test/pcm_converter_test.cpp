/*
   Regression tests for pcm_converter.
   Build: cmake --build . --target pcm_converter_test
   Run:   ctest --output-on-failure
*/

#include <gtest/gtest.h>
#include <cstring>
#include <cmath>
#include <vector>
#include <cstdint>

#include "pcm_converter.hpp"

using namespace io::github::mkckr0::audio_share_app::pb;

// ── Helper: generate PCM test data ───────────────────────────────────

static std::vector<uint8_t> make_s16_stereo(const std::vector<int16_t>& samples) {
    std::vector<uint8_t> buf(samples.size() * 2);
    std::memcpy(buf.data(), samples.data(), samples.size() * 2);
    return buf;
}

static std::vector<int16_t> read_s16(const uint8_t* data, size_t num_samples) {
    std::vector<int16_t> out(num_samples);
    std::memcpy(out.data(), data, num_samples * 2);
    return out;
}

// ── bytes_per_sample ─────────────────────────────────────────────────

TEST(PcmConverter, BytesPerSample) {
    EXPECT_EQ(pcm_converter::bytes_per_sample(AudioFormat::ENCODING_INVALID), 0);
    EXPECT_EQ(pcm_converter::bytes_per_sample(AudioFormat::ENCODING_PCM_8BIT), 1);
    EXPECT_EQ(pcm_converter::bytes_per_sample(AudioFormat::ENCODING_PCM_16BIT), 2);
    EXPECT_EQ(pcm_converter::bytes_per_sample(AudioFormat::ENCODING_PCM_24BIT), 3);
    EXPECT_EQ(pcm_converter::bytes_per_sample(AudioFormat::ENCODING_PCM_32BIT), 4);
    EXPECT_EQ(pcm_converter::bytes_per_sample(AudioFormat::ENCODING_PCM_FLOAT), 4);
}

// ── compute_block_align ──────────────────────────────────────────────

TEST(PcmConverter, BlockAlign) {
    EXPECT_EQ(pcm_converter::compute_block_align(AudioFormat::ENCODING_PCM_16BIT, 1), 2);
    EXPECT_EQ(pcm_converter::compute_block_align(AudioFormat::ENCODING_PCM_16BIT, 2), 4);
    EXPECT_EQ(pcm_converter::compute_block_align(AudioFormat::ENCODING_PCM_24BIT, 2), 6);
    EXPECT_EQ(pcm_converter::compute_block_align(AudioFormat::ENCODING_PCM_FLOAT, 6), 24);
}

// ── can_convert ──────────────────────────────────────────────────────

TEST(PcmConverter, CanConvert) {
    EXPECT_TRUE(pcm_converter::can_convert(
        AudioFormat::ENCODING_PCM_24BIT, 6,
        AudioFormat::ENCODING_PCM_16BIT, 2));
    EXPECT_TRUE(pcm_converter::can_convert(
        AudioFormat::ENCODING_PCM_FLOAT, 2,
        AudioFormat::ENCODING_PCM_16BIT, 2));
    EXPECT_FALSE(pcm_converter::can_convert(
        AudioFormat::ENCODING_INVALID, 2,
        AudioFormat::ENCODING_PCM_16BIT, 2));
    EXPECT_FALSE(pcm_converter::can_convert(
        AudioFormat::ENCODING_PCM_16BIT, 0,
        AudioFormat::ENCODING_PCM_16BIT, 2));
}

// ── Encoding: S16 → S16 (identity) ──────────────────────────────────

TEST(PcmConverter, Identity_S16_Stereo) {
    int16_t samples[] = { 1000, -1000, 32000, -32000, 0, 100 };
    auto input = make_s16_stereo({1000, -1000, 32000, -32000, 0, 100});
    std::vector<uint8_t> output(input.size());

    size_t written = pcm_converter::convert(
        input.data(), input.size(),
        AudioFormat::ENCODING_PCM_16BIT, 2,
        AudioFormat::ENCODING_PCM_16BIT, 2,
        output.data(), output.size());

    EXPECT_EQ(written, input.size());
    EXPECT_EQ(std::memcmp(input.data(), output.data(), input.size()), 0);
}

// ── Encoding: S16 → S8 ──────────────────────────────────────────────

TEST(PcmConverter, S16_to_S8) {
    // 3 frames of mono S16
    int16_t s16_samples[] = { 0, 16384, -16384, 32767, -32768, 8192 };
    size_t input_size = sizeof(s16_samples);
    std::vector<uint8_t> input(input_size);
    std::memcpy(input.data(), s16_samples, input_size);

    std::vector<uint8_t> output(6); // 6 samples × 1 byte

    size_t written = pcm_converter::convert(
        input.data(), input_size,
        AudioFormat::ENCODING_PCM_16BIT, 1,
        AudioFormat::ENCODING_PCM_8BIT, 1,
        output.data(), output.size());

    EXPECT_EQ(written, 6u);

    // 0 → 0, 16384 → ~64, -16384 → ~-64, 32767 → 127, -32768 → -128, 8192 → ~32
    auto s8 = reinterpret_cast<const int8_t*>(output.data());
    EXPECT_EQ(s8[0], 0);
    EXPECT_NEAR(s8[1], 64, 1);
    EXPECT_NEAR(s8[2], -64, 1);
    EXPECT_EQ(s8[3], 127);
    EXPECT_EQ(s8[4], -128);
    EXPECT_NEAR(s8[5], 32, 1);
}

// ── Encoding: S16 → FLOAT ───────────────────────────────────────────

TEST(PcmConverter, S16_to_FLOAT) {
    int16_t s16_samples[] = { 0, 32767, -32768, 16384 };
    size_t input_size = sizeof(s16_samples);
    std::vector<uint8_t> input(input_size);
    std::memcpy(input.data(), s16_samples, input_size);

    std::vector<uint8_t> output(4 * 4); // 4 samples × 4 bytes

    size_t written = pcm_converter::convert(
        input.data(), input_size,
        AudioFormat::ENCODING_PCM_16BIT, 1,
        AudioFormat::ENCODING_PCM_FLOAT, 1,
        output.data(), output.size());

    EXPECT_EQ(written, 16u);

    auto floats = reinterpret_cast<const float*>(output.data());
    EXPECT_NEAR(floats[0], 0.0f, 0.001f);
    EXPECT_NEAR(floats[1], 1.0f, 0.001f);
    EXPECT_NEAR(floats[2], -1.0f, 0.001f);
    EXPECT_NEAR(floats[3], 0.5f, 0.001f);
}

// ── Encoding: FLOAT → S16 ───────────────────────────────────────────

TEST(PcmConverter, FLOAT_to_S16) {
    float float_samples[] = { 0.0f, 1.0f, -1.0f, 0.5f, -0.5f };
    size_t input_size = sizeof(float_samples);
    std::vector<uint8_t> input(input_size);
    std::memcpy(input.data(), float_samples, input_size);

    std::vector<uint8_t> output(5 * 2); // 5 samples × 2 bytes

    size_t written = pcm_converter::convert(
        input.data(), input_size,
        AudioFormat::ENCODING_PCM_FLOAT, 1,
        AudioFormat::ENCODING_PCM_16BIT, 1,
        output.data(), output.size());

    EXPECT_EQ(written, 10u);

    auto s16 = reinterpret_cast<const int16_t*>(output.data());
    EXPECT_EQ(s16[0], 0);
    EXPECT_EQ(s16[1], 32767);
    EXPECT_EQ(s16[2], -32767);   // -1.0 * 32767 = -32767
    EXPECT_NEAR(s16[3], 16384, 1);
    EXPECT_NEAR(s16[4], -16384, 1);
}

// ── Encoding: S24 → S16 ─────────────────────────────────────────────

TEST(PcmConverter, S24_to_S16) {
    // 24-bit samples: 3 bytes each, little-endian
    // 0x000000 = 0, 0x7FFFFF = 8388607 (max), 0x800000 = -8388608 (min)
    uint8_t s24_data[] = {
        0x00, 0x00, 0x00,  // 0
        0x00, 0x00, 0x40,  // 0x400000 = 4194304 → ~0.5 full scale
        0xFF, 0xFF, 0x7F,  // 0x7FFFFF = 8388607 → ~1.0 full scale
        0x00, 0x00, 0x80,  // 0x800000 = -8388608 → ~-1.0 full scale
    };
    size_t input_size = sizeof(s24_data);

    std::vector<uint8_t> output(4 * 2); // 4 samples × 2 bytes

    size_t written = pcm_converter::convert(
        s24_data, input_size,
        AudioFormat::ENCODING_PCM_24BIT, 1,
        AudioFormat::ENCODING_PCM_16BIT, 1,
        output.data(), output.size());

    EXPECT_EQ(written, 8u);

    auto s16 = reinterpret_cast<const int16_t*>(output.data());
    EXPECT_EQ(s16[0], 0);
    EXPECT_NEAR(s16[1], 16384, 1);   // 0.5 * 32767 ≈ 16384
    EXPECT_EQ(s16[2], 32767);         // max
    EXPECT_EQ(s16[3], -32768);        // min
}

// ── Encoding: S32 → S16 ─────────────────────────────────────────────

TEST(PcmConverter, S32_to_S16) {
    int32_t s32_samples[] = { 0, 2147483647, -2147483647 - 1, 1073741824 };
    size_t input_size = sizeof(s32_samples);
    std::vector<uint8_t> input(input_size);
    std::memcpy(input.data(), s32_samples, input_size);

    std::vector<uint8_t> output(4 * 2); // 4 samples × 2 bytes

    size_t written = pcm_converter::convert(
        input.data(), input_size,
        AudioFormat::ENCODING_PCM_32BIT, 1,
        AudioFormat::ENCODING_PCM_16BIT, 1,
        output.data(), output.size());

    EXPECT_EQ(written, 8u);

    auto s16 = reinterpret_cast<const int16_t*>(output.data());
    EXPECT_EQ(s16[0], 0);
    EXPECT_EQ(s16[1], 32767);
    EXPECT_EQ(s16[2], -32768);
    EXPECT_NEAR(s16[3], 16384, 1);
}

// ── Channels: stereo → mono ─────────────────────────────────────────

TEST(PcmConverter, Stereo_to_Mono_S16) {
    // 3 stereo frames: (1000, 2000), (4000, 6000), (-1000, 1000)
    int16_t samples[] = { 1000, 2000, 4000, 6000, -1000, 1000 };
    auto input = make_s16_stereo({1000, 2000, 4000, 6000, -1000, 1000});

    std::vector<uint8_t> output(3 * 2); // 3 mono frames

    size_t written = pcm_converter::convert(
        input.data(), input.size(),
        AudioFormat::ENCODING_PCM_16BIT, 2,
        AudioFormat::ENCODING_PCM_16BIT, 1,
        output.data(), output.size());

    EXPECT_EQ(written, 6u);

    auto mono = read_s16(output.data(), 3);
    EXPECT_EQ(mono[0], 1500);   // avg(1000, 2000)
    EXPECT_EQ(mono[1], 5000);   // avg(4000, 6000)
    EXPECT_EQ(mono[2], 0);      // avg(-1000, 1000)
}

// ── Channels: mono → stereo ─────────────────────────────────────────

TEST(PcmConverter, Mono_to_Stereo_S16) {
    int16_t samples[] = { 1000, -2000, 3000 };
    std::vector<uint8_t> input(sizeof(samples));
    std::memcpy(input.data(), samples, sizeof(samples));

    std::vector<uint8_t> output(3 * 2 * 2); // 3 frames × 2 channels × 2 bytes

    size_t written = pcm_converter::convert(
        input.data(), input.size(),
        AudioFormat::ENCODING_PCM_16BIT, 1,
        AudioFormat::ENCODING_PCM_16BIT, 2,
        output.data(), output.size());

    EXPECT_EQ(written, 12u);

    auto stereo = read_s16(output.data(), 6);
    EXPECT_EQ(stereo[0], 1000);   // L
    EXPECT_EQ(stereo[1], 1000);   // R
    EXPECT_EQ(stereo[2], -2000);
    EXPECT_EQ(stereo[3], -2000);
    EXPECT_EQ(stereo[4], 3000);
    EXPECT_EQ(stereo[5], 3000);
}

// ── Channels: 6ch → 2ch ─────────────────────────────────────────────

TEST(PcmConverter, SixCh_to_Stereo_S16) {
    // 2 frames of 6-channel S16 (FL, FR, FC, LFE, RL, RR)
    int16_t samples[] = {
        1000, 2000, 3000, 4000, 5000, 6000,  // frame 1
        7000, 8000, 9000, 10000, 11000, 12000, // frame 2
    };
    std::vector<uint8_t> input(sizeof(samples));
    std::memcpy(input.data(), samples, sizeof(samples));

    std::vector<uint8_t> output(2 * 2 * 2); // 2 frames × 2 channels × 2 bytes

    size_t written = pcm_converter::convert(
        input.data(), input.size(),
        AudioFormat::ENCODING_PCM_16BIT, 6,
        AudioFormat::ENCODING_PCM_16BIT, 2,
        output.data(), output.size());

    EXPECT_EQ(written, 8u);

    auto stereo = read_s16(output.data(), 4);
    // Downmix takes first 2 channels (FL, FR)
    EXPECT_EQ(stereo[0], 1000);  // FL
    EXPECT_EQ(stereo[1], 2000);  // FR
    EXPECT_EQ(stereo[2], 7000);  // FL frame 2
    EXPECT_EQ(stereo[3], 8000);  // FR frame 2
}

// ── Combined: S24 6ch → S16 stereo ─────────────────────────────────

TEST(PcmConverter, S24_6ch_to_S16_Stereo) {
    // 1 frame of 6-channel S24 (6 × 3 = 18 bytes)
    // Each sample is a 24-bit LE value.
    // Sample values: 0, 0x400000 (~0.5), 0x7FFFFF (max), 0x800000 (min), 0, 0
    uint8_t input[] = {
        0x00, 0x00, 0x00,  // FL = 0
        0x00, 0x00, 0x40,  // FR = 0x400000 ≈ 0.5 full scale
        0xFF, 0xFF, 0x7F,  // FC = max
        0x00, 0x00, 0x80,  // LFE = min
        0x00, 0x00, 0x00,  // RL = 0
        0x00, 0x00, 0x00,  // RR = 0
    };

    std::vector<uint8_t> output(1 * 2 * 2); // 1 frame × 2 channels × 2 bytes

    size_t written = pcm_converter::convert(
        input, sizeof(input),
        AudioFormat::ENCODING_PCM_24BIT, 6,
        AudioFormat::ENCODING_PCM_16BIT, 2,
        output.data(), output.size());

    EXPECT_EQ(written, 4u);

    auto s16 = reinterpret_cast<const int16_t*>(output.data());
    EXPECT_EQ(s16[0], 0);       // FL = 0
    EXPECT_NEAR(s16[1], 16384, 1); // FR ≈ 0.5 * 32767
}

// ── Edge case: empty input ──────────────────────────────────────────

TEST(PcmConverter, EmptyInput) {
    std::vector<uint8_t> output(100);
    size_t written = pcm_converter::convert(
        nullptr, 0,
        AudioFormat::ENCODING_PCM_16BIT, 2,
        AudioFormat::ENCODING_PCM_16BIT, 2,
        output.data(), output.size());

    EXPECT_EQ(written, 0u);
}

// ── Edge case: output buffer too small ───────────────────────────────

TEST(PcmConverter, OutputBufferTooSmall) {
    int16_t samples[] = { 1000, 2000 };
    std::vector<uint8_t> input(sizeof(samples));
    std::memcpy(input.data(), samples, sizeof(samples));

    std::vector<uint8_t> output(1); // too small

    size_t written = pcm_converter::convert(
        input.data(), input.size(),
        AudioFormat::ENCODING_PCM_16BIT, 2,
        AudioFormat::ENCODING_PCM_16BIT, 2,
        output.data(), output.size());

    EXPECT_EQ(written, 0u);
}

// ── Edge case: partial frame (input not aligned) ────────────────────

TEST(PcmConverter, PartialFrame) {
    // 3 bytes for S16 stereo (block_align = 4), only 0 complete frames
    uint8_t input[] = { 0x01, 0x02, 0x03 };
    std::vector<uint8_t> output(100);

    size_t written = pcm_converter::convert(
        input, sizeof(input),
        AudioFormat::ENCODING_PCM_16BIT, 2,
        AudioFormat::ENCODING_PCM_16BIT, 2,
        output.data(), output.size());

    // 0 complete frames → 0 bytes
    EXPECT_EQ(written, 0u);
}

// ── compute_compatible_format ────────────────────────────────────────

TEST(PcmConverter, CompatibleFormat_NoChange) {
    AudioFormat source;
    source.set_encoding(AudioFormat::ENCODING_PCM_16BIT);
    source.set_channels(2);
    source.set_sample_rate(48000);

    FormatConstraints constraints;
    constraints.add_preferred_encodings(AudioFormat::ENCODING_PCM_16BIT);
    constraints.set_max_channels(2);

    AudioFormat target;
    bool ok = pcm_converter::compute_compatible_format(source, constraints, target);

    EXPECT_TRUE(ok);
    EXPECT_EQ(target.encoding(), AudioFormat::ENCODING_PCM_16BIT);
    EXPECT_EQ(target.channels(), 2);
    EXPECT_EQ(target.sample_rate(), 48000);
}

TEST(PcmConverter, CompatibleFormat_EncodingFallback) {
    AudioFormat source;
    source.set_encoding(AudioFormat::ENCODING_PCM_24BIT);
    source.set_channels(2);
    source.set_sample_rate(48000);

    FormatConstraints constraints;
    constraints.add_preferred_encodings(AudioFormat::ENCODING_PCM_FLOAT);
    constraints.add_preferred_encodings(AudioFormat::ENCODING_PCM_16BIT);
    constraints.set_max_channels(2);

    AudioFormat target;
    bool ok = pcm_converter::compute_compatible_format(source, constraints, target);

    EXPECT_TRUE(ok);
    EXPECT_EQ(target.encoding(), AudioFormat::ENCODING_PCM_FLOAT);
    EXPECT_EQ(target.channels(), 2);
}

TEST(PcmConverter, CompatibleFormat_ChannelClamp) {
    AudioFormat source;
    source.set_encoding(AudioFormat::ENCODING_PCM_16BIT);
    source.set_channels(6);
    source.set_sample_rate(48000);

    FormatConstraints constraints;
    constraints.add_preferred_encodings(AudioFormat::ENCODING_PCM_16BIT);
    constraints.set_max_channels(2);

    AudioFormat target;
    bool ok = pcm_converter::compute_compatible_format(source, constraints, target);

    EXPECT_TRUE(ok);
    EXPECT_EQ(target.channels(), 2);
}

TEST(PcmConverter, CompatibleFormat_BothConversion) {
    AudioFormat source;
    source.set_encoding(AudioFormat::ENCODING_PCM_32BIT);
    source.set_channels(8);
    source.set_sample_rate(192000);

    FormatConstraints constraints;
    constraints.add_preferred_encodings(AudioFormat::ENCODING_PCM_16BIT);
    constraints.set_max_channels(2);

    AudioFormat target;
    bool ok = pcm_converter::compute_compatible_format(source, constraints, target);

    EXPECT_TRUE(ok);
    EXPECT_EQ(target.encoding(), AudioFormat::ENCODING_PCM_16BIT);
    EXPECT_EQ(target.channels(), 2);
    EXPECT_EQ(target.sample_rate(), 192000);  // sample rate preserved
}

TEST(PcmConverter, CompatibleFormat_NoConstraintsDefaults) {
    AudioFormat source;
    source.set_encoding(AudioFormat::ENCODING_PCM_24BIT);
    source.set_channels(2);
    source.set_sample_rate(44100);

    FormatConstraints constraints;  // empty constraints

    AudioFormat target;
    bool ok = pcm_converter::compute_compatible_format(source, constraints, target);

    EXPECT_TRUE(ok);
    // No preferred encodings → fallback to ENCODING_PCM_16BIT
    EXPECT_EQ(target.encoding(), AudioFormat::ENCODING_PCM_16BIT);
    // No max_channels → keep source channels
    EXPECT_EQ(target.channels(), 2);
}
