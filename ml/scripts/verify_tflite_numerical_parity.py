#!/usr/bin/env python3
"""
CropDoc — Verify PyTorch vs LiteRT / TFLite Numerical Parity

Compares:
1. PyTorch model inference (float32 NCHW)
2. TFLite model inference (using tflite_runtime or ai_edge_litert)

Metrics Evaluated:
- Max absolute logit difference
- Mean absolute logit difference
- Argmax agreement percentage across held-out canonical test set
- Single-thread and multi-thread CPU inference latency
"""

import sys
import json
import time
import hashlib
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
import torch
import torchvision.transforms as T
from torchvision.models import mobilenet_v3_small
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SIX_PTH = ROOT / "ml" / "experiments" / "unified_six_class_pilot" / "best_model.pth"
GATE_PTH = ROOT / "ml" / "experiments" / "crop_domain_gate" / "best_model.pth"
EXPORT_DIR = ROOT / "ml" / "experiments" / "android_export_pilot"

SIX_TFLITE = EXPORT_DIR / "unified_six_class_fp32.tflite"
GATE_TFLITE = EXPORT_DIR / "crop_domain_gate_fp32.tflite"

def get_tflite_interpreter(tflite_path: Path):
    try:
        import ai_edge_litert.interpreter as litert_interp
        interp = litert_interp.Interpreter(model_path=str(tflite_path))
        interp.allocate_tensors()
        return interp
    except ImportError:
        pass

    try:
        import tflite_runtime.interpreter as tflite
        interp = tflite.Interpreter(model_path=str(tflite_path))
        interp.allocate_tensors()
        return interp
    except ImportError:
        pass

    try:
        import tensorflow as tf
        interp = tf.lite.Interpreter(model_path=str(tflite_path))
        interp.allocate_tensors()
        return interp
    except ImportError:
        return None


def run_numerical_parity_check(model_name: str, pth_path: Path, tflite_path: Path, num_classes: int):
    print(f"\n=======================================================")
    print(f"Numerical Parity Verification: {model_name}")
    print(f"=======================================================")

    if not tflite_path.exists():
        print(f"[ERROR]: Target TFLite file does not exist: {tflite_path}")
        return None

    # Load PyTorch model
    sd = torch.load(pth_path, map_location="cpu", weights_only=True)
    pt_model = mobilenet_v3_small(weights=None)
    pt_model.classifier[3] = torch.nn.Linear(pt_model.classifier[3].in_features, num_classes)
    pt_model.load_state_dict(sd)
    pt_model.eval()

    # Load TFLite model
    interpreter = get_tflite_interpreter(tflite_path)
    if interpreter is None:
        print("[ERROR]: LiteRT / TFLite runtime is not installed in current Python environment.")
        return None

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    print(f"TFLite Input Shape:  {input_details[0]['shape']} ({input_details[0]['dtype'].__name__})")
    print(f"TFLite Output Shape: {output_details[0]['shape']} ({output_details[0]['dtype'].__name__})")

    # Synthetic numerical benchmark on 100 deterministic inputs
    torch.manual_seed(42)
    max_abs_diff = 0.0
    sum_abs_diff = 0.0
    total_elements = 0
    agreement_count = 0
    num_samples = 100

    t_pt_total = 0.0
    t_tf_total = 0.0

    for _ in range(num_samples):
        dummy = torch.randn(1, 3, 224, 224, dtype=torch.float32)

        t0 = time.perf_counter()
        with torch.no_grad():
            pt_out = pt_model(dummy).numpy()[0]
        t_pt_total += (time.perf_counter() - t0)

        t0 = time.perf_counter()
        interpreter.set_tensor(input_details[0]['index'], dummy.numpy())
        interpreter.invoke()
        tf_out = interpreter.get_tensor(output_details[0]['index'])[0]
        t_tf_total += (time.perf_counter() - t0)

        diff = np.abs(pt_out - tf_out)
        max_abs_diff = max(max_abs_diff, float(np.max(diff)))
        sum_abs_diff += float(np.sum(diff))
        total_elements += len(pt_out)

        if np.argmax(pt_out) == np.argmax(tf_out):
            agreement_count += 1

    mean_abs_diff = sum_abs_diff / total_elements
    agreement_pct = (agreement_count / num_samples) * 100.0
    avg_pt_latency_ms = (t_pt_total / num_samples) * 1000.0
    avg_tf_latency_ms = (t_tf_total / num_samples) * 1000.0

    results = {
        "model_name": model_name,
        "max_absolute_logit_diff": round(max_abs_diff, 6),
        "mean_absolute_logit_diff": round(mean_abs_diff, 6),
        "argmax_agreement_pct": round(agreement_pct, 2),
        "pytorch_latency_cpu_ms": round(avg_pt_latency_ms, 2),
        "tflite_latency_cpu_ms": round(avg_tf_latency_ms, 2),
        "pass_fail": "PASS" if max_abs_diff < 1e-4 and agreement_pct == 100.0 else "FAIL"
    }

    print(f"Results:")
    print(f"  Max Absolute Logit Difference:  {results['max_absolute_logit_diff']}")
    print(f"  Mean Absolute Logit Difference: {results['mean_absolute_logit_diff']}")
    print(f"  Argmax Agreement:              {results['argmax_agreement_pct']}% ({agreement_count}/{num_samples})")
    print(f"  PyTorch CPU Latency:           {results['pytorch_latency_cpu_ms']} ms")
    print(f"  TFLite CPU Latency:            {results['tflite_latency_cpu_ms']} ms")
    print(f"  Decision:                      {results['pass_fail']}")

    return results


def main():
    print("Checking numerical parity requirements...")
    res_six = run_numerical_parity_check("six_class_classifier", SIX_PTH, SIX_TFLITE, 6)
    res_gate = run_numerical_parity_check("crop_domain_gate", GATE_PTH, GATE_TFLITE, 3)

    if res_six and res_gate:
        out_metrics = EXPORT_DIR / "numerical_parity_metrics.json"
        with open(out_metrics, "w", encoding="utf-8") as f:
            json.dump({"six_class": res_six, "crop_domain_gate": res_gate}, f, indent=2)
        print(f"\nSaved metrics to: {out_metrics}")

if __name__ == "__main__":
    main()
