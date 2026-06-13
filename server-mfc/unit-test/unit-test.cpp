#include "pch.h"
#include "CppUnitTest.h"
#include "../audio-share-server/util.hpp"
#include "../audio-share-server/capture_settings.hpp"
#include <map>

using namespace Microsoft::VisualStudio::CppUnitTestFramework;

namespace unittest
{
	TEST_CLASS(test_split_string)
	{
	public:
		
        TEST_METHOD(split_string_0) {
            Assert::IsTrue(std::vector<std::string>{"1", "2", "3"} == util::split_string("1.2.3", '.'));
        }

        TEST_METHOD(split_string_1) {
            Assert::IsTrue(std::vector<std::string>{"2", "3"} == util::split_string(".2.3", '.'));
        }

        TEST_METHOD(split_string_2) {
            Assert::IsTrue(std::vector<std::string>{} == util::split_string("", '.'));
        }

        TEST_METHOD(split_string_3) {
            Assert::IsTrue(std::vector<std::string>{} == util::split_string(".", '.'));
        }
	};

    TEST_CLASS(test_is_newer_version)
    {
    public:
        TEST_METHOD(is_newer_version_arg0) {
            try
            {
                util::is_newer_version("3.2.2", "v0.0.1");
            }
            catch (const std::exception&)
            {
                return;
            }

            Assert::Fail();
        }

        TEST_METHOD(is_newer_version_arg1) {
            try
            {
                util::is_newer_version("v3.2.", "v0.0.");
            }
            catch (const std::exception&)
            {
                return;
            }

            Assert::Fail();
        }

        TEST_METHOD(is_newer_version_version0) {
            Assert::IsTrue(util::is_newer_version("v0.0.17", "v0.0.9"));
        }

        TEST_METHOD(is_newer_version_version1) {
            Assert::IsTrue(util::is_newer_version("v0.1.0", "v0.0.17"));
        }

        TEST_METHOD(is_newer_version_version11) {
            Assert::IsFalse(util::is_newer_version("v0.0.17", "v0.1.0"));
        }

        TEST_METHOD(is_newer_version_version12) {
            Assert::IsFalse(util::is_newer_version("v0.1.0", "v0.1.0"));
        }

        TEST_METHOD(is_newer_version_version13) {
            Assert::IsTrue(util::is_newer_version("v0.2.0", "v0.1.0"));
        }

        TEST_METHOD(is_newer_version_version2) {
            Assert::IsTrue(util::is_newer_version("v0.17.0", "v0.9.17"));
        }

        TEST_METHOD(is_newer_version_version3) {
            Assert::IsTrue(util::is_newer_version("v12.17.0", "v1.0.0"));
        }

        TEST_METHOD(is_newer_version_version4) {
            Assert::IsFalse(util::is_newer_version("v12.17.0", "v12.17.0"));
        }
    };

    namespace {
        // In-memory profile_io so the Capture restore/heal logic can be exercised
        // without MFC or the Windows registry. Entries are keyed "<section>/<key>".
        struct fake_profile : capture_settings::profile_io
        {
            std::map<std::wstring, std::wstring> strings;
            std::map<std::wstring, int> ints;
            int write_count = 0;

            static std::wstring make_key(const wchar_t* section, const wchar_t* key) {
                return std::wstring(section) + L'/' + key;
            }
            std::wstring get_string(const wchar_t* section, const wchar_t* key, const wchar_t* def) override {
                auto it = strings.find(make_key(section, key));
                return it == strings.end() ? std::wstring(def) : it->second;
            }
            int get_int(const wchar_t* section, const wchar_t* key, int def) override {
                auto it = ints.find(make_key(section, key));
                return it == ints.end() ? def : it->second;
            }
            void write_string(const wchar_t* section, const wchar_t* key, const wchar_t* value) override {
                strings[make_key(section, key)] = value;
                ++write_count;
            }
            void write_int(const wchar_t* section, const wchar_t* key, int value) override {
                ints[make_key(section, key)] = value;
                ++write_count;
            }
        };

        const std::wstring kEndpointEntry = fake_profile::make_key(capture_settings::kSection, capture_settings::kEndpointKey);
        const std::wstring kEncodingEntry = fake_profile::make_key(capture_settings::kSection, capture_settings::kEncodingKey);

