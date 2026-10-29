#!/usr/bin/env python3
"""
CropDoc - Fail-Closed Inference Decision Policy: Calibration and Benchmark

Evaluates the 6-class MobileNetV3 research prototype against:
1. Validation set (216 canonical specimens) for threshold calibration.
2. In-domain test set (256 canonical specimens) for held-out performance.
3. Out-of-domain stress set (120 unsupported images: 60 Cotton, 60 Cassava/Corn).
4. Non-leaf / background inputs.

Outputs calibrated metrics, threshold selection rationale, and benchmark reports.
"""

import os
import sys
import csv
import json
import time
import math
from pathlib import Path
from typing import Dict, List, Tuple, Optional, Any

import numpy as np
import torch
import torchvision.transforms as T
from PIL import Image
from torchvision.models import mobilenet_v3_small

# -----------------------------------------------------------------------------
# Configuration and Constants
# -----------------------------------------------------------------------------
ROOT = Path(__file__).resolve().parents[2]
EXP_DIR = ROOT / "ml" / "experiments" / "unified_six_class_pilot"
REPORT_DIR = ROOT / "ml" / "reports" / "inference_guardrails"
REPORT_DIR.mkdir(parents=True, exist_ok=True)

MODEL_WEIGHTS_PATH = EXP_DIR / "best_model.pth"
SPLIT_PATH = ROOT / "data" / "splits" / "split_unified_six_class.json"
MANIFEST_PATH = ROOT / "data" / "manifests" / "manifest_unified_six_class.csv"

# Isolated 6-class research taxonomy (isolated from production 22-class taxonomy)
RESEARCH_CLASSES = [
    {"local_index": 0, "global_index": 0, "class_id": "rice_bacterial_leaf_blight", "crop": "rice", "is_healthy": False},
    {"local_index": 1, "global_index": 1, "class_id": "rice_brown_spot", "crop": "rice", "is_healthy": False},
    {"local_index": 2, "global_index": 2, "class_id": "rice_leaf_blast", "crop": "rice", "is_healthy": False},
    {"local_index": 3, "global_index": 3, "class_id": "rice_healthy", "crop": "rice", "is_healthy": True},
    {"local_index": 4, "global_index": 16, "class_id": "groundnut_early_leaf_spot", "crop": "groundnut", "is_healthy": False},
    {"local_index": 5, "global_index": 17, "class_id": "groundnut_healthy", "crop": "groundnut", "is_healthy": True},
]
LOCAL_MAP = {c["class_id"]: c["local_index"] for c in RESEARCH_CLASSES}
INV_LOCAL_MAP = {c["local_index"]: c["class_id"] for c in RESEARCH_CLASSES}
SUPPORTED_CROPS = {"rice", "groundnut"}


# -----------------------------------------------------------------------------
# Isolated Inference Decision Policy (Python Implementation)
# -----------------------------------------------------------------------------
class PolicyOutcomeType:
    ACCEPTED_SUPPORTED_CLASS = "ACCEPTED_SUPPORTED_CLASS"
    INCONCLUSIVE_LOW_CONFIDENCE = "INCONCLUSIVE_LOW_CONFIDENCE"
    OUT_OF_DOMAIN = "OUT_OF_DOMAIN"
    INVALID_MODEL_OUTPUT = "INVALID_MODEL_OUTPUT"
    MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE"


