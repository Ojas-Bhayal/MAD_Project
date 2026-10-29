import json
import os
import hashlib
from collections import defaultdict, Counter
from PIL import Image
import numpy as np

train_dir = os.path.join("data", "raw", "fieldplant", "train")
coco_path = os.path.join(train_dir, "_annotations.coco.json")

print("Loading COCO...")
with open(coco_path, "r", encoding="utf-8") as f:
    coco = json.load(f)

images = coco.get("images", [])
annotations = coco.get("annotations", [])
categories = coco.get("categories", [])
cat_by_id = {c["id"]: c["name"] for c in categories}
img_by_id = {im["id"]: im for im in images}

# Map annotations to image
ann_by_img = defaultdict(list)
for a in annotations:
    ann_by_img[a["image_id"]].append(a)

def compute_phash(img: Image.Image) -> str:
    small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
    pixels = np.array(small, dtype=np.float32).flatten()
    avg = np.mean(pixels)
    bits = 0
    for px in pixels:
        bits = (bits << 1) | (1 if px >= avg else 0)
    return f"{bits:016x}"

# We compute or collect img records
print("Computing image metadata...")
img_records = {}
sha_to_imgs = defaultdict(list)
phash_to_imgs = defaultdict(list)

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
    
    rec = {
        "id": iid,
        "file_name": fname,
        "path": fpath,
        "sha256": sha,
        "phash": ph,
        "width": w,
        "height": h,
        "format": fmt
    }
    img_records[iid] = rec
    sha_to_imgs[sha].append(iid)
    phash_to_imgs[ph].append(iid)

print("Exact SHA groups:", len(sha_to_imgs))
exact_dupes = {s: ids for s, ids in sha_to_imgs.items() if len(ids) > 1}
print(f"Exact duplicate groups: {len(exact_dupes)}, total images in dupes: {sum(len(v) for v in exact_dupes.values())}")

# Check annotation differences in exact duplicates
exact_dupe_ann_conflicts = 0
exact_dupe_identical_anns = 0
for sha, iids in exact_dupes.items():
    # Compare annotations of all iids in this sha group
    cat_sets = [sorted([a["category_id"] for a in ann_by_img[i]]) for i in iids]
    if all(cs == cat_sets[0] for cs in cat_sets):
        exact_dupe_identical_anns += 1
    else:
        exact_dupe_ann_conflicts += 1

print(f"Exact duplicates with identical category annotations: {exact_dupe_identical_anns}")
print(f"Exact duplicates with conflicting category annotations: {exact_dupe_ann_conflicts}")

# Check dH=0 perceptual collisions
phash_dupes = {ph: ids for ph, ids in phash_to_imgs.items() if len(ids) > 1}
print(f"Perceptual hash collision groups (dH=0): {len(phash_dupes)}, total images: {sum(len(v) for v in phash_dupes.values())}")

# Check bounding box quality
total_boxes = len(annotations)
oob_count = 0
clipped_count = 0
tiny_boxes = 0
extreme_aspect = 0

box_stats = []
for a in annotations:
    iid = a["image_id"]
    im = img_records[iid]
    w_img, h_img = im["width"], im["height"]
    x, y, w, h = a["bbox"]
    
    # check if needs clipping
    x1 = max(0, x)
    y1 = max(0, y)
    x2 = min(w_img, x + w)
    y2 = min(h_img, y + h)
    
    if x < 0 or y < 0 or (x + w) > w_img or (y + h) > h_img:
        clipped_count += 1
    
    crop_w = max(0, x2 - x1)
    crop_h = max(0, y2 - y1)
    
    if crop_w < 32 or crop_h < 32 or (crop_w * crop_h) < 1024:
        tiny_boxes += 1
    
    aspect = max(crop_w / max(crop_h, 1e-5), crop_h / max(crop_w, 1e-5))
    if aspect > 8.0:
        extreme_aspect += 1
        
    box_stats.append({
        "ann_id": a["id"],
        "image_id": iid,
        "category_id": a["category_id"],
        "category_name": cat_by_id[a["category_id"]],
        "crop_w": crop_w,
        "crop_h": crop_h,
        "area": crop_w * crop_h,
        "aspect_ratio": aspect
    })

print(f"\n--- Bounding Box Audit (Total {total_boxes} boxes) ---")
print(f"Boxes needing boundary clipping: {clipped_count}")
print(f"Tiny boxes (<32px or <1024 area): {tiny_boxes}")
print(f"Extreme aspect ratio boxes (>8:1): {extreme_aspect}")

# Per-category summary
cat_summary = {}
for cid in sorted(cat_by_id.keys()):
    cname = cat_by_id[cid]
    c_boxes = [b for b in box_stats if b["category_id"] == cid]
    c_imgs = set(b["image_id"] for b in c_boxes)
    c_sha = set(img_records[iid]["sha256"] for iid in c_imgs)
    cat_summary[cname] = {
        "category_id": cid,
        "total_leaf_annotations": len(c_boxes),
        "total_parent_images": len(c_imgs),
        "unique_parent_sha256": len(c_sha),
        "tiny_boxes": sum(1 for b in c_boxes if b["crop_w"] < 32 or b["crop_h"] < 32 or b["area"] < 1024),
        "extreme_aspect": sum(1 for b in c_boxes if b["aspect_ratio"] > 8.0)
    }

print("\n--- Per-Category Full Audit ---")
print(f"{'Category':<32} {'Leaves':<8} {'Imgs':<8} {'Unique SHA':<12} {'Tiny':<6} {'Extreme AR':<10}")
for cname, cs in cat_summary.items():
    print(f"{cname:<32} {cs['total_leaf_annotations']:<8} {cs['total_parent_images']:<8} {cs['unique_parent_sha256']:<12} {cs['tiny_boxes']:<6} {cs['extreme_aspect']:<10}")

output_data = {
    "cat_summary": cat_summary,
    "exact_dupe_groups": len(exact_dupes),
    "exact_dupe_images": sum(len(v) for v in exact_dupes.values()),
    "exact_dupe_identical_anns": exact_dupe_identical_anns,
    "exact_dupe_ann_conflicts": exact_dupe_ann_conflicts,
    "phash_dupes_count": len(phash_dupes),
    "clipped_boxes": clipped_count,
    "tiny_boxes": tiny_boxes,
    "extreme_aspect_boxes": extreme_aspect
}

with open("ml/scripts/fieldplant_detailed_audit_metrics.json", "w") as f:
    json.dump(output_data, f, indent=2)

print("\nSaved detailed metrics to ml/scripts/fieldplant_detailed_audit_metrics.json")
