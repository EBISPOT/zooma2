#!/usr/bin/env bash
#
# compare_apis.sh — Hit both old ZOOMA and new ZOOMA2 APIs with identical
# requests and compare the JSON response *structure* (field names, types,
# nesting).  Content differences are expected and ignored.
#
# Usage:
#   ./compare_apis.sh [NEW_BASE_URL]
#
# Defaults:
#   OLD = https://www.ebi.ac.uk/spot/zooma/v2/api
#   NEW = http://localhost:8090/v2/api   (override with $1)
#

set -euo pipefail

OLD_BASE="https://www.ebi.ac.uk/spot/zooma/v2/api"
NEW_BASE="${1:-http://localhost:8090/v2/api}"

PASS=0
FAIL=0
WARN=0

green()  { printf '\033[32m%s\033[0m\n' "$*"; }
red()    { printf '\033[31m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
bold()   { printf '\033[1m%s\033[0m\n' "$*"; }

# ── helpers ──────────────────────────────────────────────────────────────────

# Extract the "shape" of a JSON value: field names, types, nesting —
# but not concrete values.  Two responses with identical shapes are
# structurally compatible.
json_shape() {
    python3 - "$1" <<'PYEOF'
import json, sys

def shape(obj, path=""):
    """Return a sorted list of (dotted-key, type-name) pairs."""
    if isinstance(obj, dict):
        items = []
        for k in sorted(obj.keys()):
            child_path = f"{path}.{k}" if path else k
            items.append((child_path, type(obj[k]).__name__))
            items.extend(shape(obj[k], child_path))
        return items
    elif isinstance(obj, list):
        items = [(path, "list")]
        if obj:
            items.extend(shape(obj[0], path + "[]"))
        return items
    else:
        return []

data = json.loads(sys.argv[1])
# If top-level is a list, describe the shape of the first element
if isinstance(data, list) and data:
    pairs = [("(top)", "list")]
    pairs.extend(shape(data[0], "[]"))
elif isinstance(data, list):
    pairs = [("(top)", "list (empty)")]
else:
    pairs = shape(data)

for p, t in pairs:
    print(f"{p}\t{t}")
PYEOF
}

compare_shapes() {
    local label="$1" old_body="$2" new_body="$3"

    local old_shape new_shape
    old_shape=$(json_shape "$old_body" 2>/dev/null) || { red "  ✗ Could not parse OLD response as JSON"; FAIL=$((FAIL+1)); return; }
    new_shape=$(json_shape "$new_body" 2>/dev/null) || { red "  ✗ Could not parse NEW response as JSON"; FAIL=$((FAIL+1)); return; }

    # Fields in old but missing from new
    local missing
    missing=$(comm -23 <(echo "$old_shape" | sort) <(echo "$new_shape" | sort) || true)

    # Fields in new but not in old (additions — usually fine)
    local extra
    extra=$(comm -13 <(echo "$old_shape" | sort) <(echo "$new_shape" | sort) || true)

    if [ -z "$missing" ]; then
        green "  ✓ $label — all old fields present in new response"
        PASS=$((PASS+1))
    else
        red "  ✗ $label — MISSING fields (present in old, absent in new):"
        echo "$missing" | while IFS=$'\t' read -r key typ; do
            red "      - $key  ($typ)"
        done
        FAIL=$((FAIL+1))
    fi

    if [ -n "$extra" ]; then
        yellow "  ⚠ $label — EXTRA fields in new (not in old, probably fine):"
        echo "$extra" | while IFS=$'\t' read -r key typ; do
            yellow "      + $key  ($typ)"
        done
        WARN=$((WARN+1))
    fi
}

compare_type_compat() {
    local label="$1" old_body="$2" new_body="$3"

    python3 - "$old_body" "$new_body" "$label" <<'PYEOF'
import json, sys

def get_type_map(obj, path=""):
    """Return dict of dotted-path -> python type name."""
    result = {}
    if isinstance(obj, dict):
        for k in obj:
            child_path = f"{path}.{k}" if path else k
            result[child_path] = type(obj[k]).__name__
            result.update(get_type_map(obj[k], child_path))
    elif isinstance(obj, list):
        result[path] = "list"
        if obj:
            result.update(get_type_map(obj[0], path + "[]"))
    return result

old = json.loads(sys.argv[1])
new = json.loads(sys.argv[2])
label = sys.argv[3]

# Normalize to first element if list
if isinstance(old, list) and old: old = old[0]
if isinstance(new, list) and new: new = new[0]

old_types = get_type_map(old)
new_types = get_type_map(new)

mismatches = []
for key in sorted(set(old_types) & set(new_types)):
    ot = old_types[key]
    nt = new_types[key]
    # int vs float is usually fine for JSON numbers
    if {ot, nt} <= {"int", "float"}:
        continue
    if ot != nt:
        mismatches.append((key, ot, nt))

if mismatches:
    print(f"\033[31m  ✗ {label} — TYPE MISMATCHES:\033[0m")
    for key, ot, nt in mismatches:
        print(f"\033[31m      {key}: old={ot}  new={nt}\033[0m")
    sys.exit(1)
else:
    print(f"\033[32m  ✓ {label} — all shared fields have compatible types\033[0m")
PYEOF
    if [ $? -ne 0 ]; then
        FAIL=$((FAIL+1))
    else
        PASS=$((PASS+1))
    fi
}

# ── test runner ──────────────────────────────────────────────────────────────

run_get_test() {
    local name="$1" path="$2"
    bold "━━━ $name ━━━"
    local old_resp new_resp old_code new_code

    old_resp=$(curl -sf "${OLD_BASE}${path}" 2>/dev/null) || { red "  ✗ OLD API request failed: GET ${path}"; FAIL=$((FAIL+1)); return; }
    new_resp=$(curl -sf "${NEW_BASE}${path}" 2>/dev/null) || { red "  ✗ NEW API request failed: GET ${path}"; FAIL=$((FAIL+1)); return; }

    compare_shapes "structure" "$old_resp" "$new_resp"
    compare_type_compat "types" "$old_resp" "$new_resp"
    echo
}

run_post_test() {
    local name="$1" path="$2" body="$3" query="${4:-}"
    bold "━━━ $name ━━━"
    local url_old="${OLD_BASE}${path}${query}"
    local url_new="${NEW_BASE}${path}${query}"

    local old_resp new_resp
    old_resp=$(curl -sf -X POST -H 'Content-Type: application/json' -d "$body" "$url_old" 2>/dev/null)
    local old_rc=$?
    new_resp=$(curl -sf -X POST -H 'Content-Type: application/json' -d "$body" "$url_new" 2>/dev/null)
    local new_rc=$?

    if [ $old_rc -ne 0 ]; then
        yellow "  ⚠ OLD API POST request failed (rc=$old_rc) — old API may not support POST /map inline"
        # If old doesn't support it, check new at least returns valid JSON
        if [ $new_rc -eq 0 ]; then
            python3 -c "import json,sys; json.loads(sys.argv[1])" "$new_resp" 2>/dev/null \
                && green "  ✓ NEW API returns valid JSON" \
                || red "  ✗ NEW API does not return valid JSON"
        fi
        WARN=$((WARN+1))
        echo
        return
    fi

    if [ $new_rc -ne 0 ]; then
        red "  ✗ NEW API POST request failed"
        FAIL=$((FAIL+1))
        echo
        return
    fi

    compare_shapes "structure" "$old_resp" "$new_resp"
    compare_type_compat "types" "$old_resp" "$new_resp"
    echo
}

# ── tests ────────────────────────────────────────────────────────────────────

bold "╔══════════════════════════════════════════════════════════╗"
bold "║  ZOOMA API Compatibility Test Suite                     ║"
bold "║  OLD: $OLD_BASE"
bold "║  NEW: $NEW_BASE"
bold "╚══════════════════════════════════════════════════════════╝"
echo

# 1. GET /sources
run_get_test "GET /sources" "/sources"

# 2. GET /properties/types
run_get_test "GET /properties/types" "/properties/types"

# 3. GET /services/annotate — basic
run_get_test "GET /services/annotate (basic)" \
    "/services/annotate?propertyValue=mus+musculus"

# 4. GET /services/annotate — with propertyType
run_get_test "GET /services/annotate (with type)" \
    "/services/annotate?propertyValue=mus+musculus&propertyType=organism"

# 5. GET /services/annotate — with filter (required)
run_get_test "GET /services/annotate (filter=required)" \
    "/services/annotate?propertyValue=lung+adenocarcinoma&filter=required:%5Batlas,gwas%5D"

# 6. GET /services/annotate — with filter (required + preferred)
run_get_test "GET /services/annotate (filter=required+preferred)" \
    "/services/annotate?propertyValue=lung+adenocarcinoma&filter=required:%5Batlas,gwas%5D,preferred:%5Bgwas%5D"

# 7. GET /services/annotate — ontologies only
run_get_test "GET /services/annotate (ontologies only)" \
    "/services/annotate?propertyValue=mus+musculus&propertyType=organism&filter=required:%5Bnone%5D,ontologies:%5Befo%5D"

# 8. GET /services/annotate — no OLS fallback
run_get_test "GET /services/annotate (no OLS)" \
    "/services/annotate?propertyValue=ear+inflorescence&filter=required:%5Bsysmicro%5D,ontologies:%5Bnone%5D"

# 9. POST /services/map — async text ack (not JSON — tested separately)
bold "━━━ POST /services/map (text ack) ━━━"
# POST just returns text, not JSON - verify both return text ack
old_post=$(curl -sf -X POST -H 'Content-Type: application/json' \
    -d '[{"propertyType":"organism","propertyValue":"mus musculus"}]' \
    "${OLD_BASE}/services/map" 2>/dev/null) || true
new_post=$(curl -sf -X POST -H 'Content-Type: application/json' \
    -d '[{"propertyType":"organism","propertyValue":"mus musculus"}]' \
    "${NEW_BASE}/services/map" 2>/dev/null) || true

if echo "$old_post" | grep -qi "received"; then
    green "  ✓ Old API returns text acknowledgment"
    PASS=$((PASS+1))
else
    yellow "  ⚠ Old API POST /map response: ${old_post:0:80}"
    WARN=$((WARN+1))
fi

if echo "$new_post" | grep -qi "received"; then
    green "  ✓ New API returns text acknowledgment"
    PASS=$((PASS+1))
else
    red "  ✗ New API POST /map response: ${new_post:0:80}"
    FAIL=$((FAIL+1))
fi
echo

# 10. Async map full flow test (POST → status → results)
bold "━━━ Async map flow test ━━━"
# Test new API async flow
COOKIE_JAR=$(mktemp)
curl -s -X POST -H 'Content-Type: application/json' \
    -d '[{"propertyType":"organism","propertyValue":"mus musculus"}]' \
    -c "$COOKIE_JAR" \
    "${NEW_BASE}/services/map" > /dev/null 2>&1

# Poll status
for i in $(seq 1 30); do
    status=$(curl -s -b "$COOKIE_JAR" "${NEW_BASE}/services/map/status" 2>/dev/null || echo "error")
    if [ "$status" = "1.0" ]; then
        green "  ✓ New API status reached 1.0 after polling"
        PASS=$((PASS+1))
        break
    fi
    sleep 1
done

if [ "$status" != "1.0" ]; then
    red "  ✗ New API status did not reach 1.0 (got: $status)"
    FAIL=$((FAIL+1))
fi

# Get TSV results
tsv_result=$(curl -s -b "$COOKIE_JAR" -H 'Accept: text/plain' "${NEW_BASE}/services/map" 2>/dev/null)
if echo "$tsv_result" | grep -q "PROPERTY TYPE"; then
    green "  ✓ New API returns TSV with expected column headers"
    PASS=$((PASS+1))
else
    red "  ✗ New API TSV missing expected headers"
    FAIL=$((FAIL+1))
fi

# Verify JSON 406
json_code=$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" \
    -H 'Accept: application/json' "${NEW_BASE}/services/map" 2>/dev/null)
if [ "$json_code" = "406" ]; then
    green "  ✓ New API returns 406 for Accept: application/json on GET /map"
    PASS=$((PASS+1))
else
    red "  ✗ Expected 406 for JSON Accept, got: $json_code"
    FAIL=$((FAIL+1))
fi
rm -f "$COOKIE_JAR"
echo

# ── summary ──────────────────────────────────────────────────────────────────

echo
bold "═══════════════════════════════════════════════════════════"
bold "  Summary"
bold "═══════════════════════════════════════════════════════════"
green "  Passed:   $PASS"
[ $WARN -gt 0 ] && yellow "  Warnings: $WARN" || echo "  Warnings: $WARN"
[ $FAIL -gt 0 ] && red "  Failed:   $FAIL" || echo "  Failed:   $FAIL"
echo

exit $FAIL
