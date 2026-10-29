# CropDoc Model Training Recipe & Quantization Specification

**Document:** `docs/ml-repair/TRAINING_RECIPE.md`  
**Authors:** ML Engineering Team  
**Target Architecture:** MobileNetV3-Small / MobileNetV3-Large INT8 Quantized  
**Target Hardware:** Budget Android devices (3–4 GB RAM, MediaTek Helio / Snapdragon 680 / Unisoc)  
**Budget Constraints:** Model Size $\le 4.0\text{ MB}$, Inference Latency $\le 80\text{ ms}$, Zero Runtime Allocations.

---

## 1. Supported Taxonomy (22 Classes)

The model output tensor must have exactly 22 class dimensions in deterministic order:

```text
 0: rice_bacterial_leaf_blight
 1: rice_brown_spot
 2: rice_leaf_blast
 3: rice_healthy
 4: tomato_early_blight
 5: tomato_late_blight
 6: tomato_yellow_leaf_curl_virus
 7: tomato_healthy
 8: potato_early_blight
 9: potato_late_blight
10: potato_healthy
11: cotton_bacterial_blight
12: cotton_healthy
13: maize_common_rust
14: maize_northern_leaf_blight
15: background_not_a_leaf
16: groundnut_early_leaf_spot
17: groundnut_healthy
18: chilli_leaf_curl_virus
19: chilli_healthy
20: soybean_rust
21: soybean_healthy
```

---

## 2. Input/Output Contract

### Input Tensor Details
- **Dimensions:** `[1, 224, 224, 3]` (Batch, Height, Width, Channels)
- **Channel Order:** RGB (Standard Android Bitmap channel order)
- **Data Type:** `uint8` (`DataType.UINT8`, values 0–255) OR quantized `int8` with zero-point and scale parameters.
- **Normalization:** In-graph quantization: $x_{\text{float}} = (x_{\text{uint8}} - \text{zero\_point}) \times \text{scale}$.

### Output Tensor Details
- **Dimensions:** `[1, 224]`
- **Data Type:** `float32` (dequantized class logits) or `int8` (with dequantization scale/zero-point).

---

## 3. Preprocessing Contract (Parity with Android Client)

The training pipeline and Android client **must** use identical preprocessing:
1. **Isotropic Reticle Center Crop:** Extract square region centered on reticle. **DO NOT** use non-isotropic stretching or arbitrary bounding-box distortion (`autoTightCropLeafBounds` is strictly prohibited).
2. **Resize:** Bilinear or Bicubic downsampling to $224 \times 224$ pixels.
3. **No EXIF Rotation Flaws:** Ensure orientation metadata is applied before cropping.

---

## 4. Training Procedure

### Step 1: Base Model Architecture Selection
```python
import tensorflow as tf

base_model = tf.keras.applications.MobileNetV3Small(
    input_shape=(224, 224, 3),
    include_top=False,
    weights="imagenet",
    pooling="avg",
    minimalistic=False
)
base_model.trainable = True

# Fine-tune only the last 30 layers initially
for layer in base_model.layers[:-30]:
    layer.trainable = False

inputs = tf.keras.Input(shape=(224, 224, 3), dtype=tf.uint8)
# Preprocessing layer: converts uint8 [0, 255] to float32 [-1, 1]
x = tf.keras.layers.Rescaling(scale=1.0 / 127.5, offset=-1.0)(inputs)
features = base_model(x)
dropout = tf.keras.layers.Dropout(0.2)(features)
outputs = tf.keras.layers.Dense(22, activation=None, name="logits")(dropout)

model = tf.keras.Model(inputs=inputs, outputs=outputs, name="cropdoc_mobilenetv3_small")
```

### Step 2: Loss Function & Optimizer
- **Loss:** `tf.keras.losses.CategoricalCrossentropy(from_logits=True, label_smoothing=0.1)`
- **Optimizer:** `tf.keras.optimizers.AdamW(learning_rate=1e-4, weight_decay=1e-5)`
- **Learning Rate Schedule:** Cosine decay with 5-epoch linear warmup.
- **Data Augmentation:** Mild random rotations ($\pm 15^\circ$), horizontal flips, random brightness adjustments ($\pm 10\%$). Do not alter chromaticity ratios (e.g. hue shifting green to blue/purple).

---

## 5. Post-Training Full Integer Quantization (INT8)

To achieve maximum inference acceleration via LiteRT's XNNPACK delegate without accuracy degradation:

```python
def representative_dataset_gen():
    # Representative calibration dataset: 300 genuine field images across all 22 classes
    for img_path in calibration_images[:300]:
        img = Image.open(img_path).convert("RGB").resize((224, 224))
        arr = np.array(img, dtype=np.uint8)
        yield [np.expand_dims(arr, axis=0)]

converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.representative_dataset = representative_dataset_gen
converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
converter.inference_input_type = tf.uint8
converter.inference_output_type = tf.float32  # Direct logits for ConfidenceCalibrator

tflite_quant_model = converter.convert()

with open("models/v2/cropdoc_leaf_v2_int8.tflite", "wb") as f:
    f.write(tflite_quant_model)
```

---

## 6. SHA-256 Locking & Verification Artifacts

Once exported, model and label artifacts must be hashed and registered:
```bash
sha256sum models/v2/cropdoc_leaf_v2_int8.tflite > models/v2/cropdoc_leaf_v2_int8.tflite.sha256
sha256sum models/v2/labels.txt > models/v2/labels.txt.sha256
```

Both hashes are verified by `ModelValidator` and `ModelUpdateManager` during OTA staging.
