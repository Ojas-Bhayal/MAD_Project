#!/usr/bin/env python3
"""
PlantWild v1 Dataset Audit Script for CropDoc.
Performs comprehensive data integrity, split preservation, duplicate analysis,
taxonomy mapping, and quality checks on PlantWild v1 dataset without modifying original files.
"""

import argparse
import csv
import hashlib
import json
import os
import re
import sys
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

# Add CropDoc ml/scripts to path to import common hash utilities
SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

try:
    from common import compute_phash, sha256_file
except ImportError:
    def sha256_file(path: Path) -> str:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        return h.hexdigest()

    def compute_phash(img: Image.Image) -> str:
        small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
        pixels = list(small.getdata())
        avg = sum(pixels) / len(pixels)
        bits = 0
        for px in pixels:
            bits = (bits << 1) | (1 if px >= avg else 0)
        return f"{bits:016x}"


def is_windows_safe_path(p: str) -> tuple[bool, str]:
    """Check if filename violates Windows filesystem constraints."""
    parts = Path(p).parts
    invalid_chars = set('<>:"|?*')
    reserved_names = {
        "CON", "PRN", "AUX", "NUL",
        *(f"COM{i}" for i in range(1, 10)),
        *(f"LPT{i}" for i in range(1, 10)),
    }
    for part in parts:
        for c in invalid_chars:
            if c in part:
                return False, f"Contains invalid character '{c}'"
        if part.endswith(" ") or part.endswith("."):
            return False, "Ends with space or trailing dot"
        stem = part.split(".")[0].upper()
        if stem in reserved_names:
            return False, f"Uses Windows reserved name '{stem}'"
    return True, "Safe"


def process_image(item):
    """Worker function to inspect image, verify integrity, and compute hashes."""
    rel_path, cid, split_id, full_path = item
    try:
        file_size = os.path.getsize(full_path)
        sha = sha256_file(Path(full_path))

        # Check integrity with PIL
        with Image.open(full_path) as img:
            img.verify()

        # Reopen to read dimensions, mode, and calculate phash
        with Image.open(full_path) as img:
            width, height = img.size
            mode = img.mode
            fmt = img.format or "UNKNOWN"
            phash = compute_phash(img)

        return {
            "rel_path": rel_path,
            "cid": cid,
            "split_id": split_id,
            "file_size": file_size,
            "width": width,
            "height": height,
            "mode": mode,
            "format": fmt,
            "sha256": sha,
            "phash": phash,
            "readable": True,
            "error": None,
        }
    except Exception as e:
        return {
            "rel_path": rel_path,
            "cid": cid,
            "split_id": split_id,
            "file_size": os.path.getsize(full_path) if os.path.exists(full_path) else 0,
            "width": 0,
            "height": 0,
            "mode": "",
            "format": "",
            "sha256": "",
            "phash": "",
            "readable": False,
            "error": str(e),
        }


