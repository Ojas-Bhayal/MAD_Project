import csv
from pathlib import Path
from PIL import Image
import numpy as np

def inspect_image_details(names, title):
    print(f"\n=======================================================")
    print(f"INVESTIGATION: {title}")
    print(f"=======================================================")
    
    rows = []
    manifest_path = Path("data/manifests/manifest_cotton_staged.csv")
    with open(manifest_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for r in reader:
            fname = Path(r["staged_path"]).name
            if fname in names:
                rows.append(r)
                
    for r in rows:
        fname = Path(r["staged_path"]).name
        print(f"File: {fname}")
        print(f"  Raw path:    {r['original_raw_path']}")
        print(f"  SHA-256:     {r['sha256']}")
        print(f"  pHash:       {r['phash']}")
        print(f"  Group ID:    {r['group_id']}")
        print(f"  Split:       {r['split']}")
        print(f"  Dimensions:  {r['width']}x{r['height']}")
        
    # Compare all pairs among these names
    print("\nPairwise Comparisons:")
    for i in range(len(rows)):
        n1 = Path(rows[i]["staged_path"]).name
        im1 = np.array(Image.open(rows[i]["staged_path"]).convert("RGB"), dtype=np.int32)
        h1 = int(rows[i]["phash"], 16)
        for j in range(i + 1, len(rows)):
            n2 = Path(rows[j]["staged_path"]).name
            im2 = np.array(Image.open(rows[j]["staged_path"]).convert("RGB"), dtype=np.int32)
            h2 = int(rows[j]["phash"], 16)
            dH = (h1 ^ h2).bit_count()
            diff = np.abs(im1 - im2)
            mean_d = float(diff.mean())
            max_d = int(diff.max())
            pct_15 = float((diff < 15).mean() * 100)
            pct_0 = float((diff == 0).mean() * 100)
            
            same_spl = (rows[i]["split"] == rows[j]["split"])
            same_grp = (rows[i]["group_id"] == rows[j]["group_id"])
            print(f"  {n1} ({rows[i]['split']}) <-> {n2} ({rows[j]['split']}):")
            print(f"    dH={dH}, mean_diff={mean_d:.2f}, max_diff={max_d}, % identical={pct_0:.2f}%, % diff<15={pct_15:.1f}%")
            print(f"    Same split: {same_spl}, Same group: {same_grp}")

def main():
    # 1. bacterial_(114), (119), (120)
    inspect_image_details(
        ["bacterial_(114).jpg", "bacterial_(119).jpg", "bacterial_(120).jpg", "bacterial_(121).jpg", "bacterial_(118).jpg"],
        "bacterial_(114), (118), (119), (120), (121) Sequence"
    )

    # 2. healthy_(84) and healthy_(95) and healthy_(108)
    inspect_image_details(
        ["healthy_(84).jpg", "healthy_(95).jpg", "healthy_(108).jpg", "healthy_(49).jpg"],
        "healthy_(84), (95), (108), (49) Cluster"
    )

    # 3. bacterial_(194), (195), (196), (198), (205)
    inspect_image_details(
        ["bacterial_(194).jpg", "bacterial_(195).jpg", "bacterial_(196).jpg", "bacterial_(198).jpg", "bacterial_(205).jpg"],
        "bacterial_(194)-(205) Cluster"
    )

    # 4. bacterial_(165), (167), (168), (169), (171)
    inspect_image_details(
        ["bacterial_(165).jpg", "bacterial_(167).jpg", "bacterial_(168).jpg", "bacterial_(169).jpg", "bacterial_(171).jpg"],
        "bacterial_(165)-(171) Cluster"
    )

    # 5. healthy_(102), (22), (173), (242)
    inspect_image_details(
        ["healthy_(102).jpg", "healthy_(22).jpg", "healthy_(173).jpg", "healthy_(242).jpg"],
        "healthy_(102), (22), (173), (242) Cluster"
    )

    # 6. healthy_(116), (44), (80), (91), (151)
    inspect_image_details(
        ["healthy_(116).jpg", "healthy_(44).jpg", "healthy_(80).jpg", "healthy_(91).jpg", "healthy_(151).jpg"],
        "healthy_(116), (44), (80), (91), (151) Cluster"
    )

if __name__ == "__main__":
    main()
