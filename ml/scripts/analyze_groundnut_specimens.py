import os
import re
from collections import defaultdict
from PIL import Image

data_dir = os.path.join("data", "raw", "mendeley_groundnut")

classes = ["early_leaf_spot_1", "healthy_leaf_1"]

for c in classes:
    print(f"\n==================== Specimen Analysis: {c} ====================")
    all_files = []
    for s in ["train", "test"]:
        cdir = os.path.join(data_dir, s, c)
        for fn in os.listdir(cdir):
            all_files.append((fn, s, os.path.join(cdir, fn)))
            
    # Classify files
    dr_items = []
    non_dr_items = []
    
    for fn, s, p in all_files:
        m = re.match(r"^dr_([0-9a-zA-Z_]+?)_(\d+)\.jpg$", fn)
        if m:
            dr_items.append((fn, s, p, m.group(1), m.group(2)))
        else:
            non_dr_items.append((fn, s, p))
            
    print(f"Total files: {len(all_files)}")
    print(f"  dr_ (augmented derivatives): {len(dr_items)}")
    print(f"  non-dr (originals/base captures): {len(non_dr_items)}")
    
    # Map dr_ specimen IDs
    dr_by_specimen = defaultdict(list)
    for fn, s, p, spec_id, aug_id in dr_items:
        dr_by_specimen[spec_id].append(fn)
        
    print(f"  Distinct specimen IDs in dr_: {len(dr_by_specimen)}")
    
    # Check if non_dr files correspond to these specimen IDs
    # non-dr files can be like "104.jpg", "104_1.jpg", "IMG_03469.jpg"
    matched_non_dr = 0
    unmatched_non_dr = []
    for fn, s, p in non_dr_items:
        # try matching pure number or prefix
        base = os.path.splitext(fn)[0]
        base_num = re.sub(r"_\d+$", "", base)
        base_num_paren = re.sub(r"\s*\(\d+\)$", "", base)
        if base in dr_by_specimen or base_num in dr_by_specimen or base_num_paren in dr_by_specimen:
            matched_non_dr += 1
        else:
            unmatched_non_dr.append(fn)
            
    print(f"  Non-dr matching dr specimen IDs: {matched_non_dr}")
    print(f"  Non-dr independent specimens (no dr_ derivatives): {len(unmatched_non_dr)}")
    print(f"  Sample independent specimens: {unmatched_non_dr[:10]}")
