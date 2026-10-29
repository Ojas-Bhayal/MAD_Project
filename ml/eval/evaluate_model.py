#!/usr/bin/env python3
"""
CropDoc Model Evaluation Harness (ml/eval/evaluate_model.py)

Evaluates on-device LiteRT/TFLite models against curated test datasets.
Computes:
  - Top-1 and Top-3 Accuracy
  - Macro and Per-Class Precision, Recall, and F1-score
  - Expected Calibration Error (ECE) across 10 probability bins
  - Confusion Matrix
  - Inference Latency (mean, p50, p95 in ms)

Emits:
  - JSON artifact (e.g., reports/eval_results.json)
  - Human-readable Markdown summary (e.g., reports/EVAL_SUMMARY.md)

Fails honestly if the model is a truncated placeholder (< 512 KB) or tensor shape mismatch.
"""

import argparse
import json
import math
import os
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np
from PIL import Image, ImageOps

MIN_MODEL_BYTES = 512 * 1024  # 512 KB threshold for real MobileNetV3 models
PREPROCESSING_CONTRACT_PATH = Path("ml/preprocessing_contract.json")


def load_labels(labels_path: Path) -> List[str]:
    with open(labels_path, "r", encoding="utf-8") as f:
        return [line.strip() for line in f if line.strip()]


def compute_ece(confidences: List[float], correct: List[bool], num_bins: int = 10) -> float:
    """Computes Expected Calibration Error across equal-width confidence bins."""
    if not confidences:
        return 0.0

    bins = [[] for _ in range(num_bins)]
    for conf, is_correct in zip(confidences, correct):
        bin_idx = min(int(conf * num_bins), num_bins - 1)
        bins[bin_idx].append((conf, 1.0 if is_correct else 0.0))

    total_samples = len(confidences)
    ece = 0.0

    for b in bins:
        if not b:
            continue
        bin_size = len(b)
        bin_conf = sum(x[0] for x in b) / bin_size
        bin_acc = sum(x[1] for x in b) / bin_size
        ece += (bin_size / total_samples) * abs(bin_acc - bin_conf)

    return ece


def compute_classification_metrics(
    y_true: List[int],
    y_pred: List[int],
    classes: List[str]
) -> Tuple[Dict[str, Dict[str, float]], float, float, float]:
    """Computes per-class and macro Precision, Recall, and F1."""
    num_classes = len(classes)
    tp = [0] * num_classes
    fp = [0] * num_classes
    fn = [0] * num_classes

    for t, p in zip(y_true, y_pred):
        if t == p:
            tp[t] += 1
        else:
            fp[p] += 1
            fn[t] += 1

    per_class = {}
    macro_p = 0.0
    macro_r = 0.0
    macro_f1 = 0.0

    for i, c in enumerate(classes):
        p = tp[i] / (tp[i] + fp[i]) if (tp[i] + fp[i]) > 0 else 0.0
        r = tp[i] / (tp[i] + fn[i]) if (tp[i] + fn[i]) > 0 else 0.0
        f1 = (2 * p * r) / (p + r) if (p + r) > 0 else 0.0

        per_class[c] = {
            "precision": round(p, 4),
            "recall": round(r, 4),
            "f1": round(f1, 4),
            "support": tp[i] + fn[i]
        }
        macro_p += p
        macro_r += r
        macro_f1 += f1

    macro_p /= max(1, num_classes)
    macro_r /= max(1, num_classes)
    macro_f1 /= max(1, num_classes)

    return per_class, round(macro_p, 4), round(macro_r, 4), round(macro_f1, 4)


