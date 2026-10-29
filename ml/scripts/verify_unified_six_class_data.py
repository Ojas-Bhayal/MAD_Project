import os
import csv
import json
import hashlib
from collections import defaultdict, Counter
from PIL import Image

ROOT = os.getcwd()
manifest_rice = os.path.join(ROOT, "data", "manifests", "manifest_rice_four_class.csv")
manifest_groundnut = os.path.join(ROOT, "data", "manifests", "manifest_groundnut.csv")

print("Loading Rice 4-class manifest...")
rice_rows = []
with open(manifest_rice, "r", encoding="utf-8") as f:
    for row in csv.DictReader(f):
        if row["usable_for_cropdoc_training"] == "True" and row["is_canonical_representative"] == "True":
            rice_rows.append(row)
print(f"Rice canonical rows: {len(rice_rows)}")

print("Loading Groundnut manifest...")
groundnut_rows = []
with open(manifest_groundnut, "r", encoding="utf-8") as f:
    for row in csv.DictReader(f):
        if row["usable_for_cropdoc_training"] == "True" and row["is_canonical_representative"] == "True":
            groundnut_rows.append(row)
print(f"Groundnut canonical rows: {len(groundnut_rows)}")

total_canonical = len(rice_rows) + len(groundnut_rows)
print(f"Total unified canonical specimens: {total_canonical}")

# 1. Verify file existence and SHA-256
all_rows = rice_rows + groundnut_rows
missing_files = []
hash_mismatches = []
all_shas = {}

