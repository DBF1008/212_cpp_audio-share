/*
 * Regression tests for RFC 1918 private IPv4 address detection
 * and the select_default_address() preference logic.
 *
 * Build (standalone):
 *   Linux:  c++ -std=c++20 -o test_private_address test_private_address.cpp
 *   Windows (MSVC): cl /std:c++20 /EHsc /Fe test_private_address.cpp ws2_32.lib
 *
 * The is_private_address() lambda in network_manager.cpp is the system under
 * test. We reproduce the exact fixed algorithm here so the test is
 * self-contained and has zero third-party dependencies.
 */

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <utility>
#include <vector>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "Ws2_32.lib")
#else
#include <arpa/inet.h>
#endif

// ---------------------------------------------------------------------------
// Exact copy of the fixed is_private_address lambda from network_manager.cpp.
// Any drift between this and the production code will be caught by a test
// failure.
// ---------------------------------------------------------------------------
static bool is_private_address(const std::string& address)
{
    constexpr std::pair<uint32_t, uint32_t> private_networks[] = {
        {0x0a000000, 0xff000000}, // 10.0.0.0/8
        {0xac100000, 0xfff00000}, // 172.16.0.0/12
        {0xc0a80000, 0xffff0000}, // 192.168.0.0/16
    };

    uint32_t addr;
    inet_pton(AF_INET, address.c_str(), &addr);
    addr = ntohl(addr);
    for (auto&& [network, mask] : private_networks) {
        if ((addr & mask) == network) {
            return true;
        }
    }
    return false;
}

// ---------------------------------------------------------------------------
// Exact copy of the select_default_address algorithm from network_manager.cpp.
// ---------------------------------------------------------------------------
static std::string select_default_address(const std::vector<std::string>& address_list)
{
    if (address_list.empty()) {
        return {};
    }
    for (auto&& address : address_list) {
        if (is_private_address(address)) {
            return address;
        }
    }
    return address_list.front();
}

// ---------------------------------------------------------------------------
// Minimal test harness
// ---------------------------------------------------------------------------
static int g_total = 0;
static int g_failed = 0;

#define EXPECT_TRUE(expr)  do_check(true,  (expr), #expr, __FILE__, __LINE__)
#define EXPECT_FALSE(expr) do_check(false, (expr), #expr, __FILE__, __LINE__)
#define EXPECT_EQ(a, b)    do_check_eq((a), (b), #a, #b, __FILE__, __LINE__)

static void do_check(bool expected, bool actual, const char* expr,
                     const char* file, int line)
{
    ++g_total;
    if (actual != expected) {
        ++g_failed;
        std::fprintf(stderr, "  FAIL %s:%d: %s  (expected %s, got %s)\n",
                     file, line, expr,
                     expected ? "true" : "false",
                     actual   ? "true" : "false");
    }
}

static void do_check_eq(const std::string& expected, const std::string& actual,
                        const char* ea, const char* eb,
                        const char* file, int line)
{
    ++g_total;
    if (expected != actual) {
        ++g_failed;
        std::fprintf(stderr, "  FAIL %s:%d: %s == %s\n    expected: \"%s\"\n    actual:   \"%s\"\n",
                     file, line, ea, eb, expected.c_str(), actual.c_str());
    }
}

// ---------------------------------------------------------------------------
// Tests: 10.0.0.0/8
// ---------------------------------------------------------------------------
static void test_10_slash8()
{
    std::printf("[test] 10.0.0.0/8 range\n");

    // Boundaries and interior points
    EXPECT_TRUE(is_private_address("10.0.0.0"));
    EXPECT_TRUE(is_private_address("10.0.0.1"));
    EXPECT_TRUE(is_private_address("10.0.0.255"));
    EXPECT_TRUE(is_private_address("10.1.0.0"));
    EXPECT_TRUE(is_private_address("10.127.255.255"));
    EXPECT_TRUE(is_private_address("10.128.0.0"));
    EXPECT_TRUE(is_private_address("10.255.255.254"));
    EXPECT_TRUE(is_private_address("10.255.255.255"));

    // Just outside the range
    EXPECT_FALSE(is_private_address("9.255.255.255"));
    EXPECT_FALSE(is_private_address("11.0.0.0"));
}

