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
    base_dir = Path("data/raw/rice_mendeley_sethy")
    print(f"Auditing raw files under {base_dir}...")
    
    classes = ["Bacterialblight", "Blast", "Brownspot", "Tungro"]
    
    total_files = 0
    corrupt_files = []
    file_records = []
    
    sha_map = defaultdict(list)
    phash_map = defaultdict(list)
    
    for cls in classes:
        cls_dir = base_dir / cls
        if not cls_dir.exists():
            print(f"Directory missing: {cls_dir}")
            continue
            
        files = sorted(list(cls_dir.glob("*.*")))
        print(f"\nClass {cls}: {len(files)} files found.")
        total_files += len(files)
        
        for f in files:
            data = f.read_bytes()
            sha = hashlib.sha256(data).hexdigest()
            
            # Format and dimension check
            try:
                with Image.open(f) as img:
                    w, h = img.size
                    fmt = img.format
                    mode = img.mode
                    phash = compute_phash(img)
            except Exception as e:
                corrupt_files.append((str(f), str(e)))
                w, h, fmt, mode, phash = -1, -1, "CORRUPT", "CORRUPT", "0"*16

            rec = {
                "path": str(f.as_posix()),
                "filename": f.name,
                "source_class": cls,
                "sha256": sha,
                "phash": phash,
                "width": w,
                "height": h,
                "format": fmt,
                "mode": mode,
                "size_bytes": len(data)
            }
            file_records.append(rec)
            sha_map[sha].append(rec)
            phash_map[phash].append(rec)

    print(f"\n=======================================================")
    print(f"TOTAL FILES AUDITED: {total_files}")
    print(f"CORRUPT / UNREADABLE FILES: {len(corrupt_files)}")
    print(f"UNIQUE SHA-256 HASHES: {len(sha_map)}")
    print(f"UNIQUE pHASH VALUES:   {len(phash_map)}")

    # Check exact duplicate groups
    exact_dupes = {k: v for k, v in sha_map.items() if len(v) > 1}
    print(f"EXACT DUPLICATE SHA-256 GROUPS: {len(exact_dupes)}")
    
    # Check conflicting labels (different source_class with same sha256)
    cross_class_sha = {}
    for sha, recs in sha_map.items():
        clss = set(r["source_class"] for r in recs)
        if len(clss) > 1:
            cross_class_sha[sha] = recs

    print(f"CROSS-CLASS EXACT SHA-256 CONFLICTS: {len(cross_class_sha)}")
    if cross_class_sha:
        for sha, recs in list(cross_class_sha.items())[:5]:
            print(f"  Conflict SHA {sha[:12]}: {[r['path'] for r in recs]}")

    # Check cross-class pHash conflicts
    cross_class_phash = {}
    for phash, recs in phash_map.items():
        clss = set(r["source_class"] for r in recs)
        if len(clss) > 1:
            cross_class_phash[phash] = recs
    print(f"CROSS-CLASS pHASH (dH=0) CONFLICTS: {len(cross_class_phash)}")

    # Check dimensions and formats
    dims = Counter((r["width"], r["height"]) for r in file_records)
    formats = Counter(r["format"] for r in file_records)
    modes = Counter(r["mode"] for r in file_records)
    print(f"\nImage Dimensions Distribution: {dims}")
    print(f"Image Formats: {formats}")
    print(f"Color Modes:   {modes}")

    # Check the prefix structure (e.g. BACTERIALBLIGHT1 vs BACTERIALBLIGHT2)
    # Check if number suffix represents original specimen
    print("\n--- Investigating Prefix and Suffix Numbering ---")
    for cls in classes:
        cls_recs = [r for r in file_records if r["source_class"] == cls]
        # Extract suffix number e.g. '_001.jpg' -> '001'
        suffixes = defaultdict(list)
        for r in cls_recs:
            fname = r["filename"]
            if "_" in fname:
                sfx = fname.split("_")[-1].split(".")[0]
                suffixes[sfx].append(r)
        print(f"\nClass {cls}:")
        print(f"  Total files: {len(cls_recs)}")
        print(f"  Unique suffix numbers: {len(suffixes)}")
        sfx_lens = Counter(len(v) for v in suffixes.values())
        print(f"  Files per suffix number distribution: {sfx_lens}")
        sample_sfx = list(suffixes.keys())[0]
        print(f"  Sample suffix '_{sample_sfx}': {[r['filename'] for r in suffixes[sample_sfx]]}")

if __name__ == "__main__":
    main()