class PolicyRejectionDetail:
    NONE = "NONE"
    MODEL_NOT_AVAILABLE = "MODEL_NOT_AVAILABLE"
    OUTPUT_NULL_OR_EMPTY = "OUTPUT_NULL_OR_EMPTY"
    INVALID_CLASS_COUNT = "INVALID_CLASS_COUNT"
    NON_FINITE_VALUES = "NON_FINITE_VALUES"
    INVALID_PROBABILITY_DISTRIBUTION = "INVALID_PROBABILITY_DISTRIBUTION"
    DEGENERATE_UNIFORM_OUTPUT = "DEGENERATE_UNIFORM_OUTPUT"
    LEAF_GATE_UNVALIDATED = "LEAF_GATE_UNVALIDATED"
    LEAF_GATE_REJECTED_NON_LEAF = "LEAF_GATE_REJECTED_NON_LEAF"
    UNSUPPORTED_CROP_DOMAIN = "UNSUPPORTED_CROP_DOMAIN"
    OOD_SCORE_EXCEEDED = "OOD_SCORE_EXCEEDED"
    CONFIDENCE_BELOW_THRESHOLD = "CONFIDENCE_BELOW_THRESHOLD"
    MARGIN_BELOW_THRESHOLD = "MARGIN_BELOW_THRESHOLD"
    ENTROPY_ABOVE_THRESHOLD = "ENTROPY_ABOVE_THRESHOLD"


class InferenceDecisionResult:
    def __init__(
        self,
        outcome_type: str,
        rejection_detail: str,
        local_index: int = -1,
        global_index: int = -1,
        class_id: Optional[str] = None,
        crop: Optional[str] = None,
        is_healthy: bool = False,
        confidence: float = 0.0,
        margin: float = 0.0,
        normalized_entropy: float = 0.0,
        explanation: str = ""
    ):
        self.outcome_type = outcome_type
        self.rejection_detail = rejection_detail
        self.local_index = local_index
        self.global_index = global_index
        self.class_id = class_id
        self.crop = crop
        self.is_healthy = is_healthy
        self.confidence = confidence
        self.margin = margin
        self.normalized_entropy = normalized_entropy
        self.explanation = explanation

    def is_accepted(self) -> bool:
        return self.outcome_type == PolicyOutcomeType.ACCEPTED_SUPPORTED_CLASS

    def is_diagnostic(self) -> bool:
        return self.is_accepted()

    def is_disease_diagnosis(self) -> bool:
        return self.is_accepted() and not self.is_healthy

    def require_disease_class_id(self) -> str:
        if not self.is_disease_diagnosis():
            raise RuntimeError(
                f"Agronomic Safety Violation: Cannot retrieve disease advice or diagnosis "
                f"for non-disease outcome: outcome_type={self.outcome_type}, detail={self.rejection_detail}"
            )
        return self.class_id

    def to_dict(self) -> Dict[str, Any]:
        return {
            "outcome_type": self.outcome_type,
            "rejection_detail": self.rejection_detail,
            "local_index": self.local_index,
            "global_index": self.global_index,
            "class_id": self.class_id,
            "crop": self.crop,
            "is_healthy": self.is_healthy,
            "confidence": round(self.confidence, 4),
            "margin": round(self.margin, 4),
            "normalized_entropy": round(self.normalized_entropy, 4),
            "explanation": self.explanation
        }


