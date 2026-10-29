# Cotton Binary Pilot Model — Evaluation Report

**Experiment Name:** `cotton_binary_pilot`  
**Execution Date:** October 24, 2026  
**Status:** **COMPLETED (RESEARCH PILOT ONLY — NOT FOR PRODUCTION RELEASE)**  
**Auditor:** CropDoc Autonomous ML Quality & Safety Gate  
**Experiment Root Directory:** [`ml/experiments/cotton_binary_pilot/`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/experiments/cotton_binary_pilot)  

---

## 1. Executive Summary & Objective

This experiment evaluated an isolated, two-class transfer learning pilot model on the staged and verified subset of the Mendeley Cotton Leaf Image Dataset ([`data/staged/mendeley_cotton_2025/`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/data/staged/mendeley_cotton_2025)).

The primary objective was to:
1. Verify that the end-to-end model training, validation, early-stopping checkpointing, and evaluation pipeline executes reliably.
2. Establish a preliminary quantitative baseline for discriminating cotton bacterial blight from healthy cotton foliage.
3. Keep the experiment completely isolated from the production 22-class CropDoc model, Android application code, and release pipelines.

> [!CAUTION]
> **Important Safety Restrictions & Limitations:**
> - This is a preliminary research baseline trained on unreviewed source labels. It does **NOT** represent a certified diagnostic model.
> - The model must **NOT** be used to diagnose other cotton foliar diseases (*Alternaria*, *Fusarium*, *Verticillium*), other crop species, or non-leaf objects.
> - The model weights are **NOT** approved for mobile APK bundling or distribution due to unresolved legal distinctions between Mendeley Data's CC BY 4.0 terms and Elsevier *Data in Brief*'s CC BY-NC 4.0 publication terms.
> - The CropDoc production 22-class model (`models/v1/` and `models/v2/`), Android inference engine, and safety rejection safeguards remain 100% untouched.

---

## 2. Hardware Environment & Hardware Acceleration Diagnostic

Per CropDoc ML Safety Gate rules, hardware availability and execution devices must be reported transparently without fabricating acceleration status:

| Component | Detected Specification | Operational Status |
| :--- | :--- | :--- |
| **Operating System** | Windows 11 (AMD64) | Active Host OS |
| **CPU Architecture** | AMD / Intel Multi-Core (16 logical threads) | Active Compute Device |
| **GPU Hardware** | **NVIDIA GeForce RTX 4050 Laptop GPU** (6,141 MiB VRAM, Driver 621.01) | Detected via `nvidia-smi` |
| **Python Runtime** | **Python 3.14.7** | Active Environment |
| **PyTorch Framework** | **PyTorch 2.14.1+cpu** / **Torchvision 0.29.1+cpu** | CPU Multi-Threaded Build |
| **CUDA Acceleration** | **UNAVAILABLE in Python 3.14 Build** | **CPU Fallback Executed** |

### Root Cause Analysis for CPU Fallback:
While an NVIDIA GeForce RTX 4050 GPU with 6 GB VRAM is physically present and recognized by the OS driver, the host Python runtime is Python 3.14.7. Official PyTorch CUDA binary wheels (`cu121`/`cu124`) are currently distributed only for Python $\le 3.13$. The official PyPI repository provides only the CPU build (`torch==2.14.1+cpu`) for `cp314-win_amd64`.

Because MobileNetV3-Small is an ultra-lightweight architecture (2.54M parameters), full CPU multi-threaded training was executed as a reliable fallback. Total training time for 20 epochs across 252 images was **107.5 seconds** (~5.4s per epoch), proving that CPU training is practical for local pilot experiments.

---

## 3. Dataset Configuration & Split Independence

The model was trained exclusively on the verified, staged data partitions defined in [`data/splits/split_mendeley_cotton_2025.json`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/data/splits/split_mendeley_cotton_2025.json), locked with fixed seed `seed=42`:

