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

// Regression tests for the default listen-address selection.
//
// These lock in the fix for the private-range detection that previously used
// the network prefix as a mask, which both rejected/accepted addresses
// unreliably and mis-classified public addresses as private.
//
// Framework-free so it can be built and run anywhere with a C++20 compiler:
//   c++ -std=c++20 test/test_ip_address.cpp -o test_ip_address && ./test_ip_address

#include "ip_address.hpp"

#include <cstdio>
#include <string>
#include <vector>

namespace {

int g_checks = 0;
int g_failures = 0;

void expect_private(const std::string& address, bool expected)
{
    ++g_checks;
    const bool actual = ip_address::is_private_ipv4(address);
    if (actual != expected) {
        ++g_failures;
        std::printf("FAIL is_private_ipv4(\"%s\") = %s, expected %s\n",
            address.c_str(), actual ? "true" : "false", expected ? "true" : "false");
    }
}

void expect_select(const std::vector<std::string>& list, const std::string& expected, const char* name)
{
    ++g_checks;
    const std::string actual = ip_address::select_default_address(list);
    if (actual != expected) {
        ++g_failures;
        std::printf("FAIL select_default_address(%s) = \"%s\", expected \"%s\"\n",
            name, actual.c_str(), expected.c_str());
    }
}

} // namespace

int main()
{
    // --- Valid RFC 1918 private addresses must be detected ---
    expect_private("10.0.0.0", true);
    expect_private("10.0.0.1", true);
    expect_private("10.255.255.255", true);
    expect_private("172.16.0.0", true);
    expect_private("172.16.0.1", true);
    expect_private("172.20.5.5", true);
    expect_private("172.31.0.1", true);     // high end of 172.16.0.0/12
    expect_private("172.31.255.254", true); // the originally reported failing case
    expect_private("172.31.255.255", true);
    expect_private("192.168.0.1", true);
    expect_private("192.168.1.1", true);
    expect_private("192.168.255.255", true);

    // --- Public addresses the old prefix-as-mask logic wrongly accepted ---
    expect_private("11.0.0.1", false);    // old code: (addr & 0x0a000000) == 0x0a000000
    expect_private("172.48.0.1", false);  // old code: (addr & 0xac100000) == 0xac100000
    expect_private("200.168.0.1", false); // old code: (addr & 0xc0a80000) == 0xc0a80000

    // --- Boundaries just outside each private range ---
    expect_private("9.255.255.255", false);
    expect_private("172.15.255.255", false);
    expect_private("172.32.0.0", false);
    expect_private("192.167.255.255", false);
    expect_private("192.169.0.0", false);

    // --- Ordinary public addresses ---
    expect_private("8.8.8.8", false);
    expect_private("1.2.3.4", false);
    expect_private("203.0.113.7", false);
    expect_private("0.0.0.0", false);

    // --- Malformed input must be rejected, never crash ---
    expect_private("", false);
    expect_private("not-an-ip", false);
    expect_private("256.0.0.1", false);
    expect_private("192.168.0", false);
    expect_private("192.168.0.1.5", false);
    expect_private("192.168..1", false);

    // --- select_default_address prefers a private address even if not first ---
    expect_select({ "8.8.8.8", "192.168.1.50", "1.1.1.1" }, "192.168.1.50", "public,private,public");
    expect_select({ "203.0.113.7", "172.20.5.5" }, "172.20.5.5", "public,private");
    // 11.0.0.1 was a false positive under the old logic; the real private wins now.
    expect_select({ "11.0.0.1", "10.1.2.3" }, "10.1.2.3", "old-false-positive,real-private");
    // No private address: fall back to the first entry.
    expect_select({ "8.8.8.8", "1.1.1.1" }, "8.8.8.8", "all-public");
    // Empty list yields empty string.
    expect_select({}, "", "empty");

    if (g_failures == 0) {
        std::printf("OK: all %d checks passed\n", g_checks);
        return 0;
    }
    std::printf("%d of %d checks FAILED\n", g_failures, g_checks);
    return 1;
}
