#!/usr/bin/env python3
"""Appendix A.1: Build a dataset manifest with SHA-256 and perceptual hashes using common.py (#13)."""
import csv
import pathlib
import sys
from PIL import Image

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from common import compute_phash, sha256_file  # noqa: E402

PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[2]
ROOT = PROJECT_ROOT / "data" / "curated"
OUT = PROJECT_ROOT / "data" / "manifests" / "manifest_v2_rebuilt.csv"


def main():
    rows = []
    for p in sorted(ROOT.rglob("*")):
        if p.suffix.lower() not in {".jpg", ".jpeg", ".png"}:
            continue
        try:
            with Image.open(p) as im:
                im.load()
                sh = sha256_file(p)
                rows.append({
                    "image_id": sh[:16],
                    "path": str(p.relative_to(ROOT)),
                    "sha256": sh,
                    "phash": compute_phash(im),
                    "width": im.width,
                    "height": im.height,
                })
        except Exception as exc:
            print("skip", p, exc)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {len(rows)} rows to {OUT}")


if __name__ == "__main__":
    main()
