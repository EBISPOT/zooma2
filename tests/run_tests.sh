#!/usr/bin/env bash
#
# run_tests.sh — Start zooma, call all API endpoints, compare output to expected.
#
# Usage:
#   ./tests/run_tests.sh                    # run all tests
#   ./tests/run_tests.sh old_zooma_examples # run a specific test
#
# Set UPDATE_EXPECTED=1 to overwrite expected output instead of comparing.
#   UPDATE_EXPECTED=1 ./tests/run_tests.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PORT="${ZOOMA2_TEST_PORT:-8090}"
BASE_URL="http://localhost:${PORT}"
ZOOMA_JAR="$ROOT_DIR/backend/target/zooma2-1.0-SNAPSHOT.jar"
UPDATE_EXPECTED="${UPDATE_EXPECTED:-0}"
FAILED=0

export ZOOMA2_OLS_URL="${ZOOMA2_OLS_URL:-https://www.ebi.ac.uk/ols4}"

# ---- helpers ---------------------------------------------------------------

log()  { echo "==> $*" >&2; }
fail() { echo "FAIL: $*" >&2; FAILED=1; }

wait_for_server() {
    local max_wait=120
    local waited=0
    log "Waiting for server on port $PORT (max ${max_wait}s)..."
    while ! curl -sf "$BASE_URL/v3/api/status" >/dev/null 2>&1; do
        sleep 2
        waited=$((waited + 2))
        if [ "$waited" -ge "$max_wait" ]; then
            echo "ERROR: Server did not start within ${max_wait}s" >&2
            if [ -n "${ZOOMA_PID:-}" ]; then
                kill "$ZOOMA_PID" 2>/dev/null || true
            fi
            exit 1
        fi
    done
    log "Server ready (waited ${waited}s)"
}

normalise_json() {
    # Sort keys for stable comparison, strip timestamps that change between runs
    python3 -c "
import json, sys
def strip_timestamps(obj):
    if isinstance(obj, dict):
        return {k: (0 if k in ('annotationDate','generatedDate') and isinstance(v,(int,float)) else strip_timestamps(v)) for k,v in obj.items()}
    elif isinstance(obj, list):
        return [strip_timestamps(i) for i in obj]
    return obj
obj = strip_timestamps(json.load(sys.stdin))
json.dump(obj, sys.stdout, sort_keys=True, indent=2, ensure_ascii=False)
print()
" 2>/dev/null || cat  # fallback to raw if python3 not available
}

compare_output() {
    local name="$1"
    local actual_file="$2"
    local expected_file="$3"

    if [ "$UPDATE_EXPECTED" = "1" ]; then
        mkdir -p "$(dirname "$expected_file")"
        cp "$actual_file" "$expected_file"
        log "Updated expected: $expected_file"
        return 0
    fi

    if [ ! -f "$expected_file" ]; then
        fail "$name: expected output file missing: $expected_file"
        log "  Run with UPDATE_EXPECTED=1 to create it"
        return 0
    fi

    if ! diff -u "$expected_file" "$actual_file" > /dev/null 2>&1; then
        fail "$name: output differs from expected"
        local diff_file
        diff_file="$(mktemp)"
        diff -u "$expected_file" "$actual_file" > "$diff_file" || true
        head -60 "$diff_file" >&2
        rm -f "$diff_file"
        log "  Run with UPDATE_EXPECTED=1 to accept new output"
        return 0
    fi
    return 0
}

# ---- build if needed -------------------------------------------------------

if [ ! -f "$ZOOMA_JAR" ]; then
    log "Building backend..."
    (cd "$ROOT_DIR" && mvn package -pl backend -q -DskipTests)
fi

# ---- discover tests --------------------------------------------------------

