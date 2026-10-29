import csv
from pathlib import Path
from collections import Counter
from PIL import Image
import numpy as np

manifest_path = Path("data/manifests/manifest_cotton_staged.csv")
with open(manifest_path, "r", encoding="utf-8") as f:
    rows = list(csv.DictReader(f))

dist_counts = Counter()
close_pairs = []

for i in range(len(rows)):
    h1 = int(rows[i]["phash"], 16)
    for j in range(i + 1, len(rows)):
        h2 = int(rows[j]["phash"], 16)
        d = (h1 ^ h2).bit_count()
        dist_counts[d] += 1
        if d <= 3:
            close_pairs.append((d, rows[i], rows[j]))

print("Hamming distance distribution (0 to 12):")
for d in range(13):
    print(f"  dH = {d:2d}: {dist_counts[d]:5d} pairs")

print(f"\nTotal pairs with dH <= 3: {len(close_pairs)}")
for d, r1, r2 in sorted(close_pairs, key=lambda x: x[0]):
    name1 = Path(r1["staged_path"]).name
    name2 = Path(r2["staged_path"]).name
    c1 = r1["cropdoc_class_name"]
    c2 = r2["cropdoc_class_name"]
    s1 = r1["split"]
    s2 = r2["split"]
    g1 = r1["group_id"]
    g2 = r2["group_id"]
    cross_cls = "CROSS-CLASS" if c1 != c2 else "SAME-CLASS"
    cross_spl = "CROSS-SPLIT" if s1 != s2 else "SAME-SPLIT"
    print(f"  dH={d} [{cross_cls}, {cross_spl}]: {name1} ({c1}, {s1}, {g1}) <-> {name2} ({c2}, {s2}, {g2})")
