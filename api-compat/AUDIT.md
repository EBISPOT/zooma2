# ZOOMA API v2 Compatibility Audit

## Summary

This document compares the **old ZOOMA** API (at `https://www.ebi.ac.uk/spot/zooma/v2/api`)
against the **new ZOOMA2** API (`/v2/api`) to identify structural incompatibilities
in the JSON response format.

**Goal:** API consumers must be able to switch from old to new without changing
any client code. Output *content* may differ (different engine), but the JSON
*structure* must be identical.

---

## Endpoints Compared

| Endpoint | Method | Old Returns | New Returns | Status |
|---|---|---|---|---|
| `/sources` | GET | JSON array | JSON array | **Compatible** (see notes) |
| `/properties/types` | GET | JSON array of strings | JSON array of strings | **Compatible** |
| `/services/annotate` | GET | JSON array of annotations | JSON array of annotations | **Fixed** — all missing fields added |
| `/services/map` | POST | Plain text ack + JSESSIONID | Plain text ack + JSESSIONID | **Compatible** |
| `/services/map/status` | GET | Progress float (0.0–1.0) | Progress float (0.0–1.0) | **Compatible** |
| `/services/map` | GET | TSV text (text/plain) | TSV text (text/plain) | **Compatible** |

---

## `/services/annotate` Response Format (FIXED)

### Old API response (per annotation object):

```json
{
  "uri": null,
  "annotatedProperty": {
    "uri": "http://rdf.ebi.ac.uk/resource/zooma/...",
    "propertyType": "organism",
    "propertyValue": "Mus musculus"
  },
  "_links": {
    "olslinks": [
      {
        "href": "https://www.ebi.ac.uk/ols4/api/terms?iri=...",
        "semanticTag": "http://purl.obolibrary.org/obo/NCBITaxon_10090"
      }
    ]
  },
  "semanticTags": ["http://purl.obolibrary.org/obo/NCBITaxon_10090"],
  "replacedBy": [],
  "replaces": [],
  "derivedFrom": {
    "uri": "http://rdf.ebi.ac.uk/resource/zooma/atlas/...",
    "annotatedProperty": { ... },
    "_links": { "olslinks": [...] },
    "semanticTags": [...],
    "replacedBy": [],
    "replaces": [],
    "annotatedBiologicalEntities": [
      {
        "uri": "...",
        "name": "...",
        "types": ["..."],
        "studies": [{"uri":"...", "accession":"...", "types":["..."]}]
      }
    ],
    "provenance": {
      "source": {"type":"DATABASE", "name":"atlas", "uri":"https://www.ebi.ac.uk/gxa"},
      "evidence": "MANUAL_CURATED",
      "accuracy": "NOT_SPECIFIED",
      "generator": "https://www.ebi.ac.uk/gxa",
      "generatedDate": 1772181129000,        ← integer (epoch ms)
      "annotator": "Laura Huerta",
      "annotationDate": -61656271050000      ← integer (epoch ms)
    }
  },
  "confidence": "HIGH",
  "annotatedBiologicalEntities": [],
  "provenance": {
    "source": {"type":"DATABASE", "name":"zooma", "uri":"www.ebi.ac.uk/spot/zooma"},
    "evidence": "ZOOMA_INFERRED_FROM_CURATED",
    "accuracy": null,
    "generator": "ZOOMA",
    "generatedDate": 1772752405855,          ← integer (epoch ms)
    "annotator": "ZOOMA",
    "annotationDate": 1772752405855           ← integer (epoch ms)
  }
}
```

### New API V2AnnotationDto (CURRENT — before fix):

```json
{
  "annotatedProperty": {
    "uri": null,
    "propertyType": "organism",
    "propertyValue": "mus musculus"
  },
  "semanticTags": ["http://purl.obolibrary.org/obo/NCBITaxon_10090"],
  "confidence": "HIGH",
  "provenance": {
    "source": {"type":"DATABASE", "name":"atlas", "uri":"..."},
    "evidence": "...",
    "accuracy": "...",
    "generator": "ZOOMA",
    "generatedDate": "...",         ← STRING (should be integer)
    "annotator": "ZOOMA",
    "annotationDate": "..."         ← STRING (should be integer)
  }
}
```

### Missing Fields (MUST FIX)

| Field | Type in Old API | Required? | Notes |
|---|---|---|---|
| `uri` | `null` (always) | YES | Always null in old API, but field must exist |
| `_links` | object | YES | Contains `olslinks` array for OLS term lookups |
| `replacedBy` | `[]` (always empty) | YES | Always empty array |
| `replaces` | `[]` (always empty) | YES | Always empty array |
| `derivedFrom` | object or null | YES | Nested annotation with source provenance |
| `annotatedBiologicalEntities` | `[]` (always empty at top level) | YES | Always empty array at top level |
| `provenance.generatedDate` | integer (epoch ms) | TYPE FIX | Currently String, must be Long |
| `provenance.annotationDate` | integer (epoch ms) | TYPE FIX | Currently String, must be Long |