        // The encodings the dialog actually offers (audio_manager::encoding_t
        // values). Note encoding_invalid (1) is deliberately not selectable.
        const std::vector<int> kSelectableEncodings = { 0, 2, 3, 4, 5, 6 };
        const int kDefaultEncoding = 0;
    }

    TEST_CLASS(test_capture_settings_restore)
    {
    public:
        // Regression for the encoding fallback bug: when the saved encoding is not
        // selectable, the heal must rewrite the ENCODING key and leave the endpoint
        // key untouched. The old code wrote the default encoding to the endpoint
        // key instead, corrupting it into an int and poisoning the next restore.
        TEST_METHOD(restore_encoding_invalid_heals_encoding_not_endpoint) {
            fake_profile io;
            io.strings[kEndpointEntry] = L"{0.0.0.00000000}.{some-endpoint-id}";
            io.ints[kEncodingEntry] = 1; // encoding_invalid: not in the selectable set

            int selected = capture_settings::restore_encoding(io, kSelectableEncodings, kDefaultEncoding);

            Assert::IsTrue(selected == kDefaultEncoding);
            // the encoding key was healed to the default
            Assert::IsTrue(io.ints.find(kEncodingEntry) != io.ints.end());
            Assert::IsTrue(io.ints[kEncodingEntry] == kDefaultEncoding);
            // the endpoint string survives unchanged...
            Assert::IsTrue(io.strings[kEndpointEntry] == L"{0.0.0.00000000}.{some-endpoint-id}");
            // ...and is never written as an int
            Assert::IsTrue(io.ints.find(kEndpointEntry) == io.ints.end());
            // exactly one write happened: the encoding heal
            Assert::IsTrue(io.write_count == 1);
        }

        TEST_METHOD(restore_encoding_valid_returns_value_without_writing) {
            fake_profile io;
            io.ints[kEncodingEntry] = 4; // encoding_s16: selectable

            int selected = capture_settings::restore_encoding(io, kSelectableEncodings, kDefaultEncoding);

            Assert::IsTrue(selected == 4);
            Assert::IsTrue(io.write_count == 0);
        }

        TEST_METHOD(restore_encoding_missing_uses_default_without_writing) {
            fake_profile io; // nothing stored yet

            int selected = capture_settings::restore_encoding(io, kSelectableEncodings, kDefaultEncoding);

            Assert::IsTrue(selected == kDefaultEncoding);
            Assert::IsTrue(io.write_count == 0); // default is selectable, no heal needed
        }

        // Symmetric guard for the endpoint path: a stale endpoint heals the
        // ENDPOINT key only and must not disturb the saved encoding.
        TEST_METHOD(restore_endpoint_invalid_heals_endpoint_not_encoding) {
            fake_profile io;
            io.strings[kEndpointEntry] = L"stale-device-id";
            io.ints[kEncodingEntry] = 4; // a valid saved encoding that must survive
            std::vector<std::wstring> available = { L"default", L"live-device-id" };

            std::wstring selected = capture_settings::restore_endpoint(io, available);

            Assert::IsTrue(selected == L"default");
            Assert::IsTrue(io.strings[kEndpointEntry] == L"default"); // healed
            Assert::IsTrue(io.ints[kEncodingEntry] == 4);             // encoding untouched
            Assert::IsTrue(io.strings.find(kEncodingEntry) == io.strings.end());
            Assert::IsTrue(io.write_count == 1);
        }

        TEST_METHOD(restore_endpoint_valid_returns_value_without_writing) {
            fake_profile io;
            io.strings[kEndpointEntry] = L"live-device-id";
            std::vector<std::wstring> available = { L"default", L"live-device-id" };

            std::wstring selected = capture_settings::restore_endpoint(io, available);

            Assert::IsTrue(selected == L"live-device-id");
            Assert::IsTrue(io.write_count == 0);
        }

        TEST_METHOD(restore_endpoint_missing_uses_default_without_writing) {
            fake_profile io; // nothing stored yet
            std::vector<std::wstring> available = { L"default", L"live-device-id" };

            std::wstring selected = capture_settings::restore_endpoint(io, available);

            Assert::IsTrue(selected == L"default");
            Assert::IsTrue(io.write_count == 0); // default is available, no heal needed
        }
    };
}
