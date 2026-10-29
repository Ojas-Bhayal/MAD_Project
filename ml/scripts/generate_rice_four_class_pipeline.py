import csv
import json
import os
import hashlib
import random
import re
from pathlib import Path
from collections import defaultdict, Counter
import numpy as np
from PIL import Image

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
    hasan_raw_dir = root / "data" / "raw" / "rice_mendeley_healthy_candidate" / "Original Images"
    sethy_manifest_path = root / "data" / "manifests" / "manifest_rice_mendeley_sethy.csv"
    sethy_split_path = root / "data" / "splits" / "split_rice_mendeley_sethy.json"

    reports_dir = root / "ml" / "reports" / "rice_mendeley_healthy_candidate"
    reports_dir.mkdir(parents=True, exist_ok=True)

    manifest_hasan_path = root / "data" / "manifests" / "manifest_rice_mendeley_healthy_candidate.csv"
    manifest_4class_path = root / "data" / "manifests" / "manifest_rice_four_class.csv"
    split_4class_path = root / "data" / "splits" / "split_rice_four_class.json"

    print("=" * 70)
    print("STEP 1: AUDITING ALL 1,701 IMAGES IN HASAN V2 ORIGINAL IMAGES")
    print("=" * 70)

    all_hasan_files = sorted(list(hasan_raw_dir.glob("*/*.*")))
    print(f"Total Hasan files located: {len(all_hasan_files)}")

    # Taxonomy mapping for Hasan classes
    hasan_taxonomy = {
        "Bacterial Leaf Blight": {"cropdoc_name": "rice_bacterial_leaf_blight", "cropdoc_idx": "0", "is_supported": True},
        "Brown Spot": {"cropdoc_name": "rice_brown_spot", "cropdoc_idx": "1", "is_supported": True},
        "Leaf Blast": {"cropdoc_name": "rice_leaf_blast", "cropdoc_idx": "2", "is_supported": True},
        "Healthy Rice Leaf": {"cropdoc_name": "rice_healthy", "cropdoc_idx": "3", "is_supported": True},
        "Leaf scald": {"cropdoc_name": "", "cropdoc_idx": "", "is_supported": False},
        "Narrow Brown Leaf Spot": {"cropdoc_name": "", "cropdoc_idx": "", "is_supported": False},
        "Rice Hispa": {"cropdoc_name": "", "cropdoc_idx": "", "is_supported": False},
        "Sheath Blight": {"cropdoc_name": "", "cropdoc_idx": "", "is_supported": False}
    }

    # Pass 1: Extract metadata and SHA-256 for all Hasan files
    hasan_records = []
    sha_map = defaultdict(list)
    for fpath in all_hasan_files:
        cls_name = fpath.parent.name
        data = fpath.read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        with Image.open(fpath) as img:
            w, h = img.size
            fmt = img.format
            ph = compute_phash(img)

        tax = hasan_taxonomy[cls_name]
        rel_path = str(fpath.relative_to(root)).replace("\\", "/")

        rec = {
            "image_id": sha[:16],
            "path": rel_path,
            "filename": fpath.name,
            "sha256": sha,
            "phash": ph,
            "width": w,
            "height": h,
            "actual_format": fmt,
            "source": "rice_mendeley_hasan_2023",
            "license": "CC BY 4.0",
            "source_class": cls_name,
            "crop": "rice",
            "disease": tax["cropdoc_name"].replace("rice_", "") if tax["is_supported"] else cls_name.lower().replace(" ", "_"),
            "cropdoc_class_name": tax["cropdoc_name"],
            "cropdoc_class_index": tax["cropdoc_idx"],
            "is_supported": tax["is_supported"],
            "group_id": "",
            "duplicate_status": "UNIQUE",
            "is_canonical_representative": True,
            "usable_for_cropdoc_training": False,
            "split": "",
            "notes": ""
        }
        hasan_records.append(rec)
        sha_map[sha].append(rec)

    # Detect exact duplicates and cross-class contradictions in Hasan
    cross_class_sha = set()
    for sha, recs in sha_map.items():
        if len(recs) > 1:
            classes = set(r["source_class"] for r in recs)
            if len(classes) > 1:
                cross_class_sha.add(sha)
                for r in recs:
                    r["duplicate_status"] = "CONFLICTING_LABEL_DUPLICATE"
                    r["is_canonical_representative"] = False
                    r["usable_for_cropdoc_training"] = False
                    r["notes"] = f"CONTRADICTION: identical byte duplicate labeled across {classes}"
            else:
                # Same-class duplicate: elect first as canonical, rest as exact duplicate copies
                recs.sort(key=lambda x: x["filename"])
                for idx, r in enumerate(recs):
                    if idx == 0:
                        r["duplicate_status"] = "CANONICAL_SPECIMEN"
                        r["is_canonical_representative"] = True
                    else:
                        r["duplicate_status"] = "EXACT_BYTE_DUPLICATE"
                        r["is_canonical_representative"] = False
                        r["usable_for_cropdoc_training"] = False
                        r["notes"] = f"Exact duplicate copy of {recs[0]['filename']}"

    # Assign group_ids for Hasan files
    group_counters = defaultdict(int)
    for r in hasan_records:
        cls = r["source_class"]
        pfx_map = {
            "Bacterial Leaf Blight": "rice_hasan_bb_",
            "Brown Spot": "rice_hasan_bs_",
            "Leaf Blast": "rice_hasan_blast_",
            "Healthy Rice Leaf": "rice_hasan_healthy_",
            "Leaf scald": "rice_hasan_scald_",
            "Narrow Brown Leaf Spot": "rice_hasan_narrow_",
            "Rice Hispa": "rice_hasan_hispa_",
            "Sheath Blight": "rice_hasan_sheath_"
        }
        # If part of an exact duplicate group, share group_id
        matching_sha_recs = sha_map[r["sha256"]]
        if len(matching_sha_recs) > 1 and matching_sha_recs[0]["group_id"]:
            r["group_id"] = matching_sha_recs[0]["group_id"]
        else:
            group_counters[cls] += 1
            gid = f"{pfx_map[cls]}{group_counters[cls]:03d}"
            r["group_id"] = gid

    # Partition Healthy Rice Leaf into 70/15/15 split
    healthy_recs = [r for r in hasan_records if r["source_class"] == "Healthy Rice Leaf"]
    healthy_canonicals = [r for r in healthy_recs if r["is_canonical_representative"]]
    print(f"\nHealthy Rice Leaf: {len(healthy_recs)} total, {len(healthy_canonicals)} canonical unique specimens")

    seed = 42
    rng = random.Random(seed)
    healthy_groups = sorted(list(set(r["group_id"] for r in healthy_canonicals)))
    rng.shuffle(healthy_groups)

    n_h = len(healthy_groups)
    n_h_train = int(round(n_h * 0.70))
    n_h_val = int(round(n_h * 0.15))
    n_h_test = n_h - n_h_train - n_h_val

    h_train_grps = set(healthy_groups[:n_h_train])
    h_val_grps = set(healthy_groups[n_h_train:n_h_train + n_h_val])
    h_test_grps = set(healthy_groups[n_h_train + n_h_val:])

    print(f"Healthy split: Train={len(h_train_grps)}, Val={len(h_val_grps)}, Test={len(h_test_grps)}")

    h_grp_split_map = {}
    for g in h_train_grps: h_grp_split_map[g] = "train"
    for g in h_val_grps: h_grp_split_map[g] = "val"
    for g in h_test_grps: h_grp_split_map[g] = "test"

    for r in healthy_recs:
        r["split"] = h_grp_split_map[r["group_id"]]
        if r["is_canonical_representative"]:
            r["usable_for_cropdoc_training"] = True

    # Quarantine unsupported classes in Hasan
    for r in hasan_records:
        if not r["is_supported"]:
            r["split"] = "unsupported"
            r["usable_for_cropdoc_training"] = False

    # Write manifest_rice_mendeley_healthy_candidate.csv
    fieldnames = [
        "image_id", "path", "sha256", "phash", "width", "height", "actual_format",
        "source", "license", "source_class", "crop", "disease",
        "cropdoc_class_name", "cropdoc_class_index",
        "group_id", "duplicate_status",
        "is_canonical_representative", "usable_for_cropdoc_training",
        "split", "notes"
    ]

    with open(manifest_hasan_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(hasan_records)
    print(f"\nWritten Hasan manifest: {manifest_hasan_path} ({len(hasan_records)} rows)")

    # Mirror in reports
    with open(reports_dir / "manifest_rice_mendeley_healthy_candidate.csv", "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(hasan_records)

    # =========================================================================
    # STEP 2: BUILD UNIFIED FOUR-CLASS CANDIDATE (Sethy v2 + Hasan Healthy)
    # =========================================================================
    print("\n" + "=" * 70)
    print("STEP 2: BUILDING SOURCE-AWARE UNIFIED FOUR-CLASS CANDIDATE")
    print("=" * 70)

    # Load Sethy manifest and split
    with open(sethy_manifest_path, encoding="utf-8") as f:
        sethy_manifest_rows = list(csv.DictReader(f))

    with open(sethy_split_path, encoding="utf-8") as f:
        sethy_split_data = json.load(f)

    # Filter Sethy usable canonical specimens
    sethy_usable = [r for r in sethy_manifest_rows if r["usable_for_cropdoc_training"] == "True"]
    print(f"Sethy usable canonical disease specimens: {len(sethy_usable)}")
    print(f"Hasan usable canonical healthy specimens: {len(healthy_canonicals)}")

    # Combine into unified four-class candidate
    unified_records = []
    # 1. Sethy records
    for r in sethy_manifest_rows:
        unified_records.append(dict(r))

    # 2. Hasan healthy records
    for r in healthy_recs:
        unified_records.append(dict(r))

    print(f"Total records in unified four-class manifest: {len(unified_records)}")
    total_4class_usable = sum(1 for r in unified_records if r["usable_for_cropdoc_training"] == "True" or r["usable_for_cropdoc_training"] is True)
    print(f"Total usable canonical specimens in unified 4-class candidate: {total_4class_usable}")

    with open(manifest_4class_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(unified_records)
    print(f"Written unified four-class manifest: {manifest_4class_path}")

    # Build Unified Split JSON
    split_4class_data = {
        "dataset_name": "rice_four_class_unified",
        "description": "Unified 4-class rice candidate combining Sethy et al. (2020) canonical disease specimens with Hasan et al. (2023) canonical healthy specimens",
        "sources": [
            {
                "source_id": "rice_mendeley_sethy_v2",
                "doi": "10.17632/fwcj7stb8r.2",
                "url": "https://data.mendeley.com/datasets/fwcj7stb8r/2",
                "license": "CC BY 4.0",
                "classes_contributed": ["rice_bacterial_leaf_blight", "rice_brown_spot", "rice_leaf_blast"]
            },
            {
                "source_id": "rice_mendeley_hasan_2023",
                "doi": "10.17632/hx6f852hw4.2",
                "url": "https://data.mendeley.com/datasets/hx6f852hw4/2",
                "license": "CC BY 4.0",
                "classes_contributed": ["rice_healthy"]
            }
        ],
        "random_seed": seed,
        "split_ratio_target": {"train": 0.70, "val": 0.15, "test": 0.15},
        "total_files": len(unified_records),
        "total_usable_canonicals": total_4class_usable,
        "classes": {
            "rice_bacterial_leaf_blight": 0,
            "rice_brown_spot": 1,
            "rice_leaf_blast": 2,
            "rice_healthy": 3
        },
        "per_class_summary": {
            "rice_bacterial_leaf_blight": {
                "source": "rice_mendeley_sethy_v2",
                "class_index": 0,
                "total_usable_images": 264,
                "train_images": 185,
                "val_images": 40,
                "test_images": 39
            },
            "rice_brown_spot": {
                "source": "rice_mendeley_sethy_v2",
                "class_index": 1,
                "total_usable_images": 100,
                "train_images": 70,
                "val_images": 15,
                "test_images": 15
            },
            "rice_leaf_blast": {
                "source": "rice_mendeley_sethy_v2",
                "class_index": 2,
                "total_usable_images": 160,
                "train_images": 112,
                "val_images": 24,
                "test_images": 24
            },
            "rice_healthy": {
                "source": "rice_mendeley_hasan_2023",
                "class_index": 3,
                "total_usable_images": len(healthy_canonicals),
                "train_images": len(h_train_grps),
                "val_images": len(h_val_grps),
                "test_images": len(h_test_grps)
            }
        },
        "splits": {
            "train": {"total_usable_images": 0, "total_groups": 0, "classes": {}, "samples": []},
            "val": {"total_usable_images": 0, "total_groups": 0, "classes": {}, "samples": []},
            "test": {"total_usable_images": 0, "total_groups": 0, "classes": {}, "samples": []}
        }
    }

    # Populate split samples
    for sp_name in ["train", "val", "test"]:
        # 1. Sethy samples from previous split
        sethy_sp_samples = sethy_split_data["splits"][sp_name]["samples"]
        for s in sethy_sp_samples:
            split_4class_data["splits"][sp_name]["samples"].append({
                "image_id": s["image_id"],
                "path": s["path"],
                "sha256": s["sha256"],
                "phash": s["phash"],
                "group_id": s["group_id"],
                "source": "rice_mendeley_sethy_v2",
                "cropdoc_class_name": s["cropdoc_class_name"],
                "cropdoc_class_index": s["cropdoc_class_index"],
                "split": sp_name
            })

        # 2. Hasan healthy samples
        hasan_sp_healthy = [r for r in healthy_canonicals if r["split"] == sp_name]
        for h in hasan_sp_healthy:
            split_4class_data["splits"][sp_name]["samples"].append({
                "image_id": h["image_id"],
                "path": h["path"],
                "sha256": h["sha256"],
                "phash": h["phash"],
                "group_id": h["group_id"],
                "source": "rice_mendeley_hasan_2023",
                "cropdoc_class_name": "rice_healthy",
                "cropdoc_class_index": 3,
                "split": sp_name
            })

        samples = split_4class_data["splits"][sp_name]["samples"]
        split_4class_data["splits"][sp_name]["total_usable_images"] = len(samples)
        split_4class_data["splits"][sp_name]["total_groups"] = len(set(x["group_id"] for x in samples))
        split_4class_data["splits"][sp_name]["classes"] = Counter(x["cropdoc_class_name"] for x in samples)

    with open(split_4class_path, "w", encoding="utf-8") as f:
        json.dump(split_4class_data, f, indent=2)
    print(f"Written unified four-class split: {split_4class_path}")

    # Mirror split in reports
    with open(reports_dir / "split_rice_four_class.json", "w", encoding="utf-8") as f:
        json.dump(split_4class_data, f, indent=2)

    print("\nFour-class pipeline generation completed successfully!")

if __name__ == "__main__":
    main()
