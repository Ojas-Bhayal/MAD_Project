import os
import re
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

records = []
for c in classes:
    for s in ["train", "test"]:
        cdir = os.path.join(data_dir, s, c)
        for fn in sorted(os.listdir(cdir)):
            p = os.path.join(cdir, fn)
            if not os.path.isfile(p):
                continue
            m = re.match(r"^dr_([0-9a-zA-Z_]+?)_(\d+)\.jpg$", fn)
            if m:
                grp = f"{c}_spec_{m.group(1)}"
            else:
                base = os.path.splitext(fn)[0]
                base_clean = re.sub(r"_\d+$", "", base)
                base_clean = re.sub(r"\s*\(\d+\)$", "", base_clean)
                grp = f"{c}_spec_{base_clean}"
                
            is_conflict = (fn in conflicting_files)
            records.append({
                "class": c,
                "fname": fn,
                "fpath": p,
                "group_id": grp,
                "is_conflict": is_conflict
            })

clean_records = [r for r in records if not r["is_conflict"]]

# Assign split by group_id
random.seed(42)
split_map = {}
for c in classes:
    c_recs = [r for r in clean_records if r["class"] == c]
    groups = list(set(r["group_id"] for r in c_recs))
    random.shuffle(groups)
    
    grp_to_recs = defaultdict(list)
    for r in c_recs:
        grp_to_recs[r["group_id"]].append(r)
        
    total_imgs = len(c_recs)
    target_train = int(total_imgs * 0.70)
    target_val = int(total_imgs * 0.15)
    
    train_cnt, val_cnt, test_cnt = 0, 0, 0
    for g in groups:
        g_cnt = len(grp_to_recs[g])
        if train_cnt + g_cnt <= target_train or (val_cnt >= target_val and test_cnt >= target_val):
            split_map[g] = "train"
            train_cnt += g_cnt
        elif val_cnt + g_cnt <= target_val or (test_cnt >= target_val):
            split_map[g] = "val"
            val_cnt += g_cnt
        else:
            split_map[g] = "test"
            test_cnt += g_cnt

for r in clean_records:
    r["split"] = split_map[r["group_id"]]

print("Checking perceptual hash cross-split collisions...")
# Compute pHash for all clean records
for r in clean_records:
    with Image.open(r["fpath"]) as im:
        r["phash"] = compute_phash(im)

phash_by_split = defaultdict(set)
for r in clean_records:
    phash_by_split[r["split"]].add(r["phash"])

c_tv = phash_by_split["train"] & phash_by_split["val"]
c_tt = phash_by_split["train"] & phash_by_split["test"]
c_vt = phash_by_split["val"] & phash_by_split["test"]

print(f"Exact pHash collisions (dH=0) Train & Val: {len(c_tv)}")
print(f"Exact pHash collisions (dH=0) Train & Test: {len(c_tt)}")
print(f"Exact pHash collisions (dH=0) Val & Test: {len(c_vt)}")
