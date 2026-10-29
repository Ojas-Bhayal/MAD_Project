import csv
from pathlib import Path
from PIL import Image
import numpy as np
from collections import defaultdict

manifest_path = Path("data/manifests/manifest_cotton_staged.csv")
with open(manifest_path, "r", encoding="utf-8") as f:
    rows = list(csv.DictReader(f))

img_cache = {}
for r in rows:
    p = Path(r["staged_path"])
    with Image.open(p) as img:
        img_cache[p.name] = np.array(img.convert("RGB"), dtype=np.int16)

parent = list(range(len(rows)))
def find(i):
    while parent[i] != i:
        parent[i] = parent[parent[i]]
        i = parent[i]
    return i
def union(i, j):
    ri, rj = find(i), find(j)
    if ri != rj:
        parent[ri] = rj

links = []
for i in range(len(rows)):
    c1 = rows[i]["cropdoc_class_name"]
    n1 = Path(rows[i]["staged_path"]).name
    h1 = int(rows[i]["phash"], 16)
    im1 = img_cache[n1]
    for j in range(i + 1, len(rows)):
        c2 = rows[j]["cropdoc_class_name"]
        if c1 != c2:
            continue
        n2 = Path(rows[j]["staged_path"]).name
        h2 = int(rows[j]["phash"], 16)
        im2 = img_cache[n2]
        d = (h1 ^ h2).bit_count()
        diff = np.abs(im1 - im2)
        m = float(diff.mean())
        
        # Candidate rule
        link = False
        if d == 0:
            link = True
        elif d <= 1 and m < 25:
            link = True
        elif d <= 2 and m < 18:
            link = True
            
        if link:
            union(i, j)
            links.append((m, d, rows[i], rows[j]))

clusters = defaultdict(list)
for i in range(len(rows)):
    clusters[find(i)].append(rows[i])

print(f"Total links: {len(links)}")
print(f"Total clusters: {len(clusters)}")
multi = {k: v for k, v in clusters.items() if len(v) > 1}
print(f"Multi-member clusters: {len(multi)}")
sizes = [len(v) for v in clusters.values()]
print(f"Max cluster size: {max(sizes)}")
print(f"Size distribution: {sorted(dict(defaultdict(int, {s: sizes.count(s) for s in set(sizes)})).items())}")

for idx, (rep, members) in enumerate(sorted(multi.items(), key=lambda x: len(x[1]), reverse=True)):
    cls = members[0]["cropdoc_class_name"]
    names = [Path(m["staged_path"]).name for m in members]
    print(f"\nCluster {idx+1} ({len(members)} imgs, {cls}): {', '.join(names)}")
