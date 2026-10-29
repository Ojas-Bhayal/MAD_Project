# Groundnut Binary Research Pilot: Evaluation Report

**Experiment Name:** `groundnut_binary_pilot`  
**Date of Run:** October 24, 2026  
**Auditor / Experimenter:** CropDoc Safety & ML Evaluation Team  
**Dataset:** Mendeley Groundnut Leaf Image Dataset v3 (Aishwarya & Reddy, 2023)  
**Model Architecture:** MobileNetV3-Small (ImageNet pre-trained weights)  
**Classes Evaluated:**  
- `0: groundnut_early_leaf_spot` (Cercospora arachidicola)
- `1: groundnut_healthy` (Clinically healthy Arachis hypogaea)  
**Overall Training Status:** Research Pilot Completed (Held-out Test Verified)  
**App Release Status:** **BLOCKED** (Production app release remains locked; research evaluation only)

---

## 1. Executive Summary

This pilot experiment tested binary classification between `groundnut_early_leaf_spot` and `groundnut_healthy` using a group-locked split of Mendeley Groundnut v3. The model achieved **97.59% accuracy** and a **97.58% macro F1-score** on 539 completely held-out test images with zero physical specimen leakage and zero perceptual near-duplicate leakage ($d_H \le 2$).

### Core Metrics Table

| Metric | Overall | `groundnut_early_leaf_spot` | `groundnut_healthy` |
| :--- | :---: | :---: | :---: |
| **Support (Test Images)** | 539 | 260 | 279 |
| **Accuracy** | **97.59%** | — | — |
| **Precision** | — | **99.60%** (248/249) | **95.86%** (278/290) |
| **Recall / Sensitivity** | — | **95.38%** (248/260) | **99.64%** (278/279) |
| **Specificity** | — | 99.64% | 95.38% |
| **F1-Score** | **97.58%** | **97.45%** | **97.72%** |
| **False Positives** | — | 1 | 12 |
| **False Negatives** | — | 12 | 1 |

---

## 2. Confusion Matrix

```
                      Predicted: Early Leaf Spot    Predicted: Healthy
Actual: Early Leaf Spot         248 (TP)                    12 (FN)
Actual: Healthy                   1 (FP)                   278 (TN)
```

- **Early Leaf Spot Sensitivity:** 248 out of 260 infected test leaves correctly detected (95.38%).
- **Healthy Specificity:** 278 out of 279 healthy test leaves correctly recognized (99.64%), with only 1 false positive.
- **Pure Canonical Specimen Evaluation (Zero Augmentation Inflation):**
  When evaluated strictly on the 155 unique canonical physical specimens in the test set (1 image per specimen group), the model achieved **98.71% accuracy** (153 of 155 correct):
  - `groundnut_early_leaf_spot`: 30 of 31 canonical physical specimens correct (**96.77%**)
  - `groundnut_healthy`: 123 of 124 canonical physical specimens correct (**99.19%**)
  This confirms that test performance is not driven by repeated augmented views of a small sample of plants.
- **False Negative Analysis:** The 12 misclassified early leaf spot images were very early stage, sub-millimeter punctate lesions where chlorotic halos were not yet prominent against the green background.

---

## 3. Generalization & Overfitting Check

- **Training Loss:** Decreased from 0.2696 to 0.0078 over 6 epochs.
- **Validation Loss:** Decreased from 0.0886 to 0.0270 (minimum reached at epoch 6).
- **Training Accuracy:** 99.72%
- **Validation Accuracy:** 99.63%
- **Held-Out Test Accuracy:** 97.59%
- **Generalization Gap:** 2.13% (train accuracy minus test accuracy). The minimal generalization gap confirms that group-locked specimen partitioning successfully prevented memorization of physical leaf specimens.

---

## 4. Hardware & Resource Usage

- **Device:** CPU (Intel64 Family 6 Model 186, 12 cores)
- **Batch Size:** 32
- **Epoch Duration:** ~41.4 seconds / epoch
- **Total Training Duration:** 248.56 seconds (~4.1 minutes)
- **Peak Memory:** Minimal (<1 GB RAM)

---

## 5. Artifacts Generated

- Weights: `ml/experiments/groundnut_binary_pilot/best_model.pth`
- Metrics: `ml/experiments/groundnut_binary_pilot/metrics.json`
- Curves: `ml/experiments/groundnut_binary_pilot/training_curves.png`
- Confusion Matrix Plot: `ml/experiments/groundnut_binary_pilot/confusion_matrix.png`
- Predictions Log: `ml/experiments/groundnut_binary_pilot/test_predictions.csv`
