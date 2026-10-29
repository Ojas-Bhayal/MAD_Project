import json
import os
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

print(f"Processing {len(images)} images and {len(annotations)} annotations...")

def compute_phash(img: Image.Image) -> str:
    small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
    pixels = np.array(small, dtype=np.float32).flatten()
    avg = np.mean(pixels)
    bits = 0
    for px in pixels:
        bits = (bits << 1) | (1 if px >= avg else 0)
    return f"{bits:016x}"

# 1. Inspect image files on disk
img_records = {}
sha_groups = defaultdict(list)
corrupt_images = []

for idx, img_info in enumerate(images):
    iid = img_info["id"]
    fname = img_info["file_name"]
    fpath = os.path.join(train_dir, fname)
    
    if not os.path.isfile(fpath):
        corrupt_images.append((iid, fname, "Missing on disk"))
        continue
    
    try:
        with open(fpath, "rb") as f:
            content = f.read()
            sha = hashlib.sha256(content).hexdigest()
            file_size = len(content)
        
        with Image.open(fpath) as im:
            w, h = im.size
            fmt = im.format
            ph = compute_phash(im)
            
        img_records[iid] = {
            "id": iid,
            "file_name": fname,
            "path": fpath,
            "size_bytes": file_size,
            "sha256": sha,
            "phash": ph,
            "width": w,
            "height": h,
            "format": fmt
        }
        sha_groups[sha].append(iid)
    except Exception as e:
        corrupt_images.append((iid, fname, str(e)))

print(f"Successfully processed {len(img_records)} images.")
print(f"Corrupt/unreadable images: {len(corrupt_images)}")
print(f"Unique SHA-256 hashes: {len(sha_groups)}")
exact_dupes = {s: ids for s, ids in sha_groups.items() if len(ids) > 1}
print(f"Exact duplicate SHA-256 groups: {len(exact_dupes)} (encompassing {sum(len(v) for v in exact_dupes.values())} image files)")

# Save intermediate summary
summary = {
    "total_images": len(images),
    "processed_images": len(img_records),
    "corrupt_images": corrupt_images,
    "unique_sha256": len(sha_groups),
    "exact_dupe_groups_count": len(exact_dupes),
    "exact_dupe_images_count": sum(len(v) for v in exact_dupes.values())
}
with open("ml/scripts/fieldplant_image_audit_summary.json", "w") as f:
    json.dump(summary, f, indent=2)

print("Saved summary to ml/scripts/fieldplant_image_audit_summary.json")
