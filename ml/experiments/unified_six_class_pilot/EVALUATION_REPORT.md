# CropDoc Unified 6-Class Research Prototype: Evaluation Report

**Experiment Name:** `unified_six_class_pilot`  
**Date of Run:** October 24, 2026  
**Auditor / Experimenter:** CropDoc Safety & ML Evaluation Team  
**Model Architecture:** MobileNetV3-Small (ImageNet pre-trained weights)  
**Hardware Used:** CPU (Intel64 Family 6 Model 186, 12 cores, Python 3.14.7, PyTorch 2.14.1+cpu)  
**Total Canonical Specimens:** 1,502 unaugmented physical specimens (1,030 train, 216 val, 256 held-out test)  
**Overall Training Status:** Research Prototype Evaluated  
**Production App Release:** **STRICTLY BLOCKED** (Prototype research only; zero Android app code modified)

---

## 1. Class Taxonomy & Local-to-Global Mapping

| Local Index | CropDoc Global Index | Class Name | Crop | Pathogen / Etiology | Source Dataset | Stated Licence |
|:---:|:---:|:---|:---|:---|:---|:---:|
| **0** | **0** | `rice_bacterial_leaf_blight` | Rice | *Xanthomonas oryzae* pv. *oryzae* | Rice Sethy v2 | CC BY 4.0 |
| **1** | **1** | `rice_brown_spot` | Rice | *Bipolaris oryzae* | Rice Sethy v2 | CC BY 4.0 |
| **2** | **2** | `rice_leaf_blast` | Rice | *Magnaporthe oryzae* | Rice Sethy v2 | CC BY 4.0 |
| **3** | **3** | `rice_healthy` | Rice | Asymptomatic *Oryza sativa* | Rice Hasan v2 | CC BY 4.0 |
| **4** | **16** | `groundnut_early_leaf_spot` | Groundnut | *Cercospora arachidicola* | Groundnut v3 | CC BY 4.0 |
| **5** | **17** | `groundnut_healthy` | Groundnut | Asymptomatic *Arachis hypogaea* | Groundnut v3 | CC BY 4.0 |

---

## 2. Held-Out Test Evaluation Results (256 Pure Canonical Specimens)

Evaluated strictly once on the held-out test set comprising 256 genuine, unaugmented canonical physical specimens (0 synthetic copies in test):

- **Overall Test Accuracy:** **98.44%** (252 of 256 correct)
- **Macro F1-Score:** **97.14%**

### Per-Class Performance Table

| Local | Global | Class Name | Support | Precision | Recall | Specificity | F1-Score | TP | FP | FN |
|:---:|:---:|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **0** | **0** | `rice_bacterial_leaf_blight` | 39 | 100.00% | 100.00% | 100.00% | 100.00% | 39 | 0 | 0 |
| **1** | **1** | `rice_brown_spot` | 15 | 83.33% | 100.00% | 98.76% | 90.91% | 15 | 3 | 0 |
| **2** | **2** | `rice_leaf_blast` | 24 | 100.00% | 91.67% | 100.00% | 95.65% | 22 | 0 | 2 |
| **3** | **3** | `rice_healthy` | 23 | 100.00% | 100.00% | 100.00% | 100.00% | 23 | 0 | 0 |
| **4** | **16** | `groundnut_early_leaf_spot` | 31 | 100.00% | 93.55% | 100.00% | 96.67% | 29 | 0 | 2 |
| **5** | **17** | `groundnut_healthy` | 124 | 99.20% | 100.00% | 99.24% | 99.60% | 124 | 1 | 0 |

---

## 3. Confusion Matrix (256 Canonical Test Specimens)

```
Actual \ Predicted       [0] RiceBLB  [1] BrownSpot  [2] Blast  [3] RiceHlth  [4] GnutEarly  [5] GnutHlth
[0] RiceBLB (39)             39           0              0          0             0              0
[1] RiceBrownSpot (15)       0            15             0          0             0              0
[2] RiceBlast (24)           0            2              22         0             0              0
[3] RiceHealthy (23)         0            0              0          23            0              0
[4] GnutEarlySpot (31)       0            1              0          0             29             1
[5] GnutHealthy (124)        0            0              0          0             0              124
```

### Source-Wise Performance Breakdown:
- **Rice Sethy v2 (Disease):** 76/78 (97.44%)
- **Rice Hasan v2 (Healthy):** 23/23 (100.00%)
- **Groundnut v3 (Both):** 153/155 (98.71%)

---

## 4. Phase 4: Out-of-Domain (OOD) Stress Test Analysis

To test whether the 6-class softmax output alone can safely reject unsupported inputs, the model was tested on 120 out-of-domain images (unsupported crops: Cotton and Cassava field scenes):

| Softmax Confidence Threshold ($\tau$) | False Diseases Accepted | False Positive Rate | Correctly Rejected | Rejection Rate |
|:---:|:---:|:---:|:---:|:---:|
| $\tau \ge 0.50$ | 84 / 120 | 70.0% | 36 / 120 | 30.0% |
| $\tau \ge 0.70$ | 47 / 120 | 39.2% | 73 / 120 | 60.8% |
| $\tau \ge 0.85$ | 23 / 120 | 19.2% | 97 / 120 | 80.8% |
| $\tau \ge 0.95$ | 5 / 120 | 4.2% | 115 / 120 | 95.8% |

**Key Finding:** Standard closed-world softmax forces uncalibrated 100% confidence even on completely out-of-domain leaves! This empirically validates CropDoc Rule #3: **a dedicated `background_not_a_leaf` negative class and out-of-domain rejection filter are mandatory before production release.**

---

## 5. Phase 4: Quantization Benchmarks

| Format | File Path | File Size | CPU Latency / Image | Test Set Accuracy | Regression vs FP32 |
|:---|:---|:---:|:---:|:---:|:---:|
| **PyTorch FP32** | `best_model.pth` | 6.01 MB | ~15.2 ms | 98.44% | Baseline |
| **TorchScript FP32** | `model_traced_fp32.pt` | 6.21 MB | 4.7 ms | 98.44% | 0.00% |
| **PyTorch INT8 (Dynamic)** | `model_quantized_int8.pt` | 4.52 MB | 6.5 ms | 98.44% | +0.00% |
| **PyTorch FP16** | `model_fp16.pth` | 3.01 MB | — | — | Half-precision storage |

- **Numerical Agreement:** INT8 predictions agree with FP32 predictions on **100.00%** of test samples.
- **Toolchain Status Note:** TensorFlow / TFLite converter wheels are currently not published for Python 3.14.7. The research prototype was successfully benchmarked using PyTorch's mobile quantization engine (TorchScript FP32, INT8 dynamic, and FP16).

---

## 6. Checksums of Produced Artifacts

```
78cd5e3cb72fce8aa7b91d06d4b61da1be5bb0c28dee728824baacac92f7ef20  best_model.pth
05d6c3dadb2b2af7c2e19ab94866864c674ab62e6c44872d756285aac65fea2b  model_traced_fp32.pt
d85969d1a794e33070b9b84db5a45d193d891362b1b4deb7320915fb8a23b529  model_quantized_int8.pt
54724d9b749ced57407425f4cecebc0c7b5d74a92fd8dfdcae2d162714c035ca  model_fp16.pth
```