| Partition | Total Images | Specimen Groups | `cotton_bacterial_blight` | `cotton_healthy` | Group Leakage |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Train** | 252 (70.2%) | 161 | 130 images (101 groups) | 122 images (60 groups) | **0** |
| **Validation** | 54 (15.0%) | 54 | 28 images (28 groups) | 26 images (26 groups) | **0** |
| **Held-Out Test** | 53 (14.8%) | 53 | 27 images (27 groups) | 26 images (26 groups) | **0** |
| **TOTAL** | **359** | **268** | **185 images** | **174 images** | **0 (PASS)** |

- **Cross-Split Independence:**
  - All burst sequences (`bacterial_(114, 119, 120)` and `healthy_(84, 95)`) were consolidated 100% into `train`.
  - $\text{Train Groups} \cap \text{Val Groups} = \emptyset$, $\text{Train Groups} \cap \text{Test Groups} = \emptyset$, $\text{Val Groups} \cap \text{Test Groups} = \emptyset$.
  - Exactly zero duplicate or shared specimen groups cross split boundaries.

---

## 4. Model Architecture & Training Hyperparameters

- **Base Architecture:** MobileNetV3-Small (pretrained on ImageNet-1K, `MobileNet_V3_Small_Weights.DEFAULT`)
- **Classifier Head:** Replaced final projection layer with `nn.Linear(1024, 2)`
- **Input Resolution:** $224 \times 224 \times 3$
- **Input Normalization:** ImageNet Standard ($\mu = [0.485, 0.456, 0.406]$, $\sigma = [0.229, 0.224, 0.225]$)
- **Data Augmentation (Train Only):**
  - `RandomResizedCrop(224, scale=(0.8, 1.0))`
  - `RandomHorizontalFlip(p=0.5)`
  - `RandomVerticalFlip(p=0.5)`
  - `ColorJitter(brightness=0.1, contrast=0.1)`
- **Validation & Test Preprocessing (No Augmentation):**
  - `Resize(256)`, `CenterCrop(224)`, `ToTensor()`, `Normalize()`
- **Optimizer:** AdamW ($\text{lr} = 3 \times 10^{-4}$, $\text{weight\_decay} = 1 \times 10^{-2}$)
- **Batch Size:** 16
- **Epochs:** 20 (Early stopping patience = 5 epochs)
- **Loss Function:** Cross-Entropy Loss (`nn.CrossEntropyLoss()`)
- **Random Seed:** 42 (locked across Python, NumPy, and PyTorch)

---

## 5. Training History & Convergence Metrics

Training progressed across all 20 epochs without early termination, as validation loss continued to refine into sub-$10^{-4}$ territory:

| Epoch | Train Loss | Train Accuracy | Val Loss | Val Accuracy | Val Balanced Acc | Epoch Time | Checkpoint Status |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| **1** | 0.3034 | 87.30% | 1.5486 | 50.00% | 51.79% | 5.6s | Initial Checkpoint |
| **2** | 0.0399 | 99.21% | 1.9314 | 55.56% | 57.14% | 5.4s | — |
| **3** | 0.0651 | 97.62% | 1.5524 | 64.81% | 66.07% | 5.5s | — |
| **4** | 0.0078 | 100.00% | 0.5421 | 77.78% | 78.57% | 5.2s | Best Checkpoint |
| **5** | 0.0047 | 100.00% | 0.3600 | 87.04% | 87.50% | 5.2s | Best Checkpoint |
| **6** | 0.0079 | 99.60% | 0.2238 | 90.74% | 91.07% | 5.4s | Best Checkpoint |
| **7** | 0.0104 | 99.60% | 0.1425 | 94.44% | 94.64% | 5.1s | Best Checkpoint |
| **8** | 0.0032 | 100.00% | 0.0813 | 94.44% | 94.64% | 5.2s | Best Checkpoint |
| **9** | 0.0007 | 100.00% | 0.0177 | 100.00% | 100.00% | 5.1s | Best Checkpoint |
| **10** | 0.0014 | 100.00% | 0.0067 | 100.00% | 100.00% | 5.4s | Best Checkpoint |
| **11** | 0.0010 | 100.00% | 0.0020 | 100.00% | 100.00% | 5.4s | Best Checkpoint |
| **12** | 0.0006 | 100.00% | 0.0006 | 100.00% | 100.00% | 5.4s | Best Checkpoint |
| **13** | 0.0007 | 100.00% | 0.0003 | 100.00% | 100.00% | 5.5s | Best Checkpoint |
| **14** | 0.0008 | 100.00% | 0.0002 | 100.00% | 100.00% | 5.6s | Best Checkpoint |
| **15** | 0.0026 | 100.00% | 0.0001 | 100.00% | 100.00% | 5.3s | Best Checkpoint |
| **16** | 0.0009 | 100.00% | 0.0001 | 100.00% | 100.00% | 5.3s | Best Checkpoint |
| **17** | **0.0014** | **100.00%** | **0.000049** | **100.00%** | **100.00%** | **5.4s** | **BEST CHECKPOINT (Selected)** |
| **18** | 0.0348 | 98.81% | 0.0008 | 100.00% | 100.00% | 5.4s | — |
| **19** | 0.0229 | 99.60% | 0.0005 | 100.00% | 100.00% | 5.3s | — |
| **20** | 0.0040 | 100.00% | 0.0006 | 100.00% | 100.00% | 5.3s | Final Epoch |

