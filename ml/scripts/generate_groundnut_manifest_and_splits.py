import os
import re
import csv
import json
import random
import hashlib
from collections import defaultdict
from PIL import Image
import numpy as np

data_dir = os.path.join("data", "raw", "mendeley_groundnut")
classes = ["early_leaf_spot_1", "healthy_leaf_1"]

conflicting_files = {
    "IMG_00686.jpg", "IMG_0685.jpg", "IMG_0686.jpg", "IMG_0688.jpg",
    "IMG_0963.jpg", "IMG_0965.jpg", "IMG_0966.jpg", "IMG_0969.jpg"
}

def compute_phash(img: Image.Image) -> str:
    small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
    pixels = np.array(small, dtype=np.float32).flatten()
    avg = np.mean(pixels)
    bits = 0
    for px in pixels:
        bits = (bits << 1) | (1 if px >= avg else 0)
    return f"{bits:016x}"

# 1. Gather all files
raw_records = []
for c in classes:
    for s in ["train", "test"]:
        cdir = os.path.join(data_dir, s, c)
        for fn in sorted(os.listdir(cdir)):
            p = os.path.join(cdir, fn)
            if not os.path.isfile(p):
                continue
            with open(p, "rb") as f:
                content = f.read()
                sha = hashlib.sha256(content).hexdigest()
            with Image.open(p) as im:
                w, h = im.size
                fmt = im.format
                ph = compute_phash(im)
                
            m = re.match(r"^dr_([0-9a-zA-Z_]+?)_(\d+)\.jpg$", fn)
            if m:
                spec_id = m.group(1)
                is_dr = True
            else:
                base = os.path.splitext(fn)[0]
                spec_id = base
                is_dr = False
                
            is_conflict = (fn in conflicting_files)
            
            # Map CropDoc class
            if c == "early_leaf_spot_1":
                cd_class = "groundnut_early_leaf_spot"
                cd_idx = 16
            else:
                cd_class = "groundnut_healthy"
                cd_idx = 17
                
            raw_records.append({
                "source_class": c,
                "cropdoc_class_name": cd_class,
                "cropdoc_class_index": cd_idx,
                "publisher_split": s,
                "file_name": fn,
                "rel_path": f"data/raw/mendeley_groundnut/{s}/{c}/{fn}",
                "size_bytes": len(content),
                "sha256": sha,
                "phash": ph,
                "phash_int": int(ph, 16),
                "width": w,
                "height": h,
                "format": fmt,
                "is_dr": is_dr,
                "raw_spec_id": f"{c}_spec_{spec_id}",
                "has_conflict": is_conflict
            })

print(f"Total target files gathered: {len(raw_records)}")

# 2. Union-Find to group same specimen derivatives and dH <= 2 collisions
parent = {}
def find(i):
    if parent[i] == i:
        return i
    parent[i] = find(parent[i])
    return parent[i]

def union(i, j):
    pi, pj = find(i), find(j)
    if pi != pj:
        parent[pi] = pj

for r in raw_records:
    parent[r["raw_spec_id"]] = r["raw_spec_id"]

# Exact SHA duplicate 90.jpg and 91_3.jpg
for r in raw_records:
    if r["sha256"] == "e84e71752224097204fc7d627c2f6d214a19b882d8c3ae06e12361ff5469ecbb":
        r["raw_spec_id"] = "healthy_leaf_1_spec_90"

# Merge dH=0 collisions within each class
ph_to_specs = defaultdict(set)
for r in raw_records:
    if not r["has_conflict"]:
        ph_to_specs[(r["source_class"], r["phash"])].add(r["raw_spec_id"])

for (cls, ph), specs in ph_to_specs.items():
    if len(specs) > 1:
        slist = list(specs)
        for s in slist[1:]:
            union(slist[0], s)