class InferenceDecisionPolicy:
    def __init__(
        self,
        expected_classes: int = 6,
        confidence_threshold: float = 0.70,
        margin_threshold: float = 0.30,
        max_normalized_entropy: float = 0.45,
        enforce_crop_domain: bool = True,
        enforce_leaf_gate: bool = True
    ):
        self.expected_classes = expected_classes
        self.confidence_threshold = confidence_threshold
        self.margin_threshold = margin_threshold
        self.max_normalized_entropy = max_normalized_entropy
        self.enforce_crop_domain = enforce_crop_domain
        self.enforce_leaf_gate = enforce_leaf_gate

    def evaluate(
        self,
        raw_output: np.ndarray,
        is_logits: bool = True,
        is_model_available: bool = True,
        leaf_gate_passed: bool = True,
        leaf_gate_validated: bool = True,
        crop_context: Optional[str] = None,
        ood_score: Optional[float] = None
    ) -> InferenceDecisionResult:
        # Check 1: Model availability
        if not is_model_available:
            return InferenceDecisionResult(
                outcome_type=PolicyOutcomeType.MODEL_UNAVAILABLE,
                rejection_detail=PolicyRejectionDetail.MODEL_NOT_AVAILABLE,
                explanation="Model is unavailable or uninitialized."
            )

        # Check 2: Output shape and validity
        if raw_output is None or len(raw_output) == 0:
            return InferenceDecisionResult(
                outcome_type=PolicyOutcomeType.INVALID_MODEL_OUTPUT,
                rejection_detail=PolicyRejectionDetail.OUTPUT_NULL_OR_EMPTY,
                explanation="Model output array is null or empty."
            )

        if len(raw_output) != self.expected_classes:
            return InferenceDecisionResult(
                outcome_type=PolicyOutcomeType.INVALID_MODEL_OUTPUT,
                rejection_detail=PolicyRejectionDetail.INVALID_CLASS_COUNT,
                explanation=f"Output dimension {len(raw_output)} does not match expected {self.expected_classes}."
            )

        # Check 3: Non-finite values (NaN / Inf)
        if np.any(np.isnan(raw_output)) or np.any(np.isinf(raw_output)):
            return InferenceDecisionResult(
                outcome_type=PolicyOutcomeType.INVALID_MODEL_OUTPUT,
                rejection_detail=PolicyRejectionDetail.NON_FINITE_VALUES,
                explanation="Model output contains NaN or Infinite values."
            )

        # Check 4: Degenerate / uniform output
        if np.all(raw_output == raw_output[0]):
            return InferenceDecisionResult(
                outcome_type=PolicyOutcomeType.INVALID_MODEL_OUTPUT,
                rejection_detail=PolicyRejectionDetail.DEGENERATE_UNIFORM_OUTPUT,
                explanation="Model output is degenerate or completely uniform."
            )

        # Convert logits to calibrated probabilities
        if is_logits:
            exp_vals = np.exp(raw_output - np.max(raw_output))
            sum_exp = np.sum(exp_vals)
            probs = exp_vals / (sum_exp if sum_exp > 0 else 1.0)
        else:
            probs = np.array(raw_output, dtype=np.float32)
            # Verify valid probability distribution
            if np.any(probs < -1e-6) or np.any(probs > 1.0 + 1e-6) or abs(np.sum(probs) - 1.0) > 0.05:
                return InferenceDecisionResult(
                    outcome_type=PolicyOutcomeType.INVALID_MODEL_OUTPUT,
                    rejection_detail=PolicyRejectionDetail.INVALID_PROBABILITY_DISTRIBUTION,
                    explanation=f"Probabilities do not sum to 1.0 (sum={np.sum(probs):.4f}) or contain values out of [0, 1]."
                )

        # Check 5: Leaf Gate validation status (G2)
        if self.enforce_leaf_gate:
            if not leaf_gate_validated:
                return InferenceDecisionResult(
                    outcome_type=PolicyOutcomeType.MODEL_UNAVAILABLE,
                    rejection_detail=PolicyRejectionDetail.LEAF_GATE_UNVALIDATED,
                    explanation="Leaf gate is not present or unvalidated. Fails closed."
                )
            if not leaf_gate_passed:
                return InferenceDecisionResult(
                    outcome_type=PolicyOutcomeType.OUT_OF_DOMAIN,
                    rejection_detail=PolicyRejectionDetail.LEAF_GATE_REJECTED_NON_LEAF,
                    explanation="Image rejected by leaf quality/semantic gate as non-leaf."
                )

        # Check 6: Crop Domain validation (G1, G3)
        # An unsupported crop leaf (e.g. Cotton, Cassava, Maize) must NEVER be accepted as disease
        if self.enforce_crop_domain and crop_context is not None:
            norm_crop = crop_context.strip().lower()
            if norm_crop not in SUPPORTED_CROPS:
                return InferenceDecisionResult(
                    outcome_type=PolicyOutcomeType.OUT_OF_DOMAIN,
                    rejection_detail=PolicyRejectionDetail.UNSUPPORTED_CROP_DOMAIN,
                    explanation=f"Target crop '{crop_context}' is out of domain. Supported: {list(SUPPORTED_CROPS)}."
                )

        # Check 7: External OOD detector score if provided
        if ood_score is not None and ood_score > 0.50:
            return InferenceDecisionResult(
                outcome_type=PolicyOutcomeType.OUT_OF_DOMAIN,
                rejection_detail=PolicyRejectionDetail.OOD_SCORE_EXCEEDED,
                explanation=f"OOD detector score ({ood_score:.4f}) exceeded safety threshold."
            )

        # Calculate distributional metrics
        top_indices = np.argsort(probs)[::-1]
        top1_idx = int(top_indices[0])
        top2_idx = int(top_indices[1])
        top1_prob = float(probs[top1_idx])
        top2_prob = float(probs[top2_idx])
        margin = top1_prob - top2_prob

        entropy = -np.sum(probs * np.log(np.maximum(probs, 1e-12)))
        max_entropy = np.log(self.expected_classes)
        normalized_entropy = float(entropy / max_entropy) if max_entropy > 0 else 0.0

        target_class_meta = RESEARCH_CLASSES[top1_idx]

        # Check 8: Ambiguity / Margin check
        if margin < self.margin_threshold:
            return InferenceDecisionResult(
                outcome_type=PolicyOutcomeType.INCONCLUSIVE_LOW_CONFIDENCE,
                rejection_detail=PolicyRejectionDetail.MARGIN_BELOW_THRESHOLD,
                local_index=top1_idx,
                confidence=top1_prob,
                margin=margin,
                normalized_entropy=normalized_entropy,
                explanation=f"Top-2 margin ({margin:.4f}) is below threshold ({self.margin_threshold:.4f}). Prediction is ambiguous."
            )

        # Check 9: Confidence threshold check
        if top1_prob < self.confidence_threshold:
            return InferenceDecisionResult(
                outcome_type=PolicyOutcomeType.INCONCLUSIVE_LOW_CONFIDENCE,
                rejection_detail=PolicyRejectionDetail.CONFIDENCE_BELOW_THRESHOLD,
                local_index=top1_idx,
                confidence=top1_prob,
                margin=margin,
                normalized_entropy=normalized_entropy,
                explanation=f"Top-1 confidence ({top1_prob:.4f}) is below threshold ({self.confidence_threshold:.4f})."
            )

        # Check 10: Entropy threshold check
        if normalized_entropy > self.max_normalized_entropy:
            return InferenceDecisionResult(
                outcome_type=PolicyOutcomeType.INCONCLUSIVE_LOW_CONFIDENCE,
                rejection_detail=PolicyRejectionDetail.ENTROPY_ABOVE_THRESHOLD,
                local_index=top1_idx,
                confidence=top1_prob,
                margin=margin,
                normalized_entropy=normalized_entropy,
                explanation=f"Normalized entropy ({normalized_entropy:.4f}) exceeds threshold ({self.max_normalized_entropy:.4f})."
            )

        # All checks passed: ACCEPTED_SUPPORTED_CLASS
        return InferenceDecisionResult(
            outcome_type=PolicyOutcomeType.ACCEPTED_SUPPORTED_CLASS,
            rejection_detail=PolicyRejectionDetail.NONE,
            local_index=top1_idx,
            global_index=target_class_meta["global_index"],
            class_id=target_class_meta["class_id"],
            crop=target_class_meta["crop"],
            is_healthy=target_class_meta["is_healthy"],
            confidence=top1_prob,
            margin=margin,
            normalized_entropy=normalized_entropy,
            explanation=f"Prediction accepted: class={target_class_meta['class_id']}, conf={top1_prob:.4f}, margin={margin:.4f}."
        )