- **Best Checkpoint Selected:** **Epoch 17** ($\text{Val Loss} = 0.000049$, $\text{Val Accuracy} = 100.00\%$).
- **Visual Plots:** Saved to [`training_curves.png`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/experiments/cotton_binary_pilot/training_curves.png).

---

## 6. Held-Out Test Evaluation Results

The best checkpoint from Epoch 17 was loaded and evaluated on the 53 unaugmented images in the held-out test split:

### 6.1 Performance Summary Table
| Metric | Measured Value | Operational Interpretation |
| :--- | :---: | :--- |
| **Evaluated Test Images** | **53 images** | 27 Bacterial Blight, 26 Healthy Leaves |
| **Overall Test Accuracy** | **100.00%** (53 / 53) | All test samples correctly classified |
| **Balanced Accuracy** | **100.00%** | Equal sensitivity across both classes |
| **Macro F1-Score** | **100.00%** | Perfect harmonic mean of precision and recall |

### 6.2 Per-Class Classification Breakdown
| Class Index | Target Class Name | Test Support | Precision | Recall | F1-Score |
| :---: | :--- | :---: | :---: | :---: | :---: |
| **0** | `cotton_bacterial_blight` | 27 images | **100.00%** | **100.00%** | **100.00%** |
| **1** | `cotton_healthy` | 26 images | **100.00%** | **100.00%** | **100.00%** |

### 6.3 2x2 Confusion Matrix
```
                       Predicted Bacterial    Predicted Healthy
  Actual Bacterial:            27                     0
  Actual Healthy:               0                    26
```
- **True Positives (Bacterial Blight as Bacterial):** 27
- **False Positives (Healthy misclassified as Bacterial):** 0
- **True Negatives (Healthy correctly identified):** 26
- **False Negatives (Bacterial misclassified as Healthy):** 0

---

## 7. Critical Overfitting, Stability, and Realism Analysis

Per requirement 2.8, the relationship between training, validation, and test metrics must be evaluated critically:

### Why Did the Model Achieve 100% Test Accuracy?
1. **Severe Visual Contrast in Laboratory/Farm Setup:** The source dataset (Babu et al., 2025) depicts cotton bacterial blight as prominent, dark, necrotic, angular lesions that contrast sharply with the uniform green, unblemished lamina of healthy leaves.
2. **Homogeneous Domain & Camera Distribution:** All images were captured at a single research farm in Bangladesh with identical camera optics, uniform natural daylight, and consistent background foliage.
3. **Pretrained Feature Representational Power:** Transfer learning from ImageNet-1K provides rich convolutional filters that quickly separate necrotic brown/black lesion textures from healthy green chloroplast pigmentation.
4. **Leakage Elimination:** In the regenerated split, all burst sequences were consolidated into `train`, so the 100% test score was achieved on genuinely independent specimen groups (27 distinct bacterial blight groups, 26 distinct healthy groups).