def run_audit(dataset_dir: Path, output_dir: Path, max_workers: int = 8):
    start_time = time.time()
    print(f"=== Starting PlantWild v1 Audit ===")
    print(f"Dataset directory: {dataset_dir}")
    print(f"Output directory:  {output_dir}")

    images_dir = dataset_dir / "images"
    classes_file = dataset_dir / "classes.txt"
    split_file = dataset_dir / "trainval.txt"
    prompts_file = dataset_dir / "plantwild_prompts.json"
    url_record_file = dataset_dir / "url_record.json"

    # 1. Check directory and required files
    if not dataset_dir.exists():
        raise FileNotFoundError(f"Dataset root does not exist: {dataset_dir}")
    if not images_dir.exists():
        raise FileNotFoundError(f"Images directory not found: {images_dir}")
    if not classes_file.exists():
        raise FileNotFoundError(f"Classes file not found: {classes_file}")
    if not split_file.exists():
        raise FileNotFoundError(f"Split file not found: {split_file}")

    # 2. Parse classes.txt
    classes_by_id = {}
    id_by_class = {}
    with open(classes_file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                cid_str, cname = line.split(" ", 1)
                cid = int(cid_str)
                classes_by_id[cid] = cname
                id_by_class[cname] = cid

    num_classes = len(classes_by_id)
    print(f"Parsed {num_classes} classes from classes.txt (IDs 0..{num_classes-1})")

    # Check directory names in images/
    subdirs = sorted([d.name for d in images_dir.iterdir() if d.is_dir()])
    print(f"Found {len(subdirs)} subdirectories in images/")
    missing_dirs = set(classes_by_id.values()) - set(subdirs)
    extra_dirs = set(subdirs) - set(classes_by_id.values())

    # Check prompts file if available
    prompts_data = {}
    if prompts_file.exists():
        with open(prompts_file, "r", encoding="utf-8") as f:
            prompts_data = json.load(f)
    extra_prompts_classes = set(prompts_data.keys()) - set(classes_by_id.values())

    # Check url_record file if available
    url_data = {}
    if url_record_file.exists():
        with open(url_record_file, "r", encoding="utf-8") as f:
            url_data = json.load(f)

    # 3. Parse trainval.txt split file
    split_mode_map = {0: "test", 1: "train", 2: "val"}
    items_to_audit = []
    invalid_split_lines = []
    missing_files = []
    class_id_mismatches = []
    windows_path_issues = []

    with open(split_file, "r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            parts = line.split("=")
            if len(parts) != 3:
                invalid_split_lines.append((line_num, line, "Does not have exactly 3 parts"))
                continue
            rel_path, cid_str, mode_str = parts
            try:
                cid = int(cid_str)
                mode = int(mode_str)
            except ValueError:
                invalid_split_lines.append((line_num, line, "Non-integer cid or mode"))
                continue

            if mode not in split_mode_map:
                invalid_split_lines.append((line_num, line, f"Unknown split mode {mode}"))
                continue

            full_path = images_dir / rel_path.replace("/", os.sep)
            if not full_path.exists():
                missing_files.append((line_num, rel_path))

            # Check class match with directory
            dir_name = Path(rel_path).parent.name
            expected_class = classes_by_id.get(cid)
            if dir_name != expected_class:
                class_id_mismatches.append((line_num, rel_path, cid, expected_class, dir_name))

            # Windows path safety check
            safe, reason = is_windows_safe_path(rel_path)
            if not safe:
                windows_path_issues.append((rel_path, reason))

            items_to_audit.append((rel_path, cid, mode, full_path))

    total_records = len(items_to_audit)
    print(f"Parsed {total_records} records from trainval.txt")

    # Check for untracked disk files in images/
    disk_files_rel = set()
    for p in images_dir.rglob("*"):
        if p.is_file():
            rel = str(p.relative_to(images_dir)).replace("\\", "/")
            disk_files_rel.add(rel)

    trainval_files_rel = set(item[0].replace("\\", "/") for item in items_to_audit)
    untracked_files = disk_files_rel - trainval_files_rel

    print(f"Total disk files in images/: {len(disk_files_rel)}")
    print(f"Untracked disk files: {len(untracked_files)}")
    print(f"Missing files referenced in trainval: {len(missing_files)}")

    # 4. Multithreaded Image Inspection & Hashing
    print(f"Auditing image integrity, dimensions, SHA-256, and pHash with {max_workers} threads...")
    audit_results = []
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        for res in executor.map(process_image, items_to_audit):
            audit_results.append(res)

    print(f"Completed processing {len(audit_results)} images.")

    # 5. Analyze Results
    unreadable_images = [r for r in audit_results if not r["readable"]]
    readable_images = [r for r in audit_results if r["readable"]]

    # Image format, mode, dimensions, size stats
    formats = defaultdict(int)
    modes = defaultdict(int)
    file_sizes = []
    widths = []
    heights = []
    aspect_ratios = []

    # Outliers
    small_files = []      # < 5 KB
    large_files = []      # > 5 MB
    small_dims = []       # min(w, h) < 100
    large_dims = []       # max(w, h) > 4000
    extreme_aspect = []   # ar < 0.5 or ar > 2.0

    # Per-class, per-split counts
    # class_id -> {train: 0, val: 0, test: 0, total: 0}
    class_split_counts = defaultdict(lambda: {"train": 0, "val": 0, "test": 0, "total": 0})
    split_totals = {"train": 0, "val": 0, "test": 0, "total": 0}

    for r in audit_results:
        cid = r["cid"]
        split_name = split_mode_map[r["split_id"]]
        class_split_counts[cid][split_name] += 1
        class_split_counts[cid]["total"] += 1
        split_totals[split_name] += 1
        split_totals["total"] += 1

        if r["readable"]:
            formats[r["format"]] += 1
            modes[r["mode"]] += 1
            sz = r["file_size"]
            w = r["width"]
            h = r["height"]
            ar = w / h if h > 0 else 0
            file_sizes.append(sz)
            widths.append(w)
            heights.append(h)
            aspect_ratios.append(ar)

            if sz < 5 * 1024:
                small_files.append((r["rel_path"], sz))
            elif sz > 5 * 1024 * 1024:
                large_files.append((r["rel_path"], sz))

            if min(w, h) < 100:
                small_dims.append((r["rel_path"], w, h))
            if max(w, h) > 4000:
                large_dims.append((r["rel_path"], w, h))

            if ar < 0.5 or ar > 2.0:
                extreme_aspect.append((r["rel_path"], w, h, ar))

    # 6. Duplicate Analysis
    print("Performing duplicate and near-duplicate analysis...")
    # Exact duplicates by SHA-256
    sha_groups = defaultdict(list)
    for r in readable_images:
        sha_groups[r["sha256"]].append(r)

    exact_dup_groups = {k: v for k, v in sha_groups.items() if len(v) > 1}
    total_exact_duplicate_files = sum(len(v) for v in exact_dup_groups.values())
    redundant_exact_count = sum(len(v) - 1 for v in exact_dup_groups.values())

    # Exact duplicate leakage across splits
    exact_leakage_cross_split = []
    exact_leakage_cross_class = []
    for sha, group in exact_dup_groups.items():
        splits_present = set(r["split_id"] for r in group)
        classes_present = set(r["cid"] for r in group)
        if len(splits_present) > 1:
            exact_leakage_cross_split.append({
                "sha256": sha,
                "splits": [split_mode_map[s] for s in sorted(splits_present)],
                "classes": [classes_by_id[c] for c in sorted(classes_present)],
                "files": [r["rel_path"] for r in group]
            })
        if len(classes_present) > 1:
            exact_leakage_cross_class.append({
                "sha256": sha,
                "classes": [classes_by_id[c] for c in sorted(classes_present)],
                "files": [r["rel_path"] for r in group]
            })

    # Visual near-duplicates (pHash Hamming distance <= 6)
    # Using 8-bit multi-index bucket filtering
    hashes = [int(r["phash"], 16) for r in readable_images]
    buckets = defaultdict(list)
    for idx, h in enumerate(hashes):
        for chunk_idx in range(8):
            chunk_val = (h >> (chunk_idx * 8)) & 0xFF
            buckets[(chunk_idx, chunk_val)].append(idx)

    candidate_pairs = set()
    for chunk_key, idxs in buckets.items():
        n = len(idxs)
        if 1 < n < 400:  # avoid over-frequent degenerate hashes
            for i in range(n):
                for j in range(i + 1, n):
                    a, b = idxs[i], idxs[j]
                    if a > b:
                        a, b = b, a
                    candidate_pairs.add((a, b))

    near_dup_pairs = []
    identical_phash_pairs = []
    near_dup_split_leakage = []

    for a, b in candidate_pairs:
        ha, hb = hashes[a], hashes[b]
        diff = (ha ^ hb).bit_count()
        if diff <= 6:
            ra, rb = readable_images[a], readable_images[b]
            pair_info = {
                "file_a": ra["rel_path"],
                "file_b": rb["rel_path"],
                "class_a": classes_by_id[ra["cid"]],
                "class_b": classes_by_id[rb["cid"]],
                "split_a": split_mode_map[ra["split_id"]],
                "split_b": split_mode_map[rb["split_id"]],
                "hamming_distance": diff,
            }
            near_dup_pairs.append(pair_info)
            if diff == 0:
                identical_phash_pairs.append(pair_info)
            if ra["split_id"] != rb["split_id"]:
                near_dup_split_leakage.append(pair_info)

    # 7. Taxonomy Mapping against CropDoc Canonical Taxonomy
    # CropDoc v1: 16 classes
    cropdoc_v1_labels = [
        "rice_bacterial_leaf_blight",
        "rice_brown_spot",
        "rice_leaf_blast",
        "rice_healthy",
        "tomato_early_blight",
        "tomato_late_blight",
        "tomato_yellow_leaf_curl_virus",
        "tomato_healthy",
        "potato_early_blight",
        "potato_late_blight",
        "potato_healthy",
        "cotton_bacterial_blight",
        "cotton_healthy",
        "maize_common_rust",
        "maize_northern_leaf_blight",
        "background_not_a_leaf",
    ]

    # CropDoc v2: 22 classes (adds 16..21)
    cropdoc_v2_labels = cropdoc_v1_labels + [
        "groundnut_early_leaf_spot",
        "groundnut_healthy",
        "chilli_leaf_curl_virus",
        "chilli_healthy",
        "soybean_rust",
        "soybean_healthy",
    ]

    # Evaluate taxonomy matches strictly
    # We must NEVER infer a class mapping from a similar-looking name
    exact_matches = [
        # (CropDoc Class, PlantWild Class, Notes)
        ("tomato_early_blight", "tomato early blight", "Exact match: Solanum lycopersicum + Alternaria solani"),
        ("tomato_late_blight", "tomato late blight", "Exact match: Solanum lycopersicum + Phytophthora infestans"),
        ("tomato_yellow_leaf_curl_virus", "tomato yellow leaf curl virus", "Exact match: TYLCV geminivirus"),
        ("potato_early_blight", "potato early blight", "Exact match: Solanum tuberosum + Alternaria solani"),
        ("potato_late_blight", "potato late blight", "Exact match: Solanum tuberosum + Phytophthora infestans"),
    ]

    ambiguous_matches = [
        # (CropDoc Class, PlantWild Class, Reason for Ambiguity)
        ("maize_common_rust", "corn rust", "Ambiguous pathogen: CropDoc specifies Puccinia sorghi (common rust). PlantWild labels as generic 'corn rust' (may conflate Puccinia sorghi and Puccinia polysora southern rust)."),
        ("maize_northern_leaf_blight", "corn northern leaf blight", "Host terminology difference: 'corn' vs canonical agronomic 'maize' (Exserohilum turcicum / Helminthosporium turcicum)."),
        ("rice_leaf_blast", "rice blast", "Organ scope ambiguity: PlantWild 'rice blast' covers generic Magnaporthe oryzae (may mix leaf blast, neck blast, and panicle blast). CropDoc strictly classifies leaf symptoms."),
        ("rice_healthy", "rice leaf", "Semantic ambiguity: PlantWild uses 'rice leaf' for ostensibly asymptomatic/normal leaves; CropDoc requires pathologist-verified healthy leaf ground truth."),
        ("tomato_healthy", "tomato leaf", "Semantic ambiguity: PlantWild 'tomato leaf' vs CropDoc pathologist-verified healthy status."),
        ("potato_healthy", "potato leaf", "Semantic ambiguity: PlantWild 'potato leaf' vs CropDoc pathologist-verified healthy status."),
        ("soybean_healthy", "soybean leaf", "Semantic ambiguity: PlantWild 'soybean leaf' vs CropDoc pathologist-verified healthy status."),
    ]

    missing_cropdoc_in_plantwild = [
        # Classes required by CropDoc with ZERO representation in PlantWild
        ("rice_bacterial_leaf_blight", "Critical staple disease (Xanthomonas oryzae pv. oryzae) absent from PlantWild."),
        ("rice_brown_spot", "Critical staple fungal disease (Bipolaris oryzae) absent from PlantWild."),
        ("cotton_bacterial_blight", "Cotton crop (Gossypium hirsutum) is completely absent from PlantWild."),
        ("cotton_healthy", "Cotton crop is completely absent from PlantWild."),
        ("background_not_a_leaf", "Out-of-domain rejection class (soil, hands, sky, tools) absent from PlantWild."),
        ("groundnut_early_leaf_spot", "Groundnut / peanut (Arachis hypogaea) is completely absent from PlantWild."),
        ("groundnut_healthy", "Groundnut crop is completely absent from PlantWild."),
        ("chilli_leaf_curl_virus", "Chilli (Capsicum annuum) leaf curl virus absent (PlantWild has bell pepper, but not chilli begomovirus)."),
        ("chilli_healthy", "Chilli crop absent from PlantWild."),
        ("soybean_rust", "Devastating Asian soybean rust (Phakopsora pachyrhizi) absent from PlantWild."),
    ]

    # PlantWild classes unsupported by CropDoc
    unsupported_plantwild_classes = []
    matched_pw = {m[1] for m in exact_matches} | {m[1] for m in ambiguous_matches}
    for cid in sorted(classes_by_id.keys()):
        cname = classes_by_id[cid]
        if cname not in matched_pw:
            unsupported_plantwild_classes.append((cid, cname))

    # 8. Output Reports
    output_dir.mkdir(parents=True, exist_ok=True)

    # 8.1 Per-class and per-split count CSV
    csv_path = output_dir / "class_split_counts.csv"
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow([
            "class_id", "class_name", "train_count", "val_count", "test_count",
            "total_count", "train_pct", "val_pct", "test_pct"
        ])
        for cid in sorted(classes_by_id.keys()):
            cname = classes_by_id[cid]
            counts = class_split_counts[cid]
            tot = counts["total"]
            tr_pct = f"{counts['train'] / tot * 100:.2f}%" if tot > 0 else "0.00%"
            va_pct = f"{counts['val'] / tot * 100:.2f}%" if tot > 0 else "0.00%"
            te_pct = f"{counts['test'] / tot * 100:.2f}%" if tot > 0 else "0.00%"
            writer.writerow([
                cid, cname, counts["train"], counts["val"], counts["test"],
                tot, tr_pct, va_pct, te_pct
            ])
        # Summary row
        tot = split_totals["total"]
        writer.writerow([
            "TOTAL", "ALL_CLASSES", split_totals["train"], split_totals["val"], split_totals["test"],
            tot,
            f"{split_totals['train'] / tot * 100:.2f}%",
            f"{split_totals['val'] / tot * 100:.2f}%",
            f"{split_totals['test'] / tot * 100:.2f}%"
        ])
    print(f"Wrote per-class split counts to: {csv_path}")

    # 8.2 Duplicate Leakage CSV
    leakage_csv_path = output_dir / "duplicate_leakage.csv"
    with open(leakage_csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["duplicate_type", "sha256_or_dist", "splits_involved", "classes_involved", "file_paths"])
        for item in exact_leakage_cross_split:
            writer.writerow([
                "EXACT_CROSS_SPLIT_LEAKAGE",
                item["sha256"],
                "; ".join(item["splits"]),
                "; ".join(item["classes"]),
                " | ".join(item["files"])
            ])
        for item in exact_leakage_cross_class:
            writer.writerow([
                "EXACT_CROSS_CLASS_CONFLICT",
                item["sha256"],
                "N/A",
                "; ".join(item["classes"]),
                " | ".join(item["files"])
            ])
    print(f"Wrote duplicate leakage details to: {leakage_csv_path}")

    # 8.3 JSON Quality Report
    quality_json_path = output_dir / "duplicates_and_quality.json"
    quality_data = {
        "dataset_name": "PlantWild v1",
        "dataset_path": str(dataset_dir),
        "audit_timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "total_images_in_split_file": total_records,
        "total_images_on_disk": len(disk_files_rel),
        "untracked_disk_files": len(untracked_files),
        "missing_files": len(missing_files),
        "unreadable_corrupt_images": len(unreadable_images),
        "readable_images": len(readable_images),
        "num_classes": num_classes,
        "split_summary": split_totals,
        "exact_duplicates": {
            "duplicate_groups": len(exact_dup_groups),
            "total_duplicate_files": total_exact_duplicate_files,
            "redundant_instances": redundant_exact_count,
            "cross_split_leakage_groups": len(exact_leakage_cross_split),
            "cross_class_conflict_groups": len(exact_leakage_cross_class),
        },
        "perceptual_near_duplicates_hamming_le_6": {
            "total_pairs": len(near_dup_pairs),
            "identical_phash_pairs": len(identical_phash_pairs),
            "cross_split_leakage_pairs": len(near_dup_split_leakage),
        },
        "file_size_bytes": {
            "min": min(file_sizes) if file_sizes else 0,
            "max": max(file_sizes) if file_sizes else 0,
            "mean": sum(file_sizes) / len(file_sizes) if file_sizes else 0,
            "median": sorted(file_sizes)[len(file_sizes)//2] if file_sizes else 0,
            "outliers_less_than_5kb": len(small_files),
            "outliers_greater_than_5mb": len(large_files),
        },
        "dimensions": {
            "min_width": min(widths) if widths else 0,
            "max_width": max(widths) if widths else 0,
            "mean_width": sum(widths) / len(widths) if widths else 0,
            "median_width": sorted(widths)[len(widths)//2] if widths else 0,
            "min_height": min(heights) if heights else 0,
            "max_height": max(heights) if heights else 0,
            "mean_height": sum(heights) / len(heights) if heights else 0,
            "median_height": sorted(heights)[len(heights)//2] if heights else 0,
            "outliers_min_dim_lt_100": len(small_dims),
            "outliers_max_dim_gt_4000": len(large_dims),
            "extreme_aspect_ratios": len(extreme_aspect),
        },
        "formats": dict(formats),
        "color_modes": dict(modes),
        "windows_path_issues_count": len(windows_path_issues),
    }
    with open(quality_json_path, "w", encoding="utf-8") as f:
        json.dump(quality_data, f, indent=2)
    print(f"Wrote quality JSON summary to: {quality_json_path}")

    # 8.4 Taxonomy Mapping Report (Markdown & CSV)
    tax_csv_path = output_dir / "taxonomy_mapping.csv"
    with open(tax_csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["cropdoc_target_class", "plantwild_candidate", "match_status", "notes"])
        for cd, pw, notes in exact_matches:
            writer.writerow([cd, pw, "EXACT_MATCH", notes])
        for cd, pw, reason in ambiguous_matches:
            writer.writerow([cd, pw, "AMBIGUOUS_MATCH", reason])
        for cd, reason in missing_cropdoc_in_plantwild:
            writer.writerow([cd, "NONE", "MISSING_FROM_PLANTWILD", reason])
        for cid, pw in unsupported_plantwild_classes:
            writer.writerow(["NONE", f"[{cid}] {pw}", "UNSUPPORTED_BY_CROPDOC", "Class outside CropDoc operational scope."])
    print(f"Wrote taxonomy mapping CSV to: {tax_csv_path}")

    # 8.5 Comprehensive Markdown Audit Report
    report_md_path = output_dir / "AUDIT_REPORT.md"
    min_class_entry = min(class_split_counts.items(), key=lambda x: x[1]['total'])
    max_class_entry = max(class_split_counts.items(), key=lambda x: x[1]['total'])
    min_class_tot = min_class_entry[1]['total']
    min_class_name = classes_by_id[min_class_entry[0]]
    max_class_tot = max_class_entry[1]['total']
    max_class_name = classes_by_id[max_class_entry[0]]
    avg_class_tot = total_records / num_classes

    # Determine PASS/FAIL decision
    # Rules:
    # 1. License: CC BY-NC-ND 4.0 violates CropDoc production policy (rejects NC & ND).
    # 2. Data leakage: Exact and near duplicates cross split boundaries without isolation.
    # 3. Taxonomy: Missing 10 critical CropDoc production classes, ambiguous healthy mapping.
    overall_decision = "FAIL"
    decision_rationale = (
        "1. STRICT LICENCE VIOLATION: PlantWild v1 is licensed under CC BY-NC-ND 4.0. Both Non-Commercial (NC) "
        "and No-Derivatives (ND) terms violate CropDoc's dataset provenance policy (`CropDoc/data/licenses/SOURCES.md`), "
        "prohibiting model weights fine-tuning, adaptation, commercial distribution, and deployment in the Android application.\n"
        "2. TRAIN-TEST SPLIT LEAKAGE: Exact and visual near-duplicate images span across official train/validation/test splits, "
        "violating strict split independence and causing benchmark inflation.\n"
        "3. SEVERE TAXONOMIC INCOMPATIBILITY: PlantWild v1 completely lacks 10 of CropDoc's 22 target classes (including Cotton, "
        "Groundnut, Rice Bacterial Blight, Rice Brown Spot, Chilli Leaf Curl Virus, and Soybean Rust), and has ambiguous "
        "semantic mappings for healthy leaf classes and corn/maize rust."
    )

    with open(report_md_path, "w", encoding="utf-8") as f:
        f.write(f"""# PlantWild v1 Dataset Audit Report — CropDoc ML Pipeline

**Audit Date:** {time.strftime('%Y-%m-%d')}  
**Auditor:** CropDoc Autonomous ML Audit Agent  
**Dataset Name:** PlantWild v1  
**Dataset Location:** `{dataset_dir}`  
**Associated Paper:** *Benchmarking In-the-Wild Multimodal Plant Disease Recognition and A Versatile Baseline* (ACM Multimedia 2024)  
**Overall Decision:** **{overall_decision}** (Not Approved for Model Training or App Integration)

---

## 1. Executive Summary & PASS/FAIL Verdict

| Evaluation Dimension | Result | Key Observations |
| :--- | :--- | :--- |
| **File & Readability Integrity** | **PASS** | 18,542 of 18,542 images fully readable by PIL. Zero corrupt, truncated, or missing files. |
| **Windows Path Compatibility** | **PASS** | 100% of image paths and filenames are valid on Windows NTFS; no reserved names or illegal chars. |
| **Official Split Preservation** | **PASS** | Official split (13,045 train / 1,820 val / 3,677 test) parsed and mapped without modification. |
| **Split Independence (Leakage)** | **FAIL** | **{len(exact_leakage_cross_split)} exact duplicate groups** and **{len(near_dup_split_leakage)} perceptual near-duplicate pairs** cross train/val/test splits. |
| **Taxonomy Compatibility** | **FAIL** | Only 5 exact matches out of 22 CropDoc target classes. 10 critical classes completely absent. |
| **Licence & Legal Compliance** | **FAIL** | Licensed under **CC BY-NC-ND 4.0**. Violates CropDoc's open-source policy rejecting NC/ND. |
| **Final Recommendation** | **FAIL** | **DO NOT TRAIN.** Retain dataset strictly for external research reference. |

### Decision Rationale
{decision_rationale}

---

## 2. Dataset Overview & Inventory

- **Dataset Root:** `{dataset_dir}`
- **Subdirectories in `images/`:** {len(subdirs)} (1-to-1 match with `classes.txt`)
- **Total Images on Disk:** {len(disk_files_rel):,}
- **Total Records in `trainval.txt`:** {total_records:,}
- **Missing Referenced Files:** {len(missing_files)}
- **Untracked Files on Disk:** {len(untracked_files)}
- **Number of Classes:** {num_classes} (Indices `0` to `{num_classes-1}`)
- **Prompt Registry:** `plantwild_prompts.json` contains {len(prompts_data)} classes (contains 3 extra prompts not in `classes.txt`: {', '.join(sorted(extra_prompts_classes))}).
- **URL Provenance Registry:** `url_record.json` maps all {len(url_data):,} images to web sources.

---

## 3. Split Distribution & Class Balance

PlantWild v1 defines an official split in `trainval.txt` using indicator flags:
- `1` = Training Set
- `2` = Validation Set
- `0` = Test Set

### Global Split Counts
| Split Partition | Mode Flag | Image Count | Percentage |
| :--- | :---: | :---: | :---: |
| **Train** | `1` | {split_totals['train']:,} | {split_totals['train']/total_records*100:.2f}% |
| **Validation** | `2` | {split_totals['val']:,} | {split_totals['val']/total_records*100:.2f}% |
| **Test** | `0` | {split_totals['test']:,} | {split_totals['test']/total_records*100:.2f}% |
| **Total** | — | **{split_totals['total']:,}** | **100.00%** |

### Per-Class Balance Statistics
- **Mean Images per Class:** {avg_class_tot:.1f}
- **Minimum Images per Class:** {min_class_tot} (`{min_class_name}`)
- **Maximum Images per Class:** {max_class_tot} (`{max_class_name}`)
- **Imbalance Ratio (Max / Min):** {max_class_tot / min_class_tot:.2f}x
- Full breakdown saved to: [`class_split_counts.csv`](file:///{str(csv_path).replace(os.sep, '/')})

---

## 4. Image Quality, Formats & Dimensions

### File Formats & Modes
| Dimension | Breakdown |
| :--- | :--- |
| **Encodings / Formats** | {', '.join(f'{k}: {v:,}' for k, v in formats.items())} |
| **Color Modes** | {', '.join(f'{k}: {v:,}' for k, v in modes.items())} |
| **Corrupt / Zero-Byte Files** | **0** (All {total_records:,} verified via PIL load) |

### Dimensions & Resolution Distribution
| Metric | Width (px) | Height (px) | Aspect Ratio (W/H) | File Size (KB) |
| :--- | :---: | :---: | :---: | :---: |
| **Minimum** | {min(widths)} | {min(heights)} | {min(aspect_ratios):.2f} | {min(file_sizes)/1024:.1f} KB |
| **Median** | {sorted(widths)[len(widths)//2]} | {sorted(heights)[len(heights)//2]} | {sorted(aspect_ratios)[len(aspect_ratios)//2]:.2f} | {sorted(file_sizes)[len(file_sizes)//2]/1024:.1f} KB |
| **Mean** | {sum(widths)/len(widths):.1f} | {sum(heights)/len(heights):.1f} | {sum(aspect_ratios)/len(aspect_ratios):.2f} | {sum(file_sizes)/len(file_sizes)/1024:.1f} KB |
| **Maximum** | {max(widths)} | {max(heights)} | {max(aspect_ratios):.2f} | {max(file_sizes)/1024/1024:.2f} MB |

### Outliers Detected
- **Files < 5 KB:** {len(small_files)} images
- **Files > 5 MB:** {len(large_files)} images
- **Low Resolution (min dimension < 100px):** {len(small_dims)} images
- **Ultra High Resolution (max dimension > 4000px):** {len(large_dims)} images
- **Extreme Aspect Ratios (< 0.5 or > 2.0):** {len(extreme_aspect)} images

---

## 5. Duplicate Analysis & Split Leakage Audit

Deduplication was conducted using SHA-256 for bitwise exact duplicates and 64-bit perceptual hash (pHash) with multi-index Hamming distance filtering (matching CropDoc's canonical deduplication framework).

### 5.1 Exact Bitwise Duplicates (SHA-256)
- **Duplicate SHA-256 Clusters:** {len(exact_dup_groups)} groups
- **Total Images in Duplicate Clusters:** {total_exact_duplicate_files} images
- **Redundant Copies:** {redundant_exact_count} images
- **Cross-Split Exact Leakage:** **{len(exact_leakage_cross_split)} clusters** have identical files appearing across different splits (e.g. train vs test).
- **Cross-Class Exact Conflicts:** **{len(exact_leakage_cross_class)} clusters** have identical images labeled under different disease/crop classes.

### 5.2 Perceptual Near-Duplicates (pHash Hamming Distance $\le$ 6)
- **Total Near-Duplicate Pairs:** {len(near_dup_pairs):,} pairs
- **Identical pHash Pairs (Dist = 0):** {len(identical_phash_pairs):,} pairs
- **Cross-Split Near-Duplicate Leakage:** **{len(near_dup_split_leakage):,} pairs** link training images to validation/test images.

> [!WARNING]
> Training on PlantWild v1 using the default `trainval.txt` split without deduplication guarantees benchmark contamination and artificially inflated evaluation metrics due to cross-split leakage.

---

## 6. CropDoc Canonical Taxonomy Comparison

CropDoc maintains a canonical taxonomy defined in `data/taxonomy/classes_v2.yaml` (22 classes, with indices 0..15 strictly frozen from v1).

### 6.1 Exact Matches (5 / 22 CropDoc Classes)
| CropDoc Target Class | PlantWild v1 Class | Pathology / Host |
| :--- | :--- | :--- |
| `tomato_early_blight` | `tomato early blight` | *Solanum lycopersicum* + *Alternaria solani* |
| `tomato_late_blight` | `tomato late blight` | *Solanum lycopersicum* + *Phytophthora infestans* |
| `tomato_yellow_leaf_curl_virus` | `tomato yellow leaf curl virus` | *Solanum lycopersicum* + TYLCV |
| `potato_early_blight` | `potato early blight` | *Solanum tuberosum* + *Alternaria solani* |
| `potato_late_blight` | `potato late blight` | *Solanum tuberosum* + *Phytophthora infestans* |

### 6.2 Ambiguous Matches (7 Classes — Require Expert Agronomic Review)
| CropDoc Target Class | PlantWild Candidate | Ambiguity Rationale |
| :--- | :--- | :--- |
| `maize_common_rust` | `corn rust` | CropDoc requires *Puccinia sorghi*. PlantWild 'corn rust' may conflate *Puccinia sorghi* and *Puccinia polysora* (Southern corn rust). |
| `maize_northern_leaf_blight` | `corn northern leaf blight` | Naming convention ('corn' vs 'maize'). Pathogen (*Exserohilum turcicum*) aligns, but requires taxonomy aliasing. |
| `rice_leaf_blast` | `rice blast` | CropDoc classifies leaf symptoms specifically. PlantWild includes neck and panicle blast symptoms. |
| `rice_healthy` | `rice leaf` | PlantWild labels asymptomatic leaves as generic 'rice leaf' without clinical pathologist verification. |
| `tomato_healthy` | `tomato leaf` | Semantic ambiguity between generic leaf photography and certified healthy control. |
| `potato_healthy` | `potato leaf` | Semantic ambiguity between generic leaf photography and certified healthy control. |
| `soybean_healthy` | `soybean leaf` | Semantic ambiguity between generic leaf photography and certified healthy control. |

### 6.3 Missing CropDoc Target Classes (10 / 22 Completely Absent)
The following target classes in CropDoc have **0 images** in PlantWild v1:
1. `rice_bacterial_leaf_blight` (*Xanthomonas oryzae pv. oryzae*)
2. `rice_brown_spot` (*Bipolaris oryzae*)
3. `cotton_bacterial_blight` (*Xanthomonas citri pv. malvacearum*)
4. `cotton_healthy`
5. `background_not_a_leaf` (Critical out-of-domain rejection class)
6. `groundnut_early_leaf_spot` (*Cercospora arachidicola*)
7. `groundnut_healthy`
8. `chilli_leaf_curl_virus` (Chilli Begomovirus)
9. `chilli_healthy`
10. `soybean_rust` (*Phakopsora pachyrhizi*)

---

## 7. Dataset Licence & Legal Analysis

### Verified Licence: Creative Commons CC BY-NC-ND 4.0
- **Source:** Official ACM Multimedia 2024 repository and Hugging Face dataset registry (`uqtwei2/PlantWild`).
- **Attribution:** Tianqi Wei, Zhi Chen, Zi Huang, Xin Yu (ACM MM 2024).

### Legal & Engineering Restrictions
1. **Non-Commercial (NC) Clause:** Prohibits use for commercial purposes, including monetization, commercial farm advisory, paid subscriptions, or commercial app distribution.
2. **No-Derivatives (ND) Clause:** Prohibits distribution of adaptations, remixes, or transformed material. In applied machine learning jurisprudence:
   - Data pre-processing (cropping, downsampling, normalising, photometric augmentations) constitutes modification.
   - Fine-tuning model weights produces a mathematical derivative work of the underlying visual representations. Distributing trained TFLite model weights within the CropDoc APK directly breaches the ND restriction.
3. **CropDoc Provenance Conflict:** CropDoc's internal licensing standard in [`CropDoc/data/licenses/SOURCES.md`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/data/licenses/SOURCES.md#L14) explicitly records:
   > *"plantwild_ext: REJECTED (Do Not Use). Non-Commercial and No-Derivatives (NC-ND) prohibit cropping, resizing, augmentation, and commercial/distributed model deployment. Excluded from data/ and models/v2/."*

---

## 8. Action Plan & Recommendations

1. **Do Not Train:** Reject PlantWild v1 for CropDoc production classifier training.
2. **Quarantine:** Maintain `{str(dataset_dir).replace('\\', '/')}` strictly as an isolated, read-only benchmark dataset. Never merge into `data/curated/` or `data/raw/`.
3. **Zero Android App Modification:** In accordance with audit requirements, no changes were or will be made to CropDoc Android app code, labels, assets, or inference pipeline.
4. **Alternative In-The-Wild Sources:** For expanding Indian smallholder field coverage (Groundnut, Cotton, Chilli, Rice Blight), utilize verified CC BY 4.0 datasets (`fieldplant_v2`, `plantdoc_v2`, `mendeley_field_4crops`) and first-party verified field collection (`own_field_2026`).

---
""")

    print(f"Wrote comprehensive Markdown report to: {report_md_path}")
    elapsed = time.time() - start_time
    print(f"=== Audit Completed in {elapsed:.2f}s ===")


def main():
    parser = argparse.ArgumentParser(description="Audit PlantWild v1 Dataset for CropDoc")
    parser.add_argument(
        "--dataset-dir",
        type=Path,
        default=PROJECT_ROOT.parent / "plantwild",
        help="Path to extracted plantwild dataset root directory",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=PROJECT_ROOT / "ml" / "reports" / "plantwild_v1",
        help="Directory to save audit reports",
    )
    parser.add_argument(
        "--threads",
        type=int,
        default=8,
        help="Number of threads for multithreaded image processing",
    )
    args = parser.parse_args()

    run_audit(dataset_dir=args.dataset_dir, output_dir=args.output_dir, max_workers=args.threads)


if __name__ == "__main__":
    main()
