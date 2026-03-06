#!/usr/bin/env python3
"""
detailed_compare.py — Detailed field-by-field comparison of old ZOOMA and new
ZOOMA2 v2 API responses.

Fetches the same endpoints on both servers and reports:
  - Missing fields (in old but not new)  → FAIL
  - Extra fields (in new but not old)    → INFO
  - Type mismatches                       → FAIL
  - Value-type mismatches (e.g. int vs str for dates) → FAIL

Usage:
    python3 detailed_compare.py [--new-base http://localhost:8090/v2/api]
"""

import argparse
import json
import sys
import time
import urllib.request
import urllib.error
import urllib.parse
import http.cookiejar
from collections import OrderedDict

OLD_BASE = "https://www.ebi.ac.uk/spot/zooma/v2/api"
NEW_BASE = "http://localhost:8090/v2/api"

GREEN = "\033[32m"
RED = "\033[31m"
YELLOW = "\033[33m"
BOLD = "\033[1m"
RESET = "\033[0m"

pass_count = 0
fail_count = 0
warn_count = 0


def fetch_json(url, method="GET", body=None):
    """Fetch JSON from url. Returns (parsed, raw_text) or raises."""
    req = urllib.request.Request(url, method=method)
    req.add_header("Accept", "application/json")
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = body.encode("utf-8") if isinstance(body, str) else json.dumps(body).encode("utf-8")
        req.data = data
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            ct = resp.headers.get("Content-Type", "")
            try:
                return json.loads(raw), raw
            except json.JSONDecodeError:
                return None, f"Non-JSON response (Content-Type: {ct}): {raw[:200]}"
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8", errors="replace")
        return None, f"HTTP {e.code}: {body_text[:200]}"
    except Exception as e:
        return None, str(e)


