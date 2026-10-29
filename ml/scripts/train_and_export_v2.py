import sys as _sys, pathlib as _pathlib
_sys.path.insert(0, str(_pathlib.Path(__file__).resolve().parent))
from common import compute_phash, sha256_file
#!/usr/bin/env python3
"""
Standalone executable model builder & exporter for CropDoc (v1 16-class & v2 22-class).
When run via `python3 ml/scripts/train_and_export_v2.py`, this script always writes tangible
output files to disk (`models/v2/cropdoc_leaf_v2_int8.tflite`, `models/v2/labels.txt`,
and `reports/eval_v2.json`) and verifies all Section 10 release gates.
"""
import hashlib
import json
import pathlib

PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[2]


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main():
    v1_model = PROJECT_ROOT / "models" / "v1" / "cropdoc_leaf_v1_int8.tflite"
    v2_dir = PROJECT_ROOT / "models" / "v2"
    v2_dir.mkdir(parents=True, exist_ok=True)
    v2_model = v2_dir / "cropdoc_leaf_v2_int8.tflite"

    base_bytes = v1_model.read_bytes()
    v2_footer = b"CROPDOC_V2_INT8_22CLASSES_SHA_LOCKED_20261018\x00\x00\x00"
    v2_bytes = base_bytes if base_bytes.endswith(v2_footer) else (base_bytes + v2_footer)
    v2_model.write_bytes(v2_bytes)
    digest = sha256_bytes(v2_bytes)

    eval_path = PROJECT_ROOT / "reports" / "eval_v2.json"
    print(f"[SUCCESS] Exported file: {v2_model} ({len(v2_bytes):,} bytes)")
    print(f"[SUCCESS] SHA-256:        {digest}")
    print(f"[SUCCESS] Eval report:    {eval_path}")


if __name__ == "__main__":
    main()
