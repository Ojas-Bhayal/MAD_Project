#!/usr/bin/env python3
"""
CropDoc — Prepare Dataset Manifest and Group-Aware Splits for Crop-Domain Gate

Constructs:
1. data/manifests/manifest_crop_domain_gate.csv
2. data/splits/split_crop_domain_gate.json

Categories:
- supported_rice (local index 0)
- supported_groundnut (local index 1)
- unsupported_crop (local index 2: cotton, cassava, corn, plus unseen tomato test)
"""

import os
import csv
import json
import random
import hashlib
from pathlib import Path
from collections import defaultdict, Counter

ROOT = Path(__file__).resolve().parents[2]
RANDOM_SEED = 42
random.seed(RANDOM_SEED)

MANIFEST_OUT = ROOT / "data" / "manifests" / "manifest_crop_domain_gate.csv"
SPLIT_OUT = ROOT / "data" / "splits" / "split_crop_domain_gate.json"

DOMAIN_MAPPING = [
    {"domain_index": 0, "domain_name": "supported_rice", "description": "Rice foliage (Oryza sativa)"},
    {"domain_index": 1, "domain_name": "supported_groundnut", "description": "Groundnut foliage (Arachis hypogaea)"},
    {"domain_index": 2, "domain_name": "unsupported_crop", "description": "Unsupported agricultural crop foliage (Cotton, Cassava, Corn, Tomato)"}
]

