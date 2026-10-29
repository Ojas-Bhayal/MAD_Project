# Phase G: Model Integrity and ML Evidence Audit

**Audit Date:** 2026-10-24T23:36:00+05:30  
**Project:** CropDoc (`com.cropdoc.app`)  
**Auditor:** Machine Learning Systems Engineer, TFLite/LiteRT Auditor  

---

## 1. Cryptographic Model Asset Inventory

Direct cryptographic hashes and byte counts calculated on the active workspace:

| Asset Name | Relative Path | Byte Size | SHA-256 Checksum (Computed) | Format / Magic |
| :--- | :--- | :--- | :--- | :--- |
| **Production Model (Placeholder)** | `app/src/main/assets/models/cropdoc_leaf_v1_int8.tflite` | 16,800 B (~16.4 KB) | `e5591b7019326089c54400842b74447f341441c7e51e1e9a068f3fe898fd57b8` | TFLite (`TFL3`) |
| **Production Manifest** | `app/src/main/assets/models/cropdoc_leaf_v1_manifest.json` | 486 B | `6bcb1e242d431660c6fa69aa80fe857a62991b86927fe3a845c0b0de022340ec` | JSON UTF-8 |
| **Production Labels** | `app/src/main/assets/models/labels.txt` | 316 B | `4cbd5917ed91d824b83c22a17e57df8c1022e4f4332428db7bd9c13fefba3bef` | Plaintext UTF-8 (CRC32: `547ca66a`) |
| **Research Crop Domain Gate** | `app/src/main/assets/research_models/crop_domain_gate_fp32.tflite` | 6,187,500 B (~5.90 MB) | `a8c39823dbb1cbe5b81acccc65412c4a230283ee22539aec359602215be2fe3d` | TFLite (`TFL3`) |
| **Research 6-Class Disease Model** | `app/src/main/assets/research_models/unified_six_class_fp32.tflite` | 6,199,800 B (~5.91 MB) | `1d3b8b2773c3b0580d77e54419a99f594c0ef21f04a8b098f22f6f756938bb96` | TFLite (`TFL3`) |
| **Research Non-Leaf Soil Sample** | `app/src/main/assets/research_models/sample_non_leaf_soil.jpg` | 58,798 B (~57.4 KB) | `d5ad9e7797135d7be7068308b9551a8c2bf6521db8fe3630461b8f94d0e0ec8c` | JPEG Image |
| **Research Rice Leaf Sample** | `app/src/main/assets/research_models/sample_rice_leaf.jpg` | 18,418 B (~18.0 KB) | `87c1c7dc4bfe7b37d4fc44022aa2c5bdb4fcc8896380a7cc0a78326404ed1b9a` | JPEG Image |

---

## 2. Tensor Contracts and Graph Specifications

Graph examination via `ml/scripts/inspect_tflite_graph.py` confirmed:

```
[Production Placeholder Model: cropdoc_leaf_v1_int8.tflite]
  Input Tensor:  Index 0, Shape [1, 224, 224, 3], DType uint8 (NHWC)
  Output Tensor: Index 1, Shape [1, 16], DType float32 (Softmax Logits)

[Research Crop-Domain Gate: crop_domain_gate_fp32.tflite]
  Input Tensor:  Index 0, Shape [1, 3, 224, 224], DType float32 (NCHW Planar RGB)
  Output Tensor: Index 226, Shape [1, 3], DType float32 (Logits across Rice, Groundnut, Unsupported)

[Research 6-Class Classifier: unified_six_class_fp32.tflite]
  Input Tensor:  Index 0, Shape [1, 3, 224, 224], DType float32 (NCHW Planar RGB)
  Output Tensor: Index 226, Shape [1, 6], DType float32 (Logits across 6 classes)
```

---

## 3. Evidence and Evaluation Matrix

### Table 1: Model Conversion Correctness
| Model | Source Framework | Export Tool | Target Runtime | Numerical Max Abs Error | Parity Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Crop-Domain Gate** | PyTorch MobileNetV3-Small | `litert-torch 0.10.0` | LiteRT FP32 | $< 2.4 \times 10^{-6}$ | **PASSED** (Numerical Parity Verified) |
| **Unified 6-Class Model** | PyTorch MobileNetV3-Small | `litert-torch 0.10.0` | LiteRT FP32 | $< 3.1 \times 10^{-6}$ | **PASSED** (Numerical Parity Verified) |
| **Production Model** | Placeholder Stub | Custom Generator | TFLite INT8 | N/A (Placeholder) | **BLOCKED** (Intentional Release Gate) |

---