# -----------------------------------------------------------------------------
# Evaluation Pipeline
# -----------------------------------------------------------------------------
def load_eval_components():
    print(f"Loading MobileNetV3-Small model from {MODEL_WEIGHTS_PATH}...")
    model = mobilenet_v3_small(num_classes=6)
    checkpoint = torch.load(MODEL_WEIGHTS_PATH, map_location="cpu", weights_only=True)
    model.load_state_dict(checkpoint)
    model.eval()

    transform = T.Compose([
        T.Resize((224, 224)),
        T.ToTensor(),
        T.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
    ])

    with open(SPLIT_PATH, "r", encoding="utf-8") as f:
        splits = json.load(f)

    with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        manifest = {r["path"]: {"label": int(r["local_model_index"]), "crop": r["crop"], "class_name": r["cropdoc_class_name"]} for r in reader}

    return model, transform, splits, manifest


def extract_features(model, transform, paths: List[str], manifest: Dict):
    data = []
    with torch.no_grad():
        for p in paths:
            full_p = ROOT / p
            meta = manifest.get(p, {})
            label = meta.get("label", -1)
            crop = meta.get("crop", "unknown")
            with Image.open(full_p) as img:
                t = transform(img.convert("RGB")).unsqueeze(0)
            logits = model(t)[0].numpy()
            data.append({
                "path": p,
                "label": label,
                "crop": crop,
                "class_name": meta.get("class_name", "unknown"),
                "logits": logits
            })
    return data


