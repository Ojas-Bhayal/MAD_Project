#!/usr/bin/env python3
"""Export CropDoc's cotton binary pilot checkpoint to a DEBUG-only LiteRT/TFLite model.

This converts the existing MobileNetV3-Small checkpoint; it does not train a model.
The resulting model is research-only because its source labels have not had certified
plant-pathologist review. Never copy it into app/src/main/assets/models/ or use it to
replace the production model.

Recommended environment: Linux / Google Colab with Python >= 3.10 and litert-torch.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np
import torch
from torch import nn
from torchvision import models

EXPECTED_CHECKPOINT_SHA256 = "cc1fbcebaa3a80659da42905bcbdf548df4fd81815701de82ff4a9006077b33c"
EXPECTED_CLASSES = ["cotton_bacterial_blight", "cotton_healthy"]
INPUT_SHAPE = [1, 3, 224, 224]
OUTPUT_SHAPE = [1, 2]
IMAGENET_MEAN = [0.485, 0.456, 0.406]
IMAGENET_STD = [0.229, 0.224, 0.225]
MAX_ABS_PARITY_ERROR = 0.05


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def to_numpy(value: Any) -> np.ndarray:
    if isinstance(value, (tuple, list)):
        if len(value) != 1:
            raise RuntimeError(f"Expected one model output, got {len(value)} outputs")
        value = value[0]
    if isinstance(value, torch.Tensor):
        value = value.detach().cpu().numpy()
    return np.asarray(value)


def load_pilot(checkpoint_path: Path, metadata_path: Path) -> tuple[nn.Module, dict[str, Any]]:
    if not checkpoint_path.is_file():
        raise FileNotFoundError(f"Checkpoint not found: {checkpoint_path}")
    if not metadata_path.is_file():
        raise FileNotFoundError(f"Metadata not found: {metadata_path}")

    actual_sha = sha256(checkpoint_path)
    if actual_sha != EXPECTED_CHECKPOINT_SHA256:
        raise RuntimeError(
            "Checkpoint SHA-256 differs from the reviewed upload. "
            f"Expected {EXPECTED_CHECKPOINT_SHA256}, got {actual_sha}. Refusing export."
        )

    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if metadata.get("experiment") != "cotton_binary_pilot":
        raise RuntimeError("Metadata is not for cotton_binary_pilot")
    if metadata.get("production_approval") != "BLOCKED":
        raise RuntimeError("Expected metadata to retain production_approval=BLOCKED")
    if metadata.get("diagnostic_status") != "UNVALIDATED_PILOT (UNREVIEWED_SOURCE_LABELS)":
        raise RuntimeError("Unexpected diagnostic status; review metadata before exporting")
    if metadata.get("checkpoint_sha256") != actual_sha:
        raise RuntimeError("Checkpoint digest does not match the supplied metadata")
    if metadata.get("classes") != EXPECTED_CLASSES:
        raise RuntimeError(f"Unexpected class order in metadata: {metadata.get('classes')}")
    if metadata.get("input_tensor_shape") != INPUT_SHAPE:
        raise RuntimeError(f"Unexpected input shape in metadata: {metadata.get('input_tensor_shape')}")

    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    if checkpoint.get("architecture") != "mobilenet_v3_small":
        raise RuntimeError(f"Unexpected checkpoint architecture: {checkpoint.get('architecture')}")
    if checkpoint.get("class_map") != {EXPECTED_CLASSES[0]: 0, EXPECTED_CLASSES[1]: 1}:
        raise RuntimeError(f"Unexpected checkpoint class map: {checkpoint.get('class_map')}")

    model = models.mobilenet_v3_small(weights=None)
    model.classifier[3] = nn.Linear(model.classifier[3].in_features, len(EXPECTED_CLASSES))
    model.load_state_dict(checkpoint["model_state_dict"], strict=True)
    model.eval()
    with torch.inference_mode():
        output = model(torch.zeros(INPUT_SHAPE, dtype=torch.float32))
    if list(output.shape) != OUTPUT_SHAPE:
        raise RuntimeError(f"PyTorch output shape is {list(output.shape)}, expected {OUTPUT_SHAPE}")
    return model, metadata


def export(checkpoint_path: Path, metadata_path: Path, output_path: Path) -> None:
    model, metadata = load_pilot(checkpoint_path, metadata_path)
    try:
        import litert_torch  # provided by the `litert-torch` package
    except ImportError as exc:
        raise RuntimeError(
            "Missing `litert-torch`. Run this script in Linux/Google Colab after "
            "installing it with `pip install litert-torch torchvision`."
        ) from exc

    output_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path = output_path.with_name("cotton_binary_fp32_manifest.json")
    if output_path.exists():
        output_path.unlink()
    if manifest_path.exists():
        manifest_path.unlink()

    # Verify converter/PyTorch numerical parity on multiple synthetic normalized inputs.
    sample_inputs = (torch.zeros(INPUT_SHAPE, dtype=torch.float32),)
    edge_model = litert_torch.convert(model, sample_inputs)
    rng = np.random.default_rng(1234)
    max_abs_error = 0.0
    top1_matches = 0
    parity_cases = 0
    with torch.inference_mode():
        for index in range(8):
            arr = (rng.standard_normal(INPUT_SHAPE).astype(np.float32) * 0.75)
            sample = torch.from_numpy(arr)
            torch_out = model(sample).detach().cpu().numpy()
            lite_out = to_numpy(edge_model(sample))
            if list(lite_out.shape) != OUTPUT_SHAPE:
                raise RuntimeError(f"Converted model output shape {list(lite_out.shape)} != {OUTPUT_SHAPE}")
            error = float(np.max(np.abs(torch_out - lite_out)))
            max_abs_error = max(max_abs_error, error)
            top1_matches += int(np.argmax(torch_out, axis=1)[0] == np.argmax(lite_out, axis=1)[0])
            parity_cases += 1
    if max_abs_error > MAX_ABS_PARITY_ERROR:
        raise RuntimeError(
            f"Conversion parity failed: max absolute logit error {max_abs_error:.6f} "
            f"> {MAX_ABS_PARITY_ERROR}. No model was exported."
        )

    edge_model.export(str(output_path))
    with output_path.open("rb") as stream:
        header = stream.read(8)
    if len(header) < 8 or header[4:8] != b"TFL3":
        output_path.unlink(missing_ok=True)
        raise RuntimeError("Exported file does not have a valid TFLite TFL3 header")

    manifest = {
        "model_id": "cotton_binary_fp32",
        "model_version": "0.1.0-research",
        "research_only": True,
        "production_approval": "BLOCKED",
        "diagnostic_status": "UNVALIDATED_PILOT (UNREVIEWED_SOURCE_LABELS)",
        "checkpoint_sha256": sha256(checkpoint_path),
        "sha256": sha256(output_path),
        "input_shape": INPUT_SHAPE,
        "input_dtype": "float32",
        "output_shape": OUTPUT_SHAPE,
        "output_dtype": "float32",
        "class_order": EXPECTED_CLASSES,
        "input_layout": "NCHW",
        "normalization": {"mean": IMAGENET_MEAN, "std": IMAGENET_STD},
        "conversion_check": {
            "max_abs_logit_error": max_abs_error,
            "tolerance": MAX_ABS_PARITY_ERROR,
            "synthetic_parity_cases": parity_cases,
            "synthetic_top1_matches": top1_matches,
            "synthetic_top1_match_is_not_accuracy": True,
        },
        "source_metadata_status": metadata.get("diagnostic_status"),
        "warning": (
            "Research-only cotton binary pilot. Source labels have not been certified by a plant pathologist. "
            "Not approved for production diagnosis, advice, or release."
        ),
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print("EXPORT SUCCESS")
    print(f"Model: {output_path}")
    print(f"Bytes: {output_path.stat().st_size}")
    print(f"SHA-256: {manifest['sha256']}")
    print(f"Max absolute logit difference: {max_abs_error:.8f}")
    print(f"Synthetic parity top-1 matches: {top1_matches}/{parity_cases} (not model accuracy)")
    print(f"Manifest: {manifest_path}")
    print("Production approval remains BLOCKED.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True, help="Path to cotton best_model.pt")
    parser.add_argument("--metadata", type=Path, required=True, help="Path to cotton model_metadata.json")
    parser.add_argument("--output", type=Path, required=True, help="Output TFLite path inside app/src/debug/assets/research_models/")
    parser.add_argument("--check-only", action="store_true", help="Verify checkpoint/metadata/architecture without converting")
    args = parser.parse_args()
    try:
        model, _ = load_pilot(args.checkpoint, args.metadata)
        print(f"Checkpoint and architecture verified; logits shape: {list(model(torch.zeros(INPUT_SHAPE)).shape)}")
        if not args.check_only:
            export(args.checkpoint, args.metadata, args.output)
        return 0
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
