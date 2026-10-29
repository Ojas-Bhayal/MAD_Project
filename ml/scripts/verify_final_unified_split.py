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

    # Union-find clustering with the comprehensive rule
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
                links.append((m, d, rows[i], rows[j]))

    clusters_by_class = defaultdict(lambda: defaultdict(list))
    for i, r in enumerate(rows):
        root = find(i)
        cls = r["cropdoc_class_name"]
        clusters_by_class[cls][root].append(r)

    print("=== SUMMARY OF UNIFIED CLUSTERS ===")
    total_grps = 0
    for cls in sorted(clusters_by_class.keys()):
        cl_list = list(clusters_by_class[cls].values())
        total_imgs = sum(len(c) for c in cl_list)
        total_grps += len(cl_list)
        print(f"  {cls}: {total_imgs} images across {len(cl_list)} unified groups")
    print(f"Total unified groups across dataset: {total_grps}")

    # Split with seed=42
    seed = 42
    rng = random.Random(seed)

    split_results = {"train": [], "val": [], "test": []}
    class_stats = defaultdict(lambda: defaultdict(int))
    group_id_map = {}
    assigned_splits = {}

    for cls in sorted(clusters_by_class.keys()):
        groups = list(clusters_by_class[cls].values())
        # Shuffle with fixed seed
        rng.shuffle(groups)
        # Sort descending by group size to place larger groups into train
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

        # Class stats
        class_stats[cls]["train_imgs"] = train_cnt
        class_stats[cls]["train_grps"] = len(train_g)
        class_stats[cls]["val_imgs"] = val_cnt
        class_stats[cls]["val_grps"] = len(val_g)
        class_stats[cls]["test_imgs"] = test_cnt
        class_stats[cls]["test_grps"] = len(test_g)

        prefix = "cotton_mendeley_bb_" if "bacterial" in cls else "cotton_mendeley_hl_"
        all_ordered_groups = train_g + val_g + test_g
        for g_idx, g in enumerate(all_ordered_groups, start=1):
            gid = f"{prefix}{g_idx:03d}"
            split_tag = "train" if g in train_g else ("val" if g in val_g else "test")
            for m in g:
                fname = Path(m["staged_path"]).name
                group_id_map[fname] = gid
                assigned_splits[fname] = split_tag

        print(f"\n{cls} Partitioning:")
        print(f"  Train: {train_cnt:3d} images ({len(train_g):3d} groups) [{train_cnt/total_class_imgs*100:.1f}%]")
        print(f"  Val:   {val_cnt:3d} images ({len(val_g):3d} groups) [{val_cnt/total_class_imgs*100:.1f}%]")
        print(f"  Test:  {test_cnt:3d} images ({len(test_g):3d} groups) [{test_cnt/total_class_imgs*100:.1f}%]")

    # Check key clusters
    b114_members = [
        "bacterial_(105).jpg", "bacterial_(114).jpg", "bacterial_(118).jpg",
        "bacterial_(119).jpg", "bacterial_(120).jpg", "bacterial_(121).jpg",
        "bacterial_(37).jpg", "bacterial_(91).jpg"
    ]
    print(f"\nbacterial_(114) cluster check:")
    for b in b114_members:
        print(f"  {b} -> Group: {group_id_map[b]}, Split: {assigned_splits[b]}")

    h84_members = [
        "healthy_(108).jpg", "healthy_(120).jpg", "healthy_(155).jpg",
        "healthy_(177).jpg", "healthy_(188).jpg", "healthy_(49).jpg",
        "healthy_(62).jpg", "healthy_(73).jpg", "healthy_(84).jpg", "healthy_(95).jpg"
    ]
    print(f"\nhealthy_(84, 95) cluster check:")
    for h in h84_members:
        print(f"  {h} -> Group: {group_id_map[h]}, Split: {assigned_splits[h]}")

    # Check cross-split leakage
    train_groups = set()
    val_groups = set()
    test_groups = set()
    for fname, gid in group_id_map.items():
        s = assigned_splits[fname]
        if s == "train":
            train_groups.add(gid)
        elif s == "val":
            val_groups.add(gid)
        else:
            test_groups.add(gid)

    print("\nGroup Leakage Check:")
    print(f"  Train & Val overlap:  {len(train_groups & val_groups)}")
    print(f"  Train & Test overlap: {len(train_groups & test_groups)}")
    print(f"  Val & Test overlap:   {len(val_groups & test_groups)}")

    # Check cross-split pairs with dH <= 6
    print("\nAuditing cross-split pairs across all 359 images:")
    cross_pairs = []
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
            if d <= 6:
                im2 = img_cache[n2]
                diff = np.abs(im1 - im2)
                m = float(diff.mean())
                same_cls = (c1 == c2)
                cross_pairs.append((d, m, same_cls, n1, s1, n2, s2, c1))

    dh0_cross = [p for p in cross_pairs if p[0] == 0]
    dh1_cross = [p for p in cross_pairs if p[0] == 1 and p[2]]
    close_same_class = [p for p in cross_pairs if p[2] and p[1] < 15]

    print(f"  Cross-split dH=0 pairs: {len(dh0_cross)}")
    print(f"  Cross-split same-class dH=1 pairs: {len(dh1_cross)}")
    print(f"  Cross-split same-class pairs with mean_diff < 15: {len(close_same_class)}")

if __name__ == "__main__":
    main()
