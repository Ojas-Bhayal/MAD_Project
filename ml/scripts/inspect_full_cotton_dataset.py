"""
Inspection and classification script for the 359 staged cotton images.
Loads each full-resolution 700x700 image, verifies SHA-256, extracts morphological,
lesion, color, and blur metrics, and outputs fine-grained AI reviews.
"""

import csv
import hashlib
from pathlib import Path
import cv2
import numpy as np

def main():
    root = Path(__file__).resolve().parents[2]
    manifest_path = root / "data" / "manifests" / "manifest_cotton_staged.csv"
    orig_checklist_path = root / "ml" / "reports" / "mendeley_cotton_2025" / "expert_review_checklist.csv"
    
    with open(manifest_path, "r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
        
    print(f"Loaded {len(rows)} staged cotton manifest rows.")
    
    # Pre-parse burst groups and collisions
    group_counts = {}
    for r in rows:
        group_counts[r["group_id"]] = group_counts.get(r["group_id"], 0) + 1
        
    # Checksum and file integrity check
    integrity_failures = 0
    records = []
    
    for r in rows:
        p = root / r["staged_path"]
        if not p.exists():
            print(f"ERROR: Missing file {p}")
            integrity_failures += 1
            continue
            
        with open(p, "rb") as fp:
            data = fp.read()
        sha = hashlib.sha256(data).hexdigest()
        if sha != r["sha256"]:
            print(f"ERROR: SHA-256 mismatch for {p.name}")
            integrity_failures += 1
            continue
            
        bgr = cv2.imread(str(p))
        if bgr is None:
            print(f"ERROR: Failed to read {p.name}")
            integrity_failures += 1
            continue
            
        h, w, _ = bgr.shape
        b, g, r_ch = cv2.split(bgr.astype(np.float32))
        hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
        
        # Background segmentation: white/light paper sheet
        diff_rg = np.abs(r_ch - g)
        diff_gb = np.abs(g - b)
        diff_rb = np.abs(r_ch - b)
        max_diff = np.maximum(np.maximum(diff_rg, diff_gb), diff_rb)
        bg = (max_diff < 22) & (bgr[:,:,0] > 165) & (bgr[:,:,1] > 165) & (bgr[:,:,2] > 165)
        leaf = ~bg
        leaf_px = np.count_nonzero(leaf)
        leaf_fraction = leaf_px / (h * w)
        
        # Green healthy tissue: H in [32, 85], S > 30, V > 50
        green = leaf & (hsv[:,:,0] >= 32) & (hsv[:,:,0] <= 85) & (hsv[:,:,1] > 30) & (hsv[:,:,2] > 50)
        green_px = np.count_nonzero(green)
        green_pct = green_px / max(leaf_px, 1)
        
        # Yellow chlorotic: H in [18, 32], S > 60, V > 100
        yellow = leaf & (hsv[:,:,0] >= 18) & (hsv[:,:,0] < 32) & (hsv[:,:,1] > 60) & (hsv[:,:,2] > 100)
        yellow_px = np.count_nonzero(yellow)
        yellow_pct = yellow_px / max(leaf_px, 1)
        
        # Brown / dark necrotic: H < 20 and S > 30, or V < 70
        necrotic = leaf & (
            ((hsv[:,:,0] < 20) & (hsv[:,:,1] > 30) & (hsv[:,:,2] < 180)) |
            ((hsv[:,:,0] >= 20) & (hsv[:,:,0] < 30) & (hsv[:,:,1] > 40) & (hsv[:,:,2] < 100)) |
            ((hsv[:,:,2] < 60) & leaf)
        )
        necrotic_px = np.count_nonzero(necrotic)
        necrotic_pct = necrotic_px / max(leaf_px, 1)
        
        # Reddening / purple bronzing: H > 160 or (H < 10 and S > 50 and V in [60, 160] and r_ch > g + 25)
        reddening = leaf & ((hsv[:,:,0] > 160) | (hsv[:,:,0] < 10)) & (hsv[:,:,1] > 50) & (r_ch > g + 25)
        reddening_px = np.count_nonzero(reddening)
        red_pct = reddening_px / max(leaf_px, 1)
        
        # Blur check (Laplacian variance)
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        lap_var = cv2.Laplacian(gray, cv2.CV_64F).var()
        
        # Lesion contours (connected components)
        nec_u8 = (necrotic & ~bg).astype(np.uint8) * 255
        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
        nec_clean = cv2.morphologyEx(nec_u8, cv2.MORPH_OPEN, kernel)
        contours, _ = cv2.findContours(nec_clean, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        valid_spots = [c for c in contours if cv2.contourArea(c) >= 30]
        
        angular_count = 0
        circular_count = 0
        for c in valid_spots:
            area = cv2.contourArea(c)
            peri = cv2.arcLength(c, True)
            if peri > 0:
                circ = 4 * np.pi * area / (peri * peri)
                if circ > 0.65:
                    circular_count += 1
                else:
                    angular_count += 1
                    
        records.append({
            "row": r,
            "filename": p.name,
            "class_name": r["cropdoc_class_name"],
            "group_id": r["group_id"],
            "split": r["split"],
            "sha256": r["sha256"],
            "phash": r["phash"],
            "leaf_fraction": leaf_fraction,
            "green_pct": green_pct,
            "yellow_pct": yellow_pct,
            "necrotic_pct": necrotic_pct,
            "red_pct": red_pct,
            "lap_var": lap_var,
            "num_spots": len(valid_spots),
            "angular_spots": angular_count,
            "circular_spots": circular_count,
            "is_burst": group_counts[r["group_id"]] > 1
        })
        
    print(f"Integrity check passed: {len(records)}/{len(rows)} verified (failures: {integrity_failures})")
    
    # Detailed diagnosis of anomalies
    print("\n--- HEALTHY LEAF ANOMALIES (Necrosis > 10% or Yellow > 10%) ---")
    hl_records = [rec for rec in records if rec["class_name"] == "cotton_healthy"]
    for rec in hl_records:
        if rec["necrotic_pct"] > 0.10 or rec["yellow_pct"] > 0.10:
            print(f"  {rec['filename']}: nec={rec['necrotic_pct']:.3f}, yel={rec['yellow_pct']:.3f}, grp={rec['group_id']}")
            
    print("\n--- BACTERIAL BLIGHT ANOMALIES ---")
    bb_records = [rec for rec in records if rec["class_name"] == "cotton_bacterial_blight"]
    for rec in bb_records:
        if rec["red_pct"] > 0.08:
            print(f"  [REDDENING/STRESS] {rec['filename']}: red={rec['red_pct']:.3f}, nec={rec['necrotic_pct']:.3f}, grp={rec['group_id']}")
        elif rec["green_pct"] > 0.70 and rec["necrotic_pct"] < 0.05 and rec["yellow_pct"] < 0.05:
            print(f"  [CLEAN GREEN/NO BLIGHT] {rec['filename']}: green={rec['green_pct']:.3f}, nec={rec['necrotic_pct']:.3f}, grp={rec['group_id']}")
        elif rec["num_spots"] <= 2 and rec["necrotic_pct"] < 0.06:
            print(f"  [MINIMAL/BORDERLINE] {rec['filename']}: spots={rec['num_spots']}, nec={rec['necrotic_pct']:.3f}, grp={rec['group_id']}")

if __name__ == "__main__":
    main()
