import csv
import json
import os
import hashlib
import random
import re
from pathlib import Path
from collections import defaultdict, Counter
import numpy as np
from PIL import Image

def compute_phash(img: Image.Image) -> str:
    # 8x8 average hash / phash
    small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
    pixels = np.array(small, dtype=np.float32).flatten()
    avg = np.mean(pixels)
    bits = 0
    for px in pixels:
        bits = (bits << 1) | (1 if px >= avg else 0)
    return f"{bits:016x}"

def get_d4_thumbs(fpath: Path):
    with Image.open(fpath) as im:
        base = np.array(im.convert("L").resize((16, 16), Image.Resampling.BILINEAR), dtype=np.float32)
        tfs = []
        for rot in range(4):
            r = np.rot90(base, rot)
            tfs.append(r > np.mean(r))
            tfs.append(np.fliplr(r) > np.mean(r))
        return tfs

def main():
    root = Path(__file__).resolve().parents[2]
    raw_dir = root / "data" / "raw" / "rice_mendeley_sethy"
    manifest_path = root / "data" / "manifests" / "manifest_rice_mendeley_sethy.csv"
    split_path = root / "data" / "splits" / "split_rice_mendeley_sethy.json"
    reports_dir = root / "ml" / "reports" / "rice_mendeley_sethy"
    reports_dir.mkdir(parents=True, exist_ok=True)

    print("Auditing all 5,932 files in rice_mendeley_sethy...")
    classes = ["Bacterialblight", "Blast", "Brownspot", "Tungro"]

    all_files = []
    for cls in classes:
        cls_dir = raw_dir / cls
        for f in sorted(cls_dir.glob("*.jpg")):
            all_files.append((cls, f))

    print(f"Total files located: {len(all_files)}")

    # Pre-build Brownspot 100 canonical D4 patterns
    print("Building Brownspot 100 canonical D4 reference patterns...")
    bs_dir = raw_dir / "Brownspot"
    bs_orig_thumbs = {
        i: get_d4_thumbs(bs_dir / f"brownspot_orig_{i:03d}.jpg")
        for i in range(1, 101)
    }

    def match_brownspot_to_orig(fpath: Path) -> int:
        fname = fpath.name
        # Direct pattern match for brownspot_orig_XXX
        if fname.startswith("brownspot_orig_"):
            m = re.match(r"^brownspot_orig_([0-9]+)\.jpg$", fname, re.IGNORECASE)
            if m:
                return int(m.group(1))

        # Direct pattern match for BROWNSPOT<N>_001..100
        m = re.match(r"^BROWNSPOT[0-9]+_([0-9]+)\.jpg$", fname, re.IGNORECASE)
        if m:
            num = int(m.group(1))
            if 1 <= num <= 100:
                return num

        # For files 101..200 and brownspot_rotated_XXX, find nearest neighbor in bs_orig_thumbs
        with Image.open(fpath) as im:
            base = np.array(im.convert("L").resize((16, 16), Image.Resampling.BILINEAR), dtype=np.float32)
            bits = base > np.mean(base)
            best_d = 256
            best_i = -1
            for i in range(1, 101):
                d = min(np.count_nonzero(bits != tf) for tf in bs_orig_thumbs[i])
                if d < best_d:
                    best_d = d
                    best_i = i
                    if d == 0:
                        break
            return best_i

    # Taxonomy mapping
    taxonomy_map = {
        "Bacterialblight": {
            "crop": "rice",
            "disease": "bacterial_leaf_blight",
            "cropdoc_class_name": "rice_bacterial_leaf_blight",
            "cropdoc_class_index": "0",
            "is_supported": True,
            "prefix": "rice_sethy_bb_"
        },
        "Blast": {
            "crop": "rice",
            "disease": "leaf_blast",
            "cropdoc_class_name": "rice_leaf_blast",
            "cropdoc_class_index": "2",
            "is_supported": True,
            "prefix": "rice_sethy_blast_"
        },
        "Brownspot": {
            "crop": "rice",
            "disease": "brown_spot",
            "cropdoc_class_name": "rice_brown_spot",
            "cropdoc_class_index": "1",
            "is_supported": True,
            "prefix": "rice_sethy_bs_"
        },
        "Tungro": {
            "crop": "rice",
            "disease": "tungro",
            "cropdoc_class_name": "",
            "cropdoc_class_index": "",
            "is_supported": False,
            "prefix": "rice_sethy_tungro_"
        }
    }

    records = []
    sha_map = defaultdict(list)
    group_map = defaultdict(list)

    print("Extracting metadata, hashing, and resolving parent specimen groups...")
    for cls, fpath in all_files:
        data = fpath.read_bytes()
        sha = hashlib.sha256(data).hexdigest()

        with Image.open(fpath) as img:
            w, h = img.size
            fmt = img.format
            ph = compute_phash(img)

        fname = fpath.name
        tax = taxonomy_map[cls]

        # Determine parent specimen number and group_id
        fname_lower = fname.lower()
        if cls == "Brownspot":
            spec_num = match_brownspot_to_orig(fpath)
            gid = f"{tax['prefix']}{spec_num:03d}"
            is_can = (fname_lower == f"brownspot_orig_{spec_num:03d}.jpg")
        elif cls == "Bacterialblight":
            m = re.match(r"^([A-Za-z]+[0-9]*)[_-]([0-9]+)\.(jpg|jpeg)$", fname, re.IGNORECASE)
            spec_num = int(m.group(2))
            gid = f"{tax['prefix']}{spec_num:03d}"
            is_can = (fname_lower == f"bacterialblight1_{spec_num:03d}.jpg")
        elif cls == "Blast":
            m = re.match(r"^([A-Za-z]+[0-9]*)[_-]([0-9]+)\.(jpg|jpeg)$", fname, re.IGNORECASE)
            spec_num = int(m.group(2))
            gid = f"{tax['prefix']}{spec_num:03d}"
            is_can = (fname_lower == f"blast1_{spec_num:03d}.jpg")
        elif cls == "Tungro":
            m = re.match(r"^([A-Za-z]+[0-9]*)[_-]([0-9]+)\.(jpg|jpeg)$", fname, re.IGNORECASE)
            spec_num = int(m.group(2))
            gid = f"{tax['prefix']}{spec_num:03d}"
            is_can = (fname_lower == f"tungro1_{spec_num:03d}.jpg")

        rel_path = fpath.relative_to(root).as_posix()

        rec = {
            "image_id": sha[:16],
            "path": rel_path,
            "filename": fname,
            "sha256": sha,
            "phash": ph,
            "width": w,
            "height": h,
            "actual_format": fmt,
            "source": "rice_mendeley_sethy_v2",
            "license": "CC BY 4.0",
            "source_class": cls,
            "crop": tax["crop"],
            "disease": tax["disease"],
            "cropdoc_class_name": tax["cropdoc_class_name"],
            "cropdoc_class_index": tax["cropdoc_class_index"],
            "is_supported": tax["is_supported"],
            "specimen_num": spec_num,
            "group_id": gid,
            "is_canonical_representative": is_can,
            "duplicate_status": "CANONICAL_SPECIMEN" if is_can else "PRE_AUGMENTED_DERIVATIVE",
            "usable_for_cropdoc_training": (tax["is_supported"] and is_can),
            "split": "",
            "notes": ""
        }
        records.append(rec)
        sha_map[sha].append(rec)
        group_map[gid].append(rec)

    # Check exact byte duplicates (e.g. BLAST5 == BLAST1)
    for sha, recs in sha_map.items():
        if len(recs) > 1:
            for r in recs:
                if not r["is_canonical_representative"]:
                    r["duplicate_status"] = "EXACT_BYTE_DUPLICATE"
                    r["notes"] = f"Exact bit-for-bit duplicate copy of {[x['filename'] for x in recs if x != r][0]}"

    print(f"Total canonical specimens: {sum(1 for r in records if r['is_canonical_representative'])}")
    print(f"Total usable for CropDoc: {sum(1 for r in records if r['usable_for_cropdoc_training'])}")
    print(f"Total discrete groups: {len(group_map)}")

    # Split generation: group-aware split with seed=42
    seed = 42
    rng = random.Random(seed)

    split_data = {
        "dataset_name": "rice_mendeley_sethy_v2",
        "source_url": "https://data.mendeley.com/datasets/fwcj7stb8r/2",
        "doi": "10.17632/fwcj7stb8r.2",
        "license": "CC BY 4.0",
        "random_seed": seed,
        "split_ratio_target": {"train": 0.70, "val": 0.15, "test": 0.15},
        "total_files": len(records),
        "total_specimens": len(group_map),
        "total_usable_canonicals": sum(1 for r in records if r["usable_for_cropdoc_training"]),
        "per_class_summary": {},
        "splits": {
            "train": {"total_usable_images": 0, "total_groups": 0, "classes": {}, "samples": []},
            "val": {"total_usable_images": 0, "total_groups": 0, "classes": {}, "samples": []},
            "test": {"total_usable_images": 0, "total_groups": 0, "classes": {}, "samples": []},
            "unsupported": {"total_images": 0, "total_groups": 0, "classes": {}, "samples": []}
        }
    }

    group_split_map = {}

    for cls in classes:
        tax = taxonomy_map[cls]
        cls_records = [r for r in records if r["source_class"] == cls]
        cls_groups = sorted(list(set(r["group_id"] for r in cls_records)))

        if not tax["is_supported"]:
            # Tungro
            split_data["per_class_summary"][cls] = {
                "cropdoc_status": "UNSUPPORTED_CLASS",
                "total_files": len(cls_records),
                "total_groups": len(cls_groups),
                "usable_canonical_images": 0,
                "notes": "Tungro virus is not part of CropDoc 22-class taxonomy; quarantined in unsupported split"
            }
            split_data["splits"]["unsupported"]["classes"][cls] = {
                "total_files": len(cls_records),
                "total_groups": len(cls_groups)
            }
            for r in cls_records:
                r["split"] = "unsupported"
                split_data["splits"]["unsupported"]["samples"].append({
                    "image_id": r["image_id"],
                    "path": r["path"],
                    "group_id": r["group_id"],
                    "source_class": r["source_class"],
                    "split": "unsupported"
                })
            continue

        # Supported class: partition groups
        rng.shuffle(cls_groups)
        n_grps = len(cls_groups)
        n_train = int(round(n_grps * 0.70))
        n_val = int(round(n_grps * 0.15))
        n_test = n_grps - n_train - n_val

        train_grps = set(cls_groups[:n_train])
        val_grps = set(cls_groups[n_train:n_train + n_val])
        test_grps = set(cls_groups[n_train + n_val:])

        for g in train_grps:
            group_split_map[g] = "train"
        for g in val_grps:
            group_split_map[g] = "val"
        for g in test_grps:
            group_split_map[g] = "test"

        cls_canonicals = [r for r in cls_records if r["is_canonical_representative"]]
        train_can = sum(1 for r in cls_canonicals if r["group_id"] in train_grps)
        val_can = sum(1 for r in cls_canonicals if r["group_id"] in val_grps)
        test_can = sum(1 for r in cls_canonicals if r["group_id"] in test_grps)

        split_data["per_class_summary"][cls] = {
            "cropdoc_class_name": tax["cropdoc_class_name"],
            "cropdoc_class_index": int(tax["cropdoc_class_index"]),
            "total_files": len(cls_records),
            "total_groups": n_grps,
            "usable_canonical_images": len(cls_canonicals),
            "train_canonical_images": train_can,
            "train_groups": len(train_grps),
            "val_canonical_images": val_can,
            "val_groups": len(val_grps),
            "test_canonical_images": test_can,
            "test_groups": len(test_grps)
        }

        for split_name, grp_set, can_count in [("train", train_grps, train_can), ("val", val_grps, val_can), ("test", test_grps, test_can)]:
            split_data["splits"][split_name]["classes"][tax["cropdoc_class_name"]] = {
                "usable_images": can_count,
                "groups": len(grp_set)
            }

    # Assign split to all records
    for r in records:
        if r["is_supported"]:
            r["split"] = group_split_map[r["group_id"]]
            if r["is_canonical_representative"]:
                s = r["split"]
                split_data["splits"][s]["samples"].append({
                    "image_id": r["image_id"],
                    "path": r["path"],
                    "sha256": r["sha256"],
                    "phash": r["phash"],
                    "group_id": r["group_id"],
                    "cropdoc_class_name": r["cropdoc_class_name"],
                    "cropdoc_class_index": int(r["cropdoc_class_index"]),
                    "split": s
                })

    for s in ["train", "val", "test"]:
        s_samples = split_data["splits"][s]["samples"]
        split_data["splits"][s]["total_usable_images"] = len(s_samples)
        split_data["splits"][s]["total_groups"] = len(set(x["group_id"] for x in s_samples))

    split_data["splits"]["unsupported"]["total_images"] = len(split_data["splits"]["unsupported"]["samples"])
    split_data["splits"]["unsupported"]["total_groups"] = len(set(x["group_id"] for x in split_data["splits"]["unsupported"]["samples"]))

    # Write Manifest CSV
    fieldnames = [
        "image_id", "path", "sha256", "phash", "width", "height", "actual_format",
        "source", "license", "source_class", "crop", "disease",
        "cropdoc_class_name", "cropdoc_class_index",
        "group_id", "duplicate_status",
        "is_canonical_representative", "usable_for_cropdoc_training",
        "split", "notes"
    ]

    with open(manifest_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(records)
    print(f"Manifest written: {manifest_path} ({len(records)} rows)")

    mirror_manifest = reports_dir / "manifest_rice_mendeley_sethy.csv"
    with open(mirror_manifest, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(records)
    print(f"Mirror manifest written: {mirror_manifest}")

    # Write Split JSON
    with open(split_path, "w", encoding="utf-8") as f:
        json.dump(split_data, f, indent=2)
    print(f"Split written: {split_path}")

    mirror_split = reports_dir / "split_rice_mendeley_sethy.json"
    with open(mirror_split, "w", encoding="utf-8") as f:
        json.dump(split_data, f, indent=2)
    print(f"Mirror split written: {mirror_split}")

    print("\nAudit and Split generation completed successfully!")

if __name__ == "__main__":
    main()