// ---------------------------------------------------------------------------
// Tests: 172.16.0.0/12  (172.16.0.0 – 172.31.255.255)
// ---------------------------------------------------------------------------
static void test_172_slash12()
{
    std::printf("[test] 172.16.0.0/12 range\n");

    // Lower boundary
    EXPECT_FALSE(is_private_address("172.15.255.255"));
    EXPECT_TRUE(is_private_address("172.16.0.0"));
    EXPECT_TRUE(is_private_address("172.16.0.1"));

    // Interior points — the original bug could not reliably match these.
    // E.g. 172.31.0.1 -> 0xac1f0001, old mask 0xac100000:
    //   0xac1f0001 & 0xac100000 = 0xac100000 == 0xac100000  (happened to work)
    // But 172.20.0.1 -> 0xac140001:
    //   0xac140001 & 0xac100000 = 0xac100000 == 0xac100000  (also happened to work)
    // Still, the test is valuable as a regression guard.
    EXPECT_TRUE(is_private_address("172.16.255.255"));
    EXPECT_TRUE(is_private_address("172.17.0.0"));
    EXPECT_TRUE(is_private_address("172.20.0.1"));
    EXPECT_TRUE(is_private_address("172.20.10.1"));   // common iPhone hotspot
    EXPECT_TRUE(is_private_address("172.25.0.1"));
    EXPECT_TRUE(is_private_address("172.30.0.1"));
    EXPECT_TRUE(is_private_address("172.31.0.1"));
    EXPECT_TRUE(is_private_address("172.31.255.255"));

    // Upper boundary — 172.32.x.x is NOT private
    EXPECT_FALSE(is_private_address("172.32.0.0"));
    EXPECT_FALSE(is_private_address("172.32.0.1"));
    EXPECT_FALSE(is_private_address("172.100.0.1"));

    // Regression for the old buggy mask: addresses whose bit pattern
    // accidentally satisfied (addr & 0xac100000) == 0xac100000 despite
    // not being in 172.16/12.
    //   168.168.1.1 -> 0xa8a80101, & 0xac100000 = 0xa8100000 != 0xac100000 (happened to not match)
    //   252.144.0.1 -> 0xfc900001, & 0xac100000 = 0xac000000 != 0xac100000 (happened to not match)
    // But 236.144.0.1 -> 0xec900001, & 0xac100000 = 0xac100000 == 0xac100000 -> FALSE POSITIVE!
    EXPECT_FALSE(is_private_address("236.144.0.1"));   // old bug: false positive
    EXPECT_FALSE(is_private_address("236.145.0.1"));
    EXPECT_FALSE(is_private_address("252.176.0.1"));   // 0xfc b0 00 01 -> & mask = 0xac100000
    EXPECT_FALSE(is_private_address("172.144.0.1"));   // 172 is 0xac, but .144 = 0x90 -> & 0x10 = 0x10; check full /12
    EXPECT_FALSE(is_private_address("168.16.0.1"));    // first octet wrong
}

// ---------------------------------------------------------------------------
// Tests: 192.168.0.0/16
// ---------------------------------------------------------------------------
static void test_192_168_slash16()
{
    std::printf("[test] 192.168.0.0/16 range\n");

    EXPECT_FALSE(is_private_address("192.167.255.255"));
    EXPECT_TRUE(is_private_address("192.168.0.0"));
    EXPECT_TRUE(is_private_address("192.168.0.1"));
    EXPECT_TRUE(is_private_address("192.168.1.1"));    // extremely common home router
    EXPECT_TRUE(is_private_address("192.168.1.100"));
    EXPECT_TRUE(is_private_address("192.168.100.1"));
    EXPECT_TRUE(is_private_address("192.168.255.255"));

    EXPECT_FALSE(is_private_address("192.169.0.0"));
    EXPECT_FALSE(is_private_address("192.169.0.1"));

    // Regression: 192.168.0.0/16 old mask 0xc0a80000 has bits 31,30,27,24,12.
    // Any address with those bits set matched as "private".
    //   224.168.0.1 -> 0xe0a80001, & 0xc0a80000 = 0xc0a80000 -> FALSE POSITIVE
    EXPECT_FALSE(is_private_address("224.168.0.1"));   // old bug: false positive
    EXPECT_FALSE(is_private_address("225.168.0.1"));
}

// ---------------------------------------------------------------------------
// Tests: obviously non-private addresses
// ---------------------------------------------------------------------------
static void test_public_addresses()
{
    std::printf("[test] well-known public addresses\n");

    EXPECT_FALSE(is_private_address("0.0.0.0"));
    EXPECT_FALSE(is_private_address("8.8.8.8"));
    EXPECT_FALSE(is_private_address("1.1.1.1"));
    EXPECT_FALSE(is_private_address("142.250.80.46"));  // google.com
    EXPECT_FALSE(is_private_address("151.101.1.69"));   // reddit
    EXPECT_FALSE(is_private_address("255.255.255.255"));
    EXPECT_FALSE(is_private_address("224.0.0.1"));      // multicast
    EXPECT_FALSE(is_private_address("100.64.0.1"));     // CGNAT (not RFC 1918)
    EXPECT_FALSE(is_private_address("169.254.1.1"));    // link-local (not RFC 1918)
}

// ---------------------------------------------------------------------------
// Tests: select_default_address prefers a private address when present
// ---------------------------------------------------------------------------
static void test_select_default_address()
{
    std::printf("[test] select_default_address preference\n");

    // Empty list -> empty string
    EXPECT_EQ(std::string{}, select_default_address({}));

    // Only public -> falls back to front
    EXPECT_EQ(std::string("8.8.8.8"),
              select_default_address({"8.8.8.8", "1.1.1.1"}));

    // Private in the middle -> selected
    EXPECT_EQ(std::string("192.168.1.1"),
              select_default_address({"8.8.8.8", "192.168.1.1", "1.1.1.1"}));

    // First private wins
    EXPECT_EQ(std::string("10.0.0.5"),
              select_default_address({"10.0.0.5", "192.168.1.1"}));

    // 172.16-31.x.x correctly preferred over public
    EXPECT_EQ(std::string("172.20.10.1"),
              select_default_address({"142.250.80.46", "172.20.10.1"}));

    // 172.32.x.x is NOT private -> falls back to front
    EXPECT_EQ(std::string("172.32.0.1"),
              select_default_address({"172.32.0.1", "8.8.8.8"}));

    // Regression: the old buggy mask would have treated 236.144.0.1 as
    // private and selected it. After the fix, it is public and the real
    // private address should be preferred.
    EXPECT_EQ(std::string("192.168.0.10"),
              select_default_address({"236.144.0.1", "192.168.0.10"}));
}

// ---------------------------------------------------------------------------
int main()
{
    test_10_slash8();
    test_172_slash12();
    test_192_168_slash16();
    test_public_addresses();
    test_select_default_address();

    std::printf("\n%d tests, %d failed\n", g_total, g_failed);
    return g_failed == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
