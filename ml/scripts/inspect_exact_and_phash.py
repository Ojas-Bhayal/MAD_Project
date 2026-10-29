import os
import hashlib
from collections import defaultdict
from PIL import Image

data_dir = os.path.join("data", "raw", "mendeley_groundnut")

def compute_phash(img: Image.Image) -> str:
    small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
    pixels = list(small.getdata())
    avg = sum(pixels) / len(pixels)
    bits = 0
    for px in pixels:
        bits = (bits << 1) | (1 if px >= avg else 0)
    return f"{bits:016x}"

classes = ["early_leaf_spot_1", "healthy_leaf_1"]

records = []
for c in classes:
    for s in ["train", "test"]:
        cdir = os.path.join(data_dir, s, c)
        for fname in os.listdir(cdir):
            fpath = os.path.join(cdir, fname)
            if not os.path.isfile(fpath):
                continue
            with open(fpath, "rb") as f:
                sha = hashlib.sha256(f.read()).hexdigest()
            with Image.open(fpath) as im:
                ph = compute_phash(im)
            records.append({
                "class": c,
                "split": s,
                "fname": fname,
                "fpath": fpath,
                "sha256": sha,
                "phash": ph
            })

# Inspect exact dupe
sha_map = defaultdict(list)
for r in records:
    sha_map[r["sha256"]].append(r)

exact_dupes = [v for v in sha_map.values() if len(v) > 1]
print("Exact duplicates:")
for d in exact_dupes:
    for item in d:
        print(f"  {item['class']} | {item['split']} | {item['fname']} | {item['sha256'][:16]}")

# Inspect pHash dH=0
ph_map = defaultdict(list)
for r in records:
    ph_map[r["phash"]].append(r)

ph_dupes = [v for v in ph_map.values() if len(v) > 1]
print(f"\npHash dH=0 groups: {len(ph_dupes)}")
for d in ph_dupes[:5]:
    print("  Group:")
    for item in d:
        print(f"    {item['class']} | {item['split']} | {item['fname']} | {item['phash']}")
