import csv
from pathlib import Path
from PIL import Image
import numpy as np
from collections import defaultdict

def main():
    manifest_path = Path("data/manifests/manifest_cotton_staged.csv")
    with open(manifest_path, "r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    print("Loading images...")
    img_cache = {}
    for r in rows:
        p = Path(r["staged_path"])
        with Image.open(p) as img:
            img_cache[p.name] = np.array(img.convert("RGB"), dtype=np.int16)

    # Let's test different criteria for linking two images i and j in the same class:
    # Criterion 1: dH <= 2 AND mean_diff < 30
    # Criterion 2: dH <= 3 AND mean_diff < 25
    # Criterion 3: mean_diff < 20
    # Criterion 4: dH <= 2 (pure pHash)
    # Criterion 5: dH <= 3 (pure pHash)

    criteria = [
        ("dH == 0", lambda d, m: d == 0),
        ("dH <= 1 and mean_diff < 30", lambda d, m: d <= 1 and m < 30),
        ("dH <= 2 and mean_diff < 25", lambda d, m: d <= 2 and m < 25),
        ("dH <= 3 and mean_diff < 20", lambda d, m: d <= 3 and m < 20),
        ("dH <= 3 and mean_diff < 25", lambda d, m: d <= 3 and m < 25),
        ("dH <= 4 and mean_diff < 20", lambda d, m: d <= 4 and m < 20),
        ("dH <= 6 and mean_diff < 20", lambda d, m: d <= 6 and m < 20),
        ("mean_diff < 18", lambda d, m: m < 18),
    ]

    print("Precomputing pairwise metrics...")
    pair_metrics = []
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
            pair_metrics.append((i, j, d, m))
    print(f"Precomputed {len(pair_metrics)} intra-class pairs.")

    for crit_name, crit_fn in criteria:
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

        links = 0
        for i, j, d, m in pair_metrics:
            if crit_fn(d, m):
                union(i, j)
                links += 1

        clusters = defaultdict(list)
        for i in range(len(rows)):
            clusters[find(i)].append(rows[i])

        cluster_sizes = [len(c) for c in clusters.values()]
        multi_clusters = [c for c in clusters.values() if len(c) > 1]
        max_size = max(cluster_sizes)
        print(f"\nCriterion: {crit_name}")
        print(f"  Total links: {links}")
        print(f"  Total clusters: {len(clusters)} (Multi-member: {len(multi_clusters)}, Max size: {max_size})")

        # Check key clusters
        # Check if bacterial 114, 119, 120 are together
        b114_idx = next(idx for idx, r in enumerate(rows) if "bacterial_(114)" in r["staged_path"])
        b119_idx = next(idx for idx, r in enumerate(rows) if "bacterial_(119)" in r["staged_path"])
        b120_idx = next(idx for idx, r in enumerate(rows) if "bacterial_(120)" in r["staged_path"])
        b121_idx = next(idx for idx, r in enumerate(rows) if "bacterial_(121)" in r["staged_path"])
        b118_idx = next(idx for idx, r in enumerate(rows) if "bacterial_(118)" in r["staged_path"])

        h84_idx = next(idx for idx, r in enumerate(rows) if "healthy_(84)" in r["staged_path"])
        h95_idx = next(idx for idx, r in enumerate(rows) if "healthy_(95)" in r["staged_path"])
        h108_idx = next(idx for idx, r in enumerate(rows) if "healthy_(108)" in r["staged_path"])
        h49_idx = next(idx for idx, r in enumerate(rows) if "healthy_(49)" in r["staged_path"])

        b_same = (find(b114_idx) == find(b119_idx) == find(b120_idx))
        h_same = (find(h84_idx) == find(h95_idx) == find(h108_idx))
        print(f"  bacterial_(114, 119, 120) unified: {b_same}")
        print(f"  healthy_(84, 95, 108) unified: {h_same}")

if __name__ == "__main__":
    main()
