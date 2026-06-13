#ifndef CAPTURE_SETTINGS_HPP
#define CAPTURE_SETTINGS_HPP

#include <algorithm>
#include <string>
#include <vector>

// Persistence helpers for the "Capture" settings: the selected audio endpoint
// (stored as a string id) and the capture encoding (stored as an int enum).
//
// The restore/heal logic lives here, free of any MFC dependency, so it can be
// unit-tested in isolation. The dialog supplies a thin profile_io adapter over
// CWinApp's GetProfile*/WriteProfile* APIs.
//
// History: the endpoint and encoding restore paths used to spell out the
// registry keys inline, and an encoding fallback once healed the *endpoint* key
// by mistake. Routing every read/write through the constants and functions below
// keeps the two keys from being confused again.
namespace capture_settings
{
    // Registry section and keys. Centralised so the endpoint key (a string) and
    // the encoding key (an int) cannot be mixed up by a stray literal.
    inline constexpr const wchar_t* kSection = L"Capture";
    inline constexpr const wchar_t* kEndpointKey = L"endpoint";
    inline constexpr const wchar_t* kEncodingKey = L"encoding";

    // Default endpoint id (the "use the system default device" sentinel).
    inline constexpr const wchar_t* kDefaultEndpoint = L"default";

    // Abstraction over CWinApp's profile read/write, injected for testability.
    struct profile_io
    {
        virtual ~profile_io() = default;
        virtual std::wstring get_string(const wchar_t* section, const wchar_t* key, const wchar_t* def) = 0;
        virtual int get_int(const wchar_t* section, const wchar_t* key, int def) = 0;
        virtual void write_string(const wchar_t* section, const wchar_t* key, const wchar_t* value) = 0;
        virtual void write_int(const wchar_t* section, const wchar_t* key, int value) = 0;
    };

    // Resolve the persisted capture endpoint against the currently available
    // endpoint ids. Returns the id that should be selected. If the persisted id
    // is no longer available, the stored value is healed back to "default" and
    // "default" is returned. Only the endpoint key is ever written.
    inline std::wstring restore_endpoint(profile_io& io, const std::vector<std::wstring>& available_ids)
    {
        std::wstring configured = io.get_string(kSection, kEndpointKey, kDefaultEndpoint);
        if (std::find(available_ids.begin(), available_ids.end(), configured) != available_ids.end()) {
            return configured;
        }
        io.write_string(kSection, kEndpointKey, kDefaultEndpoint);
        return kDefaultEndpoint;
    }

    // Resolve the persisted capture encoding against the selectable encodings.
    // Returns the encoding that should be selected. If the persisted value is not
    // selectable (a corrupt config, or an enum that changed across versions), the
    // stored value is healed back to default_encoding, which is returned. Only the
    // encoding key is ever written.
    inline int restore_encoding(profile_io& io, const std::vector<int>& available_encodings, int default_encoding)
    {
        int configured = io.get_int(kSection, kEncodingKey, default_encoding);
        if (std::find(available_encodings.begin(), available_encodings.end(), configured) != available_encodings.end()) {
            return configured;
        }
        io.write_int(kSection, kEncodingKey, default_encoding);
        return default_encoding;
    }
}

#endif // !CAPTURE_SETTINGS_HPP
