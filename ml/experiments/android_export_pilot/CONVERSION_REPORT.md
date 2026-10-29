# Conversion & Compatibility Report

The full conversion and Android compatibility report is documented at:
[`ml/reports/inference_guardrails/ANDROID_EXPORT_COMPATIBILITY_REPORT.md`](../../reports/inference_guardrails/ANDROID_EXPORT_COMPATIBILITY_REPORT.md).

### Google Colab Conversion Pipeline
Because the Windows host environment runs Python 3.14 without WSL2, Google Colab serves as the remote Linux (Ubuntu 22.04 LTS) conversion environment.

**Dedicated Notebook:**  
[`ml/experiments/android_export_pilot/CropDoc_LiteRT_Export.ipynb`](./CropDoc_LiteRT_Export.ipynb)  
SHA-256: `178E2496D0EBC858CEE985DC49F18AAEFF4AED00D26FBE11E9FB45C982E999A7`

### Checkpoints to Ingest in Colab:
1. `ml/experiments/unified_six_class_pilot/best_model.pth`  
   SHA-256: `78cd5e3cb72fce8aa7b91d06d4b61da1be5bb0c28dee728824baacac92f7ef20`
2. `ml/experiments/crop_domain_gate/best_model.pth`  
   SHA-256: `222401a3641bb26be53bb3a3049691c70e7914a3bbf0ea671e1335520411179c`

### Converted Target Outputs:
- `export_output/unified_six_class_fp32.tflite` (6 outputs, MobileNetV3-Small)
- `export_output/crop_domain_gate_fp32.tflite` (3 outputs, MobileNetV3-Small)
- `export_output/parity_benchmark_report.json`

### Summary Status:
- **PyTorch Candidate Artifacts:** Verified (MobileNetV3-Small, 6 outputs and 3 outputs).
- **Android Runtime & Operator Compatibility:** Verified (LiteRT 2.3.0, 100% standard built-in operators).
- **Isolated Android Inference Harness:** Implemented in `app/src/main/java/com/cropdoc/app/ml/research/` and fully verified with unit tests (`ResearchInferenceHarnessTest.java`).
- **Colab Notebook:** Generated and verified at `CropDoc_LiteRT_Export.ipynb`.
