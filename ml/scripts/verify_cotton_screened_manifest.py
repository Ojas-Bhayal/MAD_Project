"""
Verification tool for the AI-screened cotton candidate research manifest.
Validates file presence, byte-level SHA-256 checksums, specimen-group locking,
absence of cross-split leakage, and verdict consistency.
"""
import csv
import hashlib
from pathlib import Path
from collections import defaultdict

def main():
    root = Path(__file__).resolve().parents[2]
    manifest_path = root / "data" / "manifests" / "manifest_cotton_ai_screened.csv"
    audit_csv_path = root / "ml" / "reports" / "mendeley_cotton_2025" / "expert_review_checklist_ai_final.csv"
    
    print(f"Verifying AI-screened candidate manifest: {manifest_path}")
    
    with open(manifest_path, "r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
        
    print(f"Total rows in manifest: {len(rows)}")
    
    missing_files = []
    hash_mismatches = []
    
    for r in rows:
        p = root / r["staged_path"]
        if not p.exists():
            missing_files.append(r["staged_path"])
            continue
        data = p.read_bytes()
        calculated_sha = hashlib.sha256(data).hexdigest()
        if calculated_sha != r["sha256"]:
            hash_mismatches.append((r["staged_path"], r["sha256"], calculated_sha))
            
    print(f"Missing physical files: {len(missing_files)}")
    print(f"SHA-256 hash mismatches: {len(hash_mismatches)}")
    assert len(missing_files) == 0, f"Found {len(missing_files)} missing files!"
    assert len(hash_mismatches) == 0, f"Found {len(hash_mismatches)} hash mismatches!"
    
    # Verify group locking across splits
    group_splits = defaultdict(set)
    for r in rows:
        group_splits[r["group_id"]].add(r["split"])
        
    leaking_groups = {g: sp for g, sp in group_splits.items() if len(sp) > 1}
    print(f"Specimen groups leaking across splits: {len(leaking_groups)}")
    assert len(leaking_groups) == 0, f"Group leakage detected: {leaking_groups}"
    
    # Verify verdict consistency
    non_confirmed = [r for r in rows if r["ai_review_verdict"] != "Label confirmed"]
    print(f"Non-confirmed images in clean manifest: {len(non_confirmed)}")
    assert len(non_confirmed) == 0, "Clean manifest contains unconfirmed images!"
    
    # Class and split breakdown
    summary = defaultdict(lambda: defaultdict(int))
    group_summary = defaultdict(lambda: defaultdict(set))
    for r in rows:
        summary[r["cropdoc_class_name"]][r["split"]] += 1
        group_summary[r["cropdoc_class_name"]][r["split"]].add(r["group_id"])
        
    print("\n--- Verified Clean Research Subset Breakdown ---")
    for cls in sorted(summary.keys()):
        total_imgs = sum(summary[cls].values())
        total_grps = len(set.union(*group_summary[cls].values()))
        print(f"\n{cls} ({total_imgs} images, {total_grps} independent specimen groups):")
        for sp in ["train", "val", "test"]:
            imgs = summary[cls][sp]
            grps = len(group_summary[cls][sp])
            pct = imgs / total_imgs * 100
            print(f"  {sp:5}: {imgs:3} images ({pct:5.1f}%) across {grps:2} specimen groups")

    # Cross-check against audit CSV
    print(f"\nVerifying audit CSV: {audit_csv_path}")
    with open(audit_csv_path, "r", encoding="utf-8") as f:
        audit_rows = list(csv.DictReader(f))
    print(f"Total rows in audit CSV: {len(audit_rows)}")
    assert len(audit_rows) == 359, f"Expected 359 rows, got {len(audit_rows)}"
    
    # Check that pathologist fields are 100% blank
    pathologist_fields = ["pathologist_verdict", "pathologist_confidence", "pathologist_notes", "reviewer_name", "review_timestamp"]
    for fld in pathologist_fields:
        non_empty = [r["staged_filename"] for r in audit_rows if r[fld].strip() != ""]
        assert len(non_empty) == 0, f"Field {fld} is not empty for {len(non_empty)} rows!"
    print("Confirmed: All 5 pathologist_* fields are 100% blank.")
    
    print("\nALL MANIFEST & AUDIT VERIFICATIONS PASSED SUCCESSFULLY.")

if __name__ == "__main__":
    main()
