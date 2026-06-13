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

// Standalone, dependency-free regression test for src/audio_compat.hpp.
//
// Build & run (no protobuf/asio needed):
//   c++ -std=c++20 -I../src audio_compat_test.cpp -o /tmp/audio_compat_test && /tmp/audio_compat_test
// or via CMake/CTest (see server-core/CMakeLists.txt: target audio_compat_test).

#include "audio_compat.hpp"

#include <cstdio>
#include <string>

using audio_compat::capabilities;
using audio_compat::encoding;

static int g_failures = 0;
static int g_checks = 0;

#define CHECK(cond)                                                       \
    do {                                                                  \
        ++g_checks;                                                       \
        if (!(cond)) {                                                    \
            ++g_failures;                                                 \
            std::printf("FAIL %s:%d  CHECK(%s)\n", __FILE__, __LINE__, #cond); \
        }                                                                 \
    } while (0)

static bool contains(const std::string& haystack, const std::string& needle)
{
    return haystack.find(needle) != std::string::npos;
}

static void test_is_encoding_supported()
{
    capabilities none; // empty -> no information
    CHECK(audio_compat::is_encoding_supported(encoding::pcm_32bit, none));
    CHECK(audio_compat::is_encoding_supported(encoding::pcm_16bit, none));

    capabilities only16 { { encoding::pcm_16bit }, 2, 48000 };
    CHECK(audio_compat::is_encoding_supported(encoding::pcm_16bit, only16));
    CHECK(!audio_compat::is_encoding_supported(encoding::pcm_32bit, only16));
    CHECK(!audio_compat::is_encoding_supported(encoding::pcm_24bit, only16));

    capabilities modern { { encoding::pcm_8bit, encoding::pcm_16bit, encoding::pcm_float,
                              encoding::pcm_24bit, encoding::pcm_32bit },
        8, 192000 };
    CHECK(audio_compat::is_encoding_supported(encoding::pcm_32bit, modern));
}

static void test_recommend_encoding()
{
    capabilities none;
    // No info -> keep the source unchanged.
    CHECK(audio_compat::recommend_encoding(encoding::pcm_32bit, none) == encoding::pcm_32bit);

    // Legacy device that can only do 16-bit: everything degrades to 16-bit.
    capabilities only16 { { encoding::pcm_16bit }, 2, 48000 };
    CHECK(audio_compat::recommend_encoding(encoding::pcm_32bit, only16) == encoding::pcm_16bit);
    CHECK(audio_compat::recommend_encoding(encoding::pcm_24bit, only16) == encoding::pcm_16bit);
    CHECK(audio_compat::recommend_encoding(encoding::pcm_float, only16) == encoding::pcm_16bit);

    // Device with float but no integer 24/32: prefer float (preserves range).
    capabilities floaty { { encoding::pcm_16bit, encoding::pcm_float }, 2, 48000 };
    CHECK(audio_compat::recommend_encoding(encoding::pcm_32bit, floaty) == encoding::pcm_float);
    CHECK(audio_compat::recommend_encoding(encoding::pcm_24bit, floaty) == encoding::pcm_float);

    // Source already supported -> keep it.
    capabilities modern { { encoding::pcm_16bit, encoding::pcm_float, encoding::pcm_24bit,
                              encoding::pcm_32bit },
        8, 192000 };
    CHECK(audio_compat::recommend_encoding(encoding::pcm_32bit, modern) == encoding::pcm_32bit);
    CHECK(audio_compat::recommend_encoding(encoding::pcm_float, modern) == encoding::pcm_float);
}

static void test_is_fully_compatible()
{
    capabilities only16 { { encoding::pcm_16bit }, 2, 48000 };
    CHECK(audio_compat::is_fully_compatible(encoding::pcm_16bit, 2, 48000, only16));
    CHECK(!audio_compat::is_fully_compatible(encoding::pcm_32bit, 2, 48000, only16)); // encoding
    CHECK(!audio_compat::is_fully_compatible(encoding::pcm_16bit, 6, 48000, only16)); // channels
    CHECK(!audio_compat::is_fully_compatible(encoding::pcm_16bit, 2, 96000, only16)); // sample rate

    capabilities none; // empty -> always compatible
    CHECK(audio_compat::is_fully_compatible(encoding::pcm_32bit, 8, 192000, none));
}

static void test_make_diagnostic()
{
    capabilities only16 { { encoding::pcm_16bit }, 2, 48000 };

    // Native case.
    std::string native = audio_compat::make_diagnostic(encoding::pcm_16bit, 2, 48000, only16);
    CHECK(contains(native, "natively"));

    // Encoding not playable -> recommends a concrete --encoding token.
    std::string enc = audio_compat::make_diagnostic(encoding::pcm_32bit, 2, 48000, only16);
    CHECK(contains(enc, "down-converts"));
    CHECK(contains(enc, "--encoding=s16"));

    // Float source on a float-capable device recommends f32.
    capabilities floaty { { encoding::pcm_16bit, encoding::pcm_float }, 2, 48000 };
    std::string enc2 = audio_compat::make_diagnostic(encoding::pcm_32bit, 2, 48000, floaty);
    CHECK(contains(enc2, "--encoding=f32"));

    // Too many channels -> mentions downmix and a concrete --channels value.
    std::string ch = audio_compat::make_diagnostic(encoding::pcm_16bit, 6, 48000, only16);
    CHECK(contains(ch, "downmix"));
    CHECK(contains(ch, "--channels=2"));
}

int main()
{
    test_is_encoding_supported();
    test_recommend_encoding();
    test_is_fully_compatible();
    test_make_diagnostic();

    std::printf("audio_compat_test: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
