import os
import re
from collections import defaultdict

data_dir = os.path.join("data", "raw", "mendeley_groundnut")
classes = ["early_leaf_spot_1", "healthy_leaf_1"]

for c in classes:
    print(f"\n==================== Class: {c} ====================")
    train_stems = defaultdict(list)
    test_stems = defaultdict(list)
    
    for fname in os.listdir(os.path.join(data_dir, "train", c)):
        m = re.match(r"^dr_([0-9a-zA-Z_]+?)_(\d+)\.jpg$", fname)
        if m:
            train_stems[m.group(1)].append(fname)
        else:
            base = re.sub(r"(_\d+)?\.jpg$", "", fname)
            train_stems[base].append(fname)
            
    for fname in os.listdir(os.path.join(data_dir, "test", c)):
        m = re.match(r"^dr_([0-9a-zA-Z_]+?)_(\d+)\.jpg$", fname)
        if m:
            test_stems[m.group(1)].append(fname)
        else:
            base = re.sub(r"(_\d+)?\.jpg$", "", fname)
            test_stems[base].append(fname)
            
    common_stems = set(train_stems.keys()) & set(test_stems.keys())
    print(f"Train unique specimens: {len(train_stems)}")
    print(f"Test unique specimens: {len(test_stems)}")
    print(f"SPECIMENS LEAKING ACROSS PUBLISHER'S TRAIN AND TEST: {len(common_stems)}")
    
    leaking_images_train = sum(len(train_stems[s]) for s in common_stems)
    leaking_images_test = sum(len(test_stems[s]) for s in common_stems)
    print(f"Images involved in leakage: {leaking_images_train} (train) + {leaking_images_test} (test) = {leaking_images_train + leaking_images_test} images!")
    for s in list(common_stems)[:5]:
        print(f"  Specimen '{s}': train={train_stems[s][:2]}... test={test_stems[s][:2]}...")
