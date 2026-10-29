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
MANIFEST_PATH = ROOT / "data" / "manifests" / "manifest_cotton_staged.csv"
SPLIT_PATH = ROOT / "data" / "splits" / "split_mendeley_cotton_2025.json"
EXP_DIR = ROOT / "ml" / "experiments" / "cotton_binary_pilot"
EXP_DIR.mkdir(parents=True, exist_ok=True)

CLASS_MAP = {
    "cotton_bacterial_blight": 0,
    "cotton_healthy": 1
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
    
    # Check nvidia-smi
    try:
        smi_out = subprocess.check_output(["nvidia-smi", "--query-gpu=name,memory.total,driver_version", "--format=csv,noheader"], encoding="utf-8")
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
class CottonBinaryDataset(Dataset):
    def __init__(self, samples: List[Dict], root_dir: Path, transform=None):
        self.samples = samples
        self.root_dir = root_dir
        self.transform = transform

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        s = self.samples[idx]
        img_path = self.root_dir / s["staged_path"]
        with Image.open(img_path) as img:
            img = img.convert("RGB")
        
        if self.transform:
            img = self.transform(img)
            
        label = CLASS_MAP[s["cropdoc_class_name"]]
        return img, label, s["image_id"], s["staged_path"]

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
# 4. Metric Calculations (Pure NumPy)
# ---------------------------------------------------------
def compute_metrics(y_true: np.ndarray, y_pred: np.ndarray) -> Dict:
    # Class 0: bacterial, Class 1: healthy
    total = len(y_true)
    correct = int((y_true == y_pred).sum())
    acc = correct / total if total > 0 else 0.0

    # Confusion matrix
    # Row: true, Col: pred
    # [[C0_true_as_C0, C0_true_as_C1], [C1_true_as_C0, C1_true_as_C1]]
    tp = int(((y_true == 0) & (y_pred == 0)).sum())
    fn = int(((y_true == 0) & (y_pred == 1)).sum())
    fp = int(((y_true == 1) & (y_pred == 0)).sum())
    tn = int(((y_true == 1) & (y_pred == 1)).sum())

    # Per-class metrics
    # Class 0
    p0 = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    r0 = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1_0 = (2 * p0 * r0) / (p0 + r0) if (p0 + r0) > 0 else 0.0

    # Class 1
    p1 = tn / (tn + fn) if (tn + fn) > 0 else 0.0
    r1 = tn / (tn + fp) if (tn + fp) > 0 else 0.0
    f1_1 = (2 * p1 * r1) / (p1 + r1) if (p1 + r1) > 0 else 0.0

    # Balanced accuracy (macro recall)
    bal_acc = (r0 + r1) / 2.0
    macro_f1 = (f1_0 + f1_1) / 2.0

    return {
        "total_samples": total,
        "accuracy": acc,
        "balanced_accuracy": bal_acc,
        "macro_f1": macro_f1,
        "confusion_matrix": {
            "c0_bacterial_true": {"pred_bacterial": tp, "pred_healthy": fn},
            "c1_healthy_true": {"pred_bacterial": fp, "pred_healthy": tn}
        },
        "confusion_matrix_grid": [[tp, fn], [fp, tn]],
        "class_0_cotton_bacterial_blight": {
            "support": tp + fn,
            "precision": p0,
            "recall": r0,
            "f1_score": f1_0
        },
        "class_1_cotton_healthy": {
            "support": tn + fp,
            "precision": p1,
            "recall": r1,
            "f1_score": f1_1
        }
    }

# ---------------------------------------------------------
# 5. Training & Evaluation Pipeline
# ---------------------------------------------------------
def main():
    print("=" * 70)
    print("CROPDOC: COTTON BINARY PILOT MODEL TRAINING EXPERIMENT")
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

    train_ds = CottonBinaryDataset(train_samples, ROOT, transform=train_transform)
    val_ds = CottonBinaryDataset(val_samples, ROOT, transform=eval_transform)
    test_ds = CottonBinaryDataset(test_samples, ROOT, transform=eval_transform)

    batch_size = 16
    train_loader = DataLoader(train_ds, batch_size=batch_size, shuffle=True)
    val_loader = DataLoader(val_ds, batch_size=batch_size, shuffle=False)
    test_loader = DataLoader(test_ds, batch_size=batch_size, shuffle=False)

    # Initialize MobileNetV3-Small
    device = torch.device(env_info["device_used"])
    weights = MobileNet_V3_Small_Weights.DEFAULT
    model = mobilenet_v3_small(weights=weights)

    # Replace classifier for 2 classes
    in_features = model.classifier[3].in_features
    model.classifier[3] = nn.Linear(in_features, 2)
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
    log(f"Hyperparameters: epochs={num_epochs}, batch_size={batch_size}, lr={learning_rate}, weight_decay={weight_decay}, patience={patience}")
    log("-" * 70)
    log(f"{'Epoch':<6} | {'Train Loss':<11} | {'Train Acc':<10} | {'Val Loss':<10} | {'Val Acc':<9} | {'Val BalAcc':<11} | {'Time (s)':<8}")
    log("-" * 70)

    start_time = time.time()

    for epoch in range(1, num_epochs + 1):
        epoch_start = time.time()

        # Training
        model.train()
        running_loss = 0.0
        train_preds, train_trues = [], []

        for imgs, labels, _, _ in train_loader:
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
            for imgs, labels, _, _ in val_loader:
                imgs, labels = imgs.to(device), labels.to(device)
                outputs = model(imgs)
                loss = criterion(outputs, labels)
                val_running_loss += loss.item() * imgs.size(0)
                preds = outputs.argmax(dim=1)
                val_preds.extend(preds.cpu().numpy())
                val_trues.extend(labels.cpu().numpy())

        val_loss = val_running_loss / len(val_ds)
        val_metrics = compute_metrics(np.array(val_trues), np.array(val_preds))
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
    log("-" * 70)
    log(f"Training completed in {total_training_time:.1f} seconds. Best checkpoint from Epoch {best_val_epoch} (Val Loss: {best_val_loss:.4f}).")

    # ---------------------------------------------------------
    # 6. Evaluation on Held-Out Test Set
    # ---------------------------------------------------------
    log("\nLoading best checkpoint for held-out test evaluation...")
    checkpoint = torch.load(best_checkpoint_path, map_location=device)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()

    test_preds, test_trues, test_probs, test_ids, test_paths = [], [], [], [], []
    with torch.no_grad():
        for imgs, labels, ids, paths in test_loader:
            imgs = imgs.to(device)
            outputs = model(imgs)
            probs = torch.softmax(outputs, dim=1)
            preds = outputs.argmax(dim=1)

            test_preds.extend(preds.cpu().numpy())
            test_trues.extend(labels.numpy())
            test_probs.extend(probs.cpu().numpy())
            test_ids.extend(ids)
            test_paths.extend(paths)

    test_metrics = compute_metrics(np.array(test_trues), np.array(test_preds))

    log("\n" + "=" * 50)
    log("HELD-OUT TEST SET EVALUATION RESULTS")
    log("=" * 50)
    log(f"Total Test Images Evaluated: {test_metrics['total_samples']}")
    log(f"Overall Test Accuracy:       {test_metrics['accuracy']*100:.2f}% ({int(test_metrics['accuracy']*test_metrics['total_samples'])} / {test_metrics['total_samples']})")
    log(f"Balanced Accuracy:           {test_metrics['balanced_accuracy']*100:.2f}%")
    log(f"Macro F1-Score:              {test_metrics['macro_f1']*100:.2f}%")
    log("\nPer-Class Breakdown:")
    c0 = test_metrics["class_0_cotton_bacterial_blight"]
    c1 = test_metrics["class_1_cotton_healthy"]
    log(f"  Class 0 [cotton_bacterial_blight] (Support: {c0['support']}):")
    log(f"    Precision: {c0['precision']*100:.2f}% | Recall: {c0['recall']*100:.2f}% | F1: {c0['f1_score']*100:.2f}%")
    log(f"  Class 1 [cotton_healthy] (Support: {c1['support']}):")
    log(f"    Precision: {c1['precision']*100:.2f}% | Recall: {c1['recall']*100:.2f}% | F1: {c1['f1_score']*100:.2f}%")
    log("\nConfusion Matrix (Rows: Actual, Columns: Predicted):")
    grid = test_metrics["confusion_matrix_grid"]
    log(f"                         Pred Bacterial    Pred Healthy")
    log(f"  Actual Bacterial:      {grid[0][0]:<17} {grid[0][1]}")
    log(f"  Actual Healthy:        {grid[1][0]:<17} {grid[1][1]}")

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
            "staged_path": test_paths[i],
            "true_label_idx": int(test_trues[i]),
            "true_label_name": INV_CLASS_MAP[int(test_trues[i])],
            "pred_label_idx": int(test_preds[i]),
            "pred_label_name": INV_CLASS_MAP[int(test_preds[i])],
            "prob_bacterial_blight": float(test_probs[i][0]),
            "prob_healthy": float(test_probs[i][1]),
            "is_correct": bool(test_trues[i] == test_preds[i])
        })

    eval_json = {
        "experiment_name": "cotton_binary_pilot",
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "model_architecture": "mobilenet_v3_small",
        "checkpoint_sha256": model_sha256,
        "checkpoint_file": "best_model.pt",
        "random_seed": RANDOM_SEED,
        "environment": env_info,
        "hyperparameters": {
            "num_epochs": num_epochs,
            "batch_size": batch_size,
            "learning_rate": learning_rate,
            "weight_decay": weight_decay,
            "early_stopping_patience": patience,
            "input_resolution": "224x224",
            "pretrained_weights": "ImageNet-1K (MobileNet_V3_Small_Weights.DEFAULT)"
        },
        "dataset_split": {
            "train_samples": len(train_ds),
            "val_samples": len(val_ds),
            "test_samples": len(test_ds),
            "split_file": str(SPLIT_PATH)
        },
        "best_epoch": best_val_epoch,
        "best_val_loss": best_val_loss,
        "test_metrics": test_metrics,
        "test_predictions": detailed_predictions
    }

    with open(EXP_DIR / "evaluation_results.json", "w", encoding="utf-8") as f:
        json.dump(eval_json, f, indent=2)

    # Metadata JSON
    metadata = {
        "experiment": "cotton_binary_pilot",
        "status": "COMPLETED_PILOT_RESEARCH_ONLY",
        "diagnostic_status": "UNVALIDATED_PILOT (UNREVIEWED_SOURCE_LABELS)",
        "production_approval": "BLOCKED",
        "class_mapping": CLASS_MAP,
        "classes": list(CLASS_MAP.keys()),
        "input_tensor_shape": [1, 3, 224, 224],
        "normalization": {
            "mean": IMAGENET_MEAN,
            "std": IMAGENET_STD
        },
        "checkpoint_file": "best_model.pt",
        "checkpoint_sha256": model_sha256,
        "created_at": time.strftime("%Y-%m-%d %H:%M:%S")
    }
    with open(EXP_DIR / "model_metadata.json", "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)

    # Plot Curves
    epochs_range = [h["epoch"] for h in history]
    plt.figure(figsize=(12, 5))

    # Loss subplot
    plt.subplot(1, 2, 1)
    plt.plot(epochs_range, [h["train_loss"] for h in history], "o-", label="Train Loss", color="#1f77b4")
    plt.plot(epochs_range, [h["val_loss"] for h in history], "s-", label="Val Loss", color="#ff7f0e")
    plt.axvline(best_val_epoch, linestyle="--", color="gray", label=f"Best Checkpoint (Ep {best_val_epoch})")
    plt.title("Cross-Entropy Loss vs. Epochs")
    plt.xlabel("Epoch")
    plt.ylabel("Loss")
    plt.grid(True, linestyle="--", alpha=0.6)
    plt.legend()

    # Accuracy subplot
    plt.subplot(1, 2, 2)
    plt.plot(epochs_range, [h["train_acc"] * 100 for h in history], "o-", label="Train Accuracy", color="#1f77b4")
    plt.plot(epochs_range, [h["val_acc"] * 100 for h in history], "s-", label="Val Accuracy", color="#ff7f0e")
    plt.plot(epochs_range, [h["val_balanced_acc"] * 100 for h in history], "^-", label="Val Balanced Acc", color="#2ca02c")
    plt.axvline(best_val_epoch, linestyle="--", color="gray", label=f"Best Checkpoint (Ep {best_val_epoch})")
    plt.title("Classification Accuracy vs. Epochs")
    plt.xlabel("Epoch")
    plt.ylabel("Accuracy (%)")
    plt.grid(True, linestyle="--", alpha=0.6)
    plt.legend()

    plt.tight_layout()
    plot_path = EXP_DIR / "training_curves.png"
    plt.savefig(plot_path, dpi=180)
    plt.close()

    print(f"\nAll experiment artifacts successfully saved under: {EXP_DIR}")

if __name__ == "__main__":
    main()
