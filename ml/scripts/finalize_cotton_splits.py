import csv
import json
import random
from pathlib import Path
from collections import defaultdict
from PIL import Image
import numpy as np

def main():
    root = Path(__file__).resolve().parents[2]
    manifest_path = root / "data" / "manifests" / "manifest_cotton_staged.csv"
    split_path = root / "data" / "splits" / "split_mendeley_cotton_2025.json"
    reports_dir = root / "ml" / "reports" / "mendeley_cotton_2025"
    
    with open(manifest_path, "r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    print(f"Loaded {len(rows)} staged manifest rows.")

    # Pre-load images to RAM
    img_cache = {}
    for r in rows:
        p = root / r["staged_path"]
        with Image.open(p) as img:
            img_cache[p.name] = np.array(img.convert("RGB"), dtype=np.int16)

    # 1. Intra-class Union-Find Clustering
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
            elif d <= 2 and m < 20:
                link = True
            elif d <= 6 and m < 15:
                link = True
                
            if link:
                union(i, j)

    clusters_by_class = defaultdict(lambda: defaultdict(list))
    for i, r in enumerate(rows):
        root_id = find(i)
        cls = r["cropdoc_class_name"]
        clusters_by_class[cls][root_id].append(r)

    # 2. Fixed-Seed Deterministic Group-Aware Splitting
    seed = 42
    rng = random.Random(seed)

    split_data = {
        "dataset_name": "mendeley_cotton_2025_staged",
        "random_seed": seed,
        "split_ratio_target": {"train": 0.70, "val": 0.15, "test": 0.15},
        "total_images": len(rows),
        "total_specimen_groups": 0,
        "per_class_summary": {},
        "splits": {
            "train": {"total_images": 0, "total_groups": 0, "classes": {}, "samples": []},
            "val": {"total_images": 0, "total_groups": 0, "classes": {}, "samples": []},
            "test": {"total_images": 0, "total_groups": 0, "classes": {}, "samples": []}
        }
    }

    updated_rows = []
    total_dataset_groups = 0

    for cls in sorted(clusters_by_class.keys()):
        groups = list(clusters_by_class[cls].values())
        total_dataset_groups += len(groups)
        
        # Deterministic shuffle then sort descending by size
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

        split_data["per_class_summary"][cls] = {
            "total_images": total_class_imgs,
            "total_groups": len(groups),
            "train_images": train_cnt,
            "train_groups": len(train_g),
            "val_images": val_cnt,
            "val_groups": len(val_g),
            "test_images": test_cnt,
            "test_groups": len(test_g),
        }

        # Assign canonical group IDs and splits
        prefix = "cotton_mendeley_bb_" if "bacterial" in cls else "cotton_mendeley_hl_"
        all_ordered = [("train", train_g), ("val", val_g), ("test", test_g)]
        
        g_counter = 1
        for split_name, g_list in all_ordered:
            split_data["splits"][split_name]["classes"][cls] = {
                "images": sum(len(g) for g in g_list),
                "groups": len(g_list)
            }
            for g in g_list:
                gid = f"{prefix}{g_counter:03d}"
                g_counter += 1
                for m in g:
                    m["group_id"] = gid
                    m["split"] = split_name
                    updated_rows.append(m)
                    split_data["splits"][split_name]["samples"].append({
                        "image_id": m["image_id"],
                        "staged_path": m["staged_path"],
                        "original_raw_path": m["original_raw_path"],
                        "sha256": m["sha256"],
                        "phash": m["phash"],
                        "group_id": gid,
                        "cropdoc_class_name": m["cropdoc_class_name"],
                        "cropdoc_class_index": int(m["cropdoc_class_index"]),
                        "split": split_name
                    })

    split_data["total_specimen_groups"] = total_dataset_groups
    for sname in ["train", "val", "test"]:
        s_samples = split_data["splits"][sname]["samples"]
        split_data["splits"][sname]["total_images"] = len(s_samples)
        split_data["splits"][sname]["total_groups"] = len(set(s["group_id"] for s in s_samples))

    # Sort updated_rows deterministically by staged_path
    updated_rows.sort(key=lambda r: r["staged_path"])

    # 3. Write Updated Manifests
    fieldnames = list(updated_rows[0].keys())
    with open(manifest_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(updated_rows)
    print(f"Updated primary manifest: {manifest_path}")

    # Mirror to reports
    mirror_manifest = reports_dir / "manifest_cotton_staged.csv"
    with open(mirror_manifest, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(updated_rows)
    print(f"Updated mirror manifest: {mirror_manifest}")

    # 4. Write Split JSON
    with open(split_path, "w", encoding="utf-8") as f:
        json.dump(split_data, f, indent=2)
    print(f"Updated primary split: {split_path}")

    mirror_split = reports_dir / "split_mendeley_cotton_2025.json"
    with open(mirror_split, "w", encoding="utf-8") as f:
        json.dump(split_data, f, indent=2)
    print(f"Updated mirror split: {mirror_split}")

    print("\nRegeneration Complete!")
    print(f"Total Images: {len(updated_rows)}")
    print(f"Total Specimen Groups: {total_dataset_groups}")
    for s in ["train", "val", "test"]:
        st = split_data["splits"][s]
        print(f"  {s.upper()}: {st['total_images']} images across {st['total_groups']} groups")

if __name__ == "__main__":
    main()