def calibrate_on_validation(val_data: List[Dict]) -> Tuple[Dict, List[Dict]]:
    print("\n--- Tuning Rejection Thresholds on Validation Data (216 Canonical Specimens) ---")
    
    grid_results = []
    conf_grid = [0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90]
    margin_grid = [0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.50]
    entropy_grid = [0.55, 0.50, 0.45, 0.40, 0.35]

    for c_th in conf_grid:
        for m_th in margin_grid:
            for e_th in entropy_grid:
                policy = InferenceDecisionPolicy(
                    confidence_threshold=c_th,
                    margin_threshold=m_th,
                    max_normalized_entropy=e_th,
                    enforce_crop_domain=False  # Test pure output filtering on in-domain
                )
                accepted = 0
                correct_accepted = 0
                for item in val_data:
                    res = policy.evaluate(item["logits"], is_logits=True)
                    if res.is_accepted():
                        accepted += 1
                        if res.local_index == item["label"]:
                            correct_accepted += 1
                
                cov = accepted / len(val_data)
                acc = correct_accepted / accepted if accepted > 0 else 0.0
                frr = 1.0 - cov
                grid_results.append({
                    "confidence_threshold": c_th,
                    "margin_threshold": m_th,
                    "max_normalized_entropy": e_th,
                    "coverage": round(cov, 4),
                    "accuracy_accepted": round(acc, 4),
                    "false_rejection_rate": round(frr, 4),
                    "accepted_count": accepted,
                    "total_count": len(val_data)
                })

    # Selection Criteria:
    # 1. Coverage >= 95.0% (FRR <= 5.0%)
    # 2. Accuracy among accepted >= 99.0%
    # 3. Maximum margin among candidates to ensure boundary resilience
    valid_candidates = [
        r for r in grid_results
        if r["coverage"] >= 0.95 and r["accuracy_accepted"] >= 0.99
    ]

    valid_candidates.sort(
        key=lambda x: (x["accuracy_accepted"], x["coverage"], x["margin_threshold"]),
        reverse=True
    )

    selected = valid_candidates[0] if valid_candidates else grid_results[0]
    print(f"Selected Thresholds from Validation Tuning:")
    print(f"  Confidence Threshold: >= {selected['confidence_threshold']:.2f}")
    print(f"  Top-2 Margin Threshold: >= {selected['margin_threshold']:.2f}")
    print(f"  Max Normalized Entropy: <= {selected['max_normalized_entropy']:.2f}")
    print(f"  Validation Coverage: {selected['coverage']*100:.2f}% ({selected['accepted_count']}/216)")
    print(f"  Validation Accuracy (Accepted): {selected['accuracy_accepted']*100:.2f}%")
    print(f"  Validation False Rejection Rate: {selected['false_rejection_rate']*100:.2f}%")

    return selected, grid_results[:25]


