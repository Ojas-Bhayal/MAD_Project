#!/usr/bin/env python3
"""
Near-duplicate perceptual hash clustering & group preservation for CropDoc v2 (#6).
- Pre-parses 64-bit hex pHashes once into integers.
- Uses 8-bit multi-index bucket filtering + int.bit_count() within each class_id.
- Preserves existing plant/field group_id values by merging them into Union-Find clusters.
- Ensures all members of a merged cluster share the same group_id and split (0 split leakage).
- Writes updated manifest_v2.csv atomically with a .bak backup.
"""

import argparse
import csv
import shutil
import tempfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST_CSV = ROOT / "data" / "manifests" / "manifest_v2.csv"


def cluster_manifest_rows(rows: list[dict], max_hamming: int = 6) -> tuple[list[dict], int, int]:
    n = len(rows)
    if n == 0:
        return rows, 0, 0

    hashes = [int(r["phash"], 16) for r in rows]
    parent = list(range(n))

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            if ra < rb:
                parent[rb] = ra
            else:
                parent[ra] = rb

    # 1. Preserve existing group_id links
    existing_group_rep: dict[str, int] = {}
    for idx, row in enumerate(rows):
        gid = (row.get("group_id") or "").strip()
        if gid:
            rep = existing_group_rep.setdefault(gid, idx)
            union(idx, rep)

    # 2. Multi-index 8-bit chunk buckets keyed by (class_name, chunk_idx, chunk_val)
    buckets: dict[tuple[str, int, int], list[int]] = defaultdict(list)
    for idx, h in enumerate(hashes):
        cid = rows[idx].get("class_name") or rows[idx].get("class_id") or ""
        for chunk_idx in range(8):
            chunk_val = (h >> (chunk_idx * 8)) & 0xFF
            buckets[(cid, chunk_idx, chunk_val)].append(idx)

    for idx, h in enumerate(hashes):
        cid = rows[idx].get("class_name") or rows[idx].get("class_id") or ""
        candidates: set[int] = set()
        for chunk_idx in range(8):
            chunk_val = (h >> (chunk_idx * 8)) & 0xFF
            for other in buckets[(cid, chunk_idx, chunk_val)]:
                if other > idx:
                    candidates.add(other)
        for other in candidates:
            if (h ^ hashes[other]).bit_count() <= max_hamming:
                union(idx, other)

    # 3. Assign canonical group_id and align split per cluster so no cluster straddles splits
    root_to_name: dict[int, str] = {}
    root_to_split: dict[int, str] = {}
    cluster_sizes: dict[int, int] = defaultdict(int)
    for idx, row in enumerate(rows):
        root = find(idx)
        cluster_sizes[root] += 1
        existing = (row.get("group_id") or "").strip()
        if existing and root not in root_to_name:
            root_to_name[root] = existing
        if root not in root_to_split and row.get("split"):
            root_to_split[root] = row["split"]

    for idx, row in enumerate(rows):
        root = find(idx)
        row["group_id"] = root_to_name.get(root, f"cluster_{root:05d}")
        if root in root_to_split and "split" in row:
            row["split"] = root_to_split[root]

    largest_cluster = max(cluster_sizes.values(), default=0)
    return rows, len(cluster_sizes), largest_cluster


def atomic_write_manifest(manifest_path: Path, rows: list[dict], fieldnames: list[str]) -> None:
    backup_path = manifest_path.with_suffix(manifest_path.suffix + ".bak")
    if manifest_path.exists():
        shutil.copy2(manifest_path, backup_path)

    with tempfile.NamedTemporaryFile(
        "w", dir=manifest_path.parent, delete=False, newline="", encoding="utf-8"
    ) as tmp:
        tmp_path = Path(tmp.name)
        writer = csv.DictWriter(tmp, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    tmp_path.replace(manifest_path)


def main() -> None:
    parser = argparse.ArgumentParser(description="Cluster near-duplicate images by pHash.")
    parser.add_argument("--manifest", type=Path, default=MANIFEST_CSV)
    parser.add_argument("--threshold", type=int, default=6)
    args = parser.parse_args()

    with open(args.manifest, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        fieldnames = list(reader.fieldnames or [])
        rows = list(reader)

    updated_rows, num_clusters, largest = cluster_manifest_rows(rows, max_hamming=args.threshold)
    atomic_write_manifest(args.manifest, updated_rows, fieldnames)
    backup_file = args.manifest.with_suffix(args.manifest.suffix + ".bak")
    if backup_file.exists():
        backup_file.unlink()
    print(
        f"[OK] Deduplicated {len(updated_rows)} rows into {num_clusters} groups "
        f"(largest_cluster={largest}, threshold={args.threshold})"
    )


if __name__ == "__main__":
    main()
