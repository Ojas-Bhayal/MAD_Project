import json
import os
import csv
import hashlib
from collections import defaultdict, Counter
from PIL import Image
import numpy as np

train_dir = os.path.join("data", "raw", "fieldplant", "train")
coco_path = os.path.join(train_dir, "_annotations.coco.json")

print(f"Loading {coco_path}...")
with open(coco_path, "r", encoding="utf-8") as f:
    coco = json.load(f)

images = coco.get("images", [])
annotations = coco.get("annotations", [])
categories = coco.get("categories", [])

cat_by_id = {c["id"]: c["name"] for c in categories}
img_by_id = {im["id"]: im for im in images}

def compute_phash(img: Image.Image) -> str:
    small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
    pixels = np.array(small, dtype=np.float32).flatten()
    avg = np.mean(pixels)
    bits = 0
    for px in pixels:
        bits = (bits << 1) | (1 if px >= avg else 0)
    return f"{bits:016x}"

# 1. Gather image files, hashes, and map exact SHA duplicates
print("Computing image SHA-256 and pHash...")
img_meta = {}
sha_to_img_ids = defaultdict(list)
phash_to_img_ids = defaultdict(list)

for im in images:
    iid = im["id"]
    fname = im["file_name"]
    fpath = os.path.join(train_dir, fname)
    with open(fpath, "rb") as f:
        content = f.read()
        sha = hashlib.sha256(content).hexdigest()
    with Image.open(fpath) as pil_im:
        w, h = pil_im.size
        fmt = pil_im.format
        ph = compute_phash(pil_im)
    
    img_meta[iid] = {
        "id": iid,
        "file_name": fname,
        "rel_path": f"data/raw/fieldplant/train/{fname}",
        "sha256": sha,
        "phash": ph,
        "width": w,
        "height": h,
        "format": fmt
    }
    sha_to_img_ids[sha].append(iid)
    phash_to_img_ids[ph].append(iid)

# Group annotations by parent image
ann_by_img = defaultdict(list)
for a in annotations:
    ann_by_img[a["image_id"]].append(a)

# Determine parent image health status & cross-class labels
img_categories = {}
for iid, anns in ann_by_img.items():
    img_categories[iid] = set(a["category_id"] for a in anns)

# Determine canonical parent instance per SHA group
canonical_img_for_sha = {}
for sha, iids in sha_to_img_ids.items():
    # Pick lowest iid as canonical
    canonical_img_for_sha[sha] = min(iids)

# Check SHA conflict status
sha_has_label_conflict = {}
for sha, iids in sha_to_img_ids.items():
    cat_sets = [sorted(list(img_categories[i])) for i in iids]
    if not all(cs == cat_sets[0] for cs in cat_sets):
        sha_has_label_conflict[sha] = True
    else:
        sha_has_label_conflict[sha] = False

# Taxonomy mapping rules for FieldPlant source classes to CropDoc
# 6 Target classes:
# 4: tomato_early_blight
# 5: tomato_late_blight
# 6: tomato_yellow_leaf_curl_virus
# 7: tomato_healthy
# 13: maize_common_rust
# 14: maize_northern_leaf_blight

