#!/usr/bin/env python3
"""
CropDoc — Export PyTorch Candidates to LiteRT / TFLite using Google litert-torch

Target Models:
1. Unified Six-Class Disease Classifier (MobileNetV3-Small, 6 outputs)
2. Crop-Domain Gate (MobileNetV3-Small, 3 outputs)

Environment Requirements (Google Official):
- OS: Linux (Ubuntu 20.04+ / Debian) or macOS
- Python: 3.10 or 3.11
- Package: litert-torch (or ai-edge-torch)

Usage:
  python ml/scripts/export_to_litert_tflite.py
"""

import sys
import os
import hashlib
from pathlib import Path
import torch
from torchvision.models import mobilenet_v3_small

ROOT = Path(__file__).resolve().parents[2]
SIX_PTH = ROOT / "ml" / "experiments" / "unified_six_class_pilot" / "best_model.pth"
GATE_PTH = ROOT / "ml" / "experiments" / "crop_domain_gate" / "best_model.pth"
OUT_DIR = ROOT / "ml" / "experiments" / "android_export_pilot"
OUT_DIR.mkdir(parents=True, exist_ok=True)

SIX_TFLITE_OUT = OUT_DIR / "unified_six_class_fp32.tflite"
GATE_TFLITE_OUT = OUT_DIR / "crop_domain_gate_fp32.tflite"

def check_environment():
    print("=== Environment Verification ===")
    print(f"Python: {sys.version.split()[0]} ({sys.platform})")
    print(f"PyTorch: {torch.__version__}")

    try:
        import ai_edge_torch
        print(f"Google ai-edge-torch available: {getattr(ai_edge_torch, '__version__', 'unknown')}")
        return ai_edge_torch, "ai_edge_torch"
    except ImportError:
        pass

    try:
        import litert_torch
        print(f"Google litert-torch available: {getattr(litert_torch, '__version__', 'unknown')}")
        return litert_torch, "litert_torch"
    except ImportError:
        pass

    return None, None


def load_six_class_model():
    print(f"\nLoading Six-Class Model from: {SIX_PTH}")
    sd = torch.load(SIX_PTH, map_location="cpu", weights_only=True)
    model = mobilenet_v3_small(weights=None)
    model.classifier[3] = torch.nn.Linear(model.classifier[3].in_features, 6)
    model.load_state_dict(sd)
    model.eval()
    return model


def load_crop_domain_gate():
    print(f"\nLoading Crop-Domain Gate from: {GATE_PTH}")
    sd = torch.load(GATE_PTH, map_location="cpu", weights_only=True)
    model = mobilenet_v3_small(weights=None)
    model.classifier[3] = torch.nn.Linear(model.classifier[3].in_features, 3)
    model.load_state_dict(sd)
    model.eval()
    return model


def export_model(converter, conv_name, model, sample_input, out_path):
    print(f"Converting model with {conv_name}...")
    edge_model = converter.convert(model, (sample_input,))
    edge_model.export(str(out_path))
    print(f"Successfully exported candidate to: {out_path}")
    size_mb = out_path.stat().st_size / (1024 * 1024)
    with open(out_path, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()
    print(f"  Size: {size_mb:.2f} MB")
    print(f"  SHA-256: {sha}")


def main():
    converter, conv_name = check_environment()
    if converter is None:
        print("\n[BLOCKER ERROR]: Neither 'litert-torch' nor 'ai-edge-torch' is installed.")
        print("Google litert-torch requires Linux (Ubuntu 20.04+ or WSL2) with Python 3.10/3.11.")
        print("Please run this script inside a dedicated Python 3.11 Linux/WSL2 virtual environment.")
        sys.exit(1)

    sample_input = torch.randn(1, 3, 224, 224, dtype=torch.float32)

    # 1. Convert Six-Class Disease Model
    six_model = load_six_class_model()
    export_model(converter, conv_name, six_model, sample_input, SIX_TFLITE_OUT)

    # 2. Convert Crop-Domain Gate
    gate_model = load_crop_domain_gate()
    export_model(converter, conv_name, gate_model, sample_input, GATE_TFLITE_OUT)

    print("\nAll model conversions completed successfully.")

if __name__ == "__main__":
    main()
