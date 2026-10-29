import os
import sys
import csv
import json
import time
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

ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = ROOT / "data" / "manifests" / "manifest_groundnut.csv"
SPLIT_PATH = ROOT / "data" / "splits" / "split_groundnut.json"
EXP_DIR = ROOT / "ml" / "experiments" / "groundnut_binary_pilot"
EXP_DIR.mkdir(parents=True, exist_ok=True)

CLASS_MAP = {
    "groundnut_early_leaf_spot": 0,
    "groundnut_healthy": 1
}
INV_CLASS_MAP = {v: k for k, v in CLASS_MAP.items()}

# ---------------------------------------------------------
# 2. Hardware and Environment Inspection
# ---------------------------------------------------------
def inspect_hardware() -> Dict:
    info = {
        "python_version": sys.version,
        "platform": platform.platform(),
        "processor": platform.processor(),
        "cpu_count": os.cpu_count(),
        "torch_version": torch.__version__,
        "cuda_available": torch.cuda.is_available(),
        "device_used": "cpu"
    }
    print("Environment Info:", json.dumps(info, indent=2))
    return info

# ---------------------------------------------------------
# 3. Dataset Loader
# ---------------------------------------------------------
class GroundnutDataset(Dataset):
    def __init__(self, items: List[Dict], transform=None):
        self.items = items
        self.transform = transform

    def __len__(self):
        return len(self.items)

    def __getitem__(self, idx):
        item = self.items[idx]
        img_path = ROOT / item["path"]
        with Image.open(img_path) as img:
            img = img.convert("RGB")
            if self.transform:
                img = self.transform(img)
        label = CLASS_MAP[item["cropdoc_class_name"]]
        return img, label, item["path"], item["image_id"]

def load_data():
    print("Loading manifest and splits...")
    with open(SPLIT_PATH, "r", encoding="utf-8") as f:
        split_data = json.load(f)
        
    train_paths = set(split_data["splits"]["train"])
    val_paths = set(split_data["splits"]["val"])
    test_paths = set(split_data["splits"]["test"])
    
    train_items, val_items, test_items = [], [], []
    with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row["usable_for_cropdoc_training"] != "True":
                continue
            p = row["path"]
            if p in train_paths:
                train_items.append(row)
            elif p in val_paths:
                val_items.append(row)
            elif p in test_paths:
                test_items.append(row)
                
    print(f"Loaded {len(train_items)} train, {len(val_items)} val, {len(test_items)} test items.")
    return train_items, val_items, test_items

