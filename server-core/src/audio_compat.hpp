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

#ifndef AUDIO_COMPAT_HPP
#define AUDIO_COMPAT_HPP

// Pure, dependency-free helpers for reasoning about whether the format the
// server captures can be played by a connected client, and for producing an
// actionable diagnostic for the operator.
//
// This header intentionally does NOT depend on protobuf, asio, or any platform
// API so that it can be unit-tested standalone (see server-core/test/
// audio_compat_test.cpp). network_manager maps the generated protobuf types
// onto these plain types before calling in.
//
// NOTE: the authoritative format fallback + PCM conversion happens on the
// Android client (the capture stream is shared by all peers and cannot be
// transcoded per-client). The server only uses these helpers for logging.

#include <algorithm>
#include <initializer_list>
#include <string>
#include <vector>

namespace audio_compat {

// Mirrors io.github.mkckr0.audio_share_app.pb.AudioFormat.Encoding. The integer
// values are kept identical to the proto enum so callers can static_cast.
enum class encoding : int {
    invalid = 0,
    pcm_float = 1,
    pcm_8bit = 2,
    pcm_16bit = 3,
    pcm_24bit = 4,
    pcm_32bit = 5,
};

// What the client reports it can render (see proto message PlaybackCapabilities).
struct capabilities {
    std::vector<encoding> supported_encodings;
    int max_channels = 0;    // 0 == unknown / unspecified
    int max_sample_rate = 0; // 0 == unknown / unspecified
};

inline const char* encoding_name(encoding e)
{
    switch (e) {
    case encoding::pcm_float:
        return "PCM_FLOAT(32-bit)";
    case encoding::pcm_8bit:
        return "PCM_8BIT";
    case encoding::pcm_16bit:
        return "PCM_16BIT";
    case encoding::pcm_24bit:
        return "PCM_24BIT";
    case encoding::pcm_32bit:
        return "PCM_32BIT";
    case encoding::invalid:
        return "INVALID";
    }
    return "UNKNOWN";
}

// The `--encoding` CLI token (see main.cpp / audio_manager::encoding_t) that
// makes the server capture in the given encoding, used to tell the operator
// exactly what to change.
inline const char* capture_arg_for(encoding e)
{
    switch (e) {
    case encoding::pcm_float:
        return "f32";
    case encoding::pcm_8bit:
        return "s8";
    case encoding::pcm_16bit:
        return "s16";
    case encoding::pcm_24bit:
        return "s24";
    case encoding::pcm_32bit:
        return "s32";
    default:
        return "s16";
    }
}

// When the client did not advertise any encodings we have no information, so we
// must not claim incompatibility.
inline bool is_encoding_supported(encoding e, const capabilities& caps)
{
    if (caps.supported_encodings.empty()) {
        return true;
    }
    return std::find(caps.supported_encodings.begin(), caps.supported_encodings.end(), e)
        != caps.supported_encodings.end();
}

// Pick the natively-playable encoding "closest" to the source, preferring the
// least quality loss. 16-bit is the universal fallback (every Android
// AudioTrack supports ENCODING_PCM_16BIT).
inline encoding recommend_encoding(encoding source, const capabilities& caps)
{
    if (is_encoding_supported(source, caps)) {
        return source;
    }

    auto try_order = [&](std::initializer_list<encoding> order) -> encoding {
        for (encoding e : order) {
            if (is_encoding_supported(e, caps)) {
                return e;
            }
        }
        return encoding::pcm_16bit;
    };

    switch (source) {
    case encoding::pcm_32bit:
        return try_order({ encoding::pcm_32bit, encoding::pcm_float, encoding::pcm_24bit, encoding::pcm_16bit });
    case encoding::pcm_24bit:
        return try_order({ encoding::pcm_24bit, encoding::pcm_float, encoding::pcm_16bit });
    case encoding::pcm_float:
        return try_order({ encoding::pcm_float, encoding::pcm_24bit, encoding::pcm_16bit });
    case encoding::pcm_8bit:
        return try_order({ encoding::pcm_8bit, encoding::pcm_16bit });
    case encoding::pcm_16bit:
        return try_order({ encoding::pcm_16bit, encoding::pcm_float });
    default:
        return encoding::pcm_16bit;
    }
}

// Returns true when the client can render the capture format with no
// client-side conversion.
inline bool is_fully_compatible(encoding source, int channels, int sample_rate, const capabilities& caps)
{
    const bool enc_ok = is_encoding_supported(source, caps);
    const bool ch_ok = caps.max_channels <= 0 || channels <= caps.max_channels;
    const bool rate_ok = caps.max_sample_rate <= 0 || sample_rate <= caps.max_sample_rate;
    return enc_ok && ch_ok && rate_ok;
}

// Human-readable, actionable diagnostic for the server operator.
inline std::string make_diagnostic(encoding source, int channels, int sample_rate, const capabilities& caps)
{
    if (is_fully_compatible(source, channels, sample_rate, caps)) {
        return "client can play the capture format natively ("
            + std::string(encoding_name(source)) + ", "
            + std::to_string(channels) + "ch, "
            + std::to_string(sample_rate) + "Hz)";
    }

    std::string msg = "client will convert the capture format locally:";

    if (!is_encoding_supported(source, caps)) {
        encoding rec = recommend_encoding(source, caps);
        msg += " encoding " + std::string(encoding_name(source))
            + " is not natively playable -> client down-converts to "
            + encoding_name(rec)
            + "; to capture natively restart with --encoding="
            + capture_arg_for(rec) + ".";
    }
    if (caps.max_channels > 0 && channels > caps.max_channels) {
        msg += " channels " + std::to_string(channels) + " > client max "
            + std::to_string(caps.max_channels)
            + " -> client downmixes; consider --channels="
            + std::to_string(caps.max_channels) + ".";
    }
    if (caps.max_sample_rate > 0 && sample_rate > caps.max_sample_rate) {
        msg += " sample rate " + std::to_string(sample_rate) + "Hz > client max "
            + std::to_string(caps.max_sample_rate) + "Hz.";
    }
    return msg;
}

} // namespace audio_compat

#endif // AUDIO_COMPAT_HPP
