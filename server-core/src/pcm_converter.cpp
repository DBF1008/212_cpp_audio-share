/*
   Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

#include "pcm_converter.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <set>

int pcm_converter::bytes_per_sample(Encoding encoding)
{
    switch (encoding) {
    case AudioFormat::ENCODING_PCM_8BIT:
        return 1;
    case AudioFormat::ENCODING_PCM_16BIT:
        return 2;
    case AudioFormat::ENCODING_PCM_24BIT:
        return 3;
    case AudioFormat::ENCODING_PCM_32BIT:
    case AudioFormat::ENCODING_PCM_FLOAT:
        return 4;
    default:
        return 0;
    }
}

int pcm_converter::compute_block_align(Encoding encoding, int channels)
{
    return bytes_per_sample(encoding) * channels;
}

bool pcm_converter::can_convert(Encoding src_enc, int src_channels,
                                Encoding dst_enc, int dst_channels)
{
    if (bytes_per_sample(src_enc) == 0 || bytes_per_sample(dst_enc) == 0) {
        return false;
    }
    if (src_channels <= 0 || dst_channels <= 0) {
        return false;
    }
    return true;
}

bool pcm_converter::compute_compatible_format(
    const AudioFormat& source,
    const io::github::mkckr0::audio_share_app::pb::FormatConstraints& constraints,
    AudioFormat& target)
{
    target = source;

    // Check if source encoding is in client's preferred list.
    // If so, no encoding conversion needed.
    bool encoding_ok = false;
    for (auto enc : constraints.preferred_encodings()) {
        if (enc == source.encoding()) {
            encoding_ok = true;
            break;
        }
    }

    if (!encoding_ok) {
        // Source encoding not playable by client; pick first preferred.
        if (constraints.preferred_encodings_size() > 0) {
            target.set_encoding(constraints.preferred_encodings(0));
        } else {
            // Ultimate fallback: ENCODING_PCM_16BIT
            target.set_encoding(AudioFormat::ENCODING_PCM_16BIT);
        }
    }

    // Clamp channel count to client's maximum.
    if (constraints.has_max_channels() && constraints.max_channels() > 0
        && source.channels() > constraints.max_channels()) {
        target.set_channels(constraints.max_channels());
    }

    // Keep source sample rate (Android supports most standard rates).
    // If client specified preferred sample rates and source isn't one of them,
    // keep source anyway — server-side resampling is not implemented.

    if (!can_convert(source.encoding(), source.channels(),
                     target.encoding(), target.channels())) {
        return false;
    }

    return true;
}

// ── Encoding conversion ──────────────────────────────────────────────

size_t pcm_converter::convert_encoding(
    const uint8_t* input, size_t num_samples,
    Encoding src_enc, Encoding dst_enc,
    uint8_t* output)
{
    if (src_enc == dst_enc) {
        int bps = bytes_per_sample(src_enc);
        size_t total = num_samples * bps;
        std::memcpy(output, input, total);
        return total;
    }

    size_t out_offset = 0;

    // Helper: read one sample as double (normalized to [-1.0, 1.0] range for float,
    // or full-scale integer range).
    auto read_sample_f64 = [&](const uint8_t* p, Encoding enc) -> double {
        switch (enc) {
        case AudioFormat::ENCODING_PCM_8BIT:
            return static_cast<double>(static_cast<int8_t>(p[0]));
        case AudioFormat::ENCODING_PCM_16BIT: {
            int16_t v;
            std::memcpy(&v, p, 2);
            return static_cast<double>(v);
        }
        case AudioFormat::ENCODING_PCM_24BIT: {
            int32_t v = static_cast<int32_t>(p[0])
                      | (static_cast<int32_t>(p[1]) << 8)
                      | (static_cast<int32_t>(p[2]) << 16);
            // Sign-extend from 24-bit
            if (v & 0x800000) v |= static_cast<int32_t>(0xFF000000);
            return static_cast<double>(v);
        }
        case AudioFormat::ENCODING_PCM_32BIT: {
            int32_t v;
            std::memcpy(&v, p, 4);
            return static_cast<double>(v);
        }
        case AudioFormat::ENCODING_PCM_FLOAT: {
            float f;
            std::memcpy(&f, p, 4);
            return static_cast<double>(f);
        }
        default:
            return 0.0;
        }
    };

    // Helper: write one sample from double.
    auto write_sample_f64 = [&](uint8_t* p, Encoding enc, double val) {
        switch (enc) {
        case AudioFormat::ENCODING_PCM_8BIT: {
            double clamped = std::max(-128.0, std::min(127.0, val));
            p[0] = static_cast<uint8_t>(static_cast<int8_t>(std::lround(clamped)));
            break;
        }
        case AudioFormat::ENCODING_PCM_16BIT: {
            double clamped = std::max(-32768.0, std::min(32767.0, val));
            int16_t v = static_cast<int16_t>(std::lround(clamped));
            std::memcpy(p, &v, 2);
            break;
        }
        case AudioFormat::ENCODING_PCM_24BIT: {
            double clamped = std::max(-8388608.0, std::min(8388607.0, val));
            int32_t v = static_cast<int32_t>(std::lround(clamped));
            p[0] = static_cast<uint8_t>(v & 0xFF);
            p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
            p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
            break;
        }
        case AudioFormat::ENCODING_PCM_32BIT: {
            double clamped = std::max(-2147483648.0, std::min(2147483647.0, val));
            int32_t v = static_cast<int32_t>(std::lround(clamped));
            std::memcpy(p, &v, 4);
            break;
        }
        case AudioFormat::ENCODING_PCM_FLOAT: {
            float f;
            if (src_enc == AudioFormat::ENCODING_PCM_FLOAT || dst_enc == AudioFormat::ENCODING_PCM_FLOAT) {
                // For float↔float or when already in float domain
                f = static_cast<float>(val);
            } else {
                // Normalize integer to [-1.0, 1.0]
                f = static_cast<float>(val);
            }
            std::memcpy(p, &f, 4);
            break;
        }
        default:
            break;
        }
    };

    // For float targets from integer sources, we need to normalize.
    // For integer targets from float sources, we need to scale.
    bool src_is_float = (src_enc == AudioFormat::ENCODING_PCM_FLOAT);
    bool dst_is_float = (dst_enc == AudioFormat::ENCODING_PCM_FLOAT);

    // Scale factors for float ↔ integer normalization
    double src_scale = 1.0;
    if (!src_is_float) {
        int src_bps = bytes_per_sample(src_enc);
        src_scale = static_cast<double>((1 << ((src_bps * 8) - 1)) - 1);
    }

    double dst_scale = 1.0;
    if (!dst_is_float) {
        int dst_bps = bytes_per_sample(dst_enc);
        dst_scale = static_cast<double>((1 << ((dst_bps * 8) - 1)) - 1);
    }

    for (size_t i = 0; i < num_samples; ++i) {
        const uint8_t* src_ptr = input + i * bytes_per_sample(src_enc);
        uint8_t* dst_ptr = output + i * bytes_per_sample(dst_enc);

        double val = read_sample_f64(src_ptr, src_enc);

        // Normalize to [-1.0, 1.0] if source is integer
        if (!src_is_float) {
            val /= src_scale;
        }

        // Scale to destination range if destination is integer
        if (!dst_is_float) {
            val *= dst_scale;
        }

        write_sample_f64(dst_ptr, dst_enc, val);
        out_offset += bytes_per_sample(dst_enc);
    }

    return out_offset;
}

// ── Channel conversion ───────────────────────────────────────────────

size_t pcm_converter::convert_channels(
    const uint8_t* input, size_t num_frames,
    int src_channels, int dst_channels,
    int bps,
    uint8_t* output)
{
    if (src_channels == dst_channels) {
        size_t total = num_frames * src_channels * bps;
        std::memcpy(output, input, total);
        return total;
    }

    size_t out_offset = 0;

    for (size_t f = 0; f < num_frames; ++f) {
        const uint8_t* frame_in = input + f * src_channels * bps;
        uint8_t* frame_out = output + f * dst_channels * bps;

        if (dst_channels == 1 && src_channels > 1) {
            // Downmix to mono: average all source channels
            // Use double accumulation to avoid overflow
            for (int b = 0; b < bps; ++b) {
                // For each byte position, we need to handle the whole sample
                // Actually, we need to average complete samples, not bytes
                (void)b;
            }
            // Average samples as floating-point
            double sum = 0.0;
            for (int c = 0; c < src_channels; ++c) {
                const uint8_t* sample = frame_in + c * bps;
                double val = 0.0;
                switch (bps) {
                case 1:
                    val = static_cast<double>(static_cast<int8_t>(sample[0]));
                    break;
                case 2: {
                    int16_t v;
                    std::memcpy(&v, sample, 2);
                    val = static_cast<double>(v);
                    break;
                }
                case 3: {
                    int32_t v = static_cast<int32_t>(sample[0])
                              | (static_cast<int32_t>(sample[1]) << 8)
                              | (static_cast<int32_t>(sample[2]) << 16);
                    if (v & 0x800000) v |= static_cast<int32_t>(0xFF000000);
                    val = static_cast<double>(v);
                    break;
                }
                case 4: {
                    int32_t v;
                    std::memcpy(&v, sample, 4);
                    val = static_cast<double>(v);
                    break;
                }
                }
                sum += val;
            }
            double avg = sum / src_channels;

            switch (bps) {
            case 1: {
                int8_t v = static_cast<int8_t>(std::max(-128.0, std::min(127.0, std::lround(avg))));
                std::memcpy(frame_out, &v, 1);
                break;
            }
            case 2: {
                int16_t v = static_cast<int16_t>(std::max(-32768.0, std::min(32767.0, std::lround(avg))));
                std::memcpy(frame_out, &v, 2);
                break;
            }
            case 3: {
                int32_t v = static_cast<int32_t>(std::max(-8388608.0, std::min(8388607.0, std::lround(avg))));
                frame_out[0] = static_cast<uint8_t>(v & 0xFF);
                frame_out[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
                frame_out[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
                break;
            }
            case 4: {
                int32_t v = static_cast<int32_t>(std::max(-2147483648.0, std::min(2147483647.0, std::lround(avg))));
                std::memcpy(frame_out, &v, 4);
                break;
            }
            }
        } else if (src_channels == 1 && dst_channels > 1) {
            // Upmix from mono: duplicate to all target channels
            for (int c = 0; c < dst_channels; ++c) {
                std::memcpy(frame_out + c * bps, frame_in, bps);
            }
        } else if (dst_channels < src_channels) {
            // Downmix: take first dst_channels channels and discard the rest
            // For a proper downmix, we should mix in the extra channels,
            // but taking the front channels is a reasonable approximation.
            std::memcpy(frame_out, frame_in, dst_channels * bps);
        } else {
            // Upmix: copy source channels, zero-fill extra channels
            std::memcpy(frame_out, frame_in, src_channels * bps);
            std::memset(frame_out + src_channels * bps, 0,
                        (dst_channels - src_channels) * bps);
        }

        out_offset += dst_channels * bps;
    }

    return out_offset;
}

// ── Full conversion (encoding + channels) ────────────────────────────

size_t pcm_converter::convert(
    const uint8_t* input, size_t input_size,
    Encoding src_enc, int src_channels,
    Encoding dst_enc, int dst_channels,
    uint8_t* output, size_t output_capacity)
{
    if (input_size == 0) {
        return 0;
    }

    int src_bps = bytes_per_sample(src_enc);
    int dst_bps = bytes_per_sample(dst_enc);

    if (src_bps == 0 || dst_bps == 0 || src_channels <= 0 || dst_channels <= 0) {
        return 0;
    }

    size_t src_block_align = static_cast<size_t>(src_bps * src_channels);
    if (src_block_align == 0) {
        return 0;
    }
    size_t num_frames = input_size / src_block_align;
    size_t num_samples = num_frames * src_channels;

    size_t out_block_align = static_cast<size_t>(dst_bps * dst_channels);
    size_t expected_output = num_frames * out_block_align;

    if (output_capacity < expected_output) {
        return 0;
    }

    bool need_encoding = (src_enc != dst_enc);
    bool need_channels = (src_channels != dst_channels);

    if (!need_encoding && !need_channels) {
        // No conversion needed
        std::memcpy(output, input, input_size);
        return input_size;
    }

    if (need_encoding && need_channels) {
        // Two-step: encoding first, then channels
        std::vector<uint8_t> intermediate(num_samples * dst_bps);
        convert_encoding(input, num_samples, src_enc, dst_enc, intermediate.data());
        return convert_channels(intermediate.data(), num_frames,
                               src_channels, dst_channels, dst_bps,
                               output);
    } else if (need_encoding) {
        return convert_encoding(input, num_samples, src_enc, dst_enc, output);
    } else {
        return convert_channels(input, num_frames,
                               src_channels, dst_channels, src_bps,
                               output);
    }
}