# Merge dH <= 2 pairs within each class
for c in classes:
    c_recs = [r for r in raw_records if r["source_class"] == c and not r["has_conflict"]]
    grp_hashes = defaultdict(list)
    for r in c_recs:
        grp_hashes[find(r["raw_spec_id"])].append(r["phash_int"])
        
    grps = list(grp_hashes.keys())
    for i in range(len(grps)):
        g1 = grps[i]
        hlist1 = grp_hashes[g1]
        for j in range(i + 1, len(grps)):
            g2 = grps[j]
            hlist2 = grp_hashes[g2]
            min_d = min((h1 ^ h2).bit_count() for h1 in hlist1 for h2 in hlist2)
            if min_d <= 2:
                union(g1, g2)

for r in raw_records:
    r["group_id"] = find(r["raw_spec_id"])

# 3. Generate group-aware split (Seed 42)
random.seed(42)
split_assignment = {}

clean_records = [r for r in raw_records if not r["has_conflict"]]

for c in classes:
    c_recs = [r for r in clean_records if r["source_class"] == c]
    groups = sorted(list(set(r["group_id"] for r in c_recs)))
    random.shuffle(groups)
    
    grp_recs = defaultdict(list)
    for r in c_recs:
        grp_recs[r["group_id"]].append(r)
        
    total_imgs = len(c_recs)
    target_train = int(total_imgs * 0.70)
    target_val = int(total_imgs * 0.15)
    
    train_cnt, val_cnt, test_cnt = 0, 0, 0
    for g in groups:
        g_cnt = len(grp_recs[g])
        if train_cnt + g_cnt <= target_train or (val_cnt >= target_val and test_cnt >= target_val):
            split_assignment[g] = "train"
            train_cnt += g_cnt
        elif val_cnt + g_cnt <= target_val or (test_cnt >= target_val):
            split_assignment[g] = "val"
            val_cnt += g_cnt
        else:
            split_assignment[g] = "test"
            test_cnt += g_cnt
            
    print(f"\nClass {c} split:")
    print(f"  Train: {train_cnt} images ({train_cnt/total_imgs*100:.1f}%)")
    print(f"  Val:   {val_cnt} images ({val_cnt/total_imgs*100:.1f}%)")
    print(f"  Test:  {test_cnt} images ({test_cnt/total_imgs*100:.1f}%)")

# Assign split and canonical status to each record
manifest_rows = []
grp_members = defaultdict(list)
for r in raw_records:
    grp_members[r["group_id"]].append(r)

for r in raw_records:
    fn = r["file_name"]
    is_conflict = r["has_conflict"]
    
    if is_conflict:
        assigned_split = "quarantined_conflict"
        usable = False
        dupe_status = "CROSS_CLASS_CONFLICT"
        is_canonical = False
        rejection_reason = "REJECTED_CROSS_CLASS_CONFLICT: Exact image SHA-256 matches 'nutrition_deficiency_1' in raw dataset. Quarantined to maintain pristine healthy baseline."
        notes = "Conflict with nutrition_deficiency_1"
    else:
        assigned_split = split_assignment[r["group_id"]]
        usable = True
        rejection_reason = ""
        # Check canonical representative
        # Pick non-dr original if available, else first dr_
        members = grp_members[r["group_id"]]
        non_drs = [m for m in members if not m["is_dr"]]
        if non_drs:
            canonical_fn = sorted(non_drs, key=lambda x: x["file_name"])[0]["file_name"]
        else:
            canonical_fn = sorted(members, key=lambda x: x["file_name"])[0]["file_name"]
            
        if fn == canonical_fn:
            is_canonical = True
            dupe_status = "CANONICAL_SPECIMEN_REPRESENTATIVE"
            notes = "Primary canonical physical specimen"
        else:
            is_canonical = False
            if r["sha256"] == "e84e71752224097204fc7d627c2f6d214a19b882d8c3ae06e12361ff5469ecbb" and fn == "91_3.jpg":
                dupe_status = "EXACT_SHA256_DUPLICATE"
                notes = "Exact duplicate of 90.jpg"
            else:
                dupe_status = "PRE_AUGMENTED_DERIVATIVE" if r["is_dr"] else "CO_SPECIMEN_LEAF_CAPTURE"
                notes = f"Derivative of group {r['group_id']}"
                
    image_id = r["sha256"][:16]
    
    manifest_rows.append({
        "image_id": image_id,
        "path": r["rel_path"],
        "sha256": r["sha256"],
        "phash": r["phash"],
        "width": r["width"],
        "height": r["height"],
        "actual_format": r["format"],
        "source": "mendeley_groundnut_v3",
        "license": "CC BY 4.0",
        "source_class": r["source_class"],
        "crop": "groundnut",
        "disease": "early_leaf_spot" if r["source_class"] == "early_leaf_spot_1" else "healthy",
        "cropdoc_class_name": r["cropdoc_class_name"],
        "cropdoc_class_index": r["cropdoc_class_index"],
        "group_id": r["group_id"],
        "duplicate_status": dupe_status,
        "is_canonical_representative": is_canonical,
        "usable_for_cropdoc_training": usable,
        "split": assigned_split,
        "notes": notes
    })

