# CropDoc Preprocessing Contract Specification

**Document:** `docs/ml-repair/PREPROCESSING_CONTRACT.md`  
**Contract ID:** `cropdoc_preprocessing_v1`  
**Version:** `1.0.0`  
**Status:** **ACTIVE CONTRACT FOR V1 & V2 MODELS**

---

## 1. Specification Field-by-Field

| Field | Contract Specification | Rationale & Android/Python Parity |
| :--- | :--- | :--- |
| **Input Shape** | `[1, 224, 224, 3]` | Fixed MobileNetV3 standard resolution (Batch, Height, Width, Channels) |
| **Color Space** | `sRGB` | Standard 8-bit uncompressed color space |
| **Channel Order** | `RGB` | Matches Android `Bitmap.getPixels` extraction: `(pixel >> 16) & 0xFF` (R), `(pixel >> 8) & 0xFF` (G), `pixel & 0xFF` (B) |
| **Orientation Handling** | `EXIF_TRANSPOSE_FIRST` | EXIF tag `0x0112` parsed before cropping. Android: `LeafPreprocessor.exifOrientationToDegrees()`. Python: `PIL.ImageOps.exif_transpose()` |
| **Crop Geometry** | `CENTER_SQUARE_RETICLE_CROP` | Square bounding box centered on reticle coordinates. `span = min(W, H)`. Non-isotropic stretching (`autoTightCropLeafBounds`) is strictly prohibited |
| **Resize Method** | `BILINEAR` | Bilinear interpolation. Android: `Bitmap.createScaledBitmap(..., filter=true)`. Python: `Image.Resampling.BILINEAR` |
| **Antialiasing** | `true` | Low-pass anti-aliasing filtering active during downsampling |
| **Data Type** | `uint8` (`0` to `255`) | INT8 quantized model inputs accept raw unsigned bytes |
| **In-Graph Normalization** | $x_{\text{float}} = (x_{\text{uint8}} - 128) \times \frac{1}{128}$ | Scale: `0.0078125`, Zero-point: `128` |

---

## 2. Comparison: evaluate_model.py vs LeafPreprocessor.java

Prior to this repair, `ml/eval/evaluate_model.py` squashed arbitrary rectangular images directly to $224 \times 224$ without reticle cropping or EXIF handling, whereas `LeafPreprocessor.java` performed reticle extraction.

Both have now been aligned to `cropdoc_preprocessing_v1`:
- Both apply EXIF transposition first.
- Both extract an isotropic square reticle/center region before scaling.
- Both use bilinear interpolation.
- Both populate byte tensors in `RGB` order.