def evaluate_model(
    model_path: Path,
    dataset_dir: Path,
    labels_path: Path,
    output_json: Path,
    output_md: Path
) -> int:
    labels = load_labels(labels_path)
    print(f"Loaded {len(labels)} classes from {labels_path}")

    # Check file size & integrity
    if not model_path.exists():
        print(f"[ERROR] Model file not found: {model_path}")
        return 1

    model_size = model_path.stat().st_size
    print(f"Model file size: {model_size:,} bytes")

    if model_size < MIN_MODEL_BYTES:
        err_msg = (
            f"MODEL VALIDATION REJECTED: Model '{model_path.name}' is only {model_size:,} bytes "
            f"(< {MIN_MODEL_BYTES:,} bytes threshold). This is a placeholder file and cannot be evaluated."
        )
        print(f"[REJECTED] {err_msg}")
        report_data = {
            "status": "REJECTED_PLACEHOLDER_MODEL",
            "model_path": str(model_path),
            "model_size_bytes": model_size,
            "min_required_bytes": MIN_MODEL_BYTES,
            "error": err_msg
        }
        output_json.parent.mkdir(parents=True, exist_ok=True)
        with open(output_json, "w", encoding="utf-8") as f:
            json.dump(report_data, f, indent=2)

        md_content = f"""# CropDoc Model Evaluation Report

**Model:** `{model_path.name}`  
**Status:** **REJECTED (PLACEHOLDER MODEL)**  
**Size:** {model_size:,} bytes (Threshold: {MIN_MODEL_BYTES:,} bytes)  

> [!CAUTION]
> The model file is below the 512 KB minimum threshold for a functional INT8 MobileNetV3 model.
> Evaluation was aborted to prevent fabricated metrics on uninitialized stubs.
"""
        with open(output_md, "w", encoding="utf-8") as f:
            f.write(md_content)
        return 2

    # Discover test images in class directories
    samples: List[Tuple[Path, int]] = []
    for idx, class_name in enumerate(labels):
        class_dir = dataset_dir / class_name
        if class_dir.is_dir():
            for img_file in class_dir.glob("*.jpg"):
                samples.append((img_file, idx))
            for img_file in class_dir.glob("*.png"):
                samples.append((img_file, idx))

    if not samples:
        print(f"[WARNING] No test images found matching label folders in {dataset_dir}")
        return 3

    print(f"Found {len(samples)} test images across {len(labels)} classes.")

    # Try importing TensorFlow / LiteRT runtime
    interpreter = None
    try:
        from tensorflow.lite.python.interpreter import Interpreter
        interpreter = Interpreter(model_path=str(model_path))
        interpreter.allocate_tensors()
    except Exception as e:
        print(f"[ERROR] Could not load LiteRT interpreter: {e}")
        return 1

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    input_shape = input_details[0]["shape"]
    output_shape = output_details[0]["shape"]
    output_classes = output_shape[-1]

    if output_classes != len(labels):
        err_msg = (
            f"Shape mismatch: model outputs {output_classes} classes, "
            f"but labels file contains {len(labels)} classes."
        )
        print(f"[ERROR] {err_msg}")
        return 4

    latencies_ms: List[float] = []
    y_true: List[int] = []
    y_pred: List[int] = []
    top3_correct = 0
    top1_confidences: List[float] = []
    top1_is_correct: List[bool] = []

    # Verify preprocessing contract
    if PREPROCESSING_CONTRACT_PATH.exists():
        with open(PREPROCESSING_CONTRACT_PATH, "r", encoding="utf-8") as f:
            contract = json.load(f)
            print(f"Loaded Preprocessing Contract: {contract.get('contract_id')} v{contract.get('version')}")

    for img_path, label_idx in samples:
        try:
            with Image.open(img_path) as raw_img:
                # Preprocessing contract parity: EXIF transpose first, then isotropic center square crop
                img = ImageOps.exif_transpose(raw_img)
                w, h = img.size
                span = min(w, h)
                left = (w - span) // 2
                top = (h - span) // 2
                img_cropped = img.crop((left, top, left + span, top + span))
                img_resized = img_cropped.resize(
                    (input_shape[1], input_shape[2]),
                    resample=Image.Resampling.BILINEAR
                )
                img_rgb = img_resized.convert("RGB")
                input_data = np.expand_dims(np.array(img_rgb, dtype=np.uint8), axis=0)

                t0 = time.perf_counter()
                interpreter.set_tensor(input_details[0]["index"], input_data)
                interpreter.invoke()
                output_data = interpreter.get_tensor(output_details[0]["index"])[0]
                t_elapsed = (time.perf_counter() - t0) * 1000.0

                latencies_ms.append(t_elapsed)

                # Softmax if raw logits
                exp_logits = np.exp(output_data.astype(np.float32) - np.max(output_data))
                probs = exp_logits / np.sum(exp_logits)

                pred_idx = int(np.argmax(probs))
                top3_indices = np.argsort(probs)[-3:]

                y_true.append(label_idx)
                y_pred.append(pred_idx)
                is_correct = (pred_idx == label_idx)
                top1_confidences.append(float(probs[pred_idx]))
                top1_is_correct.append(is_correct)

                if label_idx in top3_indices:
                    top3_correct += 1
        except Exception as e:
            print(f"Error processing {img_path}: {e}")

    total = len(y_true)
    top1_acc = sum(top1_is_correct) / total if total > 0 else 0.0
    top3_acc = top3_correct / total if total > 0 else 0.0
    ece = compute_ece(top1_confidences, top1_is_correct)
    per_class, macro_p, macro_r, macro_f1 = compute_classification_metrics(y_true, y_pred, labels)

    p50_lat = float(np.percentile(latencies_ms, 50)) if latencies_ms else 0.0
    p95_lat = float(np.percentile(latencies_ms, 95)) if latencies_ms else 0.0
    mean_lat = float(np.mean(latencies_ms)) if latencies_ms else 0.0

    eval_results = {
        "model": model_path.name,
        "model_size_bytes": model_size,
        "sample_count": total,
        "top1_accuracy": round(top1_acc, 4),
        "top3_accuracy": round(top3_acc, 4),
        "macro_precision": macro_p,
        "macro_recall": macro_r,
        "macro_f1": macro_f1,
        "ece": round(ece, 4),
        "latency_ms": {
            "mean": round(mean_lat, 2),
            "p50": round(p50_lat, 2),
            "p95": round(p95_lat, 2)
        },
        "per_class": per_class
    }

    output_json.parent.mkdir(parents=True, exist_ok=True)
    with open(output_json, "w", encoding="utf-8") as f:
        json.dump(eval_results, f, indent=2)

    md_report = f"""# CropDoc Model Evaluation Report

**Model:** `{model_path.name}`  
**Test Samples:** {total}  
**Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}  

## Summary Metrics

| Metric | Value | Target | Status |
| :--- | :--- | :--- | :--- |
| **Top-1 Accuracy** | {top1_acc * 100:.2f}% | &ge; 85.0% | {'PASS' if top1_acc >= 0.85 else 'FAIL'} |
| **Top-3 Accuracy** | {top3_acc * 100:.2f}% | &ge; 95.0% | {'PASS' if top3_acc >= 0.95 else 'FAIL'} |
| **Macro F1-Score** | {macro_f1:.4f} | &ge; 0.80 | {'PASS' if macro_f1 >= 0.80 else 'FAIL'} |
| **Expected Calibration Error (ECE)** | {ece:.4f} | &le; 0.10 | {'PASS' if ece <= 0.10 else 'FAIL'} |
| **Inference Latency (Mean / P95)** | {mean_lat:.1f} ms / {p95_lat:.1f} ms | &le; 80.0 ms | {'PASS' if p95_lat <= 80 else 'FAIL'} |

## Per-Class Performance

| Class | Precision | Recall | F1-Score | Support |
| :--- | :--- | :--- | :--- | :--- |
"""
    for c, metrics in per_class.items():
        md_report += f"| `{c}` | {metrics['precision']:.3f} | {metrics['recall']:.3f} | {metrics['f1']:.3f} | {metrics['support']} |\n"

    with open(output_md, "w", encoding="utf-8") as f:
        f.write(md_report)

    print(f"[SUCCESS] Wrote evaluation report to {output_json} and {output_md}")
    return 0


def main():
    parser = argparse.ArgumentParser(description="CropDoc LiteRT Model Evaluation Harness")
    parser.add_argument("--model", type=Path, default=Path("app/src/main/assets/models/cropdoc_leaf_v1_int8.tflite"))
    parser.add_argument("--dataset", type=Path, default=Path("data/curated/v2"))
    parser.add_argument("--labels", type=Path, default=Path("app/src/main/assets/models/labels.txt"))
    parser.add_argument("--out-json", type=Path, default=Path("reports/eval_results.json"))
    parser.add_argument("--out-md", type=Path, default=Path("reports/EVAL_SUMMARY.md"))

    args = parser.parse_args()
    sys.exit(evaluate_model(args.model, args.dataset, args.labels, args.out_json, args.out_md))


if __name__ == "__main__":
    main()
