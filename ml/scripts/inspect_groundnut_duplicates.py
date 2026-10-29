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

# Let's inspect early_leaf_spot_1
cls = "early_leaf_spot_1"
train_dir = os.path.join(data_dir, "train", cls)
test_dir = os.path.join(data_dir, "test", cls)

all_files = [(os.path.join(train_dir, f), f, "train") for f in os.listdir(train_dir)] + \
            [(os.path.join(test_dir, f), f, "test") for f in os.listdir(test_dir)]

print(f"Total files in {cls}: {len(all_files)}")

sha_map = defaultdict(list)
phash_map = defaultdict(list)

for path, fname, split in all_files:
    with open(path, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()
    with Image.open(path) as img:
        ph = compute_phash(img)
    sha_map[sha].append((fname, split))
    phash_map[ph].append((fname, split))

print(f"Unique SHA-256 hashes: {len(sha_map)}")
print(f"Unique pHashes: {len(phash_map)}")

# Check exact duplicate files
exact_dupes = {s: v for s, v in sha_map.items() if len(v) > 1}
print(f"Exact duplicate groups: {len(exact_dupes)}")
for s, v in list(exact_dupes.items())[:5]:
    print(f"  SHA: {s[:12]}... -> {v}")

# Check cross-split exact duplicates
cross_split_sha = [v for v in exact_dupes.values() if len(set(x[1] for x in v)) > 1]
print(f"Exact duplicate files crossing train and test: {len(cross_split_sha)}")
for v in cross_split_sha[:5]:
    print(f"  Cross-split: {v}")

# Sample investigate dr_ files vs non-dr files
dr_files = [f for p, f, s in all_files if f.startswith("dr_")]
non_dr_files = [f for p, f, s in all_files if not f.startswith("dr_")]
print(f"dr_ files: {len(dr_files)}, non-dr files: {len(non_dr_files)}")