### Overfitting & Generalization Warnings:
- **Zero Field Domain Shift Tested:** In real agricultural deployment across Telangana or pan-India fields, cotton crops exhibit complex confounding factors not present in this dataset:
  - Fungal leaf spot mimics (*Alternaria macrospora*, *Cercospora gossypina*)
  - Insect puncture and silvering (Thrips, Aphids, Whiteflies)
  - Severe nutritional reddening / chlorosis (Magnesium, Potassium deficiency)
  - Varied smartphone sensors, lens smudge, dust, and direct noon glare
- **Conclusion:** A 100% benchmark on this 53-image single-source test set confirms that the **architecture and training pipeline work correctly**, but does **NOT** guarantee clinical field robustness. Real-world validation on diverse Indian field images remains essential.

---

## 8. Artifact Inventory & Cryptographic Checksums

All experiment outputs were written exclusively beneath [`ml/experiments/cotton_binary_pilot/`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/experiments/cotton_binary_pilot):

| Artifact Filename | Description | File Size | SHA-256 Checksum |
| :--- | :--- | :---: | :--- |
| [`best_model.pt`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/experiments/cotton_binary_pilot/best_model.pt) | PyTorch weights checkpoint (Epoch 17) | 18,485,871 B | `cc1fbcebaa3a80659da42905bcbdf548df4fd81815701de82ff4a9006077b33c` |
| [`best_model.pt.sha256`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/experiments/cotton_binary_pilot/best_model.pt.sha256) | Standalone cryptographic verification hash | 81 B | `cc1fbcebaa3a80659da42905bcbdf548df4fd81815701de82ff4a9006077b33c` |
| [`training_log.csv`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/experiments/cotton_binary_pilot/training_log.csv) | Per-epoch tabular metrics (loss, acc, time) | 1,902 B | `0ee9827038ddff3239c09bf87c94441ef5e63821fc0d17926b489955d7f0ec40` |
| [`training_log.txt`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/experiments/cotton_binary_pilot/training_log.txt) | Raw console stream and evaluation log | 3,562 B | `6a43bf32cb0f9f30e0a5c4e72ddda786bf6c518ca861b5c490a614d9b4b0257e` |
| [`training_curves.png`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/experiments/cotton_binary_pilot/training_curves.png) | 180 DPI loss and accuracy convergence plots | 147,584 B | `611593c6f66299b6df7d4f9b00cb36b7617b7b15a6b7d3f8da08e33230a6c23f` |
| [`evaluation_results.json`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/experiments/cotton_binary_pilot/evaluation_results.json) | Detailed JSON test results & sample predictions | 25,207 B | `f0a35bb214f494f1c93a90da34fa3df8398ba5c453aa6735e2365ee37dfb8f2c` |
| [`model_metadata.json`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/experiments/cotton_binary_pilot/model_metadata.json) | Model metadata, tensor shape, normalization | 755 B | `11d73922d4f20ec689b6c072237eb733a41e97d19da0503f39a4cece9f1873ea` |
| [`train_cotton_binary_pilot.py`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/scripts/train_cotton_binary_pilot.py) | Reproducible training and evaluation script | 14,846 B | `9b3ff8c6d36e2f1db56ef5a63901b0e51381bbdd37ce698bf088fa071c5ec82f` |

---

## 9. Final Safety Gate & Production Status

```
┌────────────────────────────────────────────────────────────────────────┐
│                        CROPdoc ML SAFETY GATE                          │
│                                                                        │
│   STATUS: EXPERIMENTAL PILOT COMPLETED — PRODUCTION BLOCKED            │
│                                                                        │
│   • Training Pipeline Execution:          SUCCESS (107.5 seconds)      │
│   • Held-Out Test Accuracy:               100.00% (53 / 53 images)     │
│   • Model Isolation:                      100% ISOLATED (ml/experiments│
│   • Production Release Status:            STRICTLY BLOCKED             │
│   • Pathologist Sign-Off:                 PENDING HUMAN REVIEW         │
│   • Dual-Licence Reconciliation:          PENDING LEGAL REVIEW         │
└────────────────────────────────────────────────────────────────────────┘
```