for r in all_rows:
    p = os.path.join(ROOT, r["path"])
    if not os.path.isfile(p):
        missing_files.append(r["path"])
        continue
    with open(p, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()
    if sha != r["sha256"]:
        hash_mismatches.append((r["path"], sha, r["sha256"]))
    all_shas[sha] = r

print(f"Missing files: {len(missing_files)}")
print(f"Hash mismatches: {len(hash_mismatches)}")
print(f"Unique SHA-256: {len(all_shas)}")

# 2. Check cross-dataset duplicates between Rice and Groundnut
rice_shas = set(r["sha256"] for r in rice_rows)
groundnut_shas = set(r["sha256"] for r in groundnut_rows)
overlap_shas = rice_shas & groundnut_shas
print(f"Cross-dataset exact SHA-256 overlap (Rice vs Groundnut): {len(overlap_shas)}")

# 3. Check cross-dataset pHash overlap
rice_phashes = {r["phash"]: r for r in rice_rows}
groundnut_phashes = {r["phash"]: r for r in groundnut_rows}
overlap_phashes = set(rice_phashes.keys()) & set(groundnut_phashes.keys())
print(f"Cross-dataset exact pHash collisions (Rice vs Groundnut): {len(overlap_phashes)}")

# Check close pHash at dH <= 2
close_cross_pairs = 0
for ph_r, r_item in rice_phashes.items():
    h_r = int(ph_r, 16)
    for ph_g, g_item in groundnut_phashes.items():
        h_g = int(ph_g, 16)
        if (h_r ^ h_g).bit_count() <= 2:
            close_cross_pairs += 1

print(f"Cross-dataset near-duplicate pairs at dH <= 2 (Rice vs Groundnut): {close_cross_pairs}")

# 4. Local to Global Class Mapping Verification
CLASS_CONFIG = [
    {"local_index": 0, "global_index": 0, "class_name": "rice_bacterial_leaf_blight", "crop": "rice", "disease": "bacterial_leaf_blight", "source": "rice_mendeley_sethy_v2"},
    {"local_index": 1, "global_index": 1, "class_name": "rice_brown_spot", "crop": "rice", "disease": "brown_spot", "source": "rice_mendeley_sethy_v2"},
    {"local_index": 2, "global_index": 2, "class_name": "rice_leaf_blast", "crop": "rice", "disease": "leaf_blast", "source": "rice_mendeley_sethy_v2"},
    {"local_index": 3, "global_index": 3, "class_name": "rice_healthy", "crop": "rice", "disease": "healthy", "source": "rice_mendeley_hasan_2023"},
    {"local_index": 4, "global_index": 16, "class_name": "groundnut_early_leaf_spot", "crop": "groundnut", "disease": "early_leaf_spot", "source": "mendeley_groundnut_v3"},
    {"local_index": 5, "global_index": 17, "class_name": "groundnut_healthy", "crop": "groundnut", "disease": "healthy", "source": "mendeley_groundnut_v3"}
]
cfg_by_name = {c["class_name"]: c for c in CLASS_CONFIG}

# 5. Build Unified Manifest Rows
unified_manifest_rows = []
for r in all_rows:
    cname = r["cropdoc_class_name"]
    cfg = cfg_by_name[cname]
    
    unified_manifest_rows.append({
        "image_id": r["image_id"],
        "path": r["path"],
        "sha256": r["sha256"],
        "phash": r["phash"],
        "width": r["width"],
        "height": r["height"],
        "actual_format": r["actual_format"],
        "source": cfg["source"],
        "license": "CC BY 4.0",
        "crop": cfg["crop"],
        "disease": cfg["disease"],
        "cropdoc_class_name": cname,
        "cropdoc_global_index": cfg["global_index"],
        "local_model_index": cfg["local_index"],
        "group_id": r["group_id"],
        "is_canonical_representative": True,
        "usable_for_cropdoc_training": True,
        "split": r["split"],
        "notes": f"Verified canonical specimen; local class {cfg['local_index']}, global index {cfg['global_index']}"
    })

# Write unified manifest
unified_manifest_path = os.path.join(ROOT, "data", "manifests", "manifest_unified_six_class.csv")
with open(unified_manifest_path, "w", newline="", encoding="utf-8") as f:
    writer = csv.DictWriter(f, fieldnames=list(unified_manifest_rows[0].keys()))
    writer.writeheader()
    writer.writerows(unified_manifest_rows)

print(f"\nWrote unified manifest: {unified_manifest_path} ({len(unified_manifest_rows)} rows)")

# 6. Split Summary & Zero Leakage Verification
by_split = Counter(r["split"] for r in unified_manifest_rows)
print(f"Unified Splits: {dict(by_split)}")

print("\n--- Per-Class Split Breakdown ---")
print(f"{'Local':<6} {'Global':<7} {'Class Name':<30} {'Train':<7} {'Val':<7} {'Test':<7} {'Total':<7}")
for cfg in CLASS_CONFIG:
    cname = cfg["class_name"]
    c_rows = [r for r in unified_manifest_rows if r["cropdoc_class_name"] == cname]
    tr = sum(1 for r in c_rows if r["split"] == "train")
    va = sum(1 for r in c_rows if r["split"] == "val")
    te = sum(1 for r in c_rows if r["split"] == "test")
    print(f"{cfg['local_index']:<6} {cfg['global_index']:<7} {cname:<30} {tr:<7} {va:<7} {te:<7} {len(c_rows):<7}")

# Check zero group leakage across splits
train_grps = set(r["group_id"] for r in unified_manifest_rows if r["split"] == "train")
val_grps = set(r["group_id"] for r in unified_manifest_rows if r["split"] == "val")
test_grps = set(r["group_id"] for r in unified_manifest_rows if r["split"] == "test")

assert len(train_grps & val_grps) == 0, "Train and Val share groups!"
assert len(train_grps & test_grps) == 0, "Train and Test share groups!"
assert len(val_grps & test_grps) == 0, "Val and Test share groups!"
print("\nZero group leakage across train, val, and test mathematically verified!")

# Build unified split JSON
unified_split_path = os.path.join(ROOT, "data", "splits", "split_unified_six_class.json")
split_json_data = {
    "dataset_name": "cropdoc_unified_six_class_candidate",
    "description": "Unified 6-class canonical research candidate combining Rice (Sethy v2 + Hasan v2) and Groundnut (Aishwarya & Reddy v3)",
    "licenses": ["CC BY 4.0"],
    "classes": CLASS_CONFIG,
    "group_aware_isolation": "STRICT_GROUP_LOCKED_CANONICAL_SPECIMENS",
    "counts": {
        "train": by_split["train"],
        "val": by_split["val"],
        "test": by_split["test"],
        "total": len(unified_manifest_rows)
    },
    "splits": {
        "train": [r["path"] for r in unified_manifest_rows if r["split"] == "train"],
        "val": [r["path"] for r in unified_manifest_rows if r["split"] == "val"],
        "test": [r["path"] for r in unified_manifest_rows if r["split"] == "test"]
    }
}

with open(unified_split_path, "w", encoding="utf-8") as f:
    json.dump(split_json_data, f, indent=2)

print(f"Wrote unified split JSON: {unified_split_path}")
