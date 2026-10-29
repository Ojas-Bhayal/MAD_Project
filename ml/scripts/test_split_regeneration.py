import csv
import json
import random
from pathlib import Path
from collections import defaultdict
from PIL import Image
import numpy as np

def main():
    manifest_path = Path("data/manifests/manifest_cotton_staged.csv")
    with open(manifest_path, "r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    img_cache = {}
    for r in rows:
        p = Path(r["staged_path"])
        with Image.open(p) as img:
            img_cache[p.name] = np.array(img.convert("RGB"), dtype=np.int16)

    # Union-find clustering with refined rule
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
                
            if link:
                union(i, j)

    # Build cluster mapping
    clusters_by_class = defaultdict(lambda: defaultdict(list))
    for i, r in enumerate(rows):
        root = find(i)
        cls = r["cropdoc_class_name"]
        clusters_by_class[cls][root].append(r)

    print("=== CLUSTERING SUMMARY ===")
    for cls in sorted(clusters_by_class.keys()):
        cl_list = list(clusters_by_class[cls].values())
        total_imgs = sum(len(c) for c in cl_list)
        print(f"Class: {cls} -> {total_imgs} images across {len(cl_list)} unified groups")

    # Fixed seed group-aware split (target: 70% train, 15% val, 15% test)
    # Split independently per class so each class has proper 70/15/15 ratio
    seed = 42
    rng = random.Random(seed)

    splits = {"train": [], "val": [], "test": []}
    class_split_stats = defaultdict(lambda: defaultdict(int))
    group_split_map = {}

    for cls in sorted(clusters_by_class.keys()):
        groups = list(clusters_by_class[cls].values())
        # Sort groups by length descending for greedy bin packing, then shuffle deterministically
        rng.shuffle(groups)
        
        total_class_imgs = sum(len(g) for g in groups)
        target_train = int(round(total_class_imgs * 0.70))
        target_val = int(round(total_class_imgs * 0.15))
        target_test = total_class_imgs - target_train - target_val

        # Greedy assignment to balance images
        train_g, val_g, test_g = [], [], []
        train_cnt, val_cnt, test_cnt = 0, 0, 0

        # Sort by group size descending so large groups are allocated first
        groups.sort(key=lambda g: len(g), reverse=True)

        for g in groups:
            sz = len(g)
            # Find the bin with the largest deficit relative to target
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

        print(f"\nClass {cls} split yield:")
        print(f"  Train: {train_cnt} imgs ({len(train_g)} grps) [Target: {target_train}]")
        print(f"  Val:   {val_cnt} imgs ({len(val_g)} grps) [Target: {target_val}]")
        print(f"  Test:  {test_cnt} imgs ({len(test_g)} grps) [Target: {target_test}]")

        class_split_stats[cls]["train_imgs"] = train_cnt
        class_split_stats[cls]["train_grps"] = len(train_g)
        class_split_stats[cls]["val_imgs"] = val_cnt
        class_split_stats[cls]["val_grps"] = len(val_g)
        class_split_stats[cls]["test_imgs"] = test_cnt
        class_split_stats[cls]["test_grps"] = len(test_g)

        # Check key clusters
        # bacterial 114, 119, 120
        for split_name, g_list in [("train", train_g), ("val", val_g), ("test", test_g)]:
            for g in g_list:
                names = [Path(m["staged_path"]).name for m in g]
                if "bacterial_(114).jpg" in names:
                    print(f"  >>> bacterial_(114, 119, 120, ...) is in {split_name.upper()} (all {len(names)} members)")
                if "healthy_(84).jpg" in names:
                    print(f"  >>> healthy_(84, 95, 108, ...) is in {split_name.upper()} (all {len(names)} members)")
                if "healthy_(102).jpg" in names:
                    print(f"  >>> healthy_(102, 173, ...) is in {split_name.upper()} (all {len(names)} members)")

    print("\nTotal dataset split:")
    total_train = sum(class_split_stats[c]["train_imgs"] for c in class_split_stats)
    total_val = sum(class_split_stats[c]["val_imgs"] for c in class_split_stats)
    total_test = sum(class_split_stats[c]["test_imgs"] for c in class_split_stats)
    print(f"  Total Train: {total_train} imgs ({total_train/359*100:.1f}%)")
    print(f"  Total Val:   {total_val} imgs ({total_val/359*100:.1f}%)")
    print(f"  Total Test:  {total_test} imgs ({total_test/359*100:.1f}%)")
    print(f"  Sum: {total_train + total_val + total_test}")

if __name__ == "__main__":
    main()
