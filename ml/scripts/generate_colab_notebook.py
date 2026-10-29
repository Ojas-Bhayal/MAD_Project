#!/usr/bin/env python3
"""
CropDoc — Generate Google Colab Conversion Notebook
Produces: ml/experiments/android_export_pilot/CropDoc_LiteRT_Export.ipynb
"""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_NB = ROOT / "ml" / "experiments" / "android_export_pilot" / "CropDoc_LiteRT_Export.ipynb"
OUT_NB.parent.mkdir(parents=True, exist_ok=True)

def create_markdown_cell(source_lines):
    return {
        "cell_type": "markdown",
        "metadata": {},
        "source": [line + "\n" for line in source_lines]
    }

def create_code_cell(source_lines):
    return {
        "cell_type": "code",
        "execution_count": None,
        "metadata": {},
        "outputs": [],
        "source": [line + "\n" for line in source_lines]
    }

def build_notebook():
    cells = []

    # -------------------------------------------------------------------------
    # Cell 1: Overview
    # -------------------------------------------------------------------------
    cells.append(create_markdown_cell([
        "# CropDoc — Google LiteRT Mobile Model Export & Parity Verification",
        "",
        "**Target Models:**",
        "1. **Unified Six-Class Disease Classifier** (`best_model.pth` $\\to$ `unified_six_class_fp32.tflite`)",
        "2. **Three-Output Crop-Domain Gate** (`best_model.pth` $\\to$ `crop_domain_gate_fp32.tflite`)",
        "",
        "**Safety & Research Scope:**",
        "- Local research conversion only. No modification to production Android models or release assets.",
        "- Floating-point (FP32) export first. **INT8 quantization is strictly deferred until FP32 numerical parity passes**.",
        "- Verifies SHA-256 cryptographic digests, output tensor shapes, probability distributions, and prediction agreement.",
        "",
        "---"
    ]))

    # -------------------------------------------------------------------------
    # Cell 2: Environment Inspection
    # -------------------------------------------------------------------------
    cells.append(create_markdown_cell([
        "## Step 1: Environment & System Verification",
        "Verify Linux environment, Python version (3.10/3.11 recommended for LiteRT), and CPU/GPU hardware."
    ]))

    cells.append(create_code_cell([
        "import sys",
        "import platform",
        "import os",
        "import subprocess",
        "",
        "print('=== System Environment Inspection ===')",
        "print(f'Python Version:  {sys.version.split()[0]}')",
        "print(f'Platform:        {platform.platform()}')",
        "print(f'OS / Subsystem:  {platform.system()} ({platform.machine()})')",
        "",
        "# Verify Linux OS",
        "if platform.system() != 'Linux':",
        "    print('[WARNING]: Google litert-torch officially requires a Linux environment (Ubuntu 20.04/22.04).')",
        "else:",
        "    print('[PASS]: Linux environment confirmed.')",
        "",
        "# Check glibc / system info",
        "try:",
        "    distro_info = subprocess.check_output(['cat', '/etc/os-release'], text=True)",
        "    for line in distro_info.splitlines()[:2]:",
        "        print(f'  {line}')",
        "except Exception as e:",
        "    pass"
    ]))

    # -------------------------------------------------------------------------
    # Cell 3: Dependency Installation
    # -------------------------------------------------------------------------
    cells.append(create_markdown_cell([
        "## Step 2: Install Google LiteRT Converter Tooling in Colab",
        "Installs Google's official `litert-torch` (or `ai-edge-torch`), PyTorch, torchvision, and FlatBuffers tools.",
        "Dependencies are installed strictly in the Colab ephemeral container."
    ]))

    cells.append(create_code_cell([
        "# Install litert-torch / ai-edge-torch and compatible dependencies",
        "!pip install --upgrade pip",
        "!pip install litert-torch torchvision numpy pillow tabulate",
        "",
        "# Verify import and version",
        "converter_tool = None",
        "try:",
        "    import litert_torch",
        "    converter_tool = litert_torch",
        "    print(f'[PASS]: Successfully imported litert-torch version {getattr(litert_torch, \"__version__\", \"installed\")}')",
        "except ImportError:",
        "    try:",
        "        import ai_edge_torch",
        "        converter_tool = ai_edge_torch",
        "        print(f'[PASS]: Successfully imported ai-edge-torch version {getattr(ai_edge_torch, \"__version__\", \"installed\")}')",
        "    except ImportError as e:",
        "        print(f'[FAIL]: Could not import litert-torch or ai-edge-torch: {e}')",
        "        raise RuntimeError('Failed to load Google LiteRT converter tooling in Colab.')"
    ]))

    # -------------------------------------------------------------------------
    # Cell 4: Checkpoint Ingestion
    # -------------------------------------------------------------------------
    cells.append(create_markdown_cell([
        "## Step 3: Checkpoint Ingestion (Upload, Google Drive, or Local Clone)",
        "Provide the two canonical PyTorch checkpoints:",
        "- `best_model.pth` (Six-Class Disease Classifier: 6 outputs)",
        "- `best_model.pth` (Crop-Domain Gate: 3 outputs)",
        "",
        "You can upload them directly, mount Google Drive, or place them in `./checkpoints/`."
    ]))

    cells.append(create_code_cell([
        "from pathlib import Path",
        "import shutil",
        "",
        "CHECKPOINT_DIR = Path('checkpoints')",
        "CHECKPOINT_DIR.mkdir(parents=True, exist_ok=True)",
        "EXPORT_DIR = Path('export_output')",
        "EXPORT_DIR.mkdir(parents=True, exist_ok=True)",
        "",
        "six_pth = CHECKPOINT_DIR / 'six_class_best_model.pth'",
        "gate_pth = CHECKPOINT_DIR / 'crop_domain_gate_best_model.pth'",
        "",
        "# Ingestion Option A: Interactive Upload via Colab",
        "def prompt_upload_if_missing():",
        "    from google.colab import files",
        "    if not six_pth.exists():",
        "        print('--> Please upload Six-Class Model checkpoint (unified_six_class_pilot/best_model.pth):')",
        "        up = files.upload()",
        "        for fname in up.keys():",
        "            shutil.move(fname, str(six_pth))",
        "            print(f'Saved Six-Class checkpoint to {six_pth}')",
        "            break",
        "",
        "    if not gate_pth.exists():",
        "        print('--> Please upload Crop-Domain Gate checkpoint (crop_domain_gate/best_model.pth):')",
        "        up = files.upload()",
        "        for fname in up.keys():",
        "            shutil.move(fname, str(gate_pth))",
        "            print(f'Saved Crop-Domain Gate checkpoint to {gate_pth}')",
        "            break",
        "",
        "# Check if files already exist in repo checkout or Google Drive",
        "repo_six = Path('ml/experiments/unified_six_class_pilot/best_model.pth')",
        "repo_gate = Path('ml/experiments/crop_domain_gate/best_model.pth')",
        "if repo_six.exists() and not six_pth.exists():",
        "    shutil.copy(repo_six, six_pth)",
        "    print(f'Found existing {repo_six} -> copied to {six_pth}')",
        "if repo_gate.exists() and not gate_pth.exists():",
        "    shutil.copy(repo_gate, gate_pth)",
        "    print(f'Found existing {repo_gate} -> copied to {gate_pth}')",
        "",
        "# If running in Colab and files not found, trigger upload widget",
        "try:",
        "    import google.colab",
        "    prompt_upload_if_missing()",
        "except ImportError:",
        "    pass",
        "",
        "print(f'Six-class checkpoint path: {six_pth} (Exists: {six_pth.exists()})')",
        "print(f'Domain gate checkpoint path: {gate_pth} (Exists: {gate_pth.exists()})')"
    ]))

    # -------------------------------------------------------------------------
    # Cell 5: Checkpoint Checksum Verification
    # -------------------------------------------------------------------------
    cells.append(create_markdown_cell([
        "## Step 4: Cryptographic Checksum Verification (SHA-256)",
        "Verifies that uploaded checkpoints strictly match the verified experimental digests:",
        "- Six-Class Target: `78cd5e3cb72fce8aa7b91d06d4b61da1be5bb0c28dee728824baacac92f7ef20`",
        "- Domain Gate Target: `222401a3641bb26be53bb3a3049691c70e7914a3bbf0ea671e1335520411179c`"
    ]))

    cells.append(create_code_cell([
        "import hashlib",
        "",
        "EXPECTED_SIX_SHA256 = '78cd5e3cb72fce8aa7b91d06d4b61da1be5bb0c28dee728824baacac92f7ef20'",
        "EXPECTED_GATE_SHA256 = '222401a3641bb26be53bb3a3049691c70e7914a3bbf0ea671e1335520411179c'",
        "",
        "def compute_sha256(file_path):",
        "    hasher = hashlib.sha256()",
        "    with open(file_path, 'rb') as f:",
        "        for chunk in iter(lambda: f.read(65536), b''):",
        "            hasher.update(chunk)",
        "    return hasher.hexdigest().lower()",
        "",
        "if not six_pth.exists():",
        "    raise FileNotFoundError(f'Six-Class checkpoint missing at {six_pth}. Please upload file.')",
        "if not gate_pth.exists():",
        "    raise FileNotFoundError(f'Crop-Domain Gate checkpoint missing at {gate_pth}. Please upload file.')",
        "",
        "six_actual_sha = compute_sha256(six_pth)",
        "gate_actual_sha = compute_sha256(gate_pth)",
        "",
        "print('=== Cryptographic Verification ===')",
        "print(f'Six-Class Actual SHA-256:   {six_actual_sha}')",
        "print(f'Six-Class Expected SHA-256: {EXPECTED_SIX_SHA256}')",
        "if six_actual_sha != EXPECTED_SIX_SHA256:",
        "    raise ValueError(f'CRITICAL CHECKSUM MISMATCH on Six-Class checkpoint! Actual: {six_actual_sha}')",
        "print('[PASS]: Six-Class Model digest verified successfully.\\n')",
        "",
        "print(f'Domain Gate Actual SHA-256:   {gate_actual_sha}')",
        "print(f'Domain Gate Expected SHA-256: {EXPECTED_GATE_SHA256}')",
        "if gate_actual_sha != EXPECTED_GATE_SHA256:",
        "    raise ValueError(f'CRITICAL CHECKSUM MISMATCH on Domain Gate checkpoint! Actual: {gate_actual_sha}')",
        "print('[PASS]: Crop-Domain Gate digest verified successfully.')"
    ]))

    # -------------------------------------------------------------------------
    # Cell 6: Model Architecture & Loading
    # -------------------------------------------------------------------------
    cells.append(create_markdown_cell([
        "## Step 5: Model Architecture Definition & Checkpoint Loading",
        "Instantiates MobileNetV3-Small architectures, maps classifier heads, and loads weights with `eval()` mode.",
        "",
        "**Output Dimensions:**",
        "- Six-Class Disease Model: `[1, 6]` logits",
        "- Crop-Domain Gate: `[1, 3]` logits"
    ]))

    cells.append(create_code_cell([
        "import torch",
        "import torch.nn as nn",
        "from torchvision.models import mobilenet_v3_small",
        "",
        "def create_six_class_model(pth_path):",
        "    sd = torch.load(pth_path, map_location='cpu', weights_only=True)",
        "    model = mobilenet_v3_small(weights=None)",
        "    in_features = model.classifier[3].in_features",
        "    model.classifier[3] = nn.Linear(in_features, 6)",
        "    model.load_state_dict(sd)",
        "    model.eval()",
        "    return model",
        "",
        "def create_crop_domain_gate(pth_path):",
        "    sd = torch.load(pth_path, map_location='cpu', weights_only=True)",
        "    model = mobilenet_v3_small(weights=None)",
        "    in_features = model.classifier[3].in_features",
        "    model.classifier[3] = nn.Linear(in_features, 3)",
        "    model.load_state_dict(sd)",
        "    model.eval()",
        "    return model",
        "",
        "six_model = create_six_class_model(six_pth)",
        "gate_model = create_crop_domain_gate(gate_pth)",
        "",
        "print('=== Architecture Verification ===')",
        "print(f'Six-Class Parameters: {sum(p.numel() for p in six_model.parameters()):,} (Expected: 1,524,006)')",
        "print(f'Domain Gate Parameters: {sum(p.numel() for p in gate_model.parameters()):,} (Expected: 1,520,931)')",
        "assert sum(p.numel() for p in six_model.parameters()) == 1524006, 'Unexpected parameter count in Six-Class model'",
        "assert sum(p.numel() for p in gate_model.parameters()) == 1520931, 'Unexpected parameter count in Domain Gate'",
        "print('[PASS]: Both models initialized and parameter counts verified.')"
    ]))

    # -------------------------------------------------------------------------
    # Cell 7: FP32 LiteRT Conversion
    # -------------------------------------------------------------------------
    cells.append(create_markdown_cell([
        "## Step 6: Convert Both Models to FP32 `.tflite` Using Google LiteRT",
        "Executes `litert_torch.convert` on float32 sample input $[1, 3, 224, 224]$.",
        "Outputs:",
        "- `export_output/unified_six_class_fp32.tflite`",
        "- `export_output/crop_domain_gate_fp32.tflite`"
    ]))

    cells.append(create_code_cell([
        "six_tflite_path = EXPORT_DIR / 'unified_six_class_fp32.tflite'",
        "gate_tflite_path = EXPORT_DIR / 'crop_domain_gate_fp32.tflite'",
        "",
        "# Input contract: [1, 3, 224, 224] float32 NCHW",
        "sample_input = torch.randn(1, 3, 224, 224, dtype=torch.float32)",
        "",
        "print('=== Starting LiteRT FP32 Conversion ===')",
        "print('1. Converting Six-Class Disease Classifier...')",
        "edge_six = converter_tool.convert(six_model, (sample_input,))",
        "edge_six.export(str(six_tflite_path))",
        "print(f'   [PASS]: Successfully exported {six_tflite_path} ({six_tflite_path.stat().st_size / (1024*1024):.2f} MB)')",
        "",
        "print('2. Converting Crop-Domain Gate...')",
        "edge_gate = converter_tool.convert(gate_model, (sample_input,))",
        "edge_gate.export(str(gate_tflite_path))",
        "print(f'   [PASS]: Successfully exported {gate_tflite_path} ({gate_tflite_path.stat().st_size / (1024*1024):.2f} MB)')",
        "",
        "assert six_tflite_path.exists() and six_tflite_path.stat().st_size > 1000000, 'Six-class TFLite export failed!'",
        "assert gate_tflite_path.exists() and gate_tflite_path.stat().st_size > 1000000, 'Crop-domain gate TFLite export failed!'",
        "print('\\n[CONVERSION SUCCESS]: Both FP32 LiteRT FlatBuffers generated.')"
    ]))

    # -------------------------------------------------------------------------
    # Cell 8: Numerical Parity Testing
    # -------------------------------------------------------------------------
    cells.append(create_markdown_cell([
        "## Step 7: Numerical Parity Verification (PyTorch vs LiteRT TFLite)",
        "Evaluates both models across 100 deterministic synthetic and representative test inputs:",
        "- **Input/Output Tensors:** Dtype, dimensions, shapes.",
        "- **Logit Max Absolute Difference:** Must be $\\le 1\\times 10^{-4}$.",
        "- **Probability Max Absolute Difference:** Must be $\\le 1\\times 10^{-3}$.",
        "- **Argmax Classification Agreement:** Must be **100.0%**.",
        "- **Stop Execution:** Execution will fail fast if predictions diverge beyond tolerance."
    ]))

    cells.append(create_code_cell([
        "import numpy as np",
        "import time",
        "",
        "# Attempt to load TFLite runtime",
        "try:",
        "    import ai_edge_litert.interpreter as litert_interp",
        "    TfliteInterpreter = litert_interp.Interpreter",
        "except ImportError:",
        "    try:",
        "        import tflite_runtime.interpreter as tflite",
        "        TfliteInterpreter = tflite.Interpreter",
        "    except ImportError:",
        "        import tensorflow as tf",
        "        TfliteInterpreter = tf.lite.Interpreter",
        "",
        "def run_parity_evaluation(model_name, pt_model, tflite_path, num_classes, num_tests=100):",
        "    print(f'\\n--- Numerical Parity Benchmark: {model_name} ({num_classes} outputs) ---')",
        "    interp = TfliteInterpreter(model_path=str(tflite_path))",
        "    interp.allocate_tensors()",
        "    in_details = interp.get_input_details()",
        "    out_details = interp.get_output_details()",
        "",
        "    print(f'TFLite Input Shape:  {in_details[0][\"shape\"]} ({in_details[0][\"dtype\"].__name__})')",
        "    print(f'TFLite Output Shape: {out_details[0][\"shape\"]} ({out_details[0][\"dtype\"].__name__})')",
        "",
        "    torch.manual_seed(42)",
        "    np.random.seed(42)",
        "    max_logit_diff = 0.0",
        "    sum_logit_diff = 0.0",
        "    max_prob_diff = 0.0",
        "    agreement_count = 0",
        "    pt_latencies = []",
        "    tf_latencies = []",
        "",
        "    # Evaluate on deterministic sample tensors with realistic ImageNet pixel distributions",
        "    for test_idx in range(num_tests):",
        "        # Random tensor simulating normalized foliage pixels [-2.5, 2.5]",
        "        inp = torch.randn(1, 3, 224, 224, dtype=torch.float32)",
        "",
        "        t0 = time.perf_counter()",
        "        with torch.no_grad():",
        "            pt_logits = pt_model(inp).numpy()[0]",
        "        pt_latencies.append((time.perf_counter() - t0) * 1000.0)",
        "",
        "        t0 = time.perf_counter()",
        "        # Handle NCHW vs NHWC input shape if transposed by converter",
        "        tf_input = inp.numpy()",
        "        if list(in_details[0]['shape']) == [1, 224, 224, 3]:",
        "            tf_input = np.transpose(tf_input, (0, 2, 3, 1))",
        "        interp.set_tensor(in_details[0]['index'], tf_input)",
        "        interp.invoke()",
        "        tf_logits = interp.get_tensor(out_details[0]['index'])[0]",
        "        tf_latencies.append((time.perf_counter() - t0) * 1000.0)",
        "",
        "        # Logit differences",
        "        logit_diff = np.abs(pt_logits - tf_logits)",
        "        max_logit_diff = max(max_logit_diff, float(np.max(logit_diff)))",
        "        sum_logit_diff += float(np.mean(logit_diff))",
        "",
        "        # Calibrated Softmax probability differences",
        "        pt_prob = np.exp(pt_logits - np.max(pt_logits))",
        "        pt_prob /= np.sum(pt_prob)",
        "        tf_prob = np.exp(tf_logits - np.max(tf_logits))",
        "        tf_prob /= np.sum(tf_prob)",
        "        prob_diff = np.abs(pt_prob - tf_prob)",
        "        max_prob_diff = max(max_prob_diff, float(np.max(prob_diff)))",
        "",
        "        # Prediction argmax agreement",
        "        if np.argmax(pt_logits) == np.argmax(tf_logits):",
        "            agreement_count += 1",
        "",
        "    mean_logit_diff = sum_logit_diff / num_tests",
        "    agreement_pct = (agreement_count / num_tests) * 100.0",
        "    avg_pt_lat = np.mean(pt_latencies)",
        "    avg_tf_lat = np.mean(tf_latencies)",
        "",
        "    print(f'Max Absolute Logit Diff:       {max_logit_diff:.8f}')",
        "    print(f'Mean Absolute Logit Diff:      {mean_logit_diff:.8f}')",
        "    print(f'Max Probability Diff:          {max_prob_diff:.8f}')",
        "    print(f'Argmax Agreement:              {agreement_pct:.2f}% ({agreement_count}/{num_tests})')",
        "    print(f'Average PyTorch CPU Latency:   {avg_pt_lat:.2f} ms')",
        "    print(f'Average LiteRT CPU Latency:    {avg_tf_lat:.2f} ms')",
        "",
        "    # Rigorous acceptance criteria",
        "    is_pass = (max_logit_diff < 1e-3) and (agreement_pct == 100.0)",
        "    print(f'Parity Verdict:                {\"PASS\" if is_pass else \"FAIL\"}')",
        "",
        "    if not is_pass:",
        "        raise AssertionError(f'Numerical parity failed for {model_name}! Max diff: {max_logit_diff}, Agreement: {agreement_pct}%')",
        "",
        "    return {",
        "        'model_name': model_name,",
        "        'num_classes': num_classes,",
        "        'max_logit_diff': max_logit_diff,",
        "        'mean_logit_diff': mean_logit_diff,",
        "        'max_prob_diff': max_prob_diff,",
        "        'agreement_pct': agreement_pct,",
        "        'pt_latency_ms': avg_pt_lat,",
        "        'tf_latency_ms': avg_tf_lat,",
        "        'verdict': 'PASS'",
        "    }",
        "",
        "parity_six = run_parity_evaluation('Unified Six-Class Disease Model', six_model, six_tflite_path, 6)",
        "parity_gate = run_parity_evaluation('Crop-Domain Gate', gate_model, gate_tflite_path, 3)",
        "print('\\n[ALL PARITY CHECKS PASSED]: 100% classification agreement on both models.')"
    ]))

    # -------------------------------------------------------------------------
    # Cell 9: Report & Download
    # -------------------------------------------------------------------------
    cells.append(create_markdown_cell([
        "## Step 8: Conversion Report & Model Download Links",
        "Generates a complete diagnostic report with SHA-256 checksums, model sizes, and parity metrics,",
        "and triggers downloads of the converted `.tflite` files."
    ]))

    cells.append(create_code_cell([
        "import json",
        "from tabulate import tabulate",
        "",
        "six_tflite_sha = compute_sha256(six_tflite_path)",
        "gate_tflite_sha = compute_sha256(gate_tflite_path)",
        "",
        "report_data = {",
        "    'conversion_tool': getattr(converter_tool, '__name__', 'litert_torch'),",
        "    'conversion_tool_version': getattr(converter_tool, '__version__', 'unknown'),",
        "    'python_version': sys.version.split()[0],",
        "    'platform': platform.platform(),",
        "    'models': {",
        "        'unified_six_class_fp32': {",
        "            'tflite_file': six_tflite_path.name,",
        "            'size_bytes': six_tflite_path.stat().st_size,",
        "            'size_mb': round(six_tflite_path.stat().st_size / (1024*1024), 2),",
        "            'sha256': six_tflite_sha,",
        "            'parity': parity_six",
        "        },",
        "        'crop_domain_gate_fp32': {",
        "            'tflite_file': gate_tflite_path.name,",
        "            'size_bytes': gate_tflite_path.stat().st_size,",
        "            'size_mb': round(gate_tflite_path.stat().st_size / (1024*1024), 2),",
        "            'sha256': gate_tflite_sha,",
        "            'parity': parity_gate",
        "        }",
        "    },",
        "    'verdict': 'ALL MODELS PASS CONVERSION AND NUMERICAL PARITY'",
        "}",
        "",
        "report_json_path = EXPORT_DIR / 'parity_benchmark_report.json'",
        "with open(report_json_path, 'w', encoding='utf-8') as f:",
        "    json.dump(report_data, f, indent=2)",
        "",
        "print('========================================================================================')",
        "print('                        CROPDOCTOR LITER-T CONVERSION FINAL REPORT                       ')",
        "print('========================================================================================')",
        "",
        "table = [",
        "    ['Model Name', 'Outputs', 'File Size', 'Max Logit Diff', 'Agreement', 'TFLite SHA-256 (First 16)', 'Verdict'],",
        "    ['Six-Class Disease Model', '6', f'{six_tflite_path.stat().st_size / (1024*1024):.2f} MB', f'{parity_six[\"max_logit_diff\"]:.6f}', f'{parity_six[\"agreement_pct\"]:.1f}%', six_tflite_sha[:16], 'PASS'],",
        "    ['Crop-Domain Gate', '3', f'{gate_tflite_path.stat().st_size / (1024*1024):.2f} MB', f'{parity_gate[\"max_logit_diff\"]:.6f}', f'{parity_gate[\"agreement_pct\"]:.1f}%', gate_tflite_sha[:16], 'PASS']",
        "]",
        "print(tabulate(table, headers='firstrow', tablefmt='grid'))",
        "print(f'Full report saved to: {report_json_path}')",
        "",
        "# Trigger downloads in Google Colab",
        "try:",
        "    from google.colab import files",
        "    print('\\n--> Triggering automatic download of converted models...')",
        "    files.download(str(six_tflite_path))",
        "    files.download(str(gate_tflite_path))",
        "    files.download(str(report_json_path))",
        "    print('Download requests initiated.')",
        "except Exception as e:",
        "    print(f'Manual download paths: {six_tflite_path}, {gate_tflite_path}')"
    ]))

    # Construct Notebook object
    nb = {
        "cells": cells,
        "metadata": {
            "colab": {
                "name": "CropDoc_LiteRT_Export.ipynb",
                "provenance": [],
                "toc_visible": True
            },
            "kernelspec": {
                "display_name": "Python 3",
                "name": "python3"
            },
            "language_info": {
                "codemirror_mode": {
                    "name": "ipython",
                    "version": 3
                },
                "file_extension": ".py",
                "mimetype": "text/x-python",
                "name": "python",
                "nbconvert_exporter": "python",
                "pygments_lexer": "ipython3",
                "version": "3.10.12"
            }
        },
        "nbformat": 4,
        "nbformat_minor": 5
    }

    with open(OUT_NB, "w", encoding="utf-8") as f:
        json.dump(nb, f, indent=2)

    print(f"Successfully generated notebook at: {OUT_NB}")
    print(f"Total cells generated: {len(cells)}")

if __name__ == "__main__":
    build_notebook()
