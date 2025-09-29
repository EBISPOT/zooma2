#!/usr/bin/env uv run
# /// script
# requires-python = ">=3.13"
# dependencies = [
#     "requests",
# ]
# ///

import gzip
import json
import requests

def main():
    config = json.load(open("config.json"))
    for ds_name, ds in config["datasources"].items():
        import_url = ds["import_url"]
        print(f"Downloading {import_url}")
        r = requests.get(import_url)
        filename = import_url.split("/")[-1]
        with gzip.open(f"data/{filename}.gz", "wb") as f:
            f.write(r.content)

if __name__ == "__main__":
    main()




