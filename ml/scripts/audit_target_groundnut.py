import os
import hashlib
from collections import defaultdict
from PIL import Image
import numpy as np

data_dir = os.path.join("data", "raw", "mendeley_groundnut")

def compute_phash(img: Image.Image) -> str:
    small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
    pixels = np.array(small, dtype=np.float32).flatten()
    avg = np.mean(pixels)
    bits = 0
    for px in pixels:
        bits = (bits << 1) | (1 if px >= avg else 0)
    return f"{bits:016x}"

# Let's check both target classes: early_leaf_spot_1 and healthy_leaf_1
classes = ["early_leaf_spot_1", "healthy_leaf_1"]

records = []
for c in classes:
    for s in ["train", "test"]:
        cdir = os.path.join(data_dir, s, c)
        for fname in os.listdir(cdir):
            fpath = os.path.join(cdir, fname)
            if not os.path.isfile(fpath):
                continue
            records.append({
                "class": c,
                "split": s,
                "fname": fname,
                "fpath": fpath,
                "is_dr": fname.startswith("dr_")
            })

print(f"Total target records: {len(records)}")

# Compute SHA and pHash
sha_map = defaultdict(list)
phash_map = defaultdict(list)
corrupt = []

for r in records:
    try:
        with open(r["fpath"], "rb") as f:
            content = f.read()
            sha = hashlib.sha256(content).hexdigest()
            r["size_bytes"] = len(content)
            r["sha256"] = sha
        with Image.open(r["fpath"]) as im:
            r["width"], r["height"] = im.size
            r["format"] = im.format
            r["mode"] = im.mode
            r["phash"] = compute_phash(im)
        sha_map[sha].append(r)
        phash_map[r["phash"]].append(r)
    except Exception as e:
        corrupt.append((r["fpath"], str(e)))

print(f"Corrupt files: {len(corrupt)}")
print(f"Unique SHA-256: {len(sha_map)}")
print(f"Exact duplicate groups: {len([v for v in sha_map.values() if len(v) > 1])}")
print(f"Unique pHash: {len(phash_map)}")
print(f"pHash dH=0 collision groups: {len([v for v in phash_map.values() if len(v) > 1])}")

# Check cross-class SHA-256 duplicates
cross_class_sha = [v for v in sha_map.values() if len(set(x["class"] for x in v)) > 1]
print(f"Cross-class exact duplicate groups: {len(cross_class_sha)}")

# Check cross-split SHA-256 duplicates
cross_split_sha = [v for v in sha_map.values() if len(set(x["split"] for x in v)) > 1]
print(f"Cross-split exact duplicate groups: {len(cross_split_sha)}")

# Check cross-class pHash dH=0 collisions
cross_class_phash = [v for v in phash_map.values() if len(set(x["class"] for x in v)) > 1]
print(f"Cross-class pHash dH=0 collision groups: {len(cross_class_phash)}")

# Check cross-split pHash dH=0 collisions
cross_split_phash = [v for v in phash_map.values() if len(set(x["split"] for x in v)) > 1]
print(f"Cross-split pHash dH=0 collision groups: {len(cross_split_phash)}")
