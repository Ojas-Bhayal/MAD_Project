import csv
import json
import hashlib
from pathlib import Path
from PIL import Image
import numpy as np

def compute_phash(img: Image.Image) -> str:
    small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
    pixels = list(small.getdata())
    avg = sum(pixels) / len(pixels)
    bits = 0
    for px in pixels:
        bits = (bits << 1) | (1 if px >= avg else 0)
    return f"{bits:016x}"

def hamming_distance(h1: str, h2: str) -> int:
    return (int(h1, 16) ^ int(h2, 16)).bit_count()

def main():
    manifest_path = Path("data/manifests/manifest_cotton_staged.csv")
    split_path = Path("data/splits/split_mendeley_cotton_2025.json")

    with open(manifest_path, "r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    with open(split_path, "r", encoding="utf-8") as f:
        split_data = json.load(f)

    print(f"Total rows in manifest_cotton_staged.csv: {len(rows)}")

    # 1. SHA-256 Recalculation and Uniqueness
    sha_map = {}
    phash_recalc_map = {}
    missing_files = []
    hash_mismatches = []
    phash_mismatches = []

    records = []
    for r in rows:
        p = Path(r["staged_path"])
        if not p.exists():
            missing_files.append(str(p))
            continue
        data = p.read_bytes()
        calculated_sha = hashlib.sha256(data).hexdigest()
        if calculated_sha != r["sha256"]:
            hash_mismatches.append((str(p), r["sha256"], calculated_sha))

        with Image.open(p) as img:
            calc_phash = compute_phash(img)
            w, h = img.size

        if calc_phash != r["phash"]:
            phash_mismatches.append((str(p), r["phash"], calc_phash))

        rec = {
            "image_id": r["image_id"],
            "staged_path": r["staged_path"],
            "original_raw_path": r["original_raw_path"],
            "sha256": calculated_sha,
            "phash": calc_phash,
            "cropdoc_class": r["cropdoc_class_name"],
            "group_id": r["group_id"],
            "split": r["split"],
            "width": w,
            "height": h,
        }
        records.append(rec)
        sha_map.setdefault(calculated_sha, []).append(rec)
        phash_recalc_map.setdefault(calc_phash, []).append(rec)

    print(f"Missing physical files: {len(missing_files)}")
    print(f"SHA-256 mismatches against manifest: {len(hash_mismatches)}")
    print(f"pHash mismatches against manifest: {len(phash_mismatches)}")
    print(f"Unique SHA-256 hashes across 359 images: {len(sha_map)}")

    # 2. Perceptual Groups with d_H = 0
    dh0_groups = {k: v for k, v in phash_recalc_map.items() if len(v) > 1}
    print(f"\nTotal unique pHashes (d_H = 0): {len(phash_recalc_map)}")
    print(f"pHash collision groups (len > 1): {len(dh0_groups)}")

    total_images_in_dh0_groups = sum(len(v) for v in dh0_groups.values())
    extra_instances_from_dh0 = total_images_in_dh0_groups - len(dh0_groups)
    print(f"Total images in collision groups: {total_images_in_dh0_groups}")
    print(f"Extra instances accounted for by d_H=0: {extra_instances_from_dh0} (359 - {len(phash_recalc_map)} = {359 - len(phash_recalc_map)})")

    # Inspect each d_H = 0 collision
    print("\n--- Detailed Inspection of d_H = 0 Collisions ---")
    collision_details = []
    for phash_val, members in dh0_groups.items():
        print(f"\npHash {phash_val} ({len(members)} images):")
        m0 = members[0]
        m1 = members[1]
        img0 = np.array(Image.open(m0["staged_path"]).convert("RGB"))
        img1 = np.array(Image.open(m1["staged_path"]).convert("RGB"))
        shape_match = (img0.shape == img1.shape)
        if shape_match:
            diff = np.abs(img0.astype(np.int32) - img1.astype(np.int32))
            max_diff = int(diff.max())
            mean_diff = float(diff.mean())
            pct_identical = float((diff == 0).mean() * 100)
        else:
            max_diff = -1
            mean_diff = -1
            pct_identical = 0

        same_group = (m0["group_id"] == m1["group_id"])
        same_split = (m0["split"] == m1["split"])
        same_class = (m0["cropdoc_class"] == m1["cropdoc_class"])

        info = {
            "phash": phash_val,
            "class": m0["cropdoc_class"],
            "same_class": same_class,
            "group_id_0": m0["group_id"],
            "group_id_1": m1["group_id"],
            "same_group": same_group,
            "split_0": m0["split"],
            "split_1": m1["split"],
            "same_split": same_split,
            "img0": Path(m0["staged_path"]).name,
            "img1": Path(m1["staged_path"]).name,
            "raw0": Path(m0["original_raw_path"]).name,
            "raw1": Path(m1["original_raw_path"]).name,
            "shape0": f"{m0['width']}x{m0['height']}",
            "shape1": f"{m1['width']}x{m1['height']}",
            "shape_match": shape_match,
            "max_pixel_diff": max_diff,
            "mean_pixel_diff": mean_diff,
            "pct_identical_pixels": pct_identical,
        }
        collision_details.append(info)
        print(f"  Img 1: {info['img0']} (raw: {info['raw0']}, grp: {info['group_id_0']}, split: {info['split_0']})")
        print(f"  Img 2: {info['img1']} (raw: {info['raw1']}, grp: {info['group_id_1']}, split: {info['split_1']})")
        print(f"  Same class: {same_class}, Same group: {same_group}, Same split: {same_split}")
        print(f"  Dimensions: {info['shape0']} vs {info['shape1']}")
        print(f"  Max pixel diff: {max_diff}, Mean pixel diff: {mean_diff:.2f}, % identical pixels: {pct_identical:.1f}%")

    # 3. Near-Duplicate Analysis (1 <= d_H <= 6)
    print("\n--- Near-Duplicate Analysis (1 <= d_H <= 6) ---")
    near_dupe_pairs = []
    n = len(records)
    for i in range(n):
        for j in range(i + 1, n):
            dist = hamming_distance(records[i]["phash"], records[j]["phash"])
            if 1 <= dist <= 6:
                near_dupe_pairs.append((records[i], records[j], dist))

    print(f"Total pairs with 1 <= d_H <= 6: {len(near_dupe_pairs)}")
    cross_class_near = [p for p in near_dupe_pairs if p[0]["cropdoc_class"] != p[1]["cropdoc_class"]]
    cross_split_near = [p for p in near_dupe_pairs if p[0]["split"] != p[1]["split"]]
    print(f"Cross-class pairs (1 <= d_H <= 6): {len(cross_class_near)}")
    print(f"Cross-split pairs (1 <= d_H <= 6): {len(cross_split_near)}")

    for r0, r1, d in near_dupe_pairs:
        same_cls = (r0["cropdoc_class"] == r1["cropdoc_class"])
        same_spl = (r0["split"] == r1["split"])
        print(f"  dist={d}: {Path(r0['staged_path']).name} ({r0['cropdoc_class']}, {r0['split']}) <-> {Path(r1['staged_path']).name} ({r1['cropdoc_class']}, {r1['split']}) | same_class: {same_cls}, same_split: {same_spl}")

    # 4. Check Group to Split mapping in split_data
    print("\n--- Split Leakage Verification ---")
    splits = {"train": set(), "val": set(), "test": set()}
    group_to_splits = {}
    for r in records:
        splits[r["split"]].add(r["image_id"])
        group_to_splits.setdefault(r["group_id"], set()).add(r["split"])

    multi_split_groups = {g: s for g, s in group_to_splits.items() if len(s) > 1}
    print(f"Groups spanning multiple splits: {len(multi_split_groups)}")
    if multi_split_groups:
        for g, s in multi_split_groups.items():
            print(f"  LEAKAGE in group {g}: {s}")
    else:
        print("  ZERO GROUP LEAKAGE confirmed across train, val, test partitions!")

    # Summary by class
    for cls in sorted(list(set(r["cropdoc_class"] for r in records))):
        cls_recs = [r for r in records if r["cropdoc_class"] == cls]
        cls_grps = set(r["group_id"] for r in cls_recs)
        cls_phashes = set(r["phash"] for r in cls_recs)
        print(f"\nClass: {cls}")
        print(f"  Images: {len(cls_recs)}")
        print(f"  Distinct group_ids: {len(cls_grps)}")
        print(f"  Distinct pHashes: {len(cls_phashes)}")
        print(f"  Extra instances (images - groups): {len(cls_recs) - len(cls_grps)}")

if __name__ == "__main__":
    main()