if [ $# -gt 0 ]; then
    TEST_DIRS=()
    for t in "$@"; do
        TEST_DIRS+=("$SCRIPT_DIR/$t")
    done
else
    TEST_DIRS=("$SCRIPT_DIR"/*/  )
fi

# ---- start server -----------------------------------------------------------

# Check if a server is already running
if curl -sf "$BASE_URL/v3/api/status" >/dev/null 2>&1; then
    log "Server already running on port $PORT, using it"
    ZOOMA_PID=""
else
    log "Starting zooma server..."

    # Use project root as cwd so data/ and config.json are found
    cd "$ROOT_DIR"

    # Set DB and data path from the first test dir that has them
    for td in "${TEST_DIRS[@]}"; do
        td="${td%/}"
        if [ -f "$td/zooma.db.gz" ] && [ ! -f "$td/zooma.db" ]; then
            log "Decompressing $td/zooma.db.gz..."
            gunzip -k "$td/zooma.db.gz"
        fi
        if [ -f "$td/zooma.db" ]; then
            export ZOOMA2_DB_URL="jdbc:sqlite:$td/zooma.db"
            log "Using test database: $td/zooma.db"
        fi
        if [ -d "$td/data" ]; then
            export ZOOMA2_DATA_PATH="$td/data"
            log "Using test data: $td/data"
        fi
        break
    done

    ZOOMA2_PORT="$PORT" java -jar "$ZOOMA_JAR" &
    ZOOMA_PID=$!

    # Ensure cleanup on exit
    cleanup() {
        if [ -n "${ZOOMA_PID:-}" ]; then
            kill "$ZOOMA_PID" 2>/dev/null || true
            wait "$ZOOMA_PID" 2>/dev/null || true
        fi
    }
    trap cleanup EXIT

    wait_for_server
fi

# ---- run tests --------------------------------------------------------------

for TEST_DIR in "${TEST_DIRS[@]}"; do
    TEST_DIR="${TEST_DIR%/}"
    TEST_NAME="$(basename "$TEST_DIR")"
    INPUT_FILE="$TEST_DIR/text_to_map.tsv"
    OUTPUT_DIR="$TEST_DIR/output"
    ACTUAL_DIR="$(mktemp -d)"

    if [ ! -f "$INPUT_FILE" ]; then
        log "Skipping $TEST_NAME (no text_to_map.tsv)"
        continue
    fi

    log "Running test: $TEST_NAME"

    # ---- 1. Test GET endpoints ----

    # v2/api/sources
    curl -sf "$BASE_URL/v2/api/sources" | normalise_json > "$ACTUAL_DIR/v2_sources.json"
    compare_output "$TEST_NAME/v2_sources" "$ACTUAL_DIR/v2_sources.json" "$OUTPUT_DIR/v2_sources.json"

    # v2/api/properties/types
    curl -sf "$BASE_URL/v2/api/properties/types" | normalise_json > "$ACTUAL_DIR/v2_property_types.json"
    compare_output "$TEST_NAME/v2_property_types" "$ACTUAL_DIR/v2_property_types.json" "$OUTPUT_DIR/v2_property_types.json"

    # v3/api/sources
    curl -sf "$BASE_URL/v3/api/sources" | normalise_json > "$ACTUAL_DIR/v3_sources.json"
    compare_output "$TEST_NAME/v3_sources" "$ACTUAL_DIR/v3_sources.json" "$OUTPUT_DIR/v3_sources.json"

    # v3/api/properties/types
    curl -sf "$BASE_URL/v3/api/properties/types" | normalise_json > "$ACTUAL_DIR/v3_property_types.json"
    compare_output "$TEST_NAME/v3_property_types" "$ACTUAL_DIR/v3_property_types.json" "$OUTPUT_DIR/v3_property_types.json"

    # v3/api/status
    curl -sf "$BASE_URL/v3/api/status" | normalise_json > "$ACTUAL_DIR/v3_status.json"
    compare_output "$TEST_NAME/v3_status" "$ACTUAL_DIR/v3_status.json" "$OUTPUT_DIR/v3_status.json"

    # v3/api/models
    curl -sf "$BASE_URL/v3/api/models" | normalise_json > "$ACTUAL_DIR/v3_models.json"
    compare_output "$TEST_NAME/v3_models" "$ACTUAL_DIR/v3_models.json" "$OUTPUT_DIR/v3_models.json"

    # ---- 2. Test mapping endpoints (per row in TSV) ----

    mkdir -p "$ACTUAL_DIR/v2_annotate" "$ACTUAL_DIR/v3_map"

    # Read TSV, skip header
    line_num=0
    while IFS=$'\t' read -r prop_value prop_type || [ -n "$prop_value" ]; do
        line_num=$((line_num + 1))
        [ "$line_num" -eq 1 ] && continue  # skip header
        [ -z "$prop_value" ] && continue

        # Sanitise filename
        safe_name="$(echo "$prop_value" | sed 's/[^a-zA-Z0-9_-]/_/g' | head -c 80)"
        idx=$((line_num - 1))
        fname="${idx}_${safe_name}"

        # -- v2 annotate --
        v2_url="$BASE_URL/v2/api/services/annotate?propertyValue=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$prop_value'))")"
        if [ -n "$prop_type" ]; then
            v2_url="${v2_url}&propertyType=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$prop_type'))")"
        fi
        curl -sf "$v2_url" | normalise_json > "$ACTUAL_DIR/v2_annotate/${fname}.json" || true
        compare_output "$TEST_NAME/v2_annotate/$fname" \
            "$ACTUAL_DIR/v2_annotate/${fname}.json" \
            "$OUTPUT_DIR/v2_annotate/${fname}.json"

        # -- v3 map (single property) --
        if [ -n "$prop_type" ]; then
            v3_body="{\"properties\":[{\"textToMap\":$(python3 -c "import json; print(json.dumps('$prop_value'))"),\"propertyType\":$(python3 -c "import json; print(json.dumps('$prop_type'))")}]}"
        else
            v3_body="{\"properties\":[{\"textToMap\":$(python3 -c "import json; print(json.dumps('$prop_value'))")}]}"
        fi
        curl -sf -X POST "$BASE_URL/v3/api/services/map" \
            -H "Content-Type: application/json" \
            -d "$v3_body" | normalise_json > "$ACTUAL_DIR/v3_map/${fname}.json" || true
        compare_output "$TEST_NAME/v3_map/$fname" \
            "$ACTUAL_DIR/v3_map/${fname}.json" \
            "$OUTPUT_DIR/v3_map/${fname}.json"

    done < "$INPUT_FILE"

    # ---- 2b. Test ontology-filtered mapping (defining_only, issue #5) ----
    # ECTO ships its CHEBI import closure: the default ontology filter keeps
    # imported terms, defining_only:[true] restricts to ECTO's own namespace.

    mkdir -p "$ACTUAL_DIR/v2_annotate_filtered" "$ACTUAL_DIR/v3_map_filtered"

    v2_ecto_url="$BASE_URL/v2/api/services/annotate?propertyValue=vasopressin&filter=required:%5Bnone%5D,ontologies:%5Becto%5D"
    curl -sf "$v2_ecto_url" | normalise_json > "$ACTUAL_DIR/v2_annotate_filtered/vasopressin_ecto.json"
    compare_output "$TEST_NAME/v2_annotate_filtered/vasopressin_ecto" \
        "$ACTUAL_DIR/v2_annotate_filtered/vasopressin_ecto.json" \
        "$OUTPUT_DIR/v2_annotate_filtered/vasopressin_ecto.json"

    curl -sf "${v2_ecto_url},defining_only:%5Btrue%5D" | normalise_json > "$ACTUAL_DIR/v2_annotate_filtered/vasopressin_ecto_defining_only.json"
    compare_output "$TEST_NAME/v2_annotate_filtered/vasopressin_ecto_defining_only" \
        "$ACTUAL_DIR/v2_annotate_filtered/vasopressin_ecto_defining_only.json" \
        "$OUTPUT_DIR/v2_annotate_filtered/vasopressin_ecto_defining_only.json"

    v3_ecto_base='{"properties":[{"textToMap":"vasopressin"}],"targetOntologies":["ecto"],"includeOtherOntologies":false'
    curl -sf -X POST "$BASE_URL/v3/api/services/map" \
        -H "Content-Type: application/json" \
        -d "${v3_ecto_base}}" | normalise_json > "$ACTUAL_DIR/v3_map_filtered/vasopressin_ecto.json"
    compare_output "$TEST_NAME/v3_map_filtered/vasopressin_ecto" \
        "$ACTUAL_DIR/v3_map_filtered/vasopressin_ecto.json" \
        "$OUTPUT_DIR/v3_map_filtered/vasopressin_ecto.json"

    curl -sf -X POST "$BASE_URL/v3/api/services/map" \
        -H "Content-Type: application/json" \
        -d "${v3_ecto_base},\"definingOnly\":true}" | normalise_json > "$ACTUAL_DIR/v3_map_filtered/vasopressin_ecto_defining_only.json"
    compare_output "$TEST_NAME/v3_map_filtered/vasopressin_ecto_defining_only" \
        "$ACTUAL_DIR/v3_map_filtered/vasopressin_ecto_defining_only.json" \
        "$OUTPUT_DIR/v3_map_filtered/vasopressin_ecto_defining_only.json"

    # Case-insensitivity (issue #7): a capitalised query must find the same
    # lowercase-labelled ECTO term as the lowercase query.
    for cisplatin_case in cisplatin Cisplatin; do
        curl -sf "$BASE_URL/v2/api/services/annotate?propertyValue=${cisplatin_case}&filter=required:%5Bnone%5D,ontologies:%5Becto%5D,defining_only:%5Btrue%5D" \
            | normalise_json > "$ACTUAL_DIR/v2_annotate_filtered/${cisplatin_case}_ecto_defining_only.json"
        compare_output "$TEST_NAME/v2_annotate_filtered/${cisplatin_case}_ecto_defining_only" \
            "$ACTUAL_DIR/v2_annotate_filtered/${cisplatin_case}_ecto_defining_only.json" \
            "$OUTPUT_DIR/v2_annotate_filtered/${cisplatin_case}_ecto_defining_only.json"
    done

    # Legacy sentinel (issue #16): ontologies:[none] means "no ontology
    # restriction", not a literal ontology called "none" (which returned []).
    curl -sf "$BASE_URL/v2/api/services/annotate?propertyValue=diabetes&filter=required:%5Batlas%5D,ontologies:%5Bnone%5D" \
        | normalise_json > "$ACTUAL_DIR/v2_annotate_filtered/diabetes_atlas_none.json"
    compare_output "$TEST_NAME/v2_annotate_filtered/diabetes_atlas_none" \
        "$ACTUAL_DIR/v2_annotate_filtered/diabetes_atlas_none.json" \
        "$OUTPUT_DIR/v2_annotate_filtered/diabetes_atlas_none.json"

    # ---- 2d. Term-id normalisation (issue #12) ----
    # A term belongs to the ontology whose file it was found in AND to the
    # ontology that defines its id namespace: an NCBITaxon term surfaced through
    # EFO must pass an ncbitaxon filter.
    curl -sf "$BASE_URL/v2/api/services/annotate?propertyValue=rat&propertyType=organism&filter=required:%5Bnone%5D,ontologies:%5Bncbitaxon%5D" \
        | normalise_json > "$ACTUAL_DIR/v2_annotate_filtered/rat_ncbitaxon.json"
    compare_output "$TEST_NAME/v2_annotate_filtered/rat_ncbitaxon" \
        "$ACTUAL_DIR/v2_annotate_filtered/rat_ncbitaxon.json" \
        "$OUTPUT_DIR/v2_annotate_filtered/rat_ncbitaxon.json"
    # Multi-underscore short forms (EDAM_data_0849) must be recognised as EDAM's
    # own namespace under defining_only.
    curl -sf "$BASE_URL/v2/api/services/annotate?propertyValue=Sequence%20record&filter=required:%5Bnone%5D,ontologies:%5Bedam%5D,defining_only:%5Btrue%5D" \
        | normalise_json > "$ACTUAL_DIR/v2_annotate_filtered/sequence_record_edam_defining_only.json"
    compare_output "$TEST_NAME/v2_annotate_filtered/sequence_record_edam_defining_only" \
        "$ACTUAL_DIR/v2_annotate_filtered/sequence_record_edam_defining_only.json" \
        "$OUTPUT_DIR/v2_annotate_filtered/sequence_record_edam_defining_only.json"

    # ---- 2e. "Try again" through the tagger short-circuit (issue #14) ----
    # cisplatin's only full match in CHEBI is CHEBI_27899; excluding it must not
    # short-circuit the search to an empty answer but return alternatives.
    curl -sf -X POST "$BASE_URL/v3/api/services/map" \
        -H "Content-Type: application/json" \
        -d '{"properties":[{"textToMap":"cisplatin"}],"targetOntologies":["chebi"],"includeOtherOntologies":false,"excludeTermIds":["CHEBI_27899"]}' \
        | normalise_json > "$ACTUAL_DIR/v3_map_filtered/cisplatin_chebi_exclude_top.json"
    compare_output "$TEST_NAME/v3_map_filtered/cisplatin_chebi_exclude_top" \
        "$ACTUAL_DIR/v3_map_filtered/cisplatin_chebi_exclude_top.json" \
        "$OUTPUT_DIR/v3_map_filtered/cisplatin_chebi_exclude_top.json"

    # ---- 2c. Duplicate properties in one request (issue #9) ----
    # The same text under two property types shares one set of tagger annotations;
    # converting them must not cross-contaminate the groups, and identical properties
    # must each receive the single result exactly once (not a concatenated list).
    mkdir -p "$ACTUAL_DIR/v3_map_dedup"
    curl -sf -X POST "$BASE_URL/v3/api/services/map" \
        -H "Content-Type: application/json" \
        -d '{"properties":[{"textToMap":"yeast","propertyType":"organism"},{"textToMap":"yeast"},{"textToMap":"yeast"}]}' \
        | normalise_json > "$ACTUAL_DIR/v3_map_dedup/yeast_organism_and_untyped_twice.json"
    compare_output "$TEST_NAME/v3_map_dedup/yeast_organism_and_untyped_twice" \
        "$ACTUAL_DIR/v3_map_dedup/yeast_organism_and_untyped_twice.json" \
        "$OUTPUT_DIR/v3_map_dedup/yeast_organism_and_untyped_twice.json"

    # ---- 3. Test batch v3 map (all properties at once) ----

    # Build JSON array of all properties
    v3_batch_body=$(python3 -c "
import csv, json, sys
props = []
with open('$INPUT_FILE', newline='') as f:
    reader = csv.DictReader(f, delimiter='\t')
    for row in reader:
        p = {'textToMap': row['propertyValue']}
        pt = row.get('propertyType', '').strip()
        if pt:
            p['propertyType'] = pt
        props.append(p)
print(json.dumps({'properties': props}))
")

    curl -sf -X POST "$BASE_URL/v3/api/services/map" \
        -H "Content-Type: application/json" \
        -d "$v3_batch_body" | normalise_json > "$ACTUAL_DIR/v3_map_batch.json" || true
    compare_output "$TEST_NAME/v3_map_batch" \
        "$ACTUAL_DIR/v3_map_batch.json" \
        "$OUTPUT_DIR/v3_map_batch.json"

    # Clean up temp dir
    rm -rf "$ACTUAL_DIR"

    log "Test $TEST_NAME complete"
done

# ---- report -----------------------------------------------------------------

if [ "$UPDATE_EXPECTED" = "1" ]; then
    log "Expected outputs updated. Commit the changes to tests/*/output/"
    exit 0
fi

if [ "$FAILED" -eq 0 ]; then
    log "All tests passed!"
    exit 0
else
    log "Some tests failed. Run with UPDATE_EXPECTED=1 to accept new output."
    exit 1
fi