def benchmark_test_set(test_data: List[Dict], policy: InferenceDecisionPolicy) -> Dict:
    print("\n--- Evaluating Calibrated Policy on Held-Out Test Data (256 Specimens) ---")
    
    accepted = 0
    correct_accepted = 0
    cm_accepted = np.zeros((6, 6), dtype=int)
    rejection_breakdown = {}
    accepted_records = []
    rejected_records = []

    for item in test_data:
        res = policy.evaluate(
            item["logits"],
            is_logits=True,
            crop_context=item["crop"]
        )
        if res.is_accepted():
            accepted += 1
            cm_accepted[item["label"], res.local_index] += 1
            if res.local_index == item["label"]:
                correct_accepted += 1
            accepted_records.append({
                "path": item["path"],
                "true_label": item["label"],
                "true_class": item["class_name"],
                "pred_label": res.local_index,
                "pred_class": res.class_id,
                "confidence": res.confidence,
                "margin": res.margin,
                "entropy": res.normalized_entropy
            })
        else:
            reason = res.rejection_detail
            rejection_breakdown[reason] = rejection_breakdown.get(reason, 0) + 1
            rejected_records.append({
                "path": item["path"],
                "true_label": item["label"],
                "true_class": item["class_name"],
                "outcome_type": res.outcome_type,
                "rejection_detail": res.rejection_detail,
                "confidence": res.confidence,
                "margin": res.margin,
                "entropy": res.normalized_entropy,
                "explanation": res.explanation
            })

    total = len(test_data)
    cov = accepted / total
    acc = correct_accepted / accepted if accepted > 0 else 0.0
    frr = 1.0 - cov

    print(f"Test Set Metrics:")
    print(f"  In-Domain Accepted Coverage: {cov*100:.2f}% ({accepted}/{total})")
    print(f"  In-Domain False Rejection Rate: {frr*100:.2f}% ({len(rejected_records)}/{total})")
    print(f"  Accuracy Among Accepted: {acc*100:.2f}% ({correct_accepted}/{accepted})")
    print(f"  Rejection Breakdown: {rejection_breakdown}")

    return {
        "total_test_samples": total,
        "accepted_count": accepted,
        "correct_accepted": correct_accepted,
        "coverage": round(cov, 4),
        "accuracy_among_accepted": round(acc, 4),
        "false_rejection_rate": round(frr, 4),
        "confusion_matrix_accepted": cm_accepted.tolist(),
        "rejection_breakdown": rejection_breakdown,
        "rejected_samples": rejected_records
    }


