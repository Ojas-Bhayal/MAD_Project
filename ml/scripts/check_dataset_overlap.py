import os
import csv
import hashlib

manifest_cotton = "data/manifests/manifest_cotton_staged.csv"
manifest_sethy = "data/manifests/manifest_rice_mendeley_sethy.csv"
manifest_hasan = "data/manifests/manifest_rice_mendeley_healthy_candidate.csv"
manifest_fieldplant = "data/manifests/manifest_fieldplant.csv"

existing_shas = {}
for mpath, name in [(manifest_cotton, "cotton"), (manifest_sethy, "rice_sethy"), 
                    (manifest_hasan, "rice_hasan"), (manifest_fieldplant, "fieldplant")]:
    if os.path.exists(mpath):
        with open(mpath, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                s = row.get("parent_sha256") or row.get("sha256")
                if s:
                    existing_shas[s] = name

print(f"Total existing unique SHA-256 hashes loaded: {len(existing_shas)}")

groundnut_shas = {}
data_dir = os.path.join("data", "raw", "mendeley_groundnut")
for c in ["early_leaf_spot_1", "healthy_leaf_1"]:
    for s in ["train", "test"]:
        cdir = os.path.join(data_dir, s, c)
        for fn in os.listdir(cdir):
            p = os.path.join(cdir, fn)
            if os.path.isfile(p):
                with open(p, "rb") as f:
                    sha = hashlib.sha256(f.read()).hexdigest()
                groundnut_shas[sha] = (c, s, fn)

print(f"Total groundnut target hashes: {len(groundnut_shas)}")
overlap = set(groundnut_shas.keys()) & set(existing_shas.keys())
print(f"Overlap with existing CropDoc datasets: {len(overlap)}")