def type_name(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "bool"
    if isinstance(v, int):
        return "int"
    if isinstance(v, float):
        return "float"
    if isinstance(v, str):
        return "str"
    if isinstance(v, list):
        return "list"
    if isinstance(v, dict):
        return "dict"
    return type(v).__name__


def flatten_schema(obj, prefix=""):
    """Flatten a JSON object into {dotted_path: type_name} dict.
    For lists, descends into first element with [] suffix."""
    result = OrderedDict()
    if isinstance(obj, dict):
        for k in sorted(obj.keys()):
            path = f"{prefix}.{k}" if prefix else k
            result[path] = type_name(obj[k])
            result.update(flatten_schema(obj[k], path))
    elif isinstance(obj, list):
        if obj:
            result.update(flatten_schema(obj[0], prefix + "[]"))
    return result


def compare_responses(label, old_data, new_data):
    """Compare two parsed JSON responses structurally."""
    global pass_count, fail_count, warn_count

    print(f"\n{BOLD}{'━' * 60}{RESET}")
    print(f"{BOLD}  {label}{RESET}")
    print(f"{BOLD}{'━' * 60}{RESET}")

    if old_data is None:
        print(f"  {RED}✗ OLD response is not valid JSON{RESET}")
        fail_count += 1
        return
    if new_data is None:
        print(f"  {RED}✗ NEW response is not valid JSON{RESET}")
        fail_count += 1
        return

    # Normalize: for comparison, if top-level is a list, use first element
    old_sample = old_data[0] if isinstance(old_data, list) and old_data else old_data
    new_sample = new_data[0] if isinstance(new_data, list) and new_data else new_data

    # Check top-level type matches
    if type_name(old_data) != type_name(new_data):
        print(f"  {RED}✗ Top-level type mismatch: old={type_name(old_data)} new={type_name(new_data)}{RESET}")
        fail_count += 1
        return

    if isinstance(old_data, list):
        print(f"  Old returned {len(old_data)} items, New returned {len(new_data)} items")

    old_schema = flatten_schema(old_sample)
    new_schema = flatten_schema(new_sample)

    old_keys = set(old_schema.keys())
    new_keys = set(new_schema.keys())

    missing = old_keys - new_keys
    extra = new_keys - old_keys
    common = old_keys & new_keys

    has_issues = False

    # Missing fields (CRITICAL — unless it's just empty-list-child-paths)
    if missing:
        # Separate truly missing top-level fields from list-element-schema paths
        # that are absent only because the new API returns an empty list
        truly_missing = set()
        empty_list_children = set()
        for k in missing:
            # Check if this is a path inside a list element (contains [])
            # and the parent list exists in the new schema as an empty list
            parts = k.split("[]")
            if len(parts) > 1:
                # Find the parent list path
                parent_list = parts[0]
                # If the parent exists as a "list" in both schemas, it's just empty
                if parent_list in new_schema and new_schema.get(parent_list, "") == "list":
                    empty_list_children.add(k)
                    continue
            truly_missing.add(k)

        if truly_missing:
            has_issues = True
            print(f"\n  {RED}MISSING fields (in old API, absent from new):{RESET}")
            for k in sorted(truly_missing):
                print(f"    {RED}✗ {k}  (old type: {old_schema[k]}){RESET}")
            fail_count += 1

        if empty_list_children:
            print(f"\n  {YELLOW}EMPTY LIST element schema (list exists but empty in new):{RESET}")
            for k in sorted(empty_list_children):
                print(f"    {YELLOW}~ {k}  (old type: {old_schema[k]}){RESET}")
            warn_count += 1
    else:
        missing = set()  # ensure it's defined

    # Type mismatches on shared fields (CRITICAL)
    type_mismatches = []
    for k in sorted(common):
        ot = old_schema[k]
        nt = new_schema[k]
        # null is compatible with anything
        if ot == "null" or nt == "null":
            continue
        # int/float are compatible JSON numbers
        if {ot, nt} <= {"int", "float"}:
            continue
        if ot != nt:
            type_mismatches.append((k, ot, nt))

    if type_mismatches:
        has_issues = True
        print(f"\n  {RED}TYPE MISMATCHES:{RESET}")
        for k, ot, nt in type_mismatches:
            print(f"    {RED}✗ {k}: old={ot}  new={nt}{RESET}")
        fail_count += 1

    # Extra fields (INFO — usually OK)
    if extra:
        print(f"\n  {YELLOW}EXTRA fields (in new API, not in old — probably OK):{RESET}")
        for k in sorted(extra):
            print(f"    {YELLOW}+ {k}  (type: {new_schema[k]}){RESET}")
        warn_count += 1

    if not has_issues:
        print(f"\n  {GREEN}✓ Response structure is compatible{RESET}")
        pass_count += 1


# ─── Test cases ──────────────────────────────────────────────────────────────

TEST_CASES = [
    {
        "name": "GET /sources",
        "method": "GET",
        "path": "/sources",
    },
    {
        "name": "GET /properties/types",
        "method": "GET",
        "path": "/properties/types",
    },
    {
        "name": "GET /services/annotate — basic (mus musculus)",
        "method": "GET",
        "path": "/services/annotate?propertyValue=mus+musculus",
    },
    {
        "name": "GET /services/annotate — with propertyType",
        "method": "GET",
        "path": "/services/annotate?propertyValue=mus+musculus&propertyType=organism",
    },
    {
        "name": "GET /services/annotate — with filter (required)",
        "method": "GET",
        "path": "/services/annotate?propertyValue=lung+adenocarcinoma&filter=" +
                urllib.parse.quote("required:[atlas,gwas]"),
    },
    {
        "name": "GET /services/annotate — with filter (required+preferred)",
        "method": "GET",
        "path": "/services/annotate?propertyValue=lung+adenocarcinoma&filter=" +
                urllib.parse.quote("required:[atlas,gwas],preferred:[gwas]"),
    },
    {
        "name": "GET /services/annotate — ontologies only",
        "method": "GET",
        "path": "/services/annotate?propertyValue=mus+musculus&propertyType=organism&filter=" +
                urllib.parse.quote("required:[none],ontologies:[efo]"),
    },
    {
        "name": "GET /services/annotate — no OLS fallback",
        "method": "GET",
        "path": "/services/annotate?propertyValue=ear+inflorescence&filter=" +
                urllib.parse.quote("required:[sysmicro],ontologies:[none]"),
    },
    {
        "name": "POST /services/map — basic (text ack, tested separately in async flow)",
        "method": "POST",
        "path": "/services/map",
        "body": '[{"propertyType":"organism","propertyValue":"mus musculus"}]',
        "skip_json_compare": True,
    },
    {
        "name": "POST /services/map — multiple + filter (text ack, tested separately)",
        "method": "POST",
        "path": "/services/map?filter=" + urllib.parse.quote("required:[atlas,gwas]"),
        "body": '[{"propertyType":"organism","propertyValue":"mus musculus"},{"propertyType":"disease","propertyValue":"cancer"}]',
        "skip_json_compare": True,
    },
]

EXAMPLES_FROM_OLD_UI = [
    # The prepopulated examples shown in the old ZOOMA UI
    ("organism", "mus musculus"),
    ("disease", "lung adenocarcinoma"),
    ("", "Homo sapiens"),
    ("", "cancer"),
    ("", "diabetes"),
    ("organism", "Drosophila melanogaster"),
]


# Expected TSV column headers from old ZOOMA map endpoint
EXPECTED_TSV_COLUMNS = [
    "PROPERTY TYPE", "PROPERTY VALUE", "ONTOLOGY TERM LABEL(S)",
    "ONTOLOGY TERM SYNONYM(S)", "CONFIDENCE", "ONTOLOGY TERM(S)",
    "ONTOLOGY(S)", "SOURCE(S)", "STUDY",
]


def test_async_map_flow(base_url, label):
    """Test the full async POST /services/map flow against a single server.

    Returns True if the flow works correctly:
    1. POST submits job → text response + JSESSIONID cookie
    2. GET /status → returns progress float (eventually 1.0)
    3. GET /map with Accept: text/plain → returns TSV results
    """
    global pass_count, fail_count, warn_count

    print(f"\n{BOLD}{'━' * 60}{RESET}")
    print(f"{BOLD}  Async map flow — {label}{RESET}")
    print(f"{BOLD}{'━' * 60}{RESET}")

    # Step 1: POST submit
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

    body = json.dumps([{"propertyType": "organism", "propertyValue": "mus musculus"}]).encode("utf-8")
    req = urllib.request.Request(base_url + "/services/map", data=body, method="POST")
    req.add_header("Content-Type", "application/json")

    try:
        resp = opener.open(req, timeout=30)
        post_text = resp.read().decode("utf-8")
    except Exception as e:
        print(f"  {RED}✗ POST /services/map failed: {e}{RESET}")
        fail_count += 1
        return False

    if "received" not in post_text.lower():
        print(f"  {RED}✗ POST response doesn't contain 'received': {post_text[:100]}{RESET}")
        fail_count += 1
        return False

    # Check JSESSIONID cookie
    cookies = {c.name: c.value for c in cj}
    if "JSESSIONID" not in cookies:
        print(f"  {RED}✗ No JSESSIONID cookie set{RESET}")
        fail_count += 1
        return False
    print(f"  {GREEN}✓ POST accepted, JSESSIONID cookie set{RESET}")

    # Step 2: Poll status until 1.0 (max 60s)
    status_url = base_url + "/services/map/status"
    deadline = time.time() + 60
    last_progress = -1.0

    while time.time() < deadline:
        req = urllib.request.Request(status_url)
        try:
            resp = opener.open(req, timeout=10)
            status_text = resp.read().decode("utf-8").strip()
            progress = float(status_text)
        except Exception as e:
            print(f"  {RED}✗ GET /status failed: {e}{RESET}")
            fail_count += 1
            return False

        if progress != last_progress:
            print(f"  ... progress: {progress}")
            last_progress = progress

        if progress >= 1.0:
            break
        time.sleep(0.5)
    else:
        print(f"  {RED}✗ Job did not complete within 60s (last progress: {last_progress}){RESET}")
        fail_count += 1
        return False

    print(f"  {GREEN}✓ Status reached 1.0{RESET}")

    # Step 3: GET results as text/plain
    req = urllib.request.Request(base_url + "/services/map")
    req.add_header("Accept", "text/plain")
    try:
        resp = opener.open(req, timeout=30)
        tsv_text = resp.read().decode("utf-8")
    except Exception as e:
        print(f"  {RED}✗ GET /services/map (text/plain) failed: {e}{RESET}")
        fail_count += 1
        return False

    if not tsv_text.strip():
        print(f"  {RED}✗ TSV response is empty{RESET}")
        fail_count += 1
        return False

    # Validate TSV structure
    lines = tsv_text.strip().split("\n")

    # Header block
    has_header = any("Application Name:" in l for l in lines[:5])
    if has_header:
        print(f"  {GREEN}✓ TSV has Application Name header{RESET}")
    else:
        print(f"  {RED}✗ TSV missing Application Name header{RESET}")
        fail_count += 1

    # Find the column header line
    col_line = None
    col_idx = None
    for i, line in enumerate(lines):
        if line.startswith("PROPERTY TYPE\t"):
            col_line = line
            col_idx = i
            break

    if col_line is None:
        print(f"  {RED}✗ TSV missing column header line{RESET}")
        fail_count += 1
        return False

    cols = col_line.split("\t")
    if cols == EXPECTED_TSV_COLUMNS:
        print(f"  {GREEN}✓ TSV column headers match exactly{RESET}")
    else:
        print(f"  {RED}✗ TSV column mismatch:{RESET}")
        print(f"    Expected: {EXPECTED_TSV_COLUMNS}")
        print(f"    Got:      {cols}")
        fail_count += 1

    # Check data rows exist
    data_lines = [l for l in lines[col_idx + 1:] if l.strip()]
    if data_lines:
        print(f"  {GREEN}✓ {len(data_lines)} data row(s) returned{RESET}")
        # Print first row for inspection
        first_row = data_lines[0].split("\t")
        print(f"    First row cols: {len(first_row)}")
        for i, (h, v) in enumerate(zip(EXPECTED_TSV_COLUMNS, first_row)):
            print(f"      {h}: {v[:60] if v else '(empty)'}")
    else:
        print(f"  {YELLOW}⚠ No data rows in TSV (query may have returned no results){RESET}")
        warn_count += 1

    pass_count += 1
    return True


def compare_async_map_tsv(old_base, new_base):
    """Run async map on both servers and compare TSV column structure."""
    global pass_count, fail_count, warn_count

    print(f"\n{BOLD}{'━' * 60}{RESET}")
    print(f"{BOLD}  Async map TSV comparison (old vs new){RESET}")
    print(f"{BOLD}{'━' * 60}{RESET}")

    def get_tsv(base_url):
        cj = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
        body = json.dumps([{"propertyType": "organism", "propertyValue": "mus musculus"}]).encode("utf-8")
        req = urllib.request.Request(base_url + "/services/map", data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        opener.open(req, timeout=30)
        # Poll until done
        for _ in range(120):
            req = urllib.request.Request(base_url + "/services/map/status")
            resp = opener.open(req, timeout=10)
            if float(resp.read().decode("utf-8").strip()) >= 1.0:
                break
            time.sleep(0.5)
        req = urllib.request.Request(base_url + "/services/map")
        req.add_header("Accept", "text/plain")
        resp = opener.open(req, timeout=30)
        return resp.read().decode("utf-8")

    try:
        old_tsv = get_tsv(old_base)
    except Exception as e:
        print(f"  {RED}✗ Failed to get TSV from old API: {e}{RESET}")
        fail_count += 1
        return

    try:
        new_tsv = get_tsv(new_base)
    except Exception as e:
        print(f"  {RED}✗ Failed to get TSV from new API: {e}{RESET}")
        fail_count += 1
        return

    def parse_tsv_parts(tsv):
        lines = tsv.strip().split("\n")
        header_lines = []
        col_line = None
        data_lines = []
        for i, line in enumerate(lines):
            if line.startswith("PROPERTY TYPE\t"):
                col_line = line
                header_lines = lines[:i]
                data_lines = [l for l in lines[i+1:] if l.strip()]
                break
            header_lines.append(line)
        return header_lines, col_line, data_lines

    old_hdr, old_col, old_data = parse_tsv_parts(old_tsv)
    new_hdr, new_col, new_data = parse_tsv_parts(new_tsv)

    # Compare column headers
    if old_col == new_col:
        print(f"  {GREEN}✓ Column headers match{RESET}")
        pass_count += 1
    else:
        print(f"  {RED}✗ Column headers differ:{RESET}")
        print(f"    Old: {old_col}")
        print(f"    New: {new_col}")
        fail_count += 1

    # Compare header block structure
    old_hdr_keys = [l.split("\t")[0] for l in old_hdr if "\t" in l]
    new_hdr_keys = [l.split("\t")[0] for l in new_hdr if "\t" in l]
    if old_hdr_keys == new_hdr_keys:
        print(f"  {GREEN}✓ Header block keys match: {old_hdr_keys}{RESET}")
    else:
        print(f"  {YELLOW}⚠ Header keys differ: old={old_hdr_keys} new={new_hdr_keys}{RESET}")
        warn_count += 1

    # Compare data column count
    if old_data and new_data:
        old_ncols = len(old_data[0].split("\t"))
        new_ncols = len(new_data[0].split("\t"))
        if old_ncols == new_ncols:
            print(f"  {GREEN}✓ Data row column count matches: {old_ncols}{RESET}")
        else:
            print(f"  {RED}✗ Data row column count differs: old={old_ncols} new={new_ncols}{RESET}")
            fail_count += 1


def main():
    global pass_count, fail_count, warn_count

    parser = argparse.ArgumentParser(description="Compare old/new ZOOMA API responses")
    parser.add_argument("--new-base", default=NEW_BASE, help="Base URL of new ZOOMA2 v2 API")
    parser.add_argument("--old-base", default=OLD_BASE, help="Base URL of old ZOOMA v2 API")
    parser.add_argument("--save-responses", action="store_true", help="Save raw responses to files")
    args = parser.parse_args()

    old_base = args.old_base
    new_base = args.new_base

    print(f"{BOLD}╔══════════════════════════════════════════════════════════╗{RESET}")
    print(f"{BOLD}║  ZOOMA API Detailed Compatibility Comparison            ║{RESET}")
    print(f"{BOLD}║  OLD: {old_base:<50s}  ║{RESET}")
    print(f"{BOLD}║  NEW: {new_base:<50s}  ║{RESET}")
    print(f"{BOLD}╚══════════════════════════════════════════════════════════╝{RESET}")

    for tc in TEST_CASES:
        name = tc["name"]
        method = tc["method"]
        path = tc["path"]
        body = tc.get("body")

        if tc.get("skip_json_compare"):
            print(f"\n{BOLD}{'━' * 60}{RESET}")
            print(f"{BOLD}  {name}{RESET}")
            print(f"  {YELLOW}⊘ Skipped (async flow tested separately below){RESET}")
            continue

        old_url = old_base + path
        new_url = new_base + path

        old_data, old_raw = fetch_json(old_url, method, body)
        new_data, new_raw = fetch_json(new_url, method, body)

        if old_data is None and "not support POST" not in str(old_raw):
            print(f"\n{BOLD}{'━' * 60}{RESET}")
            print(f"{BOLD}  {name}{RESET}")
            print(f"  {RED}OLD API error: {old_raw}{RESET}")
            fail_count += 1
            continue

        if new_data is None:
            print(f"\n{BOLD}{'━' * 60}{RESET}")
            print(f"{BOLD}  {name}{RESET}")
            print(f"  {RED}NEW API error: {new_raw}{RESET}")
            fail_count += 1
            continue

        # For old POST /map returning plain text, handle gracefully
        if method == "POST" and old_data is None:
            print(f"\n{BOLD}{'━' * 60}{RESET}")
            print(f"{BOLD}  {name}{RESET}")
            print(f"  {YELLOW}⚠ Old API returned non-JSON for POST: {old_raw[:100]}{RESET}")
            # Still validate new returns valid JSON
            if new_data is not None:
                print(f"  {GREEN}✓ New API returns valid JSON{RESET}")
                pass_count += 1
            warn_count += 1
            continue

        if args.save_responses:
            safe_name = name.replace(" ", "_").replace("/", "_")
            with open(f"api-compat/old_{safe_name}.json", "w") as f:
                json.dump(old_data, f, indent=2)
            with open(f"api-compat/new_{safe_name}.json", "w") as f:
                json.dump(new_data, f, indent=2)

        compare_responses(name, old_data, new_data)

    # ── Async map flow tests ─────────────────────────────────────────────

    print(f"\n\n{BOLD}{'═' * 60}{RESET}")
    print(f"{BOLD}  ASYNC MAP FLOW TESTS{RESET}")
    print(f"{BOLD}{'═' * 60}{RESET}")

    test_async_map_flow(old_base, "old API")
    test_async_map_flow(new_base, "new API")
    compare_async_map_tsv(old_base, new_base)

    # ── Summary ──────────────────────────────────────────────────────────

    print(f"\n{BOLD}{'═' * 60}{RESET}")
    print(f"{BOLD}  SUMMARY{RESET}")
    print(f"{BOLD}{'═' * 60}{RESET}")
    print(f"  {GREEN}Passed:   {pass_count}{RESET}")
    if warn_count:
        print(f"  {YELLOW}Warnings: {warn_count}{RESET}")
    else:
        print(f"  Warnings: {warn_count}")
    if fail_count:
        print(f"  {RED}Failed:   {fail_count}{RESET}")
    else:
        print(f"  Failed:   {fail_count}")
    print()

    sys.exit(1 if fail_count > 0 else 0)


if __name__ == "__main__":
    main()