### Extra Fields in New API (OK — additive changes are fine)

| Field | Notes |
|---|---|
| `mappingProvenance` | V3 provenance chain — not in old API but harmless |

---

## `/services/map` POST Endpoint — Full Async Flow

The old ZOOMA `/services/map` uses a **session-based async flow** with three
endpoints and a `JSESSIONID` cookie:

### Step 1: Submit — `POST /services/map`

- **Request:** JSON array of `{propertyType, propertyValue}` objects
- **Response:** `text/plain` — `"Mapping request of N properties was successfully received"`
- **Sets cookie:** `JSESSIONID=<session-id>; Path=/spot/zooma/; HttpOnly`
- The server starts processing asynchronously in the background

### Step 2: Poll — `GET /services/map/status`

- **Requires:** `JSESSIONID` cookie from Step 1
- **Response:** `text/plain` — a float from `0.0` to `1.0`
- Client polls until value reaches `1.0`

### Step 3: Retrieve — `GET /services/map`

- **Requires:** `JSESSIONID` cookie from Step 1
- **Request header:** `Accept: text/plain` (requesting JSON returns 406)
- **Response:** `text/plain` — TSV format with header block + column headers + data rows

#### TSV Format (exact):

```
Application Name:\tZOOMA (Automatic Ontology Mapper)
Version:\t2.0
Run at:\tHH:mm.ss, dd.MM.yy
Run from:\thttp://www.ebi.ac.uk/fgpt/zooma
(empty line)
(empty line)
PROPERTY TYPE\tPROPERTY VALUE\tONTOLOGY TERM LABEL(S)\tONTOLOGY TERM SYNONYM(S)\tCONFIDENCE\tONTOLOGY TERM(S)\tONTOLOGY(S)\tSOURCE(S)\tSTUDY
organism\tmus musculus\tMus musculus\t\tHigh\tNCBITaxon_10090\thttp://purl.obolibrary.org/obo/\thttps://www.ebi.ac.uk/gxa\t[UNKNOWN EXPERIMENTS]
```

- Confidence in TSV uses title case: "High", "Good", "Medium", "Low"
- `Accept: application/json` returns HTTP 406

### New API Implementation

The new ZOOMA2 v2 API replicates this flow exactly:
- `ConcurrentHashMap<String, MapJob>` keyed by `JSESSIONID` cookie value
- Virtual threads for async execution
- Identical text responses, cookie handling, TSV format
- Jobs evicted after 30 minutes

**Assessment:** **Compatible.** ✓

---

## `/sources` Endpoint

### Old Format:
```json
[
  {"type": "DATABASE", "name": "GWAS", "uri": "http://www.ebi.ac.uk/gwas"},
  {"type": "ONTOLOGY", "name": "APO", "title": "Ascomycete...", "description": "...", "uri": "apo"}
]
```

### New Format:
Same structure — DATABASE entries have `type`, `name`, `uri`; ONTOLOGY entries also have `title` and `description`.

**Assessment:** **Compatible.** ✓

---

## `/properties/types` Endpoint

Both return a JSON array of strings.

**Assessment:** **Compatible.** ✓

---

## Code Changes Made

### 1. V2AnnotationDto.java — Added missing fields ✓

All missing fields have been added to match the old API format:
- `uri` (always null)
- `_links` (with `olslinks` array pointing to OLS4)
- `replacedBy` (empty list)
- `replaces` (empty list)
- `derivedFrom` (nested annotation from `sourceAnnotation`)
- `annotatedBiologicalEntities` (empty list)

### 2. V2AnnotationDto.Provenance — Fixed date types ✓

Changed `generatedDate` and `annotationDate` from `String` to `Long` (epoch ms).
Added `parseDateToEpochMs()` helper to parse `Date.toString()` format.

### 3. ZoomaApiV2.java — Full async map flow ✓

Implemented session-based async mapping:
- `POST /services/map` → create `MapJob`, start virtual thread, return text ack + `JSESSIONID`
- `GET /services/map/status` → return progress float from job
- `GET /services/map` → return TSV text (406 for JSON Accept)
- 30-minute eviction of old jobs

---

## Test Scripts

- `api-compat/compare_apis.sh` — Bash script for quick structural comparison
- `api-compat/detailed_compare.py` — Python script for detailed field-by-field comparison

Usage:
```bash
# Compare old API against itself (sanity check — should pass)
python3 api-compat/detailed_compare.py --old-base https://www.ebi.ac.uk/spot/zooma/v2/api --new-base https://www.ebi.ac.uk/spot/zooma/v2/api

# Compare old vs new (local server must be running on port 8090)
python3 api-compat/detailed_compare.py --new-base http://localhost:8090/v2/api
```
