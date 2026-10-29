import os
import hashlib
from collections import defaultdict
from PIL import Image

data_dir = os.path.join("data", "raw", "mendeley_groundnut")

def compute_phash(img: Image.Image) -> str:
    small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
    pixels = np.array(small, dtype=np.float32).flatten()
    avg = np.mean(pixels)
    bits = 0
    for px in pixels:
        bits = (bits << 1) | (1 if px >= avg else 0)
    return f"{bits:016x}"

all_records = []
classes = [d for d in os.listdir(os.path.join(data_dir, "train")) if os.path.isdir(os.path.join(data_dir, "train", d))]

print("All classes in dataset:", classes)

sha_map = defaultdict(list)
corrupt = []

for c in classes:
    for s in ["train", "test"]:
        cdir = os.path.join(data_dir, s, c)
        if not os.path.isdir(cdir):
            continue
        for fn in os.listdir(cdir):
            p = os.path.join(cdir, fn)
            if not os.path.isfile(p):
                continue
            try:
                with open(p, "rb") as f:
                    sha = hashlib.sha256(f.read()).hexdigest()
                sha_map[sha].append((c, s, fn, p))
            except Exception as e:
                corrupt.append((p, str(e)))

print(f"Total files processed across ALL 6 classes: {sum(len(v) for v in sha_map.values())}")
print(f"Unique SHA-256 hashes across ALL 6 classes: {len(sha_map)}")
print(f"Corrupt files across ALL 6 classes: {len(corrupt)}")

# Check cross-class SHA-256 duplicates across all classes
cross_class = {}
for sha, v in sha_map.items():
    cs = set(x[0] for x in v)
    if len(cs) > 1:
        cross_class[sha] = v

print(f"Cross-class exact duplicate groups across ALL 6 classes: {len(cross_class)}")
for sha, v in list(cross_class.items())[:10]:
    print(f"  SHA: {sha[:12]}... -> {[(x[0], x[1], x[2]) for x in v]}")
