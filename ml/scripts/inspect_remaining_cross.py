import csv
import json
import random
from pathlib import Path
from collections import defaultdict
from PIL import Image
import numpy as np

manifest_path = Path("data/manifests/manifest_cotton_staged.csv")
with open(manifest_path, "r", encoding="utf-8") as f:
    rows = list(csv.DictReader(f))

img_cache = {}
for r in rows:
    p = Path(r["staged_path"])
    with Image.open(p) as img:
        img_cache[p.name] = np.array(img.convert("RGB"), dtype=np.int16)

# Union-find clustering
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
        
        link = False
        if d == 0:
            link = True
        elif d <= 1 and m < 25:
            link = True
        elif d <= 2 and m < 18:
            link = True
        elif d <= 3 and m < 15:
            link = True
            
        if link:
            union(i, j)

clusters_by_class = defaultdict(lambda: defaultdict(list))
for i, r in enumerate(rows):
    root = find(i)
    cls = r["cropdoc_class_name"]
    clusters_by_class[cls][root].append(r)

seed = 42
rng = random.Random(seed)

assigned_splits = {}
group_id_map = {}

for cls in sorted(clusters_by_class.keys()):
    groups = list(clusters_by_class[cls].values())
    rng.shuffle(groups)
    groups.sort(key=lambda g: len(g), reverse=True)

    total_class_imgs = sum(len(g) for g in groups)
    target_train = int(round(total_class_imgs * 0.70))
    target_val = int(round(total_class_imgs * 0.15))
    target_test = total_class_imgs - target_train - target_val

    train_g, val_g, test_g = [], [], []
    train_cnt, val_cnt, test_cnt = 0, 0, 0

    for g in groups:
        sz = len(g)
        deficits = [
            (target_train - train_cnt, "train"),
            (target_val - val_cnt, "val"),
            (target_test - test_cnt, "test")
        ]
        deficits.sort(key=lambda x: x[0], reverse=True)
        chosen_bin = deficits[0][1]

        if chosen_bin == "train":
            train_g.append(g)
            train_cnt += sz
        elif chosen_bin == "val":
            val_g.append(g)
            val_cnt += sz
        else:
            test_g.append(g)
            test_cnt += sz

    prefix = "cotton_mendeley_bb_" if "bacterial" in cls else "cotton_mendeley_hl_"
    all_ordered_groups = train_g + val_g + test_g
    for g_idx, g in enumerate(all_ordered_groups, start=1):
        gid = f"{prefix}{g_idx:03d}"
        split_tag = "train" if g in train_g else ("val" if g in val_g else "test")
        for m in g:
            fname = Path(m["staged_path"]).name
            group_id_map[fname] = gid
            assigned_splits[fname] = split_tag

print("--- Cross-split dH=0 pairs ---")
n = len(rows)
for i in range(n):
    n1 = Path(rows[i]["staged_path"]).name
    s1 = assigned_splits[n1]
    c1 = rows[i]["cropdoc_class_name"]
    h1 = int(rows[i]["phash"], 16)
    im1 = img_cache[n1]
    for j in range(i + 1, n):
        n2 = Path(rows[j]["staged_path"]).name
        s2 = assigned_splits[n2]
        if s1 == s2:
            continue
        c2 = rows[j]["cropdoc_class_name"]
        h2 = int(rows[j]["phash"], 16)
        d = (h1 ^ h2).bit_count()
        if d == 0:
            diff = np.abs(im1 - img_cache[n2])
            print(f"  dH=0: {n1} ({c1}, {s1}) <-> {n2} ({c2}, {s2}) | mean_diff={diff.mean():.2f}")

print("\n--- Cross-split same-class dH=1 pairs ---")
for i in range(n):
    n1 = Path(rows[i]["staged_path"]).name
    s1 = assigned_splits[n1]
    c1 = rows[i]["cropdoc_class_name"]
    h1 = int(rows[i]["phash"], 16)
    im1 = img_cache[n1]
    for j in range(i + 1, n):
        n2 = Path(rows[j]["staged_path"]).name
        s2 = assigned_splits[n2]
        if s1 == s2:
            continue
        c2 = rows[j]["cropdoc_class_name"]
        if c1 != c2:
            continue
        h2 = int(rows[j]["phash"], 16)
        d = (h1 ^ h2).bit_count()
        if d == 1:
            diff = np.abs(im1 - img_cache[n2])
            print(f"  dH=1: {n1} ({s1}) <-> {n2} ({s2}) | mean_diff={diff.mean():.2f}")

print("\n--- Cross-split same-class pairs with mean_diff < 15 ---")
for i in range(n):
    n1 = Path(rows[i]["staged_path"]).name
    s1 = assigned_splits[n1]
    c1 = rows[i]["cropdoc_class_name"]
    h1 = int(rows[i]["phash"], 16)
    im1 = img_cache[n1]
    for j in range(i + 1, n):
        n2 = Path(rows[j]["staged_path"]).name
        s2 = assigned_splits[n2]
        if s1 == s2:
            continue
        c2 = rows[j]["cropdoc_class_name"]
        if c1 != c2:
            continue
        h2 = int(rows[j]["phash"], 16)
        d = (h1 ^ h2).bit_count()
        diff = np.abs(im1 - img_cache[n2])
        m = float(diff.mean())
        if m < 15:
            print(f"  dH={d}, mean_diff={m:.2f}: {n1} ({s1}) <-> {n2} ({s2})")