# ---------------------------------------------------------
# 4. Model Training Pipeline
# ---------------------------------------------------------
def train_model():
    env_info = inspect_hardware()
    train_items, val_items, test_items = load_data()
    
    # Transforms
    train_transform = T.Compose([
        T.RandomResizedCrop(224, scale=(0.8, 1.0)),
        T.RandomHorizontalFlip(),
        T.RandomVerticalFlip(),
        T.ColorJitter(brightness=0.1, contrast=0.1),
        T.ToTensor(),
        T.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
    ])
    
    eval_transform = T.Compose([
        T.Resize(256),
        T.CenterCrop(224),
        T.ToTensor(),
        T.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
    ])
    
    train_ds = GroundnutDataset(train_items, transform=train_transform)
    val_ds = GroundnutDataset(val_items, transform=eval_transform)
    test_ds = GroundnutDataset(test_items, transform=eval_transform)
    
    batch_size = 32
    train_loader = DataLoader(train_ds, batch_size=batch_size, shuffle=True, num_workers=0)
    val_loader = DataLoader(val_ds, batch_size=batch_size, shuffle=False, num_workers=0)
    test_loader = DataLoader(test_ds, batch_size=batch_size, shuffle=False, num_workers=0)
    
    # Build model: MobileNetV3-Small
    print("Building MobileNetV3-Small with cached weights...")
    weights = MobileNet_V3_Small_Weights.DEFAULT
    model = mobilenet_v3_small(weights=weights)
    
    # Replace classifier head for 2 classes
    in_features = model.classifier[3].in_features
    model.classifier[3] = nn.Linear(in_features, len(CLASS_MAP))
    
    device = torch.device("cpu")
    model.to(device)
    
    criterion = nn.CrossEntropyLoss()
    optimizer = torch.optim.AdamW(model.parameters(), lr=1e-4, weight_decay=1e-2)
    
    epochs = 6
    history = {
        "train_loss": [], "train_acc": [],
        "val_loss": [], "val_acc": []
    }
    
    best_val_loss = float("inf")
    best_model_path = EXP_DIR / "best_model.pth"
    
    print("\nStarting Training...")
    start_time = time.time()
    
    for epoch in range(1, epochs + 1):
        t0 = time.time()
        # Train
        model.train()
        t_loss = 0.0
        t_corr = 0
        total_t = 0
        for imgs, labels, _, _ in train_loader:
            imgs, labels = imgs.to(device), labels.to(device)
            optimizer.zero_grad()
            outputs = model(imgs)
            loss = criterion(outputs, labels)
            loss.backward()
            optimizer.step()
            
            t_loss += loss.item() * imgs.size(0)
            preds = outputs.argmax(dim=1)
            t_corr += (preds == labels).sum().item()
            total_t += imgs.size(0)
            
        epoch_t_loss = t_loss / total_t
        epoch_t_acc = t_corr / total_t
        
        # Validation
        model.eval()
        v_loss = 0.0
        v_corr = 0
        total_v = 0
        with torch.no_grad():
            for imgs, labels, _, _ in val_loader:
                imgs, labels = imgs.to(device), labels.to(device)
                outputs = model(imgs)
                loss = criterion(outputs, labels)
                v_loss += loss.item() * imgs.size(0)
                preds = outputs.argmax(dim=1)
                v_corr += (preds == labels).sum().item()
                total_v += imgs.size(0)
                
        epoch_v_loss = v_loss / total_v
        epoch_v_acc = v_corr / total_v
        
        history["train_loss"].append(epoch_t_loss)
        history["train_acc"].append(epoch_t_acc)
        history["val_loss"].append(epoch_v_loss)
        history["val_acc"].append(epoch_v_acc)
        
        elapsed = time.time() - t0
        print(f"Epoch {epoch:02d}/{epochs:02d} [{elapsed:.1f}s] - "
              f"Train Loss: {epoch_t_loss:.4f}, Train Acc: {epoch_t_acc*100:.2f}% | "
              f"Val Loss: {epoch_v_loss:.4f}, Val Acc: {epoch_v_acc*100:.2f}%")
              
        if epoch_v_loss < best_val_loss:
            best_val_loss = epoch_v_loss
            torch.save(model.state_dict(), best_model_path)
            print(f"  --> Saved new best model (Val Loss: {best_val_loss:.4f})")
            
    total_duration = time.time() - start_time
    print(f"\nTraining completed in {total_duration:.1f}s.")
    
    # Load best model for evaluation
    model.load_state_dict(torch.load(best_model_path))
    model.eval()
    
    # ---------------------------------------------------------
    # 5. Held-Out Test Evaluation
    # ---------------------------------------------------------
    print("\nRunning Held-Out Test Set Evaluation...")
    all_preds, all_labels, all_probs, all_paths = [], [], [], []
    with torch.no_grad():
        for imgs, labels, paths, ids in test_loader:
            imgs = imgs.to(device)
            outputs = model(imgs)
            probs = torch.softmax(outputs, dim=1)
            preds = probs.argmax(dim=1)
            
            all_preds.extend(preds.cpu().numpy())
            all_labels.extend(labels.numpy())
            all_probs.extend(probs.cpu().numpy())
            all_paths.extend(paths)
            
    all_preds = np.array(all_preds)
    all_labels = np.array(all_labels)
    all_probs = np.array(all_probs)
    
    # Compute metrics
    total_test = len(all_labels)
    overall_acc = (all_preds == all_labels).sum() / total_test
    
    # Confusion matrix: rows=actual, cols=predicted
    cm = np.zeros((len(CLASS_MAP), len(CLASS_MAP)), dtype=int)
    for t, p in zip(all_labels, all_preds):
        cm[t, p] += 1
        
    per_class_metrics = {}
    for cname, cidx in CLASS_MAP.items():
        tp = cm[cidx, cidx]
        fp = cm[:, cidx].sum() - tp
        fn = cm[cidx, :].sum() - tp
        tn = total_test - tp - fp - fn
        
        prec = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        rec = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        f1 = 2 * prec * rec / (prec + rec) if (prec + rec) > 0 else 0.0
        
        per_class_metrics[cname] = {
            "support": int(cm[cidx, :].sum()),
            "precision": float(prec),
            "recall": float(rec),
            "f1_score": float(f1),
            "tp": int(tp), "fp": int(fp), "fn": int(fn), "tn": int(tn)
        }
        
    macro_f1 = float(np.mean([m["f1_score"] for m in per_class_metrics.values()]))
    
    print(f"Overall Test Accuracy: {overall_acc*100:.2f}% ({overall_acc:.4f})")
    print(f"Macro F1-Score: {macro_f1*100:.2f}% ({macro_f1:.4f})")
    print("\nConfusion Matrix [rows=actual, cols=predicted]:")
    print(f"Labels: {[INV_CLASS_MAP[i] for i in range(len(CLASS_MAP))]}")
    print(cm)
    
    # Save predictions CSV
    preds_csv_path = EXP_DIR / "test_predictions.csv"
    with open(preds_csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["path", "true_class", "pred_class", "prob_early_leaf_spot", "prob_healthy", "is_correct"])
        for p, t, pr, prob in zip(all_paths, all_labels, all_preds, all_probs):
            writer.writerow([p, INV_CLASS_MAP[t], INV_CLASS_MAP[pr], f"{prob[0]:.4f}", f"{prob[1]:.4f}", t == pr])
            
    # Save metrics JSON
    metrics = {
        "experiment_name": "groundnut_binary_pilot",
        "dataset_name": "mendeley_groundnut_v3",
        "model_architecture": "mobilenet_v3_small",
        "random_seed": RANDOM_SEED,
        "classes": CLASS_MAP,
        "hardware_environment": env_info,
        "training_duration_seconds": round(total_duration, 2),
        "dataset_counts": {
            "train": len(train_items),
            "val": len(val_items),
            "test": len(test_items),
            "total_clean": len(train_items) + len(val_items) + len(test_items)
        },
        "history": history,
        "test_results": {
            "overall_accuracy": round(float(overall_acc), 4),
            "macro_f1": round(float(macro_f1), 4),
            "confusion_matrix": cm.tolist(),
            "per_class": per_class_metrics
        }
    }
    with open(EXP_DIR / "metrics.json", "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)
        
    # Plot Training Curves
    plt.figure(figsize=(10, 4))
    plt.subplot(1, 2, 1)
    plt.plot(range(1, epochs + 1), history["train_loss"], label="Train Loss", marker="o")
    plt.plot(range(1, epochs + 1), history["val_loss"], label="Val Loss", marker="s")
    plt.title("Loss Curves")
    plt.xlabel("Epoch")
    plt.ylabel("Cross-Entropy Loss")
    plt.legend()
    plt.grid(True, alpha=0.3)
    
    plt.subplot(1, 2, 2)
    plt.plot(range(1, epochs + 1), [a*100 for a in history["train_acc"]], label="Train Acc", marker="o")
    plt.plot(range(1, epochs + 1), [a*100 for a in history["val_acc"]], label="Val Acc", marker="s")
    plt.title("Accuracy Curves")
    plt.xlabel("Epoch")
    plt.ylabel("Accuracy (%)")
    plt.legend()
    plt.grid(True, alpha=0.3)
    
    plt.tight_layout()
    plt.savefig(EXP_DIR / "training_curves.png", dpi=150)
    plt.close()
    
    # Plot Confusion Matrix
    plt.figure(figsize=(6, 5))
    plt.imshow(cm, interpolation="nearest", cmap=plt.cm.Blues)
    plt.title("Held-Out Test Confusion Matrix")
    plt.colorbar()
    tick_marks = np.arange(len(CLASS_MAP))
    plt.xticks(tick_marks, [INV_CLASS_MAP[i] for i in range(len(CLASS_MAP))], rotation=20, ha="right")
    plt.yticks(tick_marks, [INV_CLASS_MAP[i] for i in range(len(CLASS_MAP))])
    
    thresh = cm.max() / 2.0
    for i in range(cm.shape[0]):
        for j in range(cm.shape[1]):
            plt.text(j, i, format(cm[i, j], "d"),
                     horizontalalignment="center",
                     color="white" if cm[i, j] > thresh else "black")
    plt.ylabel("Actual Label")
    plt.xlabel("Predicted Label")
    plt.tight_layout()
    plt.savefig(EXP_DIR / "confusion_matrix.png", dpi=150)
    plt.close()
    
    print("\nSaved artifacts to:", EXP_DIR)
    return metrics

if __name__ == "__main__":
    train_model()
