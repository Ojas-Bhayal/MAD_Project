import csv
import json
from pathlib import Path
from PIL import Image
import numpy as np

def main():
    manifest_path = Path("data/manifests/manifest_cotton_staged.csv")
    with open(manifest_path, "r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    n = len(rows)
    print(f"Total rows: {n}")
    
    # Pre-load all images into uint8 numpy arrays
    print("Pre-loading 359 images into RAM...")
    img_cache = {}
    for r in rows:
        p = Path(r["staged_path"])
        with Image.open(p) as img:
            img_cache[p.name] = np.array(img.convert("RGB"), dtype=np.int16)
    print("Done pre-loading.")

    # Find cross-split pairs with dH <= 6
    cross_split_pairs = []
    for i in range(n):
        h1 = int(rows[i]["phash"], 16)
        s1 = rows[i]["split"]
        for j in range(i + 1, n):
            s2 = rows[j]["split"]
            if s1 == s2:
                continue
            h2 = int(rows[j]["phash"], 16)
            d = (h1 ^ h2).bit_count()
            if d <= 6:
                cross_split_pairs.append((d, rows[i], rows[j]))

    print(f"Total cross-split pairs with dH <= 6: {len(cross_split_pairs)}")
    
    # Inspect by dH
    all_close_candidates = []
    for target_d in range(7):
        subset = [p for p in cross_split_pairs if p[0] == target_d]
        print(f"\n==================================================")
        print(f"--- Cross-split pairs at dH = {target_d} (Total: {len(subset)}) ---")
        
        analyzed = []
        for d, r1, r2 in subset:
            n1 = Path(r1["staged_path"]).name
            n2 = Path(r2["staged_path"]).name
            im1 = img_cache[n1]
            im2 = img_cache[n2]
            
            diff = np.abs(im1 - im2)
            mean_d = float(diff.mean())
            max_d = int(diff.max())
            pct_close = float((diff < 15).mean() * 100)
            
            same_cls = (r1["cropdoc_class_name"] == r2["cropdoc_class_name"])
            
            rec = {
                "dH": d,
                "img1": n1,
                "img2": n2,
                "cls1": r1["cropdoc_class_name"],
                "cls2": r2["cropdoc_class_name"],
                "same_cls": same_cls,
                "spl1": r1["split"],
                "spl2": r2["split"],
                "grp1": r1["group_id"],
                "grp2": r2["group_id"],
                "mean_diff": mean_d,
                "max_diff": max_d,
                "pct_close": pct_close,
            }
            analyzed.append(rec)
            if same_cls and (mean_d < 35 or pct_close > 40):
                all_close_candidates.append(rec)
        
        # Sort by mean_diff
        analyzed.sort(key=lambda x: x["mean_diff"])
        
        close_subset = [a for a in analyzed if a["same_cls"] and a["mean_diff"] < 35]
        print(f"Same-class pairs with mean_diff < 35: {len(close_subset)}")
        for a in close_subset[:15]:
            print(f"  dH={a['dH']}: {a['img1']} ({a['spl1']}, {a['grp1']}) <-> {a['img2']} ({a['spl2']}, {a['grp2']}) | mean_diff={a['mean_diff']:.2f}, max={a['max_diff']}, pct_close={a['pct_close']:.1f}%")

    print("\n==================================================")
    print(f"SUMMARY OF CANDIDATE GENUINE SPECIMENS (mean_diff < 35): {len(all_close_candidates)}")
    for c in sorted(all_close_candidates, key=lambda x: x["mean_diff"]):
        print(f"dH={c['dH']} | mean_diff={c['mean_diff']:5.2f} | pct_close={c['pct_close']:5.1f}% | {c['img1']} ({c['spl1']}) <-> {c['img2']} ({c['spl2']}) | grps: {c['grp1']} <-> {c['grp2']}")

if __name__ == "__main__":
    main()