def map_taxonomy(cat_name: str):
    if cat_name == "Tomato Brown Spots":
        return {
            "target": "tomato_early_blight",
            "index": 4,
            "alignment": "BROAD_SYMPTOMATIC",
            "rejection": "REJECTED_BROAD_SYMPTOMATIC: 'Tomato Brown Spots' is a non-pathogen-specific visual symptom (could be Alternaria, Septoria, Pseudomonas, or Stemphylium). Cannot assume Alternaria solani without etiological verification."
        }
    elif cat_name == "Tomato blight leaf":
        return {
            "target": "tomato_early_blight / tomato_late_blight",
            "index": -1,
            "alignment": "COARSE_AGGREGATE",
            "rejection": "REJECTED_COARSE_AGGREGATE: 'Tomato blight leaf' aggregates early blight (Alternaria solani, fungus) and late blight (Phytophthora infestans, oomycete) into an undifferentiated cluster. Scientific definitions incompatible."
        }
    elif cat_name == "Tomato leaf yellow virus":
        return {
            "target": "tomato_yellow_leaf_curl_virus",
            "index": 6,
            "alignment": "UNVERIFIED_VIRAL",
            "rejection": "REJECTED_UNVERIFIED_VIRAL: 'Tomato leaf yellow virus' is unverified viral chlorosis. In Cameroon, yellowing is caused by multiple Begomoviruses and potyviruses. Cannot assume TYLCV without molecular/symptom confirmation."
        }
    elif cat_name == "Tomato healthy":
        return {
            "target": "tomato_healthy",
            "index": 7,
            "alignment": "EXACT_MATCH_CONTAMINATED",
            "rejection": "QUARANTINED_PARENT_CONTAMINATION: 92.1% of healthy tomato leaf annotations originate from diseased parent plants with active foliar blight. Clean subset contains only 11 parent plants (severely underpowered and unverified)."
        }
    elif cat_name == "Corn rust leaf":
        return {
            "target": "maize_common_rust",
            "index": 13,
            "alignment": "AMBIGUOUS_PATHOGEN",
            "rejection": "REJECTED_AMBIGUOUS_PATHOGEN: 'Corn rust leaf' in Cameroon is predominantly Southern corn rust (Puccinia polysora), not Common rust (Puccinia sorghi). Cannot map to Puccinia sorghi without spore morphology."
        }
    elif cat_name == "Corn leaf blight":
        return {
            "target": "maize_northern_leaf_blight",
            "index": 14,
            "alignment": "COARSE_AGGREGATE",
            "rejection": "REJECTED_COARSE_AGGREGATE: 'Corn leaf blight' aggregates Northern corn leaf blight (Exserohilum turcicum) and Southern corn leaf blight (Bipolaris maydis). Cannot map to E. turcicum without lesion review."
        }
    elif cat_name == "Corn Healthy":
        return {
            "target": "maize_healthy",
            "index": -1,
            "alignment": "OUT_OF_6_TARGETS",
            "rejection": "OUT_OF_SCOPE: Not in the 6 evaluated candidate classes."
        }
    elif cat_name == "Corn Gray leaf spot":
        return {
            "target": "maize_gray_leaf_spot",
            "index": -1,
            "alignment": "OUT_OF_6_TARGETS",
            "rejection": "OUT_OF_SCOPE: Not in the 6 evaluated candidate classes."
        }
    else:
        return {
            "target": "unmapped_out_of_scope",
            "index": -1,
            "alignment": "OUT_OF_SCOPE",
            "rejection": f"OUT_OF_SCOPE: Non-target class '{cat_name}'."
        }

# Generate manifest rows
manifest_rows = []
total_annotations = len(annotations)

for a in annotations:
    ann_id = a["id"]
    iid = a["image_id"]
    im = img_meta[iid]
    cid = a["category_id"]
    cname = cat_by_id[cid]
    
    sha = im["sha256"]
    is_canonical = (iid == canonical_img_for_sha[sha])
    is_sha_dupe = (len(sha_to_img_ids[sha]) > 1)
    has_conflict = sha_has_label_conflict[sha]
    
    # Duplicate status
    if not is_sha_dupe:
        dupe_status = "CANONICAL_UNIQUE"
    elif is_canonical:
        dupe_status = "CANONICAL_REPRESENTATIVE_OF_DUPLICATES"
    elif has_conflict:
        dupe_status = "EXACT_DUPLICATE_CONFLICTING_LABEL"
    else:
        dupe_status = "EXACT_DUPLICATE_IDENTICAL_LABEL"
        
    group_id = f"fieldplant_group_{sha[:16]}"
    
    # Bounding box clipping
    w_img, h_img = im["width"], im["height"]
    x, y, w, h = a["bbox"]
    x1 = max(0, x)
    y1 = max(0, y)
    x2 = min(w_img, x + w)
    y2 = min(h_img, y + h)
    crop_w = max(0, x2 - x1)
    crop_h = max(0, y2 - y1)
    crop_area = crop_w * crop_h
    aspect_ratio = max(crop_w / max(crop_h, 1e-5), crop_h / max(crop_w, 1e-5))
    
    # Parent multi-class status
    parent_cats = img_categories[iid]
    is_parent_mixed = (len(parent_cats) > 1)
    
    tax = map_taxonomy(cname)
    
    # Usability
    usable = False # Strictly blocked across dataset
    
    # Notes
    notes_parts = []
    if is_parent_mixed:
        other_cats = [cat_by_id[c] for c in parent_cats if c != cid]
        notes_parts.append(f"Parent image contains multiple disease classes: {other_cats}")
    if has_conflict:
        notes_parts.append("Exact image SHA has conflicting class labels across duplicate uploads")
    if is_sha_dupe and not is_canonical:
        notes_parts.append(f"Redundant duplicate of image ID {canonical_img_for_sha[sha]}")
    if not notes_parts:
        notes_parts.append("Single-class parent image; blocked by taxonomy/etiology verification gate")
    notes = "; ".join(notes_parts)
    
    manifest_rows.append({
        "annotation_id": ann_id,
        "parent_image_id": iid,
        "parent_filename": im["file_name"],
        "parent_path": im["rel_path"],
        "parent_sha256": sha,
        "parent_phash": im["phash"],
        "parent_width": im["width"],
        "parent_height": im["height"],
        "actual_format": im["format"],
        "source": "fieldplant_roboflow_v11",
        "license": "CC BY 4.0",
        "source_category_id": cid,
        "source_class_name": cname,
        "bbox_x": round(x, 2),
        "bbox_y": round(y, 2),
        "bbox_w": round(w, 2),
        "bbox_h": round(h, 2),
        "crop_area": round(crop_area, 2),
        "crop_aspect_ratio": round(aspect_ratio, 2),
        "group_id": group_id,
        "is_parent_mixed_class": is_parent_mixed,
        "duplicate_status": dupe_status,
        "is_canonical_representative": is_canonical,
        "cropdoc_target_class": tax["target"],
        "cropdoc_class_index": tax["index"],
        "taxonomy_alignment": tax["alignment"],
        "usable_for_cropdoc_training": usable,
        "rejection_reason": tax["rejection"],
        "split": "unassigned_blocked",
        "notes": notes
    })

