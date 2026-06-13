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

#ifndef IP_ADDRESS_HPP
#define IP_ADDRESS_HPP

#include <cstdint>
#include <string>
#include <vector>

// Header-only IPv4 helpers used to pick the server's default listen address.
//
// Deliberately free of any platform/socket dependency (no inet_pton/winsock) so
// the address-selection rule can be unit tested in isolation, and so malformed
// input is rejected explicitly instead of reading uninitialized bytes.
namespace ip_address {

// Parse a dotted-decimal IPv4 string ("a.b.c.d") into a host-byte-order 32-bit
// value. Returns false for anything that is not exactly four 0-255 octets.
inline bool parse_ipv4(const std::string& text, uint32_t& out)
{
    uint32_t result = 0;
    int octet_index = 0;
    int octet_value = 0;
    int digit_count = 0;

    for (char ch : text) {
        if (ch == '.') {
            if (digit_count == 0 || octet_index == 3) {
                return false; // empty octet, or more than four octets
            }
            result = (result << 8) | static_cast<uint32_t>(octet_value);
            ++octet_index;
            octet_value = 0;
            digit_count = 0;
        } else if (ch >= '0' && ch <= '9') {
            octet_value = octet_value * 10 + (ch - '0');
            if (octet_value > 255 || ++digit_count > 3) {
                return false; // octet out of range or too many digits
            }
        } else {
            return false; // unexpected character
        }
    }

    if (octet_index != 3 || digit_count == 0) {
        return false; // need exactly four octets, last one non-empty
    }
    result = (result << 8) | static_cast<uint32_t>(octet_value);

    out = result;
    return true;
}

// Whether the address falls in one of the RFC 1918 private IPv4 ranges
// (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16). Each range is matched as
// (addr & mask) == network -- the mask, not the network prefix, decides which
// bits are significant.
inline bool is_private_ipv4(const std::string& address)
{
    uint32_t addr = 0;
    if (!parse_ipv4(address, addr)) {
        return false;
    }

    struct cidr_range {
        uint32_t network;
        uint32_t mask;
    };

    constexpr cidr_range private_ranges[] = {
        { 0x0A000000u, 0xFF000000u }, // 10.0.0.0/8
        { 0xAC100000u, 0xFFF00000u }, // 172.16.0.0/12
        { 0xC0A80000u, 0xFFFF0000u }, // 192.168.0.0/16
    };

    for (const auto& range : private_ranges) {
        if ((addr & range.mask) == range.network) {
            return true;
        }
    }
    return false;
}

// Pick the default listen address: prefer the first private (LAN-reachable)
// IPv4 address, otherwise fall back to the first address, otherwise empty.
inline std::string select_default_address(const std::vector<std::string>& address_list)
{
    if (address_list.empty()) {
        return {};
    }

    for (const auto& address : address_list) {
        if (is_private_ipv4(address)) {
            return address;
        }
    }
    return address_list.front();
}

} // namespace ip_address

#endif // !IP_ADDRESS_HPP
