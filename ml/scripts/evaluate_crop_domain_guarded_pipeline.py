#!/usr/bin/env python3
"""
CropDoc — Evaluate End-to-End Pipeline with Crop-Domain Gate vs Baseline

Compares:
1. Baseline (Raw Image -> Foliar Leaf Gate -> 6-Class Disease Model -> Calibrated Softmax, 0 caller metadata)
2. Crop-Domain Guarded (Raw Image -> Foliar Leaf Gate -> Crop-Domain Gate -> 6-Class Disease Model -> Cross-Consistency Check)

Evaluates on:
- 256 Supported Held-Out Test Specimens (101 Rice, 155 Groundnut)
- 120 Unsupported Crop Test Specimens (40 Cotton, 40 Cassava, 40 Corn)
- 100 Out-of-Training Unseen Crop Specimens (100 Tomato Parent Images)
- 5 Non-Leaf Project Assets
"""

import sys
import json
import csv
import time
from pathlib import Path
from typing import Dict, List, Tuple, Any

import numpy as np
import torch
import torchvision.transforms as T
from torchvision.models import mobilenet_v3_small
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SIX_CLASS_MODEL_PATH = ROOT / "ml" / "experiments" / "unified_six_class_pilot" / "best_model.pth"
DOMAIN_GATE_MODEL_PATH = ROOT / "ml" / "experiments" / "crop_domain_gate" / "best_model.pth"
SIX_CLASS_SPLIT_PATH = ROOT / "data" / "splits" / "split_unified_six_class.json"
SIX_CLASS_MANIFEST_PATH = ROOT / "data" / "manifests" / "manifest_unified_six_class.csv"
DOMAIN_SPLIT_PATH = ROOT / "data" / "splits" / "split_crop_domain_gate.json"
DOMAIN_MANIFEST_PATH = ROOT / "data" / "manifests" / "manifest_crop_domain_gate.csv"
OUT_DIR = ROOT / "ml" / "experiments" / "crop_domain_gate"

SIX_CLASS_NAMES = [
    "rice_bacterial_leaf_blight",
    "rice_brown_spot",
    "rice_leaf_blast",
    "rice_healthy",
    "groundnut_early_leaf_spot",
    "groundnut_healthy"
]

DOMAIN_NAMES = [
    "supported_rice",
    "supported_groundnut",
    "unsupported_crop"
]

# -----------------------------------------------------------------------------
# Image Gates
# -----------------------------------------------------------------------------

def evaluate_image_quality(img: Image.Image) -> Tuple[bool, str]:
    w, h = img.size
    if w < 50 or h < 50:
        return False, "IMAGE_DECODE_FAILED"
    im224 = img.convert("RGB").resize((224, 224))
    arr = np.array(im224, dtype=np.float32)
    mean_lum = float(np.mean(0.299 * arr[:, :, 0] + 0.587 * arr[:, :, 1] + 0.114 * arr[:, :, 2]))
    if mean_lum < 15.0:
        return False, "UNDER_EXPOSED"
    if mean_lum > 245.0:
        return False, "OVER_EXPOSED"
    return True, "ACCEPT"


def evaluate_foliar_leaf_gate(img: Image.Image) -> Tuple[bool, float]:
    im224 = img.convert("RGB").resize((224, 224))
    arr = np.array(im224, dtype=np.float32)
    r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    max_c = np.maximum(r, np.maximum(g, b))
    min_c = np.minimum(r, np.minimum(g, b))
    chroma = max_c - min_c

    leaf_mask = (g > r) & (g > b) & (chroma > 15.0) & (g > 30.0) & (max_c < 250.0)
    chlorotic_mask = (r > 60.0) & (g > 50.0) & (b < 120.0) & (chroma > 12.0) & (np.abs(r - g) < 45.0) & (max_c < 245.0)
    foliar_ratio = float(np.sum(leaf_mask | chlorotic_mask) / (224 * 224))
    return foliar_ratio >= 0.18, foliar_ratio


# -----------------------------------------------------------------------------
# Main Evaluation
# -----------------------------------------------------------------------------

