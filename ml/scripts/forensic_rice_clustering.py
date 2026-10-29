import hashlib
from pathlib import Path
from PIL import Image
import numpy as np
from collections import defaultdict

def compute_phash_variants(img: Image.Image) -> list:
    # Compute pHash for 8 orientations (4 rotations x 2 flips)
    variants = []
    base_arr = np.array(img.convert("L").resize((8, 8), Image.Resampling.BILINEAR))
    for flip in [False, True]:
        arr = np.fliplr(base_arr) if flip else base_arr
        for rot in range(4):
            r_arr = np.rot90(arr, rot)
            avg = r_arr.mean()
            bits = 0
            for px in r_arr.flatten():
                bits = (bits << 1) | (1 if px >= avg else 0)
            variants.append(f"{bits:016x}")
    return variants

def main():
    base = Path("data/raw/rice_mendeley_sethy")
    classes = ["Bacterialblight", "Blast", "Brownspot", "Tungro"]
    
    print("Forensic Image Cluster Analysis across Rice Dataset:")
    
    for cls in classes:
        cls_dir = base / cls
        files = sorted(list(cls_dir.glob("*.jpg")))
        print(f"\n=======================================================")
        print(f"CLASS: {cls} ({len(files)} total files)")
        
        # 1. Exact SHA-256 groups
        sha_groups = defaultdict(list)
        for f in files:
            sha = hashlib.sha256(f.read_bytes()).hexdigest()
            sha_groups[sha].append(f)
            
        print(f"Unique byte SHA-256 hashes: {len(sha_groups)}")
        exact_multis = {k: v for k, v in sha_groups.items() if len(v) > 1}
        print(f"Exact duplicate groups (len > 1): {len(exact_multis)} ({sum(len(v) for v in exact_multis.values())} files)")

        # 2. Specimen group analysis based on rotation/flip invariance
        # Let's map files by canonical pHash min variant
        rot_phash_map = defaultdict(list)
        for f in files:
            with Image.open(f) as img:
                vars = compute_phash_variants(img)
                min_var = min(vars)  # canonical orientation invariant hash
                rot_phash_map[min_var].append(f)
                
        print(f"Rotation & Flip invariant pHash clusters: {len(rot_phash_map)}")
        
        # Suffix number analysis
        suffixes = defaultdict(list)
        for f in files:
            fname = f.name
            # extract suffix number
            sfx = fname.split("_")[-1].split(".")[0]
            suffixes[sfx].append(f)
        print(f"Unique filename suffix IDs: {len(suffixes)}")

if __name__ == "__main__":
    main()
