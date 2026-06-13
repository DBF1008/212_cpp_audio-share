#!/usr/bin/env python3
"""
Regression test for Capture profile key type consistency in CServerTabPanel.cpp.

Background (bug fixed 2026-06):
  The encoding fallback block mistakenly called:
      theApp.WriteProfileInt(L"Capture", L"endpoint", ...)
  instead of:
      theApp.WriteProfileInt(L"Capture", L"encoding", ...)
  This overwrote the endpoint string config with an integer, corrupting
  subsequent endpoint reads and causing UI/startup parameter mismatches.

Invariants checked:
  1. "endpoint" is always read/written as a string  (GetProfileStringW / WriteProfileStringW)
  2. "encoding" is always read/written as an int    (GetProfileIntW / WriteProfileInt)
  3. Every WriteProfile*(section, key, ...) in the "Capture" section has a
     matching GetProfile*(section, key, ...) that uses the same API category
     (string vs int).
"""

import re
import sys
import os

# ---------------------------------------------------------------------------
# Locate source file relative to this script
# ---------------------------------------------------------------------------
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SOURCE_FILE = os.path.normpath(
    os.path.join(SCRIPT_DIR, os.pardir, "audio-share-server", "CServerTabPanel.cpp")
)


def parse_profile_calls(source: str):
    """
    Extract all theApp.GetProfile* / WriteProfile* calls from *source*.
    Returns a list of dicts:
        { 'api': 'WriteProfileInt', 'section': 'Capture', 'key': 'encoding', 'line': 159 }
    """
    # Match patterns like:
    #   theApp.GetProfileStringW(L"Capture", L"endpoint", ...)
    #   theApp.WriteProfileInt(L"Capture", L"encoding", ...)
    pattern = re.compile(
        r'theApp\.'
        r'(GetProfileStringW|GetProfileIntW|WriteProfileStringW|WriteProfileInt)'
        r'\s*\(\s*L"([^"]+)"\s*,\s*L"([^"]+)"',
    )
    results = []
    for m in pattern.finditer(source):
        api, section, key = m.group(1), m.group(2), m.group(3)
        # Compute line number
        line_no = source[: m.start()].count("\n") + 1
        results.append({"api": api, "section": section, "key": key, "line": line_no})
    return results


def is_string_api(api: str) -> bool:
    return "String" in api


def is_int_api(api: str) -> bool:
    return "Int" in api and "String" not in api


def check_capture_key_consistency(calls):
    """
    For each (section, key) pair, verify that all read and write APIs agree
    on the value type (string vs int).  A mismatch means a write uses int API
    while the corresponding read uses string API, or vice versa.
    """
    errors = []

    # Group calls by (section, key)
    from collections import defaultdict
    groups = defaultdict(list)
    for c in calls:
        groups[(c["section"], c["key"])].append(c)

    for (section, key), group in groups.items():
        string_apis = [c for c in group if is_string_api(c["api"])]
        int_apis = [c for c in group if is_int_api(c["api"])]

        if string_apis and int_apis:
            # Mixed types on the same (section, key) — this is the bug pattern
            str_lines = ", ".join(str(c["line"]) for c in string_apis)
            int_lines = ", ".join(str(c["line"]) for c in int_apis)
            errors.append(
                f'Type mismatch on [{section}]/{key}: '
                f'string API at line(s) {str_lines} vs int API at line(s) {int_lines}'
            )

    return errors


def check_endpoint_not_written_as_int(calls):
    """
    Hard guard: Capture/endpoint must never be written via WriteProfileInt.
    This is the exact bug that was fixed.
    """
    errors = []
    for c in calls:
        if (
            c["section"] == "Capture"
            and c["key"] == "endpoint"
            and c["api"] == "WriteProfileInt"
        ):
            errors.append(
                f'Line {c["line"]}: WriteProfileInt must NOT be used for '
                f'[Capture]/endpoint — endpoint is a string key.'
            )
    return errors


def check_encoding_not_written_as_string(calls):
    """
    Symmetric guard: Capture/encoding must never be written via WriteProfileStringW.
    """
    errors = []
    for c in calls:
        if (
            c["section"] == "Capture"
            and c["key"] == "encoding"
            and c["api"] == "WriteProfileStringW"
        ):
            errors.append(
                f'Line {c["line"]}: WriteProfileStringW must NOT be used for '
                f'[Capture]/encoding — encoding is an int key.'
            )
    return errors


def main():
    if not os.path.isfile(SOURCE_FILE):
        print(f"ERROR: source file not found: {SOURCE_FILE}", file=sys.stderr)
        sys.exit(2)

    with open(SOURCE_FILE, "r", encoding="utf-8") as f:
        source = f.read()

    calls = parse_profile_calls(source)
    if not calls:
        print("WARNING: no profile API calls found — check regex", file=sys.stderr)
        sys.exit(2)

    all_errors = []
    all_errors.extend(check_endpoint_not_written_as_int(calls))
    all_errors.extend(check_encoding_not_written_as_string(calls))
    all_errors.extend(check_capture_key_consistency(calls))

    if all_errors:
        print("FAIL — profile key type consistency violations found:")
        for err in all_errors:
            print(f"  ✘ {err}")
        sys.exit(1)
    else:
        print(f"PASS — {len(calls)} profile API calls checked, no type mismatches.")
        sys.exit(0)


if __name__ == "__main__":
    main()
