"""
RANSAC Homography Specimen Clustering for Mendeley Cotton.
Detects exact physical leaves photographed repeatedly across splits.
"""
import csv
from pathlib import Path
from collections import defaultdict
import cv2
import numpy as np

def cluster_class(rows, cls_name):
    cls_rows = [r for r in rows if r['cropdoc_class_name'] == cls_name]
    n = len(cls_rows)
    print(f"\n=======================================================")
    print(f"Extracting ORB features for {n} images of {cls_name}...")
    
    orb = cv2.ORB_create(nfeatures=400)
    bf = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=False)
    
    kps = []
    descs = []
    for r in cls_rows:
        im = cv2.imread(r['staged_path'])
        kp, des = orb.detectAndCompute(im, None)
        kps.append(kp)
        descs.append(des)
        
    parent = list(range(n))
    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    def union(i, j):
        ri, rj = find(i), find(j)
        if ri != rj:
            parent[ri] = rj

    print(f"Matching all {n*(n-1)//2} image pairs with RANSAC homography...")
    match_count = 0
    for i in range(n):
        if descs[i] is None or len(descs[i]) < 20:
            continue
        for j in range(i + 1, n):
            if descs[j] is None or len(descs[j]) < 20:
                continue
            matches = bf.knnMatch(descs[i], descs[j], k=2)
            good = [m for m, n_m in matches if m.distance < 0.75 * n_m.distance]
            if len(good) >= 14:
                src_pts = np.float32([kps[i][m.queryIdx].pt for m in good]).reshape(-1, 1, 2)
                dst_pts = np.float32([kps[j][m.trainIdx].pt for m in good]).reshape(-1, 1, 2)
                M, mask = cv2.findHomography(src_pts, dst_pts, cv2.RANSAC, 5.0)
                inliers = np.count_nonzero(mask) if mask is not None else 0
                if inliers >= 12:
                    union(i, j)
                    match_count += 1
                    
    clusters = defaultdict(list)
    for i in range(n):
        clusters[find(i)].append(cls_rows[i])
        
    print(f"Found {match_count} verified pairwise leaf matches.")
    print(f"{cls_name}: {n} images grouped into {len(clusters)} true physical specimen clusters.")
    
    # Check largest clusters and cross-split leakage
    sorted_c = sorted(clusters.values(), key=len, reverse=True)
    leaking_clusters = 0
    for idx, c in enumerate(sorted_c):
        splits = set(r['split'] for r in c)
        if len(splits) > 1:
            leaking_clusters += 1
        if idx < 10:
            sample_names = [Path(r['staged_path']).name for r in c[:4]]
            print(f"  Cluster {idx+1}: {len(c)} images, splits={splits}, names={sample_names}...")
            
    print(f"Multi-split leaking clusters in original splits: {leaking_clusters}/{len(clusters)}")
    return clusters

def main():
    root = Path(__file__).resolve().parents[2]
    manifest_path = root / "data" / "manifests" / "manifest_cotton_staged.csv"
    with open(manifest_path, "r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
        
    bb_clusters = cluster_class(rows, "cotton_bacterial_blight")
    hl_clusters = cluster_class(rows, "cotton_healthy")

if __name__ == "__main__":
    main()
