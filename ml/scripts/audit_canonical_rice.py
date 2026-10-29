import os
import hashlib
from pathlib import Path
from PIL import Image
from collections import defaultdict, Counter

def compute_phash(img: Image.Image) -> str:
    small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
    pixels = list(small.getdata())
    avg = sum(pixels) / len(pixels)
    bits = 0
    for px in pixels:
        bits = (bits << 1) | (1 if px >= avg else 0)
    return f"{bits:016x}"

def main():
    base = Path("data/raw/rice_mendeley_sethy")
    
    # Select canonical representative for each specimen number
    # For Bacterial: BACTERIALBLIGHT1_XXX or BACTERIALBLIGHT_XXX
    # For Blast: BLAST1_XXX
    # For Brownspot: brownspot_orig_XXX (if exists) else BROWNSPOT1_XXX
    # For Tungro: TUNGRO1_XXX
    
    classes = ["Bacterialblight", "Blast", "Brownspot", "Tungro"]
    
    canonical_samples = []
    
    # 1. Bacterialblight (264 specimens)
    bb_dir = base / "Bacterialblight"
    for i in range(1, 265):
        sfx = f"{i:03d}"
        p = bb_dir / f"BACTERIALBLIGHT1_{sfx}.jpg"
        if not p.exists():
            p = bb_dir / f"BACTERIALBLIGHT_{sfx}.jpg"
        canonical_samples.append(("Bacterialblight", f"rice_bb_{sfx}", p))

    # 2. Blast (160 specimens)
    blast_dir = base / "Blast"
    for i in range(1, 161):
        sfx = f"{i:03d}"
        p = blast_dir / f"BLAST1_{sfx}.jpg"
        canonical_samples.append(("Blast", f"rice_blast_{sfx}", p))

    # 3. Brownspot (200 specimens)
    bs_dir = base / "Brownspot"
    for i in range(1, 201):
        sfx = f"{i:03d}"
        p = bs_dir / f"brownspot_orig_{sfx}.jpg"
        if not p.exists():
            p = bs_dir / f"BROWNSPOT1_{sfx}.jpg"
        canonical_samples.append(("Brownspot", f"rice_bs_{sfx}", p))

    # 4. Tungro (277 specimens)
    tungro_dir = base / "Tungro"
    for i in range(1, 278):
        sfx = f"{i:03d}"
        p = tungro_dir / f"TUNGRO1_{sfx}.jpg"
        canonical_samples.append(("Tungro", f"rice_tungro_{sfx}", p))

    print(f"Total canonical specimens selected: {len(canonical_samples)}")
    
    # Inspect canonical samples
    sha_map = defaultdict(list)
    phash_map = defaultdict(list)
    
    for cls, spec_id, p in canonical_samples:
        if not p.exists():
            print(f"MISSING CANONICAL: {p}")
            continue
        data = p.read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        with Image.open(p) as img:
            phash = compute_phash(img)
            w, h = img.size
            fmt = img.format
        rec = {
            "class": cls,
            "specimen_id": spec_id,
            "path": str(p),
            "sha256": sha,
            "phash": phash,
            "width": w,
            "height": h,
            "format": fmt
        }
        sha_map[sha].append(rec)
        phash_map[phash].append(rec)

    print(f"\nUnique SHA-256 among {len(canonical_samples)} canonical specimens: {len(sha_map)}")
    print(f"Unique pHash (dH=0) among {len(canonical_samples)} canonical specimens: {len(phash_map)}")

    exact_dupes = {k: v for k, v in sha_map.items() if len(v) > 1}
    print(f"Exact SHA-256 duplicate groups among canonicals: {len(exact_dupes)}")
    for sha, recs in exact_dupes.items():
        print(f"  Exact dupe: {[r['specimen_id'] for r in recs]}")

    # Check pHash dH=0 collisions
    phash_collisions = {k: v for k, v in phash_map.items() if len(v) > 1}
    print(f"\npHash collision groups (dH=0) among canonicals: {len(phash_collisions)}")
    for phash, recs in phash_collisions.items():
        clss = set(r['class'] for r in recs)
        cross = "CROSS-CLASS" if len(clss) > 1 else "INTRA-CLASS"
        print(f"  [{cross}] pHash {phash}: {[r['specimen_id'] + '(' + r['class'] + ')' for r in recs]}")

    # Breakdown by class
    for cls in classes:
        c_recs = [r for recs in sha_map.values() for r in recs if r["class"] == cls]
        c_phashes = set(r["phash"] for r in c_recs)
        print(f"\nClass: {cls}")
        print(f"  Canonical specimens: {len(c_recs)}")
        print(f"  Unique pHashes:      {len(c_phashes)}")

if __name__ == "__main__":
    main()
