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

#ifndef PCM_CONVERTER_HPP
#define PCM_CONVERTER_HPP

#include <cstdint>
#include <cstddef>
#include <vector>

#include "client.pb.h"

using AudioFormat = io::github::mkckr0::audio_share_app::pb::AudioFormat;
using Encoding = AudioFormat::Encoding;

// Stateless PCM format converter.
// Supports encoding conversion (float/8/16/24/32-bit) and channel count
// conversion (mono/stereo/surround) for little-endian PCM data.
class pcm_converter {
public:
    // Returns the number of bytes per sample for a given encoding.
    // Returns 0 for ENCODING_INVALID.
    static int bytes_per_sample(Encoding encoding);

    // Returns the block alignment (bytes per frame) for a format.
    static int compute_block_align(Encoding encoding, int channels);

    // Convert PCM data from one format to another.
    // Returns the number of bytes written to output.
    // output must be pre-allocated with sufficient size:
    //   num_frames * bytes_per_sample(target_enc) * target_channels
    static size_t convert(
        const uint8_t* input, size_t input_size,
        Encoding src_enc, int src_channels,
        Encoding dst_enc, int dst_channels,
        uint8_t* output, size_t output_capacity);

    // Check if conversion between two formats is supported.
    static bool can_convert(Encoding src_enc, int src_channels,
                            Encoding dst_enc, int dst_channels);

    // Compute a compatible target format given the source format and
    // the client's preferred constraints.
    // Returns false if no compatible format can be found.
    static bool compute_compatible_format(
        const AudioFormat& source,
        const io::github::mkckr0::audio_share_app::pb::FormatConstraints& constraints,
        AudioFormat& target);

private:
    // Convert encoding only (channels stay the same).
    // Returns number of bytes written to output.
    static size_t convert_encoding(
        const uint8_t* input, size_t num_samples,
        Encoding src_enc, Encoding dst_enc,
        uint8_t* output);

    // Convert channel count only (encoding stays the same).
    // Returns number of bytes written to output.
    static size_t convert_channels(
        const uint8_t* input, size_t num_frames,
        int src_channels, int dst_channels,
        int bps,
        uint8_t* output);
};

#endif // PCM_CONVERTER_HPP
