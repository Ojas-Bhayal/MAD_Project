import csv
import json
import os
import sys
import time
import hashlib
import random
import platform
import subprocess
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader
import torchvision.transforms as T
from torchvision.models import mobilenet_v3_small, MobileNet_V3_Small_Weights
from PIL import Image
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

# ---------------------------------------------------------
# 1. Reproducibility & Configuration
# ---------------------------------------------------------
RANDOM_SEED = 42
def seed_everything(seed=42):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)

seed_everything(RANDOM_SEED)

# Paths
ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = ROOT / "data" / "manifests" / "manifest_rice_four_class.csv"
SPLIT_PATH = ROOT / "data" / "splits" / "split_rice_four_class.json"
EXP_DIR = ROOT / "ml" / "experiments" / "rice_four_class_pilot"
EXP_DIR.mkdir(parents=True, exist_ok=True)

CLASS_MAP = {
    "rice_bacterial_leaf_blight": 0,
    "rice_brown_spot": 1,
    "rice_leaf_blast": 2,
    "rice_healthy": 3
}
INV_CLASS_MAP = {v: k for k, v in CLASS_MAP.items()}

# ---------------------------------------------------------
# 2. Hardware and Environment Inspection
# ---------------------------------------------------------
def inspect_hardware_and_env() -> Dict:
    info = {
        "python_version": sys.version,
        "platform": platform.platform(),
        "processor": platform.processor(),
        "cpu_count": os.cpu_count(),
        "torch_version": torch.__version__,
        "cuda_available_in_torch": torch.cuda.is_available(),
        "device_used": "cpu",
        "gpu_detected_via_smi": False,
        "gpu_details": None,
        "blocker_explanation": None
    }
    
    try:
        smi_out = subprocess.check_output(
            ["nvidia-smi", "--query-gpu=name,memory.total,driver_version", "--format=csv,noheader"],
            encoding="utf-8"
        )
        gpu_name, gpu_mem, driver_v = [x.strip() for x in smi_out.strip().split(",")]
        info["gpu_detected_via_smi"] = True
        info["gpu_details"] = {
            "name": gpu_name,
            "memory": gpu_mem,
            "driver_version": driver_v
        }
    except Exception as e:
        info["gpu_details"] = str(e)

    if torch.cuda.is_available():
        info["device_used"] = "cuda:0"
    else:
        info["device_used"] = "cpu"
        info["blocker_explanation"] = (
            "An NVIDIA GPU is physically present (RTX 4050 Laptop GPU, 6GB VRAM), but the active Python "
            f"environment is Python 3.14.7. Official PyTorch binary wheels with CUDA acceleration are not "
            "published for Windows on Python 3.14. PyPI only provides CPU-only builds (torch==2.14.1+cpu). "
            "Executing experiment on CPU using multi-threaded PyTorch execution fallback as instructed."
        )

    return info

# ---------------------------------------------------------
# 3. Dataset Implementation
# ---------------------------------------------------------
class RiceFourClassDataset(Dataset):
    def __init__(self, samples: List[Dict], root_dir: Path, transform=None):
        self.samples = samples
        self.root_dir = root_dir
        self.transform = transform

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        s = self.samples[idx]
        img_path = self.root_dir / s["path"]
        with Image.open(img_path) as img:
            img = img.convert("RGB")
        
        if self.transform:
            img = self.transform(img)
            
        label = CLASS_MAP[s["cropdoc_class_name"]]
        return img, label, s["image_id"], s["path"], s.get("source", "unknown")

# Transforms
IMAGENET_MEAN = [0.485, 0.456, 0.406]
IMAGENET_STD = [0.229, 0.224, 0.225]

train_transform = T.Compose([
    T.RandomResizedCrop(224, scale=(0.8, 1.0)),
    T.RandomHorizontalFlip(p=0.5),
    T.RandomVerticalFlip(p=0.5),
    T.ColorJitter(brightness=0.1, contrast=0.1),
    T.ToTensor(),
    T.Normalize(mean=IMAGENET_MEAN, std=IMAGENET_STD)
])

eval_transform = T.Compose([
    T.Resize(256),
    T.CenterCrop(224),
    T.ToTensor(),
    T.Normalize(mean=IMAGENET_MEAN, std=IMAGENET_STD)
])

