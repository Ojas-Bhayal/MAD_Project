import os
import re
import random
from collections import defaultdict

# Group-aware splitting simulation
data_dir = os.path.join("data", "raw", "mendeley_groundnut")
classes = ["early_leaf_spot_1", "healthy_leaf_1"]

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
            m = re.match(r"^dr_([0-9a-zA-Z_]+?)_(\d+)\.jpg$", fn)
            if m:
                grp = f"{c}_spec_{m.group(1)}"
                is_dr = True
            else:
                base = os.path.splitext(fn)[0]
                base_clean = re.sub(r"_\d+$", "", base)
                base_clean = re.sub(r"\s*\(\d+\)$", "", base_clean)
                grp = f"{c}_spec_{base_clean}"
                is_dr = False
                
            is_conflict = (fn in conflicting_files)
            records.append({
                "class": c,
                "fname": fn,
                "fpath": p,
                "group_id": grp,
                "is_dr": is_dr,
                "is_conflict": is_conflict
            })

# Filter out conflicts
clean_records = [r for r in records if not r["is_conflict"]]
print(f"Clean records: {len(clean_records)} (excluded {len(records) - len(clean_records)} conflicts)")

# Split per class by group_id
random.seed(42)

split_records = {}
for c in classes:
    c_recs = [r for r in clean_records if r["class"] == c]
    groups = list(set(r["group_id"] for r in c_recs))
    random.shuffle(groups)
    
    # Calculate group assignments targeting ~70% train, 15% val, 15% test by image counts
    grp_to_recs = defaultdict(list)
    for r in c_recs:
        grp_to_recs[r["group_id"]].append(r)
        
    total_imgs = len(c_recs)
    target_train = int(total_imgs * 0.70)
    target_val = int(total_imgs * 0.15)
    
    train_grps = []
    val_grps = []
    test_grps = []
    
    train_cnt = 0
    val_cnt = 0
    test_cnt = 0
    
    for g in groups:
        g_cnt = len(grp_to_recs[g])
        if train_cnt + g_cnt <= target_train or (val_cnt >= target_val and test_cnt >= target_val):
            train_grps.append(g)
            train_cnt += g_cnt
        elif val_cnt + g_cnt <= target_val or (test_cnt >= target_val):
            val_grps.append(g)
            val_cnt += g_cnt
        else:
            test_grps.append(g)
            test_cnt += g_cnt
            
    print(f"\nClass: {c} (Total images: {total_imgs}, Total groups: {len(groups)})")
    print(f"  Train: {train_cnt} images ({len(train_grps)} groups) - {train_cnt/total_imgs*100:.1f}%")
    print(f"  Val:   {val_cnt} images ({len(val_grps)} groups) - {val_cnt/total_imgs*100:.1f}%")
    print(f"  Test:  {test_cnt} images ({len(test_grps)} groups) - {test_cnt/total_imgs*100:.1f}%")
    
    # Verify zero group leakage
    assert len(set(train_grps) & set(val_grps)) == 0
    assert len(set(train_grps) & set(test_grps)) == 0
    assert len(set(val_grps) & set(test_grps)) == 0
    print("  Zero group leakage verified!")
