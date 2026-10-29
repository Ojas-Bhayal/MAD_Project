import json
import os
import glob
from collections import defaultdict, Counter
from PIL import Image
import hashlib

data_dir = os.path.join("data", "raw", "fieldplant")
train_dir = os.path.join(data_dir, "train")
coco_path = os.path.join(train_dir, "_annotations.coco.json")

print(f"Loading {coco_path}...")
with open(coco_path, "r", encoding="utf-8") as f:
    coco = json.load(f)

images = coco.get("images", [])
annotations = coco.get("annotations", [])
categories = coco.get("categories", [])

cat_by_id = {c["id"]: c["name"] for c in categories}
img_by_id = {img["id"]: img for img in images}

print(f"Total categories: {len(categories)}")
print(f"Total images declared in COCO: {len(images)}")
print(f"Total annotations declared in COCO: {len(annotations)}")

# Map annotations to image
ann_by_img = defaultdict(list)
cat_ann_count = Counter()
cat_img_count = defaultdict(set)

# Check annotation validity
invalid_boxes = []
for ann in annotations:
    cid = ann["category_id"]
    cat_name = cat_by_id.get(cid, f"Unknown_{cid}")
    cat_ann_count[cat_name] += 1
    iid = ann["image_id"]
    cat_img_count[cat_name].add(iid)
    ann_by_img[iid].append(ann)
    
    bbox = ann.get("bbox", [])
    if len(bbox) != 4:
        invalid_boxes.append((ann["id"], "bbox length != 4", bbox))
    else:
        x, y, w, h = bbox
        if w <= 0 or h <= 0:
            invalid_boxes.append((ann["id"], "non-positive dimension", bbox))
        if ann.get("area", 0) <= 0:
            invalid_boxes.append((ann["id"], "non-positive area", bbox))

print(f"\nInvalid annotations: {len(invalid_boxes)}")

print("\n--- Per-Class Distribution ---")
print(f"{'ID':<4} {'Class Name':<32} {'Leaf Annotations':<18} {'Parent Images':<15}")
for cat in sorted(categories, key=lambda c: c["id"]):
    cid = cat["id"]
    cname = cat["name"]
    ann_cnt = cat_ann_count[cname]
    img_cnt = len(cat_img_count[cname])
    print(f"{cid:<4} {cname:<32} {ann_cnt:<18} {img_cnt:<15}")

# Check leaf count per parent image
leaves_per_img = [len(ann_by_img[img["id"]]) for img in images]
leaf_hist = Counter(leaves_per_img)
print("\n--- Leaves per Parent Image Distribution ---")
for k in sorted(leaf_hist.keys()):
    print(f"  {k} leaves/image: {leaf_hist[k]} images")

# Check images with multiple distinct classes
multi_class_imgs = 0
for iid, anns in ann_by_img.items():
    cats = set(a["category_id"] for a in anns)
    if len(cats) > 1:
        multi_class_imgs += 1
print(f"\nParent images with multiple disease classes: {multi_class_imgs}")

# Check files on disk
disk_files = set(os.listdir(train_dir))
disk_files.discard("_annotations.coco.json")
print(f"\nImages on disk in train/: {len(disk_files)}")

coco_filenames = set(img["file_name"] for img in images)
missing_on_disk = coco_filenames - disk_files
extra_on_disk = disk_files - coco_filenames
print(f"COCO images missing on disk: {len(missing_on_disk)}")
print(f"Extra files on disk not in COCO: {len(extra_on_disk)}")

# Sample check image readability and bounding box boundaries
oob_boxes = []
small_boxes = []
for ann in annotations:
    iid = ann["image_id"]
    img_info = img_by_id[iid]
    img_w, img_h = img_info["width"], img_info["height"]
    x, y, w, h = ann["bbox"]
    if x < 0 or y < 0 or (x + w) > img_w + 1 or (y + h) > img_h + 1:
        oob_boxes.append((ann["id"], x, y, w, h, img_w, img_h))
    if w < 10 or h < 10:
        small_boxes.append((ann["id"], w, h))

print(f"Out-of-bounds bounding boxes: {len(oob_boxes)}")
print(f"Extremely small bounding boxes (<10px width or height): {len(small_boxes)}")
