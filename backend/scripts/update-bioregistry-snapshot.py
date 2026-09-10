#!/usr/bin/env python3
"""Refresh the vendored Bioregistry snapshot used for offline/cold start.

`uk.ac.ebi.zooma2.prefix_map.Bioregistry` loads
`backend/src/main/resources/bioregistry/registry.json` from the classpath at
construction so the server can start without GitHub, then refreshes from the
live registry in a background thread. This script downloads the current
registry export and trims every entry to the four fields the Java code reads
(`preferred_prefix`, `synonyms`, `uri_format`, `pattern`), which shrinks the
3.7 MB export to a few hundred KB.

To refresh the snapshot (run from anywhere, no dependencies beyond Python 3):

    python3 backend/scripts/update-bioregistry-snapshot.py

then review `git diff --stat` and commit the updated registry.json.
Set BIOREGISTRY_URL to download from a different location.
"""

import json
import os
import sys
import urllib.request

DEFAULT_URL = "https://raw.githubusercontent.com/biopragmatics/bioregistry/main/exports/registry/registry.json"
KEPT_FIELDS = ("preferred_prefix", "synonyms", "uri_format", "pattern")

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUTPUT = os.path.join(REPO_ROOT, "backend", "src", "main", "resources", "bioregistry", "registry.json")


def main():
    url = os.environ.get("BIOREGISTRY_URL", DEFAULT_URL)
    print(f"Downloading {url}", file=sys.stderr)
    with urllib.request.urlopen(url, timeout=60) as resp:
        full = json.load(resp)

    trimmed = {}
    for prefix, entry in full.items():
        trimmed[prefix] = {k: entry[k] for k in KEPT_FIELDS if k in entry}

    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
    # Sorted keys and compact separators keep the committed diff stable and small.
    with open(OUTPUT, "w", encoding="utf-8") as f:
        json.dump(trimmed, f, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        f.write("\n")

    print(f"Wrote {len(trimmed)} entries to {OUTPUT} ({os.path.getsize(OUTPUT) // 1024} KB)", file=sys.stderr)


if __name__ == "__main__":
    main()
