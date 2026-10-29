import os
import re
import hashlib
from collections import defaultdict, Counter

data_dir = os.path.join("data", "raw", "mendeley_groundnut")
classes = ["early_leaf_spot_1", "healthy_leaf_1"]

# 8 conflicting files with nutrition_deficiency_1
conflicting_files = {
    "IMG_00686.jpg", "IMG_0685.jpg", "IMG_0686.jpg", "IMG_0688.jpg",
    "IMG_0963.jpg", "IMG_0965.jpg", "IMG_0966.jpg", "IMG_0969.jpg"
}

records = []
for c in classes:
    for s in ["train", "test"]:
        cdir = os.path.join(data_dir, s, c)
        for fn in sorted(os.listdir(cdir)):
            p = os.path.join(cdir, fn)
            if not os.path.isfile(p):
                continue
            with open(p, "rb") as f:
                sha = hashlib.sha256(f.read()).hexdigest()
            records.append({
                "class": c,
                "publisher_split": s,
                "fname": fn,
                "fpath": p,
                "sha256": sha
            })

print(f"Total target records: {len(records)}")

# Grouping logic:
# Extract specimen_key
def get_specimen_key(cls, fname):
    # If dr_ prefix:
    m = re.match(r"^dr_([0-9a-zA-Z_]+?)_(\d+)\.jpg$", fname)
    if m:
        return f"{cls}_spec_{m.group(1)}"
    # If numeric or IMG_
    base = os.path.splitext(fname)[0]
    # strip _\d+ or ( \d+ )
    base_clean = re.sub(r"_\d+$", "", base)
    base_clean = re.sub(r"\s*\(\d+\)$", "", base_clean)
    return f"{cls}_spec_{base_clean}"

# Assign specimen groups
specimen_groups = defaultdict(list)
for r in records:
    # special check for exact dupe 90.jpg and 91_3.jpg:
    if r["sha256"] == "e84e71752224097204fc7d627c2f6d214a19b882d8c3ae06e12361ff5469ecbb":
        key = "healthy_leaf_1_spec_90"
    else:
        key = get_specimen_key(r["class"], r["fname"])
    r["group_id"] = key
    specimen_groups[key].append(r)

print(f"Total unique specimen groups across target classes: {len(specimen_groups)}")

# Check per-class groups
for c in classes:
    c_recs = [r for r in records if r["class"] == c]
    c_groups = set(r["group_id"] for r in c_recs)
    c_dr = [r for r in c_recs if r["fname"].startswith("dr_")]
    c_nondr = [r for r in c_recs if not r["fname"].startswith("dr_")]
    print(f"\nClass: {c}")
    print(f"  Total images: {len(c_recs)}")
    print(f"  Unique specimen groups: {len(c_groups)}")
    print(f"  Augmented (dr_): {len(c_dr)}")
    print(f"  Original (non-dr): {len(c_nondr)}")

# Check group size distribution
sizes = Counter([len(v) for v in specimen_groups.values()])
print("\nSpecimen group size distribution (number of images per specimen group):")
for sz, count in sorted(sizes.items()):
    print(f"  {sz} images/group: {count} groups")