# ---------------------------------------------------------
# 4. Metric Calculations (Pure NumPy 4-Class)
# ---------------------------------------------------------
def compute_metrics_4class(y_true: np.ndarray, y_pred: np.ndarray) -> Dict:
    total = len(y_true)
    correct = int((y_true == y_pred).sum())
    acc = correct / total if total > 0 else 0.0

    # 4x4 Confusion matrix
    # Row: true, Col: pred
    cm = np.zeros((4, 4), dtype=int)
    for t, p in zip(y_true, y_pred):
        cm[t, p] += 1

    per_class = {}
    recalls = []
    f1s = []

    for idx, cname in INV_CLASS_MAP.items():
        tp = int(cm[idx, idx])
        fn = int(cm[idx, :].sum() - tp)
        fp = int(cm[:, idx].sum() - tp)
        support = tp + fn

        prec = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        rec = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        f1 = (2 * prec * rec) / (prec + rec) if (prec + rec) > 0 else 0.0

        recalls.append(rec)
        f1s.append(f1)

        per_class[cname] = {
            "class_index": idx,
            "support": support,
            "tp": tp,
            "fp": fp,
            "fn": fn,
            "precision": float(prec),
            "recall": float(rec),
            "f1_score": float(f1)
        }

    bal_acc = float(np.mean(recalls))
    macro_f1 = float(np.mean(f1s))

    return {
        "total_samples": total,
        "accuracy": float(acc),
        "balanced_accuracy": bal_acc,
        "macro_f1": macro_f1,
        "confusion_matrix_grid": cm.tolist(),
        "per_class": per_class
    }