# Write CSV
manifest_path = os.path.join("data", "manifests", "manifest_groundnut.csv")
os.makedirs(os.path.dirname(manifest_path), exist_ok=True)
fieldnames = list(manifest_rows[0].keys())

print(f"\nWriting {len(manifest_rows)} rows to {manifest_path}...")
with open(manifest_path, "w", newline="", encoding="utf-8") as f:
    writer = csv.DictWriter(f, fieldnames=fieldnames)
    writer.writeheader()
    writer.writerows(manifest_rows)

print(f"Manifest written successfully: {manifest_path} ({os.path.getsize(manifest_path)} bytes)")

# 4. Generate split_groundnut.json
split_path = os.path.join("data", "splits", "split_groundnut.json")
os.makedirs(os.path.dirname(split_path), exist_ok=True)

train_items = [r["path"] for r in manifest_rows if r["split"] == "train"]
val_items = [r["path"] for r in manifest_rows if r["split"] == "val"]
test_items = [r["path"] for r in manifest_rows if r["split"] == "test"]
quarantined_items = [r["path"] for r in manifest_rows if r["split"] == "quarantined_conflict"]

split_data = {
    "dataset_name": "mendeley_groundnut_v3",
    "doi": "10.17632/22p2vcbxfk.3",
    "paper_doi": "10.1016/j.dib.2023.109185",
    "license": "CC BY 4.0",
    "overall_training_eligibility": "ELIGIBLE_RESEARCH_PILOT",
    "classes_included": [
        "groundnut_early_leaf_spot",
        "groundnut_healthy"
    ],
    "random_seed": 42,
    "group_aware_isolation": "GROUP_LOCKED_SPECIMEN_CLUSTERS",
    "split_counts": {
        "train": len(train_items),
        "val": len(val_items),
        "test": len(test_items),
        "quarantined": len(quarantined_items),
        "total_evaluated": len(manifest_rows)
    },
    "per_class_counts": {
        "groundnut_early_leaf_spot": {
            "train": sum(1 for r in manifest_rows if r["split"] == "train" and r["cropdoc_class_name"] == "groundnut_early_leaf_spot"),
            "val": sum(1 for r in manifest_rows if r["split"] == "val" and r["cropdoc_class_name"] == "groundnut_early_leaf_spot"),
            "test": sum(1 for r in manifest_rows if r["split"] == "test" and r["cropdoc_class_name"] == "groundnut_early_leaf_spot")
        },
        "groundnut_healthy": {
            "train": sum(1 for r in manifest_rows if r["split"] == "train" and r["cropdoc_class_name"] == "groundnut_healthy"),
            "val": sum(1 for r in manifest_rows if r["split"] == "val" and r["cropdoc_class_name"] == "groundnut_healthy"),
            "test": sum(1 for r in manifest_rows if r["split"] == "test" and r["cropdoc_class_name"] == "groundnut_healthy")
        }
    },
    "splits": {
        "train": train_items,
        "val": val_items,
        "test": test_items
    }
}

with open(split_path, "w", encoding="utf-8") as f:
    json.dump(split_data, f, indent=2)

print(f"Split file written successfully: {split_path}")