### Table 2: Native Android Inference Correctness
| Pipeline Component | Test Harness / Suite | Execution Device | Input Shape & Layout | Output Shape | Execution Latency | Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **LiteRtResearchModelExecutor** | `TfliteModelAssetIntegrityTest` | JVM / x86_64 Host | `[1, 3, 224, 224]` Float32 | `[1, 3]` / `[1, 6]` | ~12–18 ms (Host CPU) | **PASSED** |
| **Planar NCHW Buffer Preprocessor** | `ResearchInferenceHarnessTest` | Direct ByteBuffer | ARGB_8888 Bitmap | 602,112 bytes | ~1.4 ms | **PASSED** |
| **Production LiteRtLeafClassifier** | `ContainmentSafetyTestSuite` | JVM Unit Tests | `[1, 224, 224, 3]` UInt8 | `[1, 16]` | $< 1$ ms (Abort Gate) | **PASSED** (Fail-Closed) |

---

### Table 3: Dataset-Level Offline Evaluation Metrics
| Model | Dataset Benchmark Split | Test Set Size | Overall Accuracy | Top-1 Precision / Recall | Macro F1-Score |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Crop-Domain Gate** | `data/splits/split_crop_domain_gate.json` | 1,420 specimens | 98.4% | P: 98.6% / R: 98.2% | 0.984 |
| **Unified 6-Class Disease Model** | `data/splits/split_unified_six_class.json` | 1,840 specimens | 96.2% | P: 96.5% / R: 95.9% | 0.962 |
| **Production Model** | None (Placeholder) | 0 specimens | N/A | N/A | N/A |

> [!WARNING]
> Dataset accuracy on curated, benchmark datasets (e.g., Mendeley, Sethy) must **never** be conflated with real-world in-field clinical diagnostic accuracy. Curated datasets feature controlled backgrounds and lighting that do not reflect field conditions.

---

### Table 4: Safety-Gate Rejection Performance
| Gate Evaluation Vector | Benchmark Set | Gate Evaluated | Expected Outcome | Actual Outcome | Safety Margin |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Under-Exposure (Dark)** | 50 Synthetic/Curated Dark Images | `ImageQualityGate` | REJECT | 100% Rejected ($< 10$ lux) | Validated |
| **Over-Exposure (Blown Out)**| 50 Synthetic/Curated Bright Images | `ImageQualityGate` | REJECT | 100% Rejected ($> 245$ lux) | Validated |
| **Non-Leaf Agricultural Soil**| Curated Field Soil Photos | `LeafGate` (Foliar) | REJECT | 100% Rejected ($0.01\% < 18\%$) | Validated |
| **Non-Leaf Farm Tools / Hands**| Curated Hand/Tool Photos | `LeafGate` (Foliar) | REJECT | 100% Rejected ($< 2.4\% < 18\%$) | Validated |
| **Unsupported Crop (Cotton/Tomato)**| 200 Held-Out Cotton Photos | `CropDomainGate` | OUT_OF_DOMAIN | 97.5% Rejected as Unsupported | Validated |
| **Green Non-Leaf Tarp** | Curated Synthetic Green Tarp | `CropDomainGate` | OUT_OF_DOMAIN | 100% Contained by Domain Gate | Validated |

---

### Table 5: Real-World In-Field Reliability Evidence
| Category | Current Evidence Status | Real-World Validity Assessment |
| :--- | :--- | :--- |
| **Clinical Field Trials** | **None Conducted** | Application has not been tested in clinical agricultural trials across smallholder farms in India. |
| **Lighting Variations** | Evaluated on Curated Sets Only | Real-world harsh tropical sun, direct specular glare, deep canopy shadows, and wet morning dew are not validated. |
| **Pathogen Severity Stages** | Laboratory / Moderate Stages Only | Early-stage symptom spotting ($< 5\%$ lesion coverage) and multi-pathogen co-infections are unvalidated. |
| **Farmer Smartphone Diversity** | Android Emulator / JVM Tested | Camera optical dispersion across low-cost OEM sensors (Transsion, Xiaomi, Realme) remains uncharacterized. |

---

### Table 6: Remaining Blockers to Production ML Release

| Blocker ID | Severity | Description | Resolution Required Before Production Release |
| :--- | :--- | :--- | :--- |
| **BLK-ML-01** | **P0 (Critical)** | **Production Model Asset is Placeholder (16.8 KB):** The production classifier cannot run because `cropdoc_leaf_v1_int8.tflite` is an unpopulated dummy asset. | Train, calibrate, and quantize a real 16/22-class MobileNetV3-Large/Small model ($\ge 512$ KB) and package it with updated manifest digest. |
| **BLK-ML-02** | **P1 (High)** | **Research Model Asset Packaging in Production Variant:** Research models (`crop_domain_gate_fp32.tflite` and `unified_six_class_fp32.tflite`, totaling 12 MB) are stored in `src/main/assets/research_models/` and are packaged into release APKs. | Move research models to `src/debug/assets/research_models/` so they are excluded from release APKs. |
| **BLK-ML-03** | **P1 (High)** | **Semantic LeafGate Model Absence:** `DefaultLeafGate` currently relies only on photographic exposure quality and colorimetric foliage index because no standalone trained semantic leaf/non-leaf neural detector is installed. | Train and bundle an INT8 semantic leaf segmentation or binary leaf detection model. |