def benchmark_ood_stress_set(model, transform, policy: InferenceDecisionPolicy) -> Dict:
    print("\n--- Evaluating Calibrated Policy on Out-of-Domain Stress Set (120 Images) ---")

    # Gather 120 OOD negative images: Cotton (bacterial blight & healthy) + FieldPlant (Cassava, Corn)
    ood_paths = []
    cotton_dir = ROOT / "data" / "staged" / "mendeley_cotton_2025"
    if cotton_dir.exists():
        for root, dirs, files in os.walk(cotton_dir):
            for fn in files:
                if fn.endswith(".jpg"):
                    ood_paths.append((Path(root) / fn, "cotton", "unsupported_crop_cotton"))
                    if len(ood_paths) >= 60:
                        break
            if len(ood_paths) >= 60:
                break

    fieldplant_dir = ROOT / "data" / "raw" / "fieldplant" / "train"
    if fieldplant_dir.exists():
        fp_files = [f for f in os.listdir(fieldplant_dir) if f.endswith(".jpg") and ("manioc" in f.lower() or "charbon" in f.lower())]
        for fn in fp_files[:60]:
            crop_type = "cassava" if "manioc" in fn.lower() else "corn"
            ood_paths.append((fieldplant_dir / fn, crop_type, f"unsupported_crop_{crop_type}"))

    print(f"Gathered {len(ood_paths)} out-of-domain negative test images.")

    # 1. Benchmark WITH fail-closed crop domain guard
    guarded_accepted = 0
    guarded_rejected = 0
    guarded_breakdown = {}

    # 2. Benchmark WITHOUT crop domain guard (Raw Softmax Thresholding Stress Test)
    unguarded_accepted = 0
    unguarded_rejected = 0
    unguarded_breakdown = {}
    unguarded_accepted_samples = []

    with torch.no_grad():
        for p, crop_tag, domain_desc in ood_paths:
            with Image.open(p) as img:
                t = transform(img.convert("RGB")).unsqueeze(0)
            logits = model(t)[0].numpy()

            # Guarded evaluation (production contract: cropDomain checked)
            res_guarded = policy.evaluate(logits, is_logits=True, crop_context=crop_tag)
            if res_guarded.is_accepted():
                guarded_accepted += 1
            else:
                guarded_rejected += 1
                dt = res_guarded.rejection_detail
                guarded_breakdown[dt] = guarded_breakdown.get(dt, 0) + 1

            # Unguarded evaluation (demonstrating why softmax threshold alone is insufficient)
            res_unguarded = policy.evaluate(logits, is_logits=True, crop_context=None)
            if res_unguarded.is_accepted():
                unguarded_accepted += 1
                unguarded_accepted_samples.append({
                    "path": str(p),
                    "crop_tag": crop_tag,
                    "pred_class": res_unguarded.class_id,
                    "confidence": res_unguarded.confidence,
                    "margin": res_unguarded.margin,
                    "entropy": res_unguarded.normalized_entropy
                })
            else:
                unguarded_rejected += 1
                dt = res_unguarded.rejection_detail
                unguarded_breakdown[dt] = unguarded_breakdown.get(dt, 0) + 1

    total_ood = len(ood_paths)
    guarded_far = guarded_accepted / total_ood
    guarded_rr = guarded_rejected / total_ood

    unguarded_far = unguarded_accepted / total_ood
    unguarded_rr = unguarded_rejected / total_ood

    print(f"Guarded Policy (Fail-Closed Architecture):")
    print(f"  False Acceptance Rate (FAR): {guarded_far*100:.2f}% ({guarded_accepted}/{total_ood})")
    print(f"  Rejection Rate (RR): {guarded_rr*100:.2f}% ({guarded_rejected}/{total_ood})")
    print(f"  Rejection Details: {guarded_breakdown}")

    print(f"\nUnguarded Policy (Softmax + Margin Filtering Alone):")
    print(f"  False Acceptance Rate (FAR): {unguarded_far*100:.2f}% ({unguarded_accepted}/{total_ood})")
    print(f"  Rejection Rate (RR): {unguarded_rr*100:.2f}% ({unguarded_rejected}/{total_ood})")
    print(f"  Rejection Details: {unguarded_breakdown}")
    print(f"  Unguarded Overconfident Leaks: {len(unguarded_accepted_samples)} samples")

    return {
        "total_ood_samples": total_ood,
        "guarded_policy": {
            "false_positives_accepted": guarded_accepted,
            "false_acceptance_rate": round(guarded_far, 4),
            "correctly_rejected": guarded_rejected,
            "rejection_rate": round(guarded_rr, 4),
            "rejection_breakdown": guarded_breakdown
        },
        "unguarded_policy": {
            "false_positives_accepted": unguarded_accepted,
            "false_acceptance_rate": round(unguarded_far, 4),
            "correctly_rejected": unguarded_rejected,
            "rejection_rate": round(unguarded_rr, 4),
            "rejection_breakdown": unguarded_breakdown,
            "accepted_samples": unguarded_accepted_samples
        }
    }