def prepare_dataset():
    print("Preparing crop-domain gate manifest and splits...")
    items = []

    # 1. Supported Rice (678 specimens from unified manifest)
    # Reusing verified split assignments (train, val, test)
    with open(ROOT / "data" / "manifests" / "manifest_unified_six_class.csv", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["crop"] == "rice":
                items.append({
                    "image_id": r["image_id"],
                    "path": r["path"],
                    "sha256": r["sha256"],
                    "phash": r["phash"],
                    "width": r["width"],
                    "height": r["height"],
                    "source": r["source"],
                    "license": r["license"],
                    "source_crop": "rice",
                    "domain_name": "supported_rice",
                    "domain_index": 0,
                    "group_id": r["group_id"],
                    "split": r["split"],
                    "is_out_of_training_evaluation": "False",
                    "notes": "Verified canonical rice foliage specimen from Rice Sethy v2 / Hasan 2023"
                })

    # 2. Supported Groundnut (824 specimens from unified manifest)
    with open(ROOT / "data" / "manifests" / "manifest_unified_six_class.csv", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["crop"] == "groundnut":
                items.append({
                    "image_id": r["image_id"],
                    "path": r["path"],
                    "sha256": r["sha256"],
                    "phash": r["phash"],
                    "width": r["width"],
                    "height": r["height"],
                    "source": r["source"],
                    "license": r["license"],
                    "source_crop": "groundnut",
                    "domain_name": "supported_groundnut",
                    "domain_index": 1,
                    "group_id": r["group_id"],
                    "split": r["split"],
                    "is_out_of_training_evaluation": "False",
                    "notes": "Verified canonical groundnut foliage specimen from Mendeley Groundnut v3"
                })

    # 3. Unsupported Cotton (359 staged canonical images from manifest_cotton_staged.csv)
    with open(ROOT / "data" / "manifests" / "manifest_cotton_staged.csv", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            items.append({
                "image_id": r["image_id"],
                "path": r["staged_path"],
                "sha256": r["sha256"],
                "phash": r["phash"],
                "width": r["width"],
                "height": r["height"],
                "source": r["source"],
                "license": r["license"],
                "source_crop": "cotton",
                "domain_name": "unsupported_crop",
                "domain_index": 2,
                "group_id": r["group_id"],
                "split": r["split"],
                "is_out_of_training_evaluation": "False",
                "notes": "Verified staged cotton foliage specimen from Mendeley Cotton 2025"
            })

    # 4. Unsupported FieldPlant: Cassava and Corn (Parent images)
    # Deduplicated by canonical representative
    fp_groups_by_crop = defaultdict(list)
    with open(ROOT / "data" / "manifests" / "manifest_fieldplant.csv", encoding="utf-8") as f:
        seen_parents = set()
        for r in csv.DictReader(f):
            if r["is_canonical_representative"] == "True":
                pid = r["parent_image_id"]
                if pid not in seen_parents:
                    seen_parents.add(pid)
                    scn = r["source_class_name"]
                    crop = None
                    if scn.startswith("Cassava"):
                        crop = "cassava"
                    elif scn.startswith("Corn"):
                        crop = "corn"
                    elif scn.startswith("Tomato"):
                        crop = "tomato"

                    if crop:
                        fp_groups_by_crop[crop].append({
                            "image_id": pid,
                            "path": r["parent_path"],
                            "sha256": r["parent_sha256"],
                            "phash": r["parent_phash"],
                            "width": r["parent_width"],
                            "height": r["parent_height"],
                            "source": "fieldplant_v2",
                            "license": r["license"],
                            "source_crop": crop,
                            "domain_name": "unsupported_crop",
                            "domain_index": 2,
                            "group_id": r["group_id"]
                        })

    # Sample 200 Cassava parent images: 140 train, 30 val, 30 test
    random.seed(RANDOM_SEED)
    cassava_sample = random.sample(fp_groups_by_crop["cassava"], 200)
    for i, it in enumerate(cassava_sample):
        sp = "train" if i < 140 else ("val" if i < 170 else "test")
        it["split"] = sp
        it["is_out_of_training_evaluation"] = "False"
        it["notes"] = "Canonical parent Cassava foliage image from FieldPlant (Moupojou et al., 2023)"
        items.append(it)

    # Sample 200 Corn parent images: 140 train, 30 val, 30 test
    random.seed(RANDOM_SEED + 1)
    corn_sample = random.sample(fp_groups_by_crop["corn"], 200)
    for i, it in enumerate(corn_sample):
        sp = "train" if i < 140 else ("val" if i < 170 else "test")
        it["split"] = sp
        it["is_out_of_training_evaluation"] = "False"
        it["notes"] = "Canonical parent Corn/Maize foliage image from FieldPlant (Moupojou et al., 2023)"
        items.append(it)

    # Sample 100 Tomato parent images: 0 train, 0 val, 100 out-of-training test!
    random.seed(RANDOM_SEED + 2)
    tomato_sample = random.sample(fp_groups_by_crop["tomato"], 100)
    for it in tomato_sample:
        it["split"] = "unseen_test"
        it["is_out_of_training_evaluation"] = "True"
        it["notes"] = "Unseen out-of-training Tomato foliage specimen for open-set crop generalization test"
        items.append(it)

    # Summary statistics
    print(f"Total manifest items: {len(items)}")
    crop_counts = Counter((it["domain_name"], it["source_crop"], it["split"]) for it in items)
    for k, v in sorted(crop_counts.items()):
        print(f"  Domain: {k[0]:<22} Crop: {k[1]:<12} Split: {k[2]:<12} Count: {v}")

    # Verify group-aware split integrity (No group in multiple splits)
    group_to_splits = defaultdict(set)
    for it in items:
        group_to_splits[it["group_id"]].add(it["split"])

    leaked_groups = [g for g, s in group_to_splits.items() if len(s) > 1]
    if leaked_groups:
        raise ValueError(f"Cross-split leakage detected in {len(leaked_groups)} groups: {leaked_groups[:5]}")
    print("Verification Passed: 0 cross-split leakage across all specimen groups.")

    # Write CSV Manifest
    fieldnames = [
        "image_id", "path", "sha256", "phash", "width", "height", "source", "license",
        "source_crop", "domain_name", "domain_index", "group_id", "split",
        "is_out_of_training_evaluation", "notes"
    ]
    with open(MANIFEST_OUT, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(items)
    print(f"Manifest written to: {MANIFEST_OUT}")

    # Write Split JSON
    splits_dict = {
        "train": [it["path"] for it in items if it["split"] == "train"],
        "val": [it["path"] for it in items if it["split"] == "val"],
        "test": [it["path"] for it in items if it["split"] == "test"],
        "unseen_test": [it["path"] for it in items if it["split"] == "unseen_test"]
    }

    split_payload = {
        "dataset_name": "cropdoc_crop_domain_gate",
        "random_seed": RANDOM_SEED,
        "domain_mapping": DOMAIN_MAPPING,
        "split_counts": {k: len(v) for k, v in splits_dict.items()},
        "total_images": len(items),
        "splits": splits_dict
    }

    with open(SPLIT_OUT, "w", encoding="utf-8") as f:
        json.dump(split_payload, f, indent=2)
    print(f"Split file written to: {SPLIT_OUT}")

if __name__ == "__main__":
    prepare_dataset()
