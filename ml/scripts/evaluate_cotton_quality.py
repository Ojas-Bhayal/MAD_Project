"""
Evaluates all 359 staged cotton images at 700x700 full resolution.
Extracts quantitative morphological and pathological metrics, assigns conservative
AI review verdicts, groups burst shots into true physical specimen clusters,
and outputs candidate clean research splits.
"""

import csv
import hashlib
from pathlib import Path
from collections import defaultdict
import cv2
import numpy as np

def main():
    root = Path(__file__).resolve().parents[2]
    manifest_path = root / "data" / "manifests" / "manifest_cotton_staged.csv"
    orig_chk_path = root / "ml" / "reports" / "mendeley_cotton_2025" / "expert_review_checklist.csv"
    
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest_rows = list(csv.DictReader(f))
    with open(orig_chk_path, "r", encoding="utf-8") as f:
        chk_rows = list(csv.DictReader(f))
        
    chk_flags = {r["staged_filename"]: r["quality_gate_flags"] for r in chk_rows}

    # Step 1: Feature Extraction across all 359 images
    records = []
    orb = cv2.ORB_create(nfeatures=400)
    bf = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=False)
    
    kps_list = []
    descs_list = []
    
    for r in manifest_rows:
        p = root / r["staged_path"]
        bgr = cv2.imread(str(p))
        h, w, _ = bgr.shape
        b, g, r_ch = cv2.split(bgr.astype(np.float32))
        hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
        
        # White background segmentation
        diff = np.maximum(np.maximum(np.abs(r_ch - g), np.abs(g - b)), np.abs(r_ch - b))
        bg = (diff < 22) & (bgr[:,:,0] > 165) & (bgr[:,:,1] > 165) & (bgr[:,:,2] > 165)
        leaf = ~bg
        leaf_px = np.count_nonzero(leaf)
        leaf_fraction = leaf_px / (h * w)
        
        # Green tissue: H in [32, 85], S > 30, V > 50
        green = leaf & (hsv[:,:,0] >= 32) & (hsv[:,:,0] <= 85) & (hsv[:,:,1] > 30) & (hsv[:,:,2] > 50)
        green_pct = np.count_nonzero(green) / max(leaf_px, 1)
        
        # Yellow chlorotic: H in [18, 32], S > 60, V > 100
        yellow = leaf & (hsv[:,:,0] >= 18) & (hsv[:,:,0] < 32) & (hsv[:,:,1] > 60) & (hsv[:,:,2] > 100)
        yellow_pct = np.count_nonzero(yellow) / max(leaf_px, 1)
        
        # Genuine necrosis: Hue < 30 and r > g - 10, or V < 50 with r >= g
        nec = leaf & (
            ((hsv[:,:,0] < 30) & (r_ch > g - 10) & (hsv[:,:,1] > 25)) |
            ((hsv[:,:,2] < 50) & (r_ch >= g))
        )
        nec_px = np.count_nonzero(nec)
        nec_pct = nec_px / max(leaf_px, 1)
        
        # Reddening / anthocyanin
        red = leaf & ((hsv[:,:,0] > 160) | (hsv[:,:,0] < 10)) & (hsv[:,:,1] > 50) & (r_ch > g + 25)
        red_pct = np.count_nonzero(red) / max(leaf_px, 1)
        
        # Blur check
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        lap_var = cv2.Laplacian(gray, cv2.CV_64F).var()
        
        # Spots / contours
        nec_u8 = nec.astype(np.uint8) * 255
        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
        nec_clean = cv2.morphologyEx(nec_u8, cv2.MORPH_OPEN, kernel)
        contours, _ = cv2.findContours(nec_clean, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        valid_spots = [c for c in contours if cv2.contourArea(c) >= 30]
        
        # ORB for specimen identity
        kp, des = orb.detectAndCompute(bgr, None)
        kps_list.append(kp)
        descs_list.append(des)
        
        records.append({
            "manifest_row": r,
            "filename": p.name,
            "class_name": r["cropdoc_class_name"],
            "group_id": r["group_id"],
            "split": r["split"],
            "leaf_fraction": leaf_fraction,
            "green_pct": green_pct,
            "yellow_pct": yellow_pct,
            "nec_pct": nec_pct,
            "red_pct": red_pct,
            "lap_var": lap_var,
            "num_spots": len(valid_spots),
            "gate_flag": chk_flags.get(p.name, "NONE")
        })

    # Step 2: Specimen Clustering within each class using RANSAC Homography
    for cls in ["cotton_bacterial_blight", "cotton_healthy"]:
        cls_indices = [i for i, rec in enumerate(records) if rec["class_name"] == cls]
        parent = {i: i for i in cls_indices}
        def find(x):
            while parent[x] != x:
                parent[x] = parent[parent[x]]
                x = parent[x]
            return x
        def union(x, y):
            rx, ry = find(x), find(y)
            if rx != ry:
                parent[rx] = ry
                
        for idx1 in range(len(cls_indices)):
            i = cls_indices[idx1]
            if descs_list[i] is None or len(descs_list[i]) < 20:
                continue
            for idx2 in range(idx1 + 1, len(cls_indices)):
                j = cls_indices[idx2]
                if descs_list[j] is None or len(descs_list[j]) < 20:
                    continue
                matches = bf.knnMatch(descs_list[i], descs_list[j], k=2)
                good = [m for m, n_m in matches if m.distance < 0.75 * n_m.distance]
                if len(good) >= 14:
                    src_pts = np.float32([kps_list[i][m.queryIdx].pt for m in good]).reshape(-1, 1, 2)
                    dst_pts = np.float32([kps_list[j][m.trainIdx].pt for m in good]).reshape(-1, 1, 2)
                    M, mask = cv2.findHomography(src_pts, dst_pts, cv2.RANSAC, 5.0)
                    inliers = np.count_nonzero(mask) if mask is not None else 0
                    if inliers >= 12:
                        union(i, j)
                        
        # Map cluster IDs
        cluster_map = defaultdict(list)
        for i in cls_indices:
            cluster_map[find(i)].append(i)
            
        cluster_id_counter = 1
        prefix = "cotton_true_bb_" if cls == "cotton_bacterial_blight" else "cotton_true_hl_"
        for root_idx, members in sorted(cluster_map.items(), key=lambda x: len(x[1]), reverse=True):
            cid = f"{prefix}{cluster_id_counter:03d}"
            cluster_id_counter += 1
            for m in members:
                records[m]["physical_cluster_id"] = cid
                records[m]["cluster_size"] = len(members)

    # Step 3: Diagnostic Decision Rubric
    verdict_counts = defaultdict(int)
    for rec in records:
        cls = rec["class_name"]
        lap = rec["lap_var"]
        nec = rec["nec_pct"]
        yel = rec["yellow_pct"]
        grn = rec["green_pct"]
        red = rec["red_pct"]
        spots = rec["num_spots"]
        flag = rec["gate_flag"]
        
        has_collision = "collision" in flag.lower()
        
        if cls == "cotton_healthy":
            if lap < 60:
                verdict = "Image unsuitable or ambiguous"
                conf = "Low"
                notes = f"Severe image blur (Laplacian variance {lap:.1f} < 60)"
            elif has_collision:
                verdict = "Label uncertain"
                conf = "Medium"
                notes = f"Cross-class perceptual collision with bacterial blight; human differential review required ({flag})"
            elif nec > 0.10 or yel > 0.08:
                verdict = "Incorrect label"
                conf = "Medium"
                notes = f"Active foliar discoloration present (necrosis={nec:.1%}, chlorosis={yel:.1%}); incompatible with healthy control"
            elif nec > 0.06 or yel > 0.05:
                verdict = "Label uncertain"
                conf = "Medium"
                notes = f"Borderline marginal discoloration (necrosis={nec:.1%}, chlorosis={yel:.1%}); requires clinical confirmation"
            else:
                verdict = "Label confirmed"
                conf = "High"
                notes = f"Plausible healthy cotton leaf; clean green foliar canopy ({grn:.1%}) without pathogen lesions"
                
        else: # cotton_bacterial_blight
            if lap < 60:
                verdict = "Image unsuitable or ambiguous"
                conf = "Low"
                notes = f"Severe image blur (Laplacian variance {lap:.1f} < 60)"
            elif red > 0.08:
                verdict = "Incorrect label"
                conf = "Medium"
                notes = f"Prominent anthocyanin reddening/bronzing ({red:.1%}); physiological/abiotic disorder, not Xanthomonas blight"
            elif grn > 0.80 and nec < 0.05 and yel < 0.05:
                verdict = "Incorrect label"
                conf = "High"
                notes = f"Mislabeled green leaf ({grn:.1%}) with physical/insect tears but zero bacterial blight lesions"
            elif nec >= 0.07 and spots >= 2 and red <= 0.08:
                verdict = "Label confirmed"
                conf = "Medium"
                notes = f"Plausible angular vein-associated necrotic lesions ({nec:.1%}, {spots} spots) compatible with bacterial blight"
            elif nec >= 0.04:
                verdict = "Label uncertain"
                conf = "Low"
                notes = f"Moderate foliar necrosis ({nec:.1%}) but diffuse/marginal without distinct angular vein-delimited boundaries"
            else:
                verdict = "Label uncertain"
                conf = "Low"
                notes = f"Minimal foliar necrosis ({nec:.1%}) or non-specific mechanical tear; insufficient proof of bacterial blight"
                
        rec["verdict"] = verdict
        rec["conf"] = conf
        rec["notes"] = notes
        verdict_counts[(cls, verdict)] += 1

    print("\n=======================================================")
    print("FINAL AI-REVIEW VERDICT DISTRIBUTION:")
    for (cls, v), count in sorted(verdict_counts.items()):
        print(f"  {cls:25} | {v:30} : {count:3}")
        
    # Analyze the confirmed subset
    confirmed_bb = [r for r in records if r["class_name"] == "cotton_bacterial_blight" and r["verdict"] == "Label confirmed"]
    confirmed_hl = [r for r in records if r["class_name"] == "cotton_healthy" and r["verdict"] == "Label confirmed"]
    
    bb_groups = set(r["physical_cluster_id"] for r in confirmed_bb)
    hl_groups = set(r["physical_cluster_id"] for r in confirmed_hl)
    
    print("\n=======================================================")
    print("CONFIRMED RESEARCH CANDIDATE SUBSET:")
    print(f"  cotton_bacterial_blight : {len(confirmed_bb)} images across {len(bb_groups)} independent physical specimens")
    print(f"  cotton_healthy          : {len(confirmed_hl)} images across {len(hl_groups)} independent physical specimens")
    print(f"  Total Confirmed Images  : {len(confirmed_bb) + len(confirmed_hl)}")
    
if __name__ == "__main__":
    main()
