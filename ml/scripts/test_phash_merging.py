import os
import re
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

# Load all clean records
records = []
for c in classes:
    for s in ["train", "test"]:
        cdir = os.path.join(data_dir, s, c)
        for fn in sorted(os.listdir(cdir)):
            p = os.path.join(cdir, fn)
            if not os.path.isfile(p):
                continue
            if fn in conflicting_files:
                continue
            with open(p, "rb") as f:
                sha = hashlib.sha256(f.read()).hexdigest()
            with Image.open(p) as im:
                ph = compute_phash(im)
            
            # initial group from filename
            m = re.match(r"^dr_([0-9a-zA-Z_]+?)_(\d+)\.jpg$", fn)
            if m:
                spec_id = m.group(1)
            else:
                base = os.path.splitext(fn)[0]
                spec_id = base
                
            records.append({
                "class": c,
                "publisher_split": s,
                "fname": fn,
                "fpath": p,
                "sha256": sha,
                "phash": ph,
                "phash_int": int(ph, 16),
                "spec_id": f"{c}_spec_{spec_id}"
            })

print(f"Loaded {len(records)} clean records.")

# Union-Find / Connected components for grouping:
# 1. Start with spec_id as cluster
# 2. If any two images have dH == 0 within same class, merge their clusters!
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

for r in records:
    parent[r["spec_id"]] = r["spec_id"]

# Check dH == 0 collisions within each class
ph_to_specs = defaultdict(set)
for r in records:
    ph_to_specs[(r["class"], r["phash"])].add(r["spec_id"])

merged_count = 0
for (cls, ph), specs in ph_to_specs.items():
    if len(specs) > 1:
        slist = list(specs)
        for s in slist[1:]:
            union(slist[0], s)
            merged_count += 1

print(f"Clusters merged via dH=0: {merged_count}")

# Assign final resolved cluster
for r in records:
    r["final_group"] = find(r["spec_id"])

groups_by_class = defaultdict(set)
for r in records:
    groups_by_class[r["class"]].add(r["final_group"])

print("\nFinal Specimen Clusters per Class:")
for c, grps in groups_by_class.items():
    print(f"  {c}: {len(grps)} independent clusters across {sum(1 for r in records if r['class'] == c)} images")