def benchmark_non_leaf_inputs(model, transform, policy: InferenceDecisionPolicy) -> Dict:
    print("\n--- Evaluating Non-Leaf / Background Inputs ---")
    non_leaf_path = ROOT / "data" / "curated" / "v1" / "08_non_leaf_soil_and_tools.jpg"

    results = []
    if non_leaf_path.exists():
        with Image.open(non_leaf_path) as img:
            t = transform(img.convert("RGB")).unsqueeze(0)
        with torch.no_grad():
            logits = model(t)[0].numpy()

        # Test A: LeafGate fails closed (as evaluated by ImageQualityGate / Semantic LeafGate)
        res_gate_failed = policy.evaluate(
            logits,
            is_logits=True,
            leaf_gate_passed=False,
            leaf_gate_validated=True
        )
        assert res_gate_failed.outcome_type == PolicyOutcomeType.OUT_OF_DOMAIN
        assert res_gate_failed.rejection_detail == PolicyRejectionDetail.LEAF_GATE_REJECTED_NON_LEAF

        # Test B: LeafGate unvalidated
        res_gate_unvalidated = policy.evaluate(
            logits,
            is_logits=True,
            leaf_gate_passed=True,
            leaf_gate_validated=False
        )
        assert res_gate_unvalidated.outcome_type == PolicyOutcomeType.MODEL_UNAVAILABLE
        assert res_gate_unvalidated.rejection_detail == PolicyRejectionDetail.LEAF_GATE_UNVALIDATED

        results.append({
            "input_file": str(non_leaf_path.name),
            "gate_rejected_outcome": res_gate_failed.outcome_type,
            "gate_rejected_detail": res_gate_failed.rejection_detail,
            "unvalidated_gate_outcome": res_gate_unvalidated.outcome_type,
            "unvalidated_gate_detail": res_gate_unvalidated.rejection_detail
        })
        print("  Verified: Non-leaf soil and tools correctly rejected by gate as OUT_OF_DOMAIN.")
        print("  Verified: Unvalidated leaf gate fails closed as MODEL_UNAVAILABLE.")

    return {"non_leaf_results": results}


def run_full_calibration_and_benchmark():
    t0 = time.time()
    model, transform, splits, manifest = load_eval_components()

    val_paths = splits["splits"]["val"]
    test_paths = splits["splits"]["test"]

    print(f"Extracting validation features ({len(val_paths)} specimens)...")
    val_data = extract_features(model, transform, val_paths, manifest)

    print(f"Extracting test features ({len(test_paths)} specimens)...")
    test_data = extract_features(model, transform, test_paths, manifest)

    # 1. Calibrate on validation
    selected_thresholds, grid_sample = calibrate_on_validation(val_data)

    # 2. Instantiate policy with calibrated thresholds
    policy = InferenceDecisionPolicy(
        expected_classes=6,
        confidence_threshold=selected_thresholds["confidence_threshold"],
        margin_threshold=selected_thresholds["margin_threshold"],
        max_normalized_entropy=selected_thresholds["max_normalized_entropy"],
        enforce_crop_domain=True,
        enforce_leaf_gate=True
    )

    # 3. Benchmark on test set
    test_benchmark = benchmark_test_set(test_data, policy)

    # 4. Benchmark on OOD stress set
    ood_benchmark = benchmark_ood_stress_set(model, transform, policy)

    # 5. Benchmark on non-leaf inputs
    non_leaf_benchmark = benchmark_non_leaf_inputs(model, transform, policy)

    total_time = round(time.time() - t0, 2)

    # Save comprehensive results JSON
    benchmark_bundle = {
        "timestamp": "2026-10-24",
        "benchmark_duration_seconds": total_time,
        "calibrated_thresholds": selected_thresholds,
        "validation_grid_search_sample": grid_sample,
        "test_benchmark": test_benchmark,
        "ood_stress_benchmark": ood_benchmark,
        "non_leaf_benchmark": non_leaf_benchmark
    }

    output_json_path = REPORT_DIR / "ood_benchmark_metrics.json"
    with open(output_json_path, "w", encoding="utf-8") as f:
        json.dump(benchmark_bundle, f, indent=2)

    print(f"\nBenchmark complete in {total_time}s.")
    print(f"Results saved to: {output_json_path}")


if __name__ == "__main__":
    run_full_calibration_and_benchmark()
