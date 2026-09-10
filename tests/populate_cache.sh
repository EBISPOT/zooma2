#!/usr/bin/env bash
#
# populate_cache.sh — Run zooma with live external services to populate the
# external_api_cache and embeddings tables, then copy the database for testing.
#
# This produces a zooma.db in each test directory that contains all the cached
# HTTP responses + embeddings needed to run that test offline.
#
# After running this, also run:
#   UPDATE_EXPECTED=1 ./tests/run_tests.sh
# to capture the expected output JSON files.
#
# Usage:
#   ./tests/populate_cache.sh                    # populate for all tests
#   ./tests/populate_cache.sh old_zooma_examples # populate for one test
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PORT="${ZOOMA2_TEST_PORT:-8090}"
BASE_URL="http://localhost:${PORT}"
ZOOMA_JAR="$ROOT_DIR/backend/target/zooma2-1.0-SNAPSHOT.jar"

log() { echo "==> $*" >&2; }

# Build if needed
if [ ! -f "$ZOOMA_JAR" ]; then
    log "Building backend..."
    (cd "$ROOT_DIR" && mvn package -pl backend -q -DskipTests)
fi

# Determine tests
if [ $# -gt 0 ]; then
    TEST_DIRS=()
    for t in "$@"; do TEST_DIRS+=("$SCRIPT_DIR/$t"); done
else
    TEST_DIRS=("$SCRIPT_DIR"/*/)
fi

# Use a temporary zooma.db for this run
CACHE_DB="$(mktemp -d)/zooma.db"
export ZOOMA2_DB_URL="jdbc:sqlite:$CACHE_DB"
log "Populating cache into: $CACHE_DB"

# Kill any existing server on the port
if lsof -i ":$PORT" -t >/dev/null 2>&1; then
    log "Killing existing process on port $PORT"
    lsof -i ":$PORT" -t | xargs kill 2>/dev/null || true
    sleep 2
fi

# Start server with live services
cd "$ROOT_DIR"

# Use test-specific data/ if it exists (first test dir wins)
for td in "${TEST_DIRS[@]}"; do
    td="${td%/}"
    if [ -d "$td/data" ]; then
        export ZOOMA2_DATA_PATH="$td/data"
        log "Using test data: $td/data"
        break
    fi
done

java -jar "$ZOOMA_JAR" &
ZOOMA_PID=$!

cleanup() {
    if [ -n "${ZOOMA_PID:-}" ]; then
        kill "$ZOOMA_PID" 2>/dev/null || true
        wait "$ZOOMA_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# Wait for server
max_wait=180
waited=0
log "Waiting for server (max ${max_wait}s)..."
while ! curl -sf "$BASE_URL/v3/api/status" >/dev/null 2>&1; do
    sleep 3
    waited=$((waited + 3))
    if [ "$waited" -ge "$max_wait" ]; then
        echo "ERROR: Server did not start within ${max_wait}s" >&2
        exit 1
    fi
done
log "Server ready (waited ${waited}s)"

# For each test, run all the queries to populate the cache
for TEST_DIR in "${TEST_DIRS[@]}"; do
    TEST_DIR="${TEST_DIR%/}"
    TEST_NAME="$(basename "$TEST_DIR")"
    INPUT_FILE="$TEST_DIR/text_to_map.tsv"

    if [ ! -f "$INPUT_FILE" ]; then
        log "Skipping $TEST_NAME (no text_to_map.tsv)"
        continue
    fi

    log "Populating cache for: $TEST_NAME"

    # Hit GET endpoints
    curl -sf "$BASE_URL/v2/api/sources" > /dev/null
    curl -sf "$BASE_URL/v2/api/properties/types" > /dev/null
    curl -sf "$BASE_URL/v3/api/sources" > /dev/null
    curl -sf "$BASE_URL/v3/api/properties/types" > /dev/null
    curl -sf "$BASE_URL/v3/api/status" > /dev/null
    curl -sf "$BASE_URL/v3/api/models" > /dev/null

    # Hit per-row endpoints
    line_num=0
    while IFS=$'\t' read -r prop_value prop_type || [ -n "$prop_value" ]; do
        line_num=$((line_num + 1))
        [ "$line_num" -eq 1 ] && continue
        [ -z "$prop_value" ] && continue

        log "  [$line_num] $prop_value ($prop_type)"

        # v2 annotate
        v2_url="$BASE_URL/v2/api/services/annotate?propertyValue=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$prop_value'))")"
        if [ -n "$prop_type" ]; then
            v2_url="${v2_url}&propertyType=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$prop_type'))")"
        fi
        curl -sf "$v2_url" > /dev/null || true

        # v3 map
        if [ -n "$prop_type" ]; then
            v3_body="{\"properties\":[{\"textToMap\":$(python3 -c "import json; print(json.dumps('$prop_value'))"),\"propertyType\":$(python3 -c "import json; print(json.dumps('$prop_type'))")}]}"
        else
            v3_body="{\"properties\":[{\"textToMap\":$(python3 -c "import json; print(json.dumps('$prop_value'))")}]}"
        fi
        curl -sf -X POST "$BASE_URL/v3/api/services/map" \
            -H "Content-Type: application/json" \
            -d "$v3_body" > /dev/null || true

    done < "$INPUT_FILE"

    # Ontology-filtered cases (defining_only, issue #5)
    v2_ecto_url="$BASE_URL/v2/api/services/annotate?propertyValue=vasopressin&filter=required:%5Bnone%5D,ontologies:%5Becto%5D"
    curl -sf "$v2_ecto_url" > /dev/null || true
    curl -sf "${v2_ecto_url},defining_only:%5Btrue%5D" > /dev/null || true
    v3_ecto_base='{"properties":[{"textToMap":"vasopressin"}],"targetOntologies":["ecto"],"includeOtherOntologies":false'
    curl -sf -X POST "$BASE_URL/v3/api/services/map" -H "Content-Type: application/json" \
        -d "${v3_ecto_base}}" > /dev/null || true
    curl -sf -X POST "$BASE_URL/v3/api/services/map" -H "Content-Type: application/json" \
        -d "${v3_ecto_base},\"definingOnly\":true}" > /dev/null || true
    for cisplatin_case in cisplatin Cisplatin; do
        curl -sf "$BASE_URL/v2/api/services/annotate?propertyValue=${cisplatin_case}&filter=required:%5Bnone%5D,ontologies:%5Becto%5D,defining_only:%5Btrue%5D" > /dev/null || true
    done
    # Legacy ontologies:[none] sentinel (issue #16)
    curl -sf "$BASE_URL/v2/api/services/annotate?propertyValue=diabetes&filter=required:%5Batlas%5D,ontologies:%5Bnone%5D" > /dev/null || true

    # Term-id normalisation cases (issue #12)
    curl -sf "$BASE_URL/v2/api/services/annotate?propertyValue=rat&propertyType=organism&filter=required:%5Bnone%5D,ontologies:%5Bncbitaxon%5D" > /dev/null || true
    curl -sf "$BASE_URL/v2/api/services/annotate?propertyValue=Sequence%20record&filter=required:%5Bnone%5D,ontologies:%5Bedam%5D,defining_only:%5Btrue%5D" > /dev/null || true

    # "Try again" through the tagger short-circuit (issue #14)
    curl -sf -X POST "$BASE_URL/v3/api/services/map" -H "Content-Type: application/json" \
        -d '{"properties":[{"textToMap":"cisplatin"}],"targetOntologies":["chebi"],"includeOtherOntologies":false,"excludeTermIds":["CHEBI_27899"]}' > /dev/null || true

    # Duplicate properties in one request (issue #9)
    curl -sf -X POST "$BASE_URL/v3/api/services/map" -H "Content-Type: application/json" \
        -d '{"properties":[{"textToMap":"yeast","propertyType":"organism"},{"textToMap":"yeast"},{"textToMap":"yeast"}]}' > /dev/null || true

    # Batch v3 map
    v3_batch_body=$(python3 -c "
import csv, json
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
        -d "$v3_batch_body" > /dev/null || true

    # Copy the populated database to the test directory
    cp "$CACHE_DB" "$TEST_DIR/zooma.db"
    log "Saved cache: $TEST_DIR/zooma.db"
done

log "Cache population complete!"
log "Now run: UPDATE_EXPECTED=1 ./tests/run_tests.sh"