def main():
    device = torch.device("cpu")
    eval_tf = T.Compose([
        T.Resize((224, 224)),
        T.ToTensor(),
        T.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
    ])

    # 1. Load 6-Class Disease Model
    six_model = mobilenet_v3_small(weights=None)
    six_model.classifier[3] = torch.nn.Linear(six_model.classifier[3].in_features, 6)
    six_model.load_state_dict(torch.load(SIX_CLASS_MODEL_PATH, map_location="cpu", weights_only=True))
    six_model.eval()

    # 2. Load Crop-Domain Gate Model
    domain_model = mobilenet_v3_small(weights=None)
    domain_model.classifier[3] = torch.nn.Linear(domain_model.classifier[3].in_features, 3)
    domain_model.load_state_dict(torch.load(DOMAIN_GATE_MODEL_PATH, map_location="cpu", weights_only=True))
    domain_model.eval()

    print("Successfully loaded 6-class disease model and 3-class domain gate.")

    # 3. Load Datasets
    # Supported test items
    with open(SIX_CLASS_SPLIT_PATH, "r", encoding="utf-8") as f:
        six_split = json.load(f)
    test_rel_paths = set(six_split.get("splits", {}).get("test", six_split.get("test", [])))

    supported_items = []
    with open(SIX_CLASS_MANIFEST_PATH, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row["path"] in test_rel_paths:
                cls_name = row.get("cropdoc_class_name") or row.get("cropdoc_class")
                supported_items.append({
                    "path": ROOT / row["path"],
                    "class": cls_name,
                    "crop": "rice" if "rice" in cls_name else "groundnut",
                    "type": "supported"
                })

    # Unsupported in-dist (Cotton, Cassava, Corn from domain split test)
    with open(DOMAIN_SPLIT_PATH, "r", encoding="utf-8") as f:
        dom_split = json.load(f)
    dom_splits = dom_split.get("splits", dom_split)
    dom_test_set = set(dom_splits.get("test", []))
    dom_unseen_set = set(dom_splits.get("unseen_test", []))

    unsupported_indist_items = []
    unseen_tomato_items = []
    with open(DOMAIN_MANIFEST_PATH, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row["path"] in dom_test_set and row.get("domain_name") == "unsupported_crop":
                unsupported_indist_items.append({
                    "path": ROOT / row["path"],
                    "crop": row["source_crop"],
                    "type": "unsupported_indist"
                })
            elif row["path"] in dom_unseen_set:
                unseen_tomato_items.append({
                    "path": ROOT / row["path"],
                    "crop": "tomato",
                    "type": "unseen_tomato"
                })

    non_leaf_items = [
        {"path": ROOT / "data" / "curated" / "v1" / "08_non_leaf_soil_and_tools.jpg", "crop": "non_leaf", "type": "non_leaf"},
        {"path": ROOT / "data" / "curated" / "v1" / "10_low_confidence_dim_blurry.jpg", "crop": "non_leaf", "type": "non_leaf"},
        {"path": ROOT / "data" / "curated" / "v2" / "background_not_a_leaf" / "background_not_a_leaf_train_00.jpg", "crop": "non_leaf", "type": "non_leaf"},
        {"path": ROOT / "data" / "curated" / "v2" / "background_not_a_leaf" / "background_not_a_leaf_val_01.jpg", "crop": "non_leaf", "type": "non_leaf"},
        {"path": ROOT / "data" / "curated" / "v2" / "background_not_a_leaf" / "background_not_a_leaf_test_02.jpg", "crop": "non_leaf", "type": "non_leaf"},
    ]

    print(f"Dataset Counts:")
    print(f"  Supported Held-Out Test:       {len(supported_items)} (Rice: 101, Groundnut: 155)")
    print(f"  Unsupported In-Dist Test:      {len(unsupported_indist_items)} (Cotton: 53, Cassava: 30, Corn: 30)")
    print(f"  Unsupported Unseen Tomato:     {len(unseen_tomato_items)}")
    print(f"  Non-Leaf Assets:               {len(non_leaf_items)}")

    # 4. Evaluation Function for both pipelines
    def run_pipeline(item: Dict, use_domain_gate: bool) -> Dict:
        p = item["path"]
        if not p.exists():
            return {"outcome": "ERROR_FILE_NOT_FOUND", "accepted": False}

        with Image.open(p) as raw_im:
            raw_im = raw_im.convert("RGB")
            # Quality Gate
            q_pass, q_reason = evaluate_image_quality(raw_im)
            if not q_pass:
                return {"outcome": f"REJECTED_QUALITY_{q_reason}", "accepted": False, "stage": "quality"}

            # Foliar Leaf Gate
            leaf_pass, foliar_ratio = evaluate_foliar_leaf_gate(raw_im)
            if not leaf_pass:
                return {"outcome": "REJECTED_NON_LEAF", "accepted": False, "foliar_ratio": foliar_ratio, "stage": "leaf_gate"}

            tensor = eval_tf(raw_im).unsqueeze(0).to(device)

            # Optional Crop-Domain Gate
            domain_pred = None
            domain_probs = None
            if use_domain_gate:
                with torch.no_grad():
                    dom_logits = domain_model(tensor)
                    domain_probs = torch.softmax(dom_logits, dim=1).cpu().numpy()[0]
                    domain_idx = int(np.argmax(domain_probs))
                    domain_pred = DOMAIN_NAMES[domain_idx]

                # Rejection if domain gate says unsupported or uncertain
                if domain_pred == "unsupported_crop":
                    return {
                        "outcome": "REJECTED_UNSUPPORTED_CROP_DOMAIN",
                        "accepted": False,
                        "domain_pred": domain_pred,
                        "domain_probs": domain_probs.tolist(),
                        "stage": "crop_domain_gate"
                    }
                if domain_probs[domain_idx] < 0.70:
                    return {
                        "outcome": "REJECTED_UNCERTAIN_CROP_DOMAIN",
                        "accepted": False,
                        "domain_pred": domain_pred,
                        "domain_probs": domain_probs.tolist(),
                        "stage": "crop_domain_gate"
                    }

            # 6-Class Disease Model Inference
            with torch.no_grad():
                logits = six_model(tensor)
                probs = torch.softmax(logits, dim=1).cpu().numpy()[0]

            top_indices = np.argsort(probs)[::-1]
            top1_idx, top2_idx = int(top_indices[0]), int(top_indices[1])
            top1_p, top2_p = float(probs[top1_idx]), float(probs[top2_idx])
            margin = top1_p - top2_p
            entropy = float(-np.sum(probs * np.log(probs + 1e-12)) / np.log(len(probs)))
            pred_class = SIX_CLASS_NAMES[top1_idx]
            pred_crop = "rice" if top1_idx < 4 else "groundnut"

            # Cross-consistency check with domain gate
            if use_domain_gate:
                expected_crop = "rice" if domain_pred == "supported_rice" else "groundnut"
                if pred_crop != expected_crop:
                    return {
                        "outcome": "REJECTED_DOMAIN_CROSS_MISMATCH",
                        "accepted": False,
                        "domain_pred": domain_pred,
                        "pred_class": pred_class,
                        "pred_crop": pred_crop,
                        "stage": "cross_consistency"
                    }

            # Calibrated Decision Policy Thresholds
            # Calibrated: p >= 0.85, margin >= 0.20, entropy <= 0.45
            if top1_p < 0.85:
                return {
                    "outcome": "REJECTED_LOW_CONFIDENCE",
                    "accepted": False,
                    "confidence": top1_p,
                    "pred_class": pred_class,
                    "stage": "calibration"
                }
            if margin < 0.20:
                return {
                    "outcome": "REJECTED_NARROW_MARGIN",
                    "accepted": False,
                    "margin": margin,
                    "pred_class": pred_class,
                    "stage": "calibration"
                }
            if entropy > 0.45:
                return {
                    "outcome": "REJECTED_HIGH_ENTROPY",
                    "accepted": False,
                    "entropy": entropy,
                    "pred_class": pred_class,
                    "stage": "calibration"
                }

            return {
                "outcome": "ACCEPTED_DIAGNOSIS" if "healthy" not in pred_class else "ACCEPTED_HEALTHY",
                "accepted": True,
                "pred_class": pred_class,
                "pred_crop": pred_crop,
                "confidence": top1_p,
                "margin": margin,
                "entropy": entropy,
                "stage": "passed"
            }

    # Run evaluations
    print("\n--- Running Evaluation: Baseline vs Crop-Domain Guarded ---")
    results = {
        "baseline": {"supported": [], "unsupported_indist": [], "unseen_tomato": [], "non_leaf": []},
        "guarded":  {"supported": [], "unsupported_indist": [], "unseen_tomato": [], "non_leaf": []}
    }

    datasets = [
        ("supported", supported_items),
        ("unsupported_indist", unsupported_indist_items),
        ("unseen_tomato", unseen_tomato_items),
        ("non_leaf", non_leaf_items)
    ]

    for mode in ["baseline", "guarded"]:
        use_gate = (mode == "guarded")
        for group_name, items in datasets:
            for item in items:
                out = run_pipeline(item, use_domain_gate=use_gate)
                res_record = dict(item)
                res_record.pop("path", None)
                res_record.update(out)
                results[mode][group_name].append(res_record)

    # 5. Aggregate Metrics
    summary = {}
    for mode in ["baseline", "guarded"]:
        mode_summary = {}

        # Supported
        supp_total = len(results[mode]["supported"])
        supp_acc_count = sum(1 for r in results[mode]["supported"] if r["accepted"] and r.get("pred_class") == r.get("class"))
        supp_rejected = sum(1 for r in results[mode]["supported"] if not r["accepted"])
        supp_frr = supp_rejected / supp_total if supp_total > 0 else 0.0
        mode_summary["supported"] = {
            "total": supp_total,
            "correct_accepted": supp_acc_count,
            "false_rejections": supp_rejected,
            "false_rejection_rate": round(supp_frr, 4),
            "diagnostic_accuracy": round(supp_acc_count / supp_total, 4)
        }

        # Unsupported in-dist
        unsupp_in_total = len(results[mode]["unsupported_indist"])
        unsupp_in_accepted = sum(1 for r in results[mode]["unsupported_indist"] if r["accepted"])
        unsupp_in_far = unsupp_in_accepted / unsupp_in_total if unsupp_in_total > 0 else 0.0
        mode_summary["unsupported_indist"] = {
            "total": unsupp_in_total,
            "false_accepted": unsupp_in_accepted,
            "false_acceptance_rate": round(unsupp_in_far, 4),
            "per_crop_leaks": {}
        }
        for crop in ["cotton", "cassava", "corn"]:
            crop_items = [r for r in results[mode]["unsupported_indist"] if r.get("crop") == crop]
            crop_leaks = sum(1 for r in crop_items if r["accepted"])
            mode_summary["unsupported_indist"]["per_crop_leaks"][crop] = {
                "total": len(crop_items),
                "leaks": crop_leaks,
                "leak_rate": round(crop_leaks / len(crop_items), 4) if crop_items else 0.0
            }

        # Unseen tomato
        unseen_total = len(results[mode]["unseen_tomato"])
        unseen_accepted = sum(1 for r in results[mode]["unseen_tomato"] if r["accepted"])
        unseen_far = unseen_accepted / unseen_total if unseen_total > 0 else 0.0
        mode_summary["unseen_tomato"] = {
            "total": unseen_total,
            "false_accepted": unseen_accepted,
            "false_acceptance_rate": round(unseen_far, 4)
        }

        # Non-leaf
        nonleaf_total = len(results[mode]["non_leaf"])
        nonleaf_accepted = sum(1 for r in results[mode]["non_leaf"] if r["accepted"])
        mode_summary["non_leaf"] = {
            "total": nonleaf_total,
            "false_accepted": nonleaf_accepted,
            "rejected": nonleaf_total - nonleaf_accepted
        }

        summary[mode] = mode_summary

    # Print Comparison Table
    print("\n" + "=" * 78)
    print("END-TO-END PIPELINE SAFETY EVALUATION COMPARISON")
    print("=" * 78)
    print(f"{'Metric':<40} {'Baseline (No Domain Gate)':<20} {'Guarded (Domain Gate)':<20}")
    print("-" * 78)

    b_supp = summary["baseline"]["supported"]
    g_supp = summary["guarded"]["supported"]
    print(f"{'Supported Canonical Test Accuracy':<40} {b_supp['diagnostic_accuracy']*100:.2f}% ({b_supp['correct_accepted']}/{b_supp['total']}){'':<5} {g_supp['diagnostic_accuracy']*100:.2f}% ({g_supp['correct_accepted']}/{g_supp['total']})")
    print(f"{'Supported False Rejection Rate (FRR)':<40} {b_supp['false_rejection_rate']*100:.2f}% ({b_supp['false_rejections']}/{b_supp['total']}){'':<5} {g_supp['false_rejection_rate']*100:.2f}% ({g_supp['false_rejections']}/{g_supp['total']})")

    b_unsupp = summary["baseline"]["unsupported_indist"]
    g_unsupp = summary["guarded"]["unsupported_indist"]
    print(f"{'In-Dist Unsupported False Accept (FAR)':<40} {b_unsupp['false_acceptance_rate']*100:.2f}% ({b_unsupp['false_accepted']}/{b_unsupp['total']}){'':<5} {g_unsupp['false_acceptance_rate']*100:.2f}% ({g_unsupp['false_accepted']}/{g_unsupp['total']})")

    for crop in ["cotton", "cassava", "corn"]:
        b_c = b_unsupp["per_crop_leaks"][crop]
        g_c = g_unsupp["per_crop_leaks"][crop]
        print(f"  - {crop.capitalize()} Leaks (<1.00):{'':<22} {b_c['leaks']}/{b_c['total']} ({b_c['leak_rate']*100:.1f}%){'':<8} {g_c['leaks']}/{g_c['total']} ({g_c['leak_rate']*100:.1f}%)")

    b_tom = summary["baseline"]["unseen_tomato"]
    g_tom = summary["guarded"]["unseen_tomato"]
    print(f"{'Unseen Tomato False Accept (FAR)':<40} {b_tom['false_acceptance_rate']*100:.2f}% ({b_tom['false_accepted']}/{b_tom['total']}){'':<5} {g_tom['false_acceptance_rate']*100:.2f}% ({g_tom['false_accepted']}/{g_tom['total']})")

    b_nl = summary["baseline"]["non_leaf"]
    g_nl = summary["guarded"]["non_leaf"]
    print(f"{'Non-Leaf Assets False Accept':<40} {b_nl['false_accepted']}/{b_nl['total']}{'':<17} {g_nl['false_accepted']}/{g_nl['total']}")
    print("=" * 78)

    # Save summary
    out_file = OUT_DIR / "pipeline_evaluation_metrics.json"
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
    print(f"\nDetailed pipeline comparison metrics written to: {out_file}")

if __name__ == "__main__":
    main()
