import csv
from pathlib import Path
from PIL import Image
import numpy as np
from collections import defaultdict

def main():
    manifest_path = Path("data/manifests/manifest_cotton_staged.csv")
    with open(manifest_path, "r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    print("Loading 359 staged images into RAM...")
    img_cache = {}
    for r in rows:
        p = Path(r["staged_path"])
        with Image.open(p) as img:
            img_cache[p.name] = np.array(img.convert("RGB"), dtype=np.int16)
    print("Loaded.")

    classes = ["cotton_bacterial_blight", "cotton_healthy"]
    
    for cls in classes:
        cls_rows = [r for r in rows if r["cropdoc_class_name"] == cls]
        m = len(cls_rows)
        print(f"\n==================================================")
        print(f"CLASS: {cls} ({m} images)")
        
        # Calculate pairwise diff for all m*(m-1)/2 pairs
        pairs = []
        for i in range(m):
            n1 = Path(cls_rows[i]["staged_path"]).name
            h1 = int(cls_rows[i]["phash"], 16)
            im1 = img_cache[n1]
            for j in range(i + 1, m):
                n2 = Path(cls_rows[j]["staged_path"]).name
                h2 = int(cls_rows[j]["phash"], 16)
                im2 = img_cache[n2]
                
                dH = (h1 ^ h2).bit_count()
                diff = np.abs(im1 - im2)
                mean_d = float(diff.mean())
                pct_15 = float((diff < 15).mean() * 100)
                
                pairs.append((mean_d, dH, pct_15, cls_rows[i], cls_rows[j]))
        
        pairs.sort(key=lambda x: x[0])
        
        print(f"Top 20 most similar pairs in {cls}:")
        for mean_d, dH, pct_15, r1, r2 in pairs[:20]:
            n1 = Path(r1["staged_path"]).name
            n2 = Path(r2["staged_path"]).name
            s1 = r1["split"]
            s2 = r2["split"]
            g1 = r1["group_id"]
            g2 = r2["group_id"]
            print(f"  mean_diff={mean_d:5.2f} (dH={dH:2d}, <15:{pct_15:4.1f}%): {n1} ({s1}, {g1}) <-> {n2} ({s2}, {g2})")
            
        # Distribution of mean_diff
        print("\nMean pixel diff distribution:")
        for threshold in [10, 15, 20, 25, 30, 35, 40, 50]:
            cnt = sum(1 for p in pairs if p[0] < threshold)
            print(f"  mean_diff < {threshold:2d}: {cnt:4d} pairs")

if __name__ == "__main__":
    main()
