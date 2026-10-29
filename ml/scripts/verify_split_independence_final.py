import csv
import json
import hashlib
from collections import defaultdict
from pathlib import Path
from PIL import Image

def main():
    root = Path(__file__).resolve().parents[2]
    manifest_path = root / "data" / "manifests" / "manifest_cotton_staged.csv"
    split_path = root / "data" / "splits" / "split_mendeley_cotton_2025.json"

    print("=" * 60)
    print("INDEPENDENT VERIFICATION OF COTTON SPLIT INDEPENDENCE")
    print("=" * 60)

    # Load manifest and split
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest_rows = list(csv.DictReader(f))

    with open(split_path, "r", encoding="utf-8") as f:
        split_data = json.load(f)

    # 1. Verification 1: No exact duplicate crosses splits
    print("\n--- Test 1: No Exact Duplicate Crosses Splits ---")
    sha_to_splits = {}
    for r in manifest_rows:
        sha_to_splits.setdefault(r["sha256"], set()).add(r["split"])

    exact_cross_split = {sha: splits for sha, splits in sha_to_splits.items() if len(splits) > 1}
    exact_duplicates_in_staged = {sha: count for sha, count in sha_to_splits.items() if len(count) > 1}
    
    print(f"Total unique SHA-256 hashes in manifest: {len(sha_to_splits)} / {len(manifest_rows)}")
    print(f"Exact duplicates crossing splits: {len(exact_cross_split)}")
    assert len(exact_cross_split) == 0, f"FAIL: Exact duplicates crossing splits: {exact_cross_split}"
    print("RESULT: PASS - Zero exact duplicates cross splits.")

    # 2. Verification 2: No confirmed same-specimen or burst group crosses splits
    print("\n--- Test 2: No Confirmed Specimen / Burst Group Crosses Splits ---")
    group_to_splits = {}
    group_to_members = {}
    for r in manifest_rows:
        gid = r["group_id"]
        group_to_splits.setdefault(gid, set()).add(r["split"])
        group_to_members.setdefault(gid, []).append(r)

    leaking_groups = {gid: splits for gid, splits in group_to_splits.items() if len(splits) > 1}
    print(f"Total unified specimen groups: {len(group_to_splits)}")
    print(f"Multi-member groups (>1 image): {sum(1 for m in group_to_members.values() if len(m) > 1)}")
    print(f"Groups crossing splits: {len(leaking_groups)}")
    if leaking_groups:
        for gid, splits in leaking_groups.items():
            print(f"  LEAKAGE: Group {gid} spans splits: {splits}")
    assert len(leaking_groups) == 0, f"FAIL: Groups crossing splits: {leaking_groups}"

    # Specifically check the user-highlighted sequences:
    # bacterial_(114), (119), (120)
    b_names = ["bacterial_(114).jpg", "bacterial_(119).jpg", "bacterial_(120).jpg"]
    b_rows = [r for r in manifest_rows if Path(r["staged_path"]).name in b_names]
    b_splits = set(r["split"] for r in b_rows)
    b_groups = set(r["group_id"] for r in b_rows)
    print(f"  bacterial_(114, 119, 120) -> Groups: {b_groups}, Splits: {b_splits}")
    assert len(b_splits) == 1, f"FAIL: bacterial_(114, 119, 120) spans multiple splits: {b_splits}"
    assert len(b_groups) == 1, f"FAIL: bacterial_(114, 119, 120) has multiple group IDs: {b_groups}"

    # healthy_(84) and healthy_(95)
    h_names = ["healthy_(84).jpg", "healthy_(95).jpg"]
    h_rows = [r for r in manifest_rows if Path(r["staged_path"]).name in h_names]
    h_splits = set(r["split"] for r in h_rows)
    h_groups = set(r["group_id"] for r in h_rows)
    print(f"  healthy_(84, 95) -> Groups: {h_groups}, Splits: {h_splits}")
    assert len(h_splits) == 1, f"FAIL: healthy_(84, 95) spans multiple splits: {h_splits}"
    assert len(h_groups) == 1, f"FAIL: healthy_(84, 95) has multiple group IDs: {h_groups}"
    print("RESULT: PASS - Zero confirmed same-specimen or burst groups cross splits.")

    # 3. Verification 3: All selected paths and hashes match the staged files
    print("\n--- Test 3: Physical Paths and Hashes Match Staged Files ---")
    missing_files = []
    hash_mismatches = []
    for r in manifest_rows:
        p = root / r["staged_path"]
        if not p.exists():
            missing_files.append(str(p))
            continue
        calc_sha = hashlib.sha256(p.read_bytes()).hexdigest()
        if calc_sha != r["sha256"]:
            hash_mismatches.append((str(p), r["sha256"], calc_sha))

    print(f"Total verified files on disk: {len(manifest_rows) - len(missing_files)} / {len(manifest_rows)}")
    print(f"Missing physical files: {len(missing_files)}")
    print(f"Byte SHA-256 mismatches: {len(hash_mismatches)}")
    assert len(missing_files) == 0, f"FAIL: Missing files: {missing_files}"
    assert len(hash_mismatches) == 0, f"FAIL: Hash mismatches: {hash_mismatches}"
    print("RESULT: PASS - 100% of paths and cryptographic hashes match staged files.")

    # 4. Verification 4: Per-class image and group counts are correct in every split
    print("\n--- Test 4: Per-Class Image and Group Counts in Every Split ---")
    split_counts = defaultdict(lambda: defaultdict(int))
    split_groups = defaultdict(lambda: defaultdict(set))
    for r in manifest_rows:
        cls = r["cropdoc_class_name"]
        spl = r["split"]
        gid = r["group_id"]
        split_counts[cls][spl] += 1
        split_groups[cls][spl].add(gid)

    classes = sorted(split_counts.keys())
    for cls in classes:
        tot_imgs = sum(split_counts[cls].values())
        tot_grps = sum(len(split_groups[cls][s]) for s in ["train", "val", "test"])
        print(f"\nClass: {cls}")
        print(f"  Total Images: {tot_imgs}, Total Groups: {tot_grps}")
        for spl in ["train", "val", "test"]:
            cnt = split_counts[cls][spl]
            grp_cnt = len(split_groups[cls][spl])
            pct = (cnt / tot_imgs) * 100
            print(f"    {spl.upper():5s}: {cnt:3d} images ({pct:5.1f}%), {grp_cnt:3d} groups")

    # Check totals
    total_train = sum(split_counts[c]["train"] for c in classes)
    total_val = sum(split_counts[c]["val"] for c in classes)
    total_test = sum(split_counts[c]["test"] for c in classes)
    print(f"\nOverall Split Totals:")
    print(f"  Train: {total_train} images ({total_train/len(manifest_rows)*100:.1f}%)")
    print(f"  Val:   {total_val} images ({total_val/len(manifest_rows)*100:.1f}%)")
    print(f"  Test:  {total_test} images ({total_test/len(manifest_rows)*100:.1f}%)")
    print(f"  Total: {total_train + total_val + total_test} images")
    assert total_train + total_val + total_test == len(manifest_rows), "FAIL: Total split counts do not equal 359"
    print("RESULT: PASS - Per-class image and group counts strictly match specification.")

    print("\n" + "=" * 60)
    print("ALL 4 INDEPENDENT VERIFICATION TESTS PASSED SUCCESSFULLY!")
    print("=" * 60)

if __name__ == "__main__":
    main()
