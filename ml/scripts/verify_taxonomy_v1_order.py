#!/usr/bin/env python3
"""Appendix A.4: CI check ensuring v1 class indices 0..N-1 are 100% unchanged in classes_v2.yaml."""
import pathlib
import re

PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[2]
V1_LABELS = PROJECT_ROOT / "models" / "v1" / "labels.txt"
V2_TAXONOMY = PROJECT_ROOT / "data" / "taxonomy" / "classes_v2.yaml"


def main():
    v1 = [line.strip() for line in V1_LABELS.read_text(encoding="utf-8").splitlines() if line.strip()]
    v2_by_index = {}
    pattern = re.compile(r'index:\s*(\d+).*?class_name:\s*"([^"]+)"')
    for line in V2_TAXONOMY.read_text(encoding="utf-8").splitlines():
        m = pattern.search(line)
        if m:
            v2_by_index[int(m.group(1))] = m.group(2)

    for i, name in enumerate(v1):
        assert i in v2_by_index, f"v1 class index {i} missing from v2 taxonomy"
        assert v2_by_index[i] == name, f"Index {i} mismatch: v1={name} vs v2={v2_by_index[i]}"

    print(f"[PASS] v1 class indices 0..{len(v1)-1} preserved identically in classes_v2.yaml (total v2 classes={len(v2_by_index)}).")


if __name__ == "__main__":
    main()
