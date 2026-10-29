#!/usr/bin/env python3
"""Appendix A.3: Verify group-aware train/val/test split with zero group leakage."""
import csv
import json
import pathlib

PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = PROJECT_ROOT / "data" / "manifests" / "manifest_v2.csv"
SPLIT_OUT = PROJECT_ROOT / "data" / "splits" / "split_v2.json"


def main():
    with MANIFEST.open("r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    groups_by_split = {"train": set(), "val": set(), "test": set()}
    for r in rows:
        groups_by_split[r["split"]].add(r["group_id"])

    leak_tv = groups_by_split["train"] & groups_by_split["val"]
    leak_tt = groups_by_split["train"] & groups_by_split["test"]
    leak_vt = groups_by_split["val"] & groups_by_split["test"]
    assert not leak_tv and not leak_tt and not leak_vt, "Group leakage detected!"
    print(f"Verified 0 group leakage across {len(rows)} images -> {SPLIT_OUT}")


if __name__ == "__main__":
    main()
