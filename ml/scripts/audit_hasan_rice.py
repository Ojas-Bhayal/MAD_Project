import hashlib
import json
import os
import re
from pathlib import Path
from collections import defaultdict, Counter
from PIL import Image
import numpy as np

def compute_phash(img: Image.Image) -> str:
    small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
    pixels = np.array(small, dtype=np.float32).flatten()
    avg = np.mean(pixels)
    bits = 0
    for px in pixels:
        bits = (bits << 1) | (1 if px >= avg else 0)
    return f"{bits:016x}"

def main():
    root = Path(__file__).resolve().parents[2]
    raw_dir = root / "data" / "raw" / "rice_mendeley_healthy_candidate" / "Original Images"
    sethy_dir = root / "data" / "raw" / "rice_mendeley_sethy"

    print("Auditing all 1,701 files in Hasan v2 Original Images...")
    all_files = list(raw_dir.glob("*/*.*"))
    print(f"Total files located: {len(all_files)}")

    # 1. Read files and check corruption
    records = []
    sha_map = defaultdict(list)
    phash_map = defaultdict(list)
    class_counts = Counter()
    dimensions = Counter()
    formats = Counter()
    modes = Counter()
    corrupt_files = []

    for fpath in all_files:
        cls_name = fpath.parent.name
        class_counts[cls_name] += 1
        try:
            data = fpath.read_bytes()
            sha = hashlib.sha256(data).hexdigest()
            with Image.open(fpath) as img:
                w, h = img.size
                fmt = img.format
                mode = img.mode
                ph = compute_phash(img)
                dimensions[(w, h)] += 1
                formats[fmt] += 1
                modes[mode] += 1
            
            rec = {
                "path": str(fpath.relative_to(root)).replace("\\", "/"),
                "filename": fpath.name,
                "source_class": cls_name,
                "sha256": sha,
                "phash": ph,
                "width": w,
                "height": h,
                "format": fmt,
                "mode": mode,
                "size_bytes": len(data)
            }
            records.append(rec)
            sha_map[sha].append(rec)
            phash_map[ph].append(rec)
        except Exception as e:
            corrupt_files.append((str(fpath), str(e)))

    print("\n--- IMAGE INTEGRITY & GEOMETRY ---")
    print(f"Corrupt / Unreadable files: {len(corrupt_files)}")
    print(f"Formats: {dict(formats)}")
    print(f"Modes: {dict(modes)}")
    print(f"Top 5 Dimensions: {dimensions.most_common(5)}")

    print("\n--- CLASS COUNTS ---")
    for cls, cnt in sorted(class_counts.items()):
        print(f"  {cls:<30}: {cnt:>5} images")

    # 2. Internal exact duplicates
    exact_dup_groups = {k: v for k, v in sha_map.items() if len(v) > 1}
    print(f"\n--- INTERNAL DUPLICATES ---")
    print(f"Exact SHA-256 duplicate groups: {len(exact_dup_groups)}")
    if exact_dup_groups:
        for sha, recs in list(exact_dup_groups.items())[:5]:
            print(f"  SHA: {sha[:16]} ({len(recs)} files): {[r['path'] for r in recs]}")

    # Check cross-class conflicting exact duplicates
    cross_class_exact = []
    for sha, recs in exact_dup_groups.items():
        classes_in_grp = set(r["source_class"] for r in recs)
        if len(classes_in_grp) > 1:
            cross_class_exact.append((sha, classes_in_grp, [r["path"] for r in recs]))
    print(f"Cross-class conflicting exact duplicates: {len(cross_class_exact)}")

    # 3. Internal perceptual duplicates (d_H = 0)
    phash_zero_groups = {k: v for k, v in phash_map.items() if len(v) > 1 and len(set(r["sha256"] for r in v)) > 1}
    print(f"Perceptual collision groups (d_H=0, different SHA): {len(phash_zero_groups)}")
    if phash_zero_groups:
        for ph, recs in list(phash_zero_groups.items())[:5]:
            print(f"  pHash: {ph} ({len(recs)} files): {[r['path'] for r in recs]}")

    # 4. Cross-Dataset Comparison with Sethy v2!
    print("\n--- CROSS-DATASET COLLISION CHECK (Hasan v2 vs. Sethy v2) ---")
    sethy_manifest_path = root / "data" / "manifests" / "manifest_rice_mendeley_sethy.csv"
    import csv
    with open(sethy_manifest_path, encoding="utf-8") as f:
        sethy_rows = list(csv.DictReader(f))

    sethy_sha_set = {r["sha256"]: r for r in sethy_rows}
    sethy_phash_set = defaultdict(list)
    for r in sethy_rows:
        sethy_phash_set[r["phash"]].append(r)

    hasan_sethy_sha_collisions = []
    for r in records:
        if r["sha256"] in sethy_sha_set:
            hasan_sethy_sha_collisions.append((r, sethy_sha_set[r["sha256"]]))

    print(f"Exact SHA-256 collisions between Hasan and Sethy: {len(hasan_sethy_sha_collisions)}")
    if hasan_sethy_sha_collisions:
        for h_rec, s_rec in hasan_sethy_sha_collisions[:5]:
            print(f"  COLLISION: Hasan [{h_rec['source_class']}] {h_rec['filename']} == Sethy [{s_rec['source_class']}] {s_rec['path']}")

    # Check d_H = 0 collisions between Hasan and Sethy
    hasan_sethy_phash_collisions = []
    for r in records:
        if r["phash"] in sethy_phash_set:
            matches = [s for s in sethy_phash_set[r["phash"]] if s["sha256"] != r["sha256"]]
            if matches:
                hasan_sethy_phash_collisions.append((r, matches))

    print(f"Perceptual d_H=0 collisions between Hasan and Sethy: {len(hasan_sethy_phash_collisions)}")
    if hasan_sethy_phash_collisions:
        for h_rec, s_matches in hasan_sethy_phash_collisions[:5]:
            print(f"  pHash match: Hasan [{h_rec['source_class']}] {h_rec['filename']} vs Sethy {[m['path'] for m in s_matches[:2]]}")

if __name__ == "__main__":
    main()