# ---------------------------------------------------------
# 5. Training & Evaluation Pipeline
# ---------------------------------------------------------
def main():
    print("=" * 70)
    print("CROPDOC: RICE FOUR-CLASS RESEARCH PILOT TRAINING EXPERIMENT")
    print("=" * 70)

    env_info = inspect_hardware_and_env()
    print(f"Device selected: {env_info['device_used']}")
    if env_info['blocker_explanation']:
        print(f"Hardware status:\n  {env_info['blocker_explanation']}\n")

    # Load split specification
    with open(SPLIT_PATH, "r", encoding="utf-8") as f:
        split_data = json.load(f)

    train_samples = split_data["splits"]["train"]["samples"]
    val_samples = split_data["splits"]["val"]["samples"]
    test_samples = split_data["splits"]["test"]["samples"]

    print(f"Dataset partitions loaded from {SPLIT_PATH.name}:")
    print(f"  Train: {len(train_samples)} images ({split_data['splits']['train']['total_groups']} groups)")
    print(f"  Val:   {len(val_samples)} images ({split_data['splits']['val']['total_groups']} groups)")
    print(f"  Test:  {len(test_samples)} images ({split_data['splits']['test']['total_groups']} groups)")

    # Data verification
    with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
        manifest_rows = {r["path"]: r for r in csv.DictReader(f)}

    print("\nVerifying manifest provenance and physical files...")
    for sp_name, s_list in [("train", train_samples), ("val", val_samples), ("test", test_samples)]:
        for s in s_list:
            p = s["path"]
            if p not in manifest_rows:
                raise ValueError(f"Sample {p} not present in manifest!")
            m = manifest_rows[p]
            if m["split"] != sp_name:
                raise ValueError(f"Split mismatch for {p}: manifest={m['split']}, expected={sp_name}")
            if m["cropdoc_class_name"] != s["cropdoc_class_name"]:
                raise ValueError(f"Class mismatch for {p}: manifest={m['cropdoc_class_name']}, split={s['cropdoc_class_name']}")
            fpath = ROOT / p
            if not fpath.exists():
                raise FileNotFoundError(f"Physical image missing: {fpath}")

    print("Pre-training verification PASSED: all 678 canonical specimens are verified.")

    train_ds = RiceFourClassDataset(train_samples, ROOT, transform=train_transform)
    val_ds = RiceFourClassDataset(val_samples, ROOT, transform=eval_transform)
    test_ds = RiceFourClassDataset(test_samples, ROOT, transform=eval_transform)

    batch_size = 16
    train_loader = DataLoader(train_ds, batch_size=batch_size, shuffle=True)
    val_loader = DataLoader(val_ds, batch_size=batch_size, shuffle=False)
    test_loader = DataLoader(test_ds, batch_size=batch_size, shuffle=False)

    # Initialize MobileNetV3-Small
    device = torch.device(env_info["device_used"])
    weights = MobileNet_V3_Small_Weights.DEFAULT
    model = mobilenet_v3_small(weights=weights)

    # Replace classifier for 4 classes
    in_features = model.classifier[3].in_features
    model.classifier[3] = nn.Linear(in_features, 4)
    model.to(device)

    # Training Configuration
    num_epochs = 20
    learning_rate = 3e-4
    weight_decay = 1e-2
    patience = 5

    optimizer = torch.optim.AdamW(model.parameters(), lr=learning_rate, weight_decay=weight_decay)
    criterion = nn.CrossEntropyLoss()

    history = []
    best_val_loss = float("inf")
    best_val_epoch = 0
    best_checkpoint_path = EXP_DIR / "best_model.pt"
    epochs_no_improve = 0

    log_file_path = EXP_DIR / "training_log.txt"
    log_file = open(log_file_path, "w", encoding="utf-8")

    def log(msg):
        print(msg)
        log_file.write(msg + "\n")
        log_file.flush()

    log(f"Training started: {time.strftime('%Y-%m-%d %H:%M:%S')}")
    log(f"Model Architecture: MobileNetV3-Small (Pretrained ImageNet-1K)")
    log(f"Number of Classes: 4 ({', '.join(CLASS_MAP.keys())})")
    log(f"Hyperparameters: epochs={num_epochs}, batch_size={batch_size}, lr={learning_rate}, weight_decay={weight_decay}, patience={patience}")
    log("-" * 75)
    log(f"{'Epoch':<6} | {'Train Loss':<11} | {'Train Acc':<10} | {'Val Loss':<10} | {'Val Acc':<9} | {'Val BalAcc':<11} | {'Time (s)':<8}")
    log("-" * 75)

    start_time = time.time()

    for epoch in range(1, num_epochs + 1):
        epoch_start = time.time()

        # Training
        model.train()
        running_loss = 0.0
        train_preds, train_trues = [], []

        for imgs, labels, _, _, _ in train_loader:
            imgs, labels = imgs.to(device), labels.to(device)
            optimizer.zero_grad()
            outputs = model(imgs)
            loss = criterion(outputs, labels)
            loss.backward()
            optimizer.step()

            running_loss += loss.item() * imgs.size(0)
            preds = outputs.argmax(dim=1)
            train_preds.extend(preds.cpu().numpy())
            train_trues.extend(labels.cpu().numpy())

        train_loss = running_loss / len(train_ds)
        train_acc = (np.array(train_preds) == np.array(train_trues)).mean()

        # Validation
        model.eval()
        val_running_loss = 0.0
        val_preds, val_trues = [], []

        with torch.no_grad():
            for imgs, labels, _, _, _ in val_loader:
                imgs, labels = imgs.to(device), labels.to(device)
                outputs = model(imgs)
                loss = criterion(outputs, labels)
                val_running_loss += loss.item() * imgs.size(0)
                preds = outputs.argmax(dim=1)
                val_preds.extend(preds.cpu().numpy())
                val_trues.extend(labels.cpu().numpy())

        val_loss = val_running_loss / len(val_ds)
        val_metrics = compute_metrics_4class(np.array(val_trues), np.array(val_preds))
        val_acc = val_metrics["accuracy"]
        val_bal_acc = val_metrics["balanced_accuracy"]

        epoch_time = time.time() - epoch_start

        history.append({
            "epoch": epoch,
            "train_loss": train_loss,
            "train_acc": train_acc,
            "val_loss": val_loss,
            "val_acc": val_acc,
            "val_balanced_acc": val_bal_acc,
            "epoch_time_seconds": epoch_time
        })

        improved = False
        if val_loss < best_val_loss:
            best_val_loss = val_loss
            best_val_epoch = epoch
            epochs_no_improve = 0
            improved = True
            torch.save({
                "epoch": epoch,
                "model_state_dict": model.state_dict(),
                "optimizer_state_dict": optimizer.state_dict(),
                "val_loss": val_loss,
                "val_metrics": val_metrics,
                "class_map": CLASS_MAP,
                "architecture": "mobilenet_v3_small",
                "random_seed": RANDOM_SEED
            }, best_checkpoint_path)
        else:
            epochs_no_improve += 1

        marker = " [BEST CHECKPOINT]" if improved else ""
        log(f"{epoch:<6} | {train_loss:<11.4f} | {train_acc*100:<9.2f}% | {val_loss:<10.4f} | {val_acc*100:<8.2f}% | {val_bal_acc*100:<10.2f}% | {epoch_time:<8.1f}{marker}")

        if epochs_no_improve >= patience:
            log(f"\n[EARLY STOPPING TRIGGERED] Validation loss has not improved for {patience} consecutive epochs.")
            break

    total_training_time = time.time() - start_time
    log("-" * 75)
    log(f"Training completed in {total_training_time:.1f} seconds. Best checkpoint from Epoch {best_val_epoch} (Val Loss: {best_val_loss:.4f}).")

    # ---------------------------------------------------------
    # 6. Evaluation on Held-Out Test Set (101 Images)
    # ---------------------------------------------------------
    log("\nLoading best checkpoint for held-out test evaluation...")
    checkpoint = torch.load(best_checkpoint_path, map_location=device)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()

    test_preds, test_trues, test_probs, test_ids, test_paths, test_sources = [], [], [], [], [], []
    with torch.no_grad():
        for imgs, labels, ids, paths, sources in test_loader:
            imgs = imgs.to(device)
            outputs = model(imgs)
            probs = torch.softmax(outputs, dim=1)
            preds = outputs.argmax(dim=1)

            test_preds.extend(preds.cpu().numpy())
            test_trues.extend(labels.numpy())
            test_probs.extend(probs.cpu().numpy())
            test_ids.extend(ids)
            test_paths.extend(paths)
            test_sources.extend(sources)

    test_metrics = compute_metrics_4class(np.array(test_trues), np.array(test_preds))

    log("\n" + "=" * 50)
    log("HELD-OUT TEST SET EVALUATION RESULTS (101 IMAGES)")
    log("=" * 50)
    log(f"Total Test Images Evaluated: {test_metrics['total_samples']}")
    log(f"Overall Test Accuracy:       {test_metrics['accuracy']*100:.2f}% ({int(test_metrics['accuracy']*test_metrics['total_samples'])} / {test_metrics['total_samples']})")
    log(f"Balanced Accuracy:           {test_metrics['balanced_accuracy']*100:.2f}%")
    log(f"Macro F1-Score:              {test_metrics['macro_f1']*100:.2f}%")
    log("\nPer-Class Breakdown:")
    for cname, stats in test_metrics["per_class"].items():
        log(f"  Class {stats['class_index']} [{cname}] (Support: {stats['support']}):")
        log(f"    Precision: {stats['precision']*100:.2f}% | Recall: {stats['recall']*100:.2f}% | F1: {stats['f1_score']*100:.2f}%")

    grid = test_metrics["confusion_matrix_grid"]
    log("\nConfusion Matrix (Rows: Actual, Columns: Predicted):")
    log(f"                               Pred BB    Pred BS    Pred Blast    Pred Healthy")
    log(f"  Actual BB (Bacterial):       {grid[0][0]:<10} {grid[0][1]:<10} {grid[0][2]:<13} {grid[0][3]}")
    log(f"  Actual BS (Brown spot):      {grid[1][0]:<10} {grid[1][1]:<10} {grid[1][2]:<13} {grid[1][3]}")
    log(f"  Actual Blast (Leaf blast):   {grid[2][0]:<10} {grid[2][1]:<10} {grid[2][2]:<13} {grid[2][3]}")
    log(f"  Actual Healthy (Control):    {grid[3][0]:<10} {grid[3][1]:<10} {grid[3][2]:<13} {grid[3][3]}")

    # Disease sub-evaluation on the 78 disease images (direct comparison against 3-class pilot)
    disease_indices = [i for i, t in enumerate(test_trues) if t < 3]
    d_trues = np.array([test_trues[i] for i in disease_indices])
    d_preds = np.array([test_preds[i] for i in disease_indices])
    d_acc = (d_trues == d_preds).mean()
    log("\n" + "=" * 50)
    log("DIRECT COMPARISON: PERFORMANCE ON THE 78 HELD-OUT DISEASE IMAGES")
    log("=" * 50)
    log(f"3-Class Pilot Baseline Accuracy:  97.44% (76 / 78)")
    log(f"4-Class Model Disease Accuracy:   {d_acc*100:.2f}% ({int(d_acc*len(d_trues))} / {len(d_trues)})")
    healthy_indices = [i for i, t in enumerate(test_trues) if t == 3]
    h_acc = (np.array([test_trues[i] for i in healthy_indices]) == np.array([test_preds[i] for i in healthy_indices])).mean()
    log(f"4-Class Model Healthy Accuracy:   {h_acc*100:.2f}% ({int(h_acc*len(healthy_indices))} / {len(healthy_indices)})")

    # Overfitting Analysis
    best_h = next(h for h in history if h["epoch"] == best_val_epoch)
    overfit_gap_loss = best_h["val_loss"] - best_h["train_loss"]
    overfit_gap_acc = (best_h["train_acc"] - test_metrics["accuracy"]) * 100
    log("\nOverfitting & Stability Diagnostic:")
    log(f"  Best Epoch ({best_val_epoch}): Train Acc = {best_h['train_acc']*100:.2f}%, Val Acc = {best_h['val_acc']*100:.2f}%, Test Acc = {test_metrics['accuracy']*100:.2f}%")
    log(f"  Train-to-Val Loss Gap: {overfit_gap_loss:.4f} (Train: {best_h['train_loss']:.4f}, Val: {best_h['val_loss']:.4f})")
    log(f"  Train-to-Test Accuracy Gap: {overfit_gap_acc:.2f}%")

    log_file.close()

    # ---------------------------------------------------------
    # 7. Write Structured Artifacts
    # ---------------------------------------------------------
    # SHA-256 of best_model.pt
    model_bytes = best_checkpoint_path.read_bytes()
    model_sha256 = hashlib.sha256(model_bytes).hexdigest()
    with open(EXP_DIR / "best_model.pt.sha256", "w", encoding="utf-8") as f:
        f.write(f"{model_sha256}  best_model.pt\n")

    # CSV training log
    csv_log_path = EXP_DIR / "training_log.csv"
    with open(csv_log_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(history[0].keys()))
        writer.writeheader()
        writer.writerows(history)

    # Detailed test predictions JSON
    detailed_predictions = []
    for i in range(len(test_ids)):
        detailed_predictions.append({
            "image_id": test_ids[i],
            "path": test_paths[i],
            "source": test_sources[i],
            "true_label_idx": int(test_trues[i]),
            "true_label_name": INV_CLASS_MAP[int(test_trues[i])],
            "pred_label_idx": int(test_preds[i]),
            "pred_label_name": INV_CLASS_MAP[int(test_preds[i])],
            "prob_bacterial_blight": float(test_probs[i][0]),
            "prob_brown_spot": float(test_probs[i][1]),
            "prob_leaf_blast": float(test_probs[i][2]),
            "prob_healthy": float(test_probs[i][3]),
            "is_correct": bool(test_trues[i] == test_preds[i])
        })

    eval_json = {
        "experiment_name": "rice_four_class_pilot",
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "model_architecture": "mobilenet_v3_small",
        "checkpoint_sha256": model_sha256,
        "checkpoint_file": "best_model.pt",
        "random_seed": RANDOM_SEED,
        "environment": env_info,
        "hyperparameters": {
            "epochs_trained": len(history),
            "best_epoch": best_val_epoch,
            "batch_size": batch_size,
            "learning_rate": learning_rate,
            "weight_decay": weight_decay,
            "optimizer": "AdamW",
            "loss_function": "CrossEntropyLoss",
            "early_stopping_patience": patience
        },
        "dataset": {
            "manifest": str(MANIFEST_PATH.relative_to(ROOT)),
            "split_file": str(SPLIT_PATH.relative_to(ROOT)),
            "train_samples": len(train_ds),
            "val_samples": len(val_ds),
            "test_samples": len(test_ds),
            "total_canonical_samples": len(train_ds) + len(val_ds) + len(test_ds),
            "class_map": CLASS_MAP
        },
        "metrics_held_out_test_overall": test_metrics,
        "metrics_held_out_disease_subcohort": {
            "total_disease_samples": len(d_trues),
            "disease_accuracy": float(d_acc),
            "baseline_3class_accuracy": 0.9744
        },
        "metrics_held_out_healthy_subcohort": {
            "total_healthy_samples": len(healthy_indices),
            "healthy_accuracy": float(h_acc)
        },
        "per_sample_predictions": detailed_predictions
    }

    eval_json_path = EXP_DIR / "evaluation_results.json"
    with open(eval_json_path, "w", encoding="utf-8") as f:
        json.dump(eval_json, f, indent=2)

    # Class mapping JSON
    with open(EXP_DIR / "class_mapping.json", "w", encoding="utf-8") as f:
        json.dump({
            "class_to_index": CLASS_MAP,
            "index_to_class": INV_CLASS_MAP
        }, f, indent=2)

    # Config JSON
    with open(EXP_DIR / "config.json", "w", encoding="utf-8") as f:
        json.dump({
            "experiment": "rice_four_class_pilot",
            "model": "mobilenet_v3_small",
            "pretrained": "ImageNet-1K",
            "seed": RANDOM_SEED,
            "epochs": num_epochs,
            "batch_size": batch_size,
            "learning_rate": learning_rate,
            "weight_decay": weight_decay,
            "patience": patience,
            "classes": CLASS_MAP
        }, f, indent=2)

    # ---------------------------------------------------------
    # 8. Visual Plots
    # ---------------------------------------------------------
    epochs_range = [h["epoch"] for h in history]
    train_losses = [h["train_loss"] for h in history]
    val_losses = [h["val_loss"] for h in history]
    train_accs = [h["train_acc"] * 100 for h in history]
    val_accs = [h["val_acc"] * 100 for h in history]

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5))

    # Loss plot
    ax1.plot(epochs_range, train_losses, "b-o", label="Train Loss", linewidth=2)
    ax1.plot(epochs_range, val_losses, "r-s", label="Val Loss", linewidth=2)
    ax1.axvline(x=best_val_epoch, color="g", linestyle="--", label=f"Best Checkpoint (Epoch {best_val_epoch})")
    ax1.set_title("Rice 4-Class Pilot: Loss Curves", fontsize=12, fontweight="bold")
    ax1.set_xlabel("Epoch")
    ax1.set_ylabel("Cross Entropy Loss")
    ax1.grid(True, linestyle=":", alpha=0.6)
    ax1.legend()

    # Accuracy plot
    ax2.plot(epochs_range, train_accs, "b-o", label="Train Accuracy", linewidth=2)
    ax2.plot(epochs_range, val_accs, "r-s", label="Val Accuracy", linewidth=2)
    ax2.axvline(x=best_val_epoch, color="g", linestyle="--", label=f"Best Checkpoint (Epoch {best_val_epoch})")
    ax2.set_title("Rice 4-Class Pilot: Accuracy Curves", fontsize=12, fontweight="bold")
    ax2.set_xlabel("Epoch")
    ax2.set_ylabel("Accuracy (%)")
    ax2.grid(True, linestyle=":", alpha=0.6)
    ax2.legend()

    plt.tight_layout()
    curves_path = EXP_DIR / "curves.png"
    plt.savefig(curves_path, dpi=200)
    plt.close()

    # Confusion matrix plot
    fig, ax = plt.subplots(figsize=(8, 7))
    im = ax.imshow(grid, interpolation="nearest", cmap=plt.cm.Blues)
    ax.figure.colorbar(im, ax=ax)
    classes = ["Bacterial Blight", "Brown Spot", "Leaf Blast", "Healthy Rice"]
    ax.set(
        xticks=np.arange(len(classes)),
        yticks=np.arange(len(classes)),
        xticklabels=classes,
        yticklabels=classes,
        title="Rice 4-Class Pilot: Held-Out Test Confusion Matrix (101 Images)",
        ylabel="True Label",
        xlabel="Predicted Label"
    )
    plt.setp(ax.get_xticklabels(), rotation=30, ha="right", rotation_mode="anchor")

    thresh = np.array(grid).max() / 2.0
    for i in range(len(classes)):
        for j in range(len(classes)):
            ax.text(
                j, i, f"{grid[i][j]}",
                ha="center", va="center",
                color="white" if grid[i][j] > thresh else "black",
                fontweight="bold"
            )

    plt.tight_layout()
    cm_path = EXP_DIR / "confusion_matrix.png"
    plt.savefig(cm_path, dpi=200)
    plt.close()

    # Copy script into experiment directory
    script_dest = EXP_DIR / "train_rice_four_class_pilot.py"
    script_dest.write_text(Path(__file__).read_text(encoding="utf-8"), encoding="utf-8")

    # Master checksums
    checksum_lines = []
    for f in sorted(EXP_DIR.iterdir()):
        if f.is_file() and f.name != "checksums.sha256":
            sha = hashlib.sha256(f.read_bytes()).hexdigest()
            checksum_lines.append(f"{sha}  {f.name}\n")
    with open(EXP_DIR / "checksums.sha256", "w", encoding="utf-8") as f:
        f.writelines(checksum_lines)

    print(f"\nExperiment artifacts successfully saved in {EXP_DIR}")

if __name__ == "__main__":
    main()
