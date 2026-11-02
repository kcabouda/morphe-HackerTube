#!/usr/bin/env python3
"""
Builds bespoke/catalog.json, the slim video catalog the Bespoke feed downloads.

Input: videos.jsonl from the HackerTube data export (https://hackertube.org/data),
ODC-By 1.0. See bespoke/NOTICE.md.

Usage: python3 bespoke/build_catalog.py path/to/videos.jsonl
"""
import json
import sys
from pathlib import Path

OUT = Path(__file__).with_name("catalog.json")


def main(src: str) -> None:
    videos = []
    areas = {}
    for line in Path(src).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        # Keep real recommendations only, and skip videos that only mattered around one event.
        if r["hn"]["recommenders"] == 0 or r.get("shelf_life") == "news":
            continue
        cat = r["categories"][0] if r.get("categories") else None
        area = cat["area"] if cat else "other"
        areas[area] = cat["area_name"] if cat else "Other"
        videos.append({
            "v": r["vid"],
            "t": r["title"],
            "c": r.get("channel") or "",
            "a": area,
            "k": cat["name"] if cat else "",
            "s": r["score"],
            "r": r["hn"]["recommenders"],
            "y": r["hn"].get("first_year"),
            "w": r.get("why_hn") or "",
        })

    videos.sort(key=lambda x: -x["s"])
    catalog = {
        "format": "bespoke-catalog",
        "version": 1,
        "source": "HackerTube data export 2026-10-31 (hackertube.org/data), ODC-By 1.0",
        "areas": [{"id": k, "name": v} for k, v in sorted(areas.items(), key=lambda kv: kv[1])],
        "videos": videos,
    }
    OUT.write_text(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"{len(videos)} videos, {len(areas)} areas -> {OUT} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main(sys.argv[1])