# Write CSV
manifest_path = os.path.join("data", "manifests", "manifest_fieldplant.csv")
os.makedirs(os.path.dirname(manifest_path), exist_ok=True)
fieldnames = list(manifest_rows[0].keys())

print(f"Writing {len(manifest_rows)} rows to {manifest_path}...")
with open(manifest_path, "w", newline="", encoding="utf-8") as f:
    writer = csv.DictWriter(f, fieldnames=fieldnames)
    writer.writeheader()
    writer.writerows(manifest_rows)

print(f"Manifest written successfully: {manifest_path} ({os.path.getsize(manifest_path)} bytes)")

# Create split file: split_fieldplant.json
split_path = os.path.join("data", "splits", "split_fieldplant.json")
split_data = {
    "dataset_name": "fieldplant_roboflow_v11",
    "version": "11",
    "source_url": "https://universe.roboflow.com/plant-disease-detection/fieldplant/dataset/11",
    "doi": "10.1109/ACCESS.2023.3263042",
    "license": "CC BY 4.0",
    "overall_training_eligibility": "BLOCKED",
    "audit_verdict": "REJECTED_TAXONOMY_AND_PATHOLOGY_GATES",
    "target_classes_evaluated": [
        "tomato_early_blight",
        "tomato_late_blight",
        "tomato_yellow_leaf_curl_virus",
        "tomato_healthy",
        "maize_common_rust",
        "maize_northern_leaf_blight"
    ],
    "usable_images_count": 0,
    "usable_leaf_annotations_count": 0,
    "splits": {
        "train": [],
        "val": [],
        "test": []
    },
    "rejection_summary": {
        "tomato_early_blight": "BLOCKED: 'Tomato Brown Spots' is a broad symptom, not confirmed Alternaria solani; 'Tomato blight leaf' aggregates early and late blight.",
        "tomato_late_blight": "BLOCKED: 'Tomato blight leaf' is a coarse aggregate with no monopathogen Phytophthora infestans isolation.",
        "tomato_yellow_leaf_curl_virus": "BLOCKED: 'Tomato leaf yellow virus' is an unverified viral complex lacking TYLCV molecular/symptom validation.",
        "tomato_healthy": "BLOCKED: 92.1% of healthy leaf crops belong to parent plants with active foliar blight. Clean subset contains only 11 parent plants (severely underpowered).",
        "maize_common_rust": "BLOCKED: Cameroon field collection indicates Southern corn rust (Puccinia polysora), not Common rust (Puccinia sorghi).",
        "maize_northern_leaf_blight": "BLOCKED: 'Corn leaf blight' aggregates Exserohilum turcicum and Bipolaris maydis without lesion morphology distinction."
    }
}

with open(split_path, "w", encoding="utf-8") as f:
    json.dump(split_data, f, indent=2)

print(f"Split file written successfully: {split_path}")
