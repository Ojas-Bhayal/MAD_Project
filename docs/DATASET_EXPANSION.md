# CropDoc: Dataset Expansion Guide

**Purpose:** a safe, step-by-step plan to grow the CropDoc training dataset (more images, more field realism, more crops and diseases) **without breaking anything that already works**: the current model, the app, Firebase data, or earlier docs.

**Last researched:** 18 Oct 2026. Dataset facts and licences in section 4 come from the sources listed at the end. Licences change and mirrors disagree, so **re-check each licence at the original source before using any dataset, and record the decision** (section 6.5).

---

## 0. Summary

1. **Never edit the current dataset, model or labels in place.** Freeze them as `v1`. Everything new is added beside them as `v2`.
2. **Biggest weakness to fix:** PlantVillage-style data is photographed in a lab on plain backgrounds. Models trained mostly on it score very high on it but drop on real field photos. Expansion should therefore add **field images**, not more lab images.
3. **Add data in this order:** (a) audit and freeze what exists, (b) fill gaps with openly licensed field datasets, (c) collect your own labelled field photos for the crops that matter in India, (d) clean, de-duplicate and split by group, (e) retrain as a **new model version**, (f) release only if it passes the regression gates in section 10.
4. **The app only needs three artifacts to change:** model file, labels file, and advice content for any new class. No app code should change if the classifier reads the class count from the labels file (check this, section 11.1).

---

## 1. Golden rules (what keeps previous work safe)

| # | Rule | Why |
|---|------|-----|
| 1 | **Additive only.** Never overwrite, rename, re-order or delete files in the existing dataset, model, labels or notebook. | The shipped app depends on the exact class order of the current model. |
| 2 | **Freeze v1.** Record a hash manifest of every v1 image and the v1 train/val/test split, and make the folder read-only. | Gives you a baseline to compare against and a way to roll back. |
| 3 | **Never change class indices.** New classes are appended after the last existing index, or a new model and labels file ship together as one versioned pair. | Output index N must always mean the same disease for a given model version. |
| 4 | **The v1 test set is sacred.** Never train on it, never tune on it, never add its images (or near-duplicates) to training. | It is the proof that the new model is not worse. |
| 5 | **Split by group, not by image.** All photos of the same leaf, plant, field or near-duplicate cluster go to the same split. | Random splits leak near-identical images and fake high accuracy. |
| 6 | **Every image has provenance.** Source, licence, date, author/collector, label origin, consent (own data). | Needed for licence compliance and for removing an image later. |
| 7 | **Synthetic and augmented images never enter val or test.** | Evaluation must reflect real photos only. |
| 8 | **New model = new version number**, released gradually and reversible. | A bad model can be rolled back without an app update. |
| 9 | **Work on a Git branch** (`dataset-expansion`) and version large data with DVC or similar. | Keeps history reviewable and the main branch stable. |
| 10 | **Document every decision** in `docs/` (what was added, why, licence, results). | Future audits and the Phase 2 learnings document stay accurate. |

---

## 2. Step 1: Audit and freeze the current state (before adding anything)

Adapt paths to your repository layout; the names below are suggestions.

### 2.1 Freeze v1

1. Create a branch: `git checkout -b dataset-expansion`.
2. Generate `manifests/manifest_v1.csv` with one row per existing image: relative path, SHA-256, width, height, class name, class index, split (train/val/test).
3. Copy the current `labels.txt`, model file, training notebook and evaluation report into `models/v1/` (or leave them where they are and tag the commit: `git tag model-v1`).
4. Make the v1 data folder read-only and store its manifest hash in the README.
5. Record the **baseline metrics** on the v1 test set: overall accuracy, per-class precision and recall, confusion matrix, calibration error, model size, latency on a mid-range phone. Save as `reports/baseline_v1.json`. Every later model is judged against this file.

### 2.2 Find the gaps

Produce `reports/gap_analysis.md` answering:

| Question | How to answer it |
|----------|------------------|
| Which classes have the fewest images? | Count per class from the manifest. |
| How many v1 images are lab-style (plain background) vs field? | Sample 200 images per class, or measure background uniformity. |
| Which crops do Indian farmers grow that are missing? (e.g. rice, wheat, cotton, groundnut, chilli, soybean, sugarcane, banana, mango, brinjal, okra) | Compare the class list to crop priorities from the Phase 1 research. |
| Which diseases are visually confusable? | Read the baseline confusion matrix. |
| Which conditions are missing? (low light, shadows, wet leaves, dust, close-ups, whole-plant shots, multiple leaves, early symptoms) | Check app feedback data and a manual review. |
| Is there a "not a leaf / unsupported crop" class? | If not, plan one (section 3.3). |

### 2.3 Set targets (rules of thumb, not from a source; adjust to your results)

| Item | Suggested target |
|------|------------------|
| Minimum images per class | 300 field images, ideally 1,000+ |
| Field share of every class | At least 50% real-field images |
| Test set size per class | At least 50 real field images, never used in training |
| Class imbalance (largest : smallest) | At most 10:1 after cleaning, then use weighted loss for the rest |
| Per-class recall on field test set | Set per class from baseline; no class may drop below its v1 value |

---

## 3. Step 2: Decide what to add

### 3.1 Priority order

1. **Weakest existing classes** (lowest recall on field images).
2. **Most confusable pairs** (add contrasting examples for both).
3. **New crops and diseases** that are important for Indian farmers.
4. **Condition diversity** for existing classes (lighting, background, devices, growth stages, severity).
5. **Hard negatives** (healthy leaves with insect damage, nutrient deficiency, dust, water spots, mechanical damage) so the model does not call everything a disease.

### 3.2 New classes: handle with care

A new class touches several places. Plan all of them before collecting images:

- Training data and taxonomy entry (section 6.3)
- Label index appended at the end (or a new labels file version)
- Advice content in Firestore (causes, treatment, prevention, sources) in English, Hindi and Telugu
- Disclaimer and low-confidence behaviour tested for the new class
- Evaluation rows in the report

### 3.3 Add a "background / not a supported leaf" class

Add images of non-leaf scenes (soil, hands, rooms, packaging, animals, unsupported crops, blurry frames, leaves with no visible symptom pattern) as an `other` class or use a separate out-of-distribution check. This reduces confident wrong answers, which is the highest-risk failure of the app. Keep it out of the disease list shown to users.

---

## 4. Step 3: Public datasets to consider

Figures and licences below were found in the sources at the end of this file. **Treat them as leads to verify, not as final.**

| Dataset | What it adds | Size and classes (as reported) | Licence (as reported) | Notes and cautions |
|---------|--------------|--------------------------------|------------------------|--------------------|
| **PlantVillage** | Lab images, many crops | About 54,300 images, 38 classes, 14 crops | CC0 1.0 on several mirrors; the original paper describes CC BY-SA 3.0 with a share-alike extension for models trained on it | **Licence sources conflict.** Decide from the original source and record it. Lab backgrounds cause poor field generalisation. You likely already use it; do not add more of it. |
| **PlantDoc** | Mixed lab and field images from web search, cropped leaves | 2,598 images, 13 species, 27 classes (17 disease, 10 healthy); a Roboflow version lists 2,569 images and 30 classes | CC BY 4.0 | Small, but real-world. Class names differ from PlantVillage; map them in the taxonomy. Check for overlap with PlantVillage. |
| **PlantWild** | In-the-wild images from search engines | About 18.5k images and 89 classes (v1); a combined mirror lists about 30k samples | CC BY-NC-ND 4.0 on the mirror checked | **Non-commercial, no derivatives.** Do not use for a distributed or commercial model unless the original authors grant permission. Mirror also flags about 673 likely duplicates and 181 images under 100 px. |
| **PlantSeg** | In-the-wild images with segmentation masks, 115 diseases on 34 hosts | Large-scale; see paper | CC BY-NC 4.0 | Non-commercial. Useful for research and for training a leaf-segmentation helper, not for a commercial classifier without permission. |
| **Grapes, groundnut, guava, soybean field dataset (Mendeley)** | Real field images of Indian-relevant crops | 9,469 images, 4 species | CC BY 4.0 | Good candidate. Check class definitions and image resolution (the dataset reports resizing to 2000 x 1126). |
| **Cotton leaf dataset (Mendeley, 2025)** | Real-condition cotton images | 1,373 original plus 4,963 augmented, 5 classes | Check the record | **Use originals only.** The augmented set must not appear in val or test. |
| **Real Field Crop Image Dataset (IEEE Dataport, 2026)** | Tomato images from Maharashtra districts | See record | Check the record | Collected for the same purpose as this project; confirm terms and label quality. |
| **FieldPlant** | Natural-field plant disease images | See paper (IEEE Access 2023) | Check source | Cited in reviews as a field-condition benchmark. |
| **Cassava Leaf Disease** | Crowd-sourced real-world images | See source | Check source | Reviews report variable accuracy across architectures, so expect noisy labels. |
| **ICAR-CITH apple leaf images, Bangladesh chilli, brinjal, banana, litchi, tea, bottle gourd, bitter gourd datasets (Mendeley, 2025)** | Regional crops, many field-collected | Various | Mostly check per record | Listed in a 2025 dataset survey; verify each record individually. |

### 4.1 Licence quick guide

| Licence | Can use in a free app? | Can use in a commercial app? | Conditions |
|---------|------------------------|-------------------------------|------------|
| CC0 | Yes | Yes | None required (citing is good practice) |
| CC BY 4.0 | Yes | Yes | Credit the authors (put credits in an in-app "Data sources" screen and the README) |
| CC BY-SA | Yes | Yes, with share-alike duties | Derived data and possibly trained models may need the same licence; get legal advice |
| CC BY-NC / NC-ND | Research only | **No** | Do not use for a distributed commercial model; ND also forbids modified versions (crops, resizing, augmentation) |
| No licence stated | Do not assume | Do not assume | Ask the authors in writing |

This is general information, not legal advice. If the app will ever be monetised or published widely, have a lawyer check the dataset licences, especially share-alike and non-commercial terms.

---

## 5. Step 4: Collect your own field data (highest value)

Public field datasets are small and rarely cover your exact crops, varieties, phones and regions. Your own labelled images will improve real-world accuracy the most.

### 5.1 Collection protocol

| Element | Guidance |
|---------|----------|
| **Who** | Agronomy students, KVK or agricultural university partners, farmer groups, local agri extension officers. |
| **Devices** | A mix of budget, mid-range and flagship phones used by farmers, so the model sees real camera quality. |
| **Conditions** | Morning, noon, evening, shade, overcast; wet and dry leaves; dusty leaves. |
| **Framing** | Single leaf close-up, several leaves, whole plant section, fruit or stem where relevant. Include imperfect shots (slightly blurry, partly in shadow): that is what the app will receive. |
| **Backgrounds** | Soil, other plants, hands, sky, mulch, the field itself. Do not use plain backdrops. |
| **Growth stage and severity** | Early, mid and late symptoms; healthy leaves from the same plants and fields. |
| **Volume per session** | Several images per plant, many plants, many fields. Group id = plant or field so the split is group-safe. |
| **Metadata** | Crop, variety if known, growth stage, date, district/state (not exact GPS), device model, collector id, lighting. **Strip GPS EXIF from stored images** to match the app's privacy notice. |

### 5.2 Labelling process

1. A trained agronomist or plant pathologist labels each image. Where possible, confirm with a lab test or field inspection, not photo alone.
2. **Double-label at least 10-20% of images** with a second expert; measure agreement (Cohen's kappa). Resolve disagreements by discussion and write down the rule.
3. Keep an `uncertain` bucket for images experts cannot label; exclude them from training instead of guessing.
4. Keep a written **label guide** per disease (symptom description, example photos, look-alikes). Share it with all labellers.
5. Record who labelled what and when.

### 5.3 Using the app itself as a data source (opt-in only)

The app already has thumbs up/down feedback and Firestore/Storage. You can extend it later, **but only after** these are in place:

- Clear opt-in consent screen (not pre-ticked), explaining that cropped leaf photos may be used to improve the model, can be withdrawn, and are deleted on request. This must align with the India DPDP Act notes already in the app.
- Upload only the cropped leaf image, with GPS and device identifiers stripped.
- Store contributions in a **separate** Storage path and Firestore collection (for example `contributions/`), never mixed with the user's history data.
- Contributed images are **unlabelled candidates**. They enter training only after expert review. Never train on raw user feedback.
- Prioritise **low-confidence scans and thumbs-down results** for review (active learning, section 8.2).
- Provide a deletion path that also removes the contributed image from future dataset versions.

Do not ship any of this without a privacy review. It is a separate release, and this guide does not require it.

---

## 6. Step 5: Build the expanded dataset (v2)

### 6.1 Folder layout (suggested)

```
data/
  v1_frozen/                 # existing dataset, read-only
  raw/
    plantdoc/                # untouched downloads, one folder per source
    mendeley_field_4crops/
    own_field_2026/
  curated/                   # cleaned, renamed, de-duplicated images
  manifests/
    manifest_v1.csv
    manifest_v2.csv
  taxonomy/
    classes_v2.yaml          # class names, aliases, crop, disease, index
  splits/
    split_v2.json            # group-aware train/val/test assignment
  licenses/
    SOURCES.md               # one entry per source: URL, licence, date checked, decision
models/
  v1/                        # existing model and labels (unchanged)
  v2/                        # new model and labels (after approval)
reports/
  baseline_v1.json
  gap_analysis.md
  eval_v2.json
```

### 6.2 Manifest schema (`manifest_v2.csv`)

| Column | Meaning |
|--------|---------|
| `image_id` | Stable unique id (SHA-256 prefix) |
| `path` | Relative path in `curated/` |
| `sha256` | Exact-duplicate detection |
| `phash` | Perceptual hash for near-duplicates |
| `width`, `height` | Pixels |
| `source` | Dataset or collector name |
| `license` | Licence string |
| `image_style` | `lab`, `field`, `web`, `synthetic` |
| `crop`, `disease`, `class_name`, `class_index` | Label (class_index from `classes_v2.yaml`) |
| `label_origin` | `original_dataset`, `expert`, `expert_double`, `pseudo` |
| `group_id` | Plant, field or near-duplicate cluster id (used for splitting) |
| `split` | `train`, `val`, `test` |
| `added_in` | `v1` or `v2` |
| `notes` | Anything unusual |

### 6.3 Taxonomy file (`classes_v2.yaml`)

One source of truth that maps every dataset's class names to yours, and fixes the index order.

```yaml
version: 2
classes:
  # Indices 0..37 are copied from v1 unchanged and in the same order.
  - {index: 0,  crop: apple,  disease: apple_scab,   aliases: ["Apple___Apple_scab", "Apple Scab Leaf"]}
  - {index: 1,  crop: apple,  disease: black_rot,    aliases: ["Apple___Black_rot", "Apple leaf black rot"]}
  # ... all v1 classes ...
  # New classes only appended below.
  - {index: 38, crop: groundnut, disease: early_leaf_spot, aliases: ["groundnut_early_leaf_spot"]}
  - {index: 39, crop: other,     disease: not_a_leaf,       aliases: ["background", "non_leaf"]}
```

Add a CI check that fails if indices 0..N-1 differ from `models/v1/labels.txt`.

### 6.4 Cleaning pipeline

Run in this order and log how many images each step removes:

1. **Integrity:** drop corrupt or unreadable files, tiny images (for example under 100 px on the short side), and images that are almost uniform in colour.
2. **Exact duplicates:** group by SHA-256; keep one.
3. **Near duplicates:** cluster by perceptual hash (and optionally by embedding similarity from a pretrained model). Put every cluster in one `group_id`. Review clusters across sources, since the same web image often appears in several datasets.
4. **Quality filter:** flag blurry images (variance of Laplacian), over- and under-exposed images; review the flagged set manually rather than deleting automatically, because blurry field photos are realistic.
5. **Label noise check:** train a quick model with cross-validation and review the images whose given label disagrees confidently with the prediction (the "confident learning" idea, available in libraries such as cleanlab). Have an expert resolve each flagged image.
6. **Re-label mapping:** convert source class names to `classes_v2.yaml` names; images that do not map cleanly go to `uncertain/`.
7. **Strip metadata:** remove GPS and personal EXIF from stored images.
8. **Resize policy:** keep the original resolution in `curated/`; resize only at training time to the model's input size, so you can change the model later.

### 6.5 Licence log (`licenses/SOURCES.md`)

For every source record: name, URL, licence text as shown on the **original** page, date checked, who checked, decision (`use`, `research only`, `rejected`), attribution text, and where credits appear in the app. Reject or quarantine NC/ND sources for the production model.

### 6.6 Leakage-safe split

- Keep the **v1 test set** exactly as it is: its images and near-duplicates are excluded from training.
- Build a **new field test set** from real field images (own data plus field datasets you did not train on). Report v1-test and field-test results separately.
- Assign by `group_id`, stratified by class.

---

## 7. Augmentation and synthetic data

### 7.1 Online augmentation (default)

Apply random transforms during training only: rotation, flips, scale and crop, colour and brightness jitter, blur, noise, JPEG compression, random erasing, and **background replacement** (paste the leaf onto field backgrounds). Do not save augmented images into the dataset, and keep it out of val and test.

### 7.2 Hard-case simulation

Add augmentations that imitate phone-in-the-field conditions: low light, harsh sun, motion blur, shadows, dust, water droplets. These help more than extra clean images.

### 7.3 Synthetic images (use carefully)

Generative models (GAN or diffusion) can fill rare classes, but they also invent symptoms that do not exist. If used:

- Add them to **training only**, as a minority of each class (for example no more than 20-30%).
- Have an agronomist review a sample from every class.
- Tag them `image_style=synthetic` in the manifest.
- Compare the model with and without them on the **real** field test set; keep them only if real-image metrics improve.

### 7.4 Class imbalance

Use class-weighted loss or focal loss, balanced sampling, and more real data for rare classes. Do not rely on duplicating images.

---

## 8. Semi-supervised and active learning (optional, after the basics)

### 8.1 Pseudo-labelling

Use the current model to label unlabelled field images, keep only very confident predictions, and add them to training with `label_origin=pseudo`. Never put pseudo-labelled images in val or test. Spot-check at least 5% by expert, because the current model's mistakes get copied.

### 8.2 Active learning

Prioritise expert labelling for images where the model is uncertain, or where predictions disagree between two models. This gives the most accuracy gain per labelled image.

---

## 9. Step 6: Retrain as a new model version

1. **Do not touch** `models/v1/`. Train into `models/v2/`.
2. **Start from the v1 weights** or a pretrained backbone and compare both. Fine-tuning on a mix of old and new data usually avoids forgetting; training only on new data can make the model forget old classes.
3. Keep v1 images in the training mix (v1 train split only).
4. Keep the preprocessing identical to the app's (size, normalisation, channel order, orientation); any change must be mirrored in the app classifier wrapper and covered by a test.
5. Use fixed random seeds and pinned library versions; log the dataset manifest hash with the model.
6. Calibrate confidence (for example temperature scaling on the validation set) and re-select the low-confidence threshold using the **field** validation set.
7. Quantise (for example float16 or int8) and re-run evaluation **after** quantisation, since accuracy can shift.
8. Export `labels.txt` together with the model and a `model_version` string (for example `2.0.0`), plus a `model_card.md` listing data sources, class list, metrics, known limits.

---

## 10. Step 7: Release gates (the new model must pass all of these)

| Gate | Requirement |
|------|-------------|
| **No regression** | On the frozen v1 test set, overall accuracy and every class's recall are not worse than `baseline_v1.json` beyond an agreed small tolerance (decide the tolerance up front, for example 1 point). |
| **Field improvement** | On the new field test set, overall accuracy and macro-F1 are clearly better than v1. Report per class. |
| **Weak classes improved** | The classes chosen in the gap analysis improve in recall. |
| **Confusions** | The top confusable pairs from the baseline do not get worse. |
| **Calibration** | Expected calibration error is not worse; confidence matches accuracy. |
| **Out-of-distribution** | Non-leaf and unsupported-crop images are rejected or flagged low-confidence at an acceptable rate. |
| **Budgets** | Model size and on-device latency still meet the Phase 2 budgets after quantisation, on a low-end device profile. |
| **Licences** | `SOURCES.md` is complete and contains no NC/ND data in a production model. |
| **Advice content ready** | Every new class has advice in en/hi/te, with sources and disclaimers. |
| **Reproducible** | Someone else can re-run training from the manifest and reproduce the metrics within normal variance. |

If any gate fails, do not release; fix the data or training, not the gate.

---

## 11. Step 8: Ship without breaking the app

### 11.1 Check the app is ready (read-only check first)

- Does the classifier wrapper read the number of classes from `labels.txt` or from the model output shape, or is the count hard-coded? If hard-coded, make one small change to read it dynamically, add a unit test, and ship that app update **before** any model with more classes.
- Do result screens and the advice lookup use the label string or index as the key? Keep keys stable (use the disease id from `classes_v2.yaml`, not the display name).
- Are class display names localised through resources or Firestore, with a safe fallback when a translation is missing?
- Does history store the predicted class id and `model_version`, so old history entries remain readable after the model changes?

### 11.2 Roll out

1. Add the new advice documents to Firestore first. They are additive and invisible until a model uses them.
2. Upload the v2 model and labels as a pair, with `model_version`.
3. Roll out gradually (for example 5%, then 25%, then 100%) through Firebase Remote Config or Firebase ML model delivery, whichever the project already uses. Check Crashlytics and feedback between stages.
4. Keep v1 available for instant rollback. The app should fall back to the bundled or last-known-good model if the download fails or its checksum does not match.
5. Do not delete v1 artifacts for at least one full release cycle.

### 11.3 Monitor after release

Track the share of low-confidence results, thumbs-down rate per class, crash-free sessions and inference latency, comparing v1 and v2 cohorts. Feed the worst cases into the next labelling round.

---

## 12. Risks and how this plan handles them

| Risk | Mitigation |
|------|------------|
| Class order changes and the app shows the wrong disease | Taxonomy index check in CI; labels and model shipped as a pair; append-only indices |
| Accuracy looks better only because of leakage | Group-aware splits; frozen v1 test set; separate field test set |
| Model forgets old classes | Fine-tune with v1 train data in the mix; per-class regression gate |
| Licence violation | `SOURCES.md`; NC/ND sources excluded from the production model; attribution screen |
| Wrong labels from web images or non-experts | Expert labelling, double labels, noise audit with review |
| Synthetic or pseudo labels pollute the model | Training-only, minority share, expert spot checks, real-data evaluation |
| Privacy breach with user photos | Opt-in consent, GPS stripping, separate storage, deletion path, DPDP review |
| Larger model breaks speed or size budgets | Evaluate after quantisation on a low-end device; keep the Phase 2 budgets as a gate |
| Bad release | Staged rollout, last-known-good fallback, v1 retained |

---

## 13. Suggested phases and checklist

| Phase | Work | Done when |
|-------|------|-----------|
| **A. Freeze and audit** | Branch, v1 manifest, baseline metrics, gap analysis | `baseline_v1.json` and `gap_analysis.md` exist; v1 folder read-only |
| **B. Licences and sources** | Verify licences, download approved public field datasets into `raw/` | `SOURCES.md` complete; no NC/ND in the production set |
| **C. Field collection** | Protocol, labelling guide, collection rounds, expert labelling | At least the minimum per class for target gap classes; kappa recorded |
| **D. Curate** | Cleaning pipeline, taxonomy, manifest, group-aware split | `manifest_v2.csv` and `split_v2.json` created; leak check passed |
| **E. Train** | Train v2 with augmentation, calibration, quantisation | `models/v2/` and `eval_v2.json` produced |
| **F. Gate** | Compare with baseline against section 10 | All gates pass or issues fixed |
| **G. Release** | Advice content, app readiness check, staged rollout | v2 live at 100% with v1 fallback available |
| **H. Monitor** | Dashboards, feedback review, next labelling batch | Monitoring in place; next round planned |

### Quick pre-merge checklist

- [ ] v1 data, model, labels and test set untouched (hash check passes)
- [ ] Taxonomy indices 0..N-1 equal v1 labels
- [ ] Every image has source, licence and group id
- [ ] No test or near-duplicate images in training
- [ ] No synthetic, augmented or pseudo-labelled images in val or test
- [ ] Gates in section 10 all pass, with numbers recorded
- [ ] Advice content (en/hi/te) ready for every new class
- [ ] Rollout and rollback plan written
- [ ] Docs updated (this file, README, model card)

---

## Appendix A: Minimal scripts

These are starting points; adapt paths and thresholds, and test on a small sample first.

### A.1 Build a manifest with hashes

```python
import csv, hashlib, pathlib
from PIL import Image
import imagehash

ROOT = pathlib.Path("data/curated")
OUT = pathlib.Path("data/manifests/manifest_v2.csv")

def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

rows = []
for p in sorted(ROOT.rglob("*")):
    if p.suffix.lower() not in {".jpg", ".jpeg", ".png"}:
        continue
    try:
        with Image.open(p) as im:
            im.load()
            rows.append({
                "image_id": sha256(p)[:16],
                "path": str(p.relative_to(ROOT)),
                "sha256": sha256(p),
                "phash": str(imagehash.phash(im)),
                "width": im.width,
                "height": im.height,
            })
    except Exception as exc:  # corrupt or unreadable file
        print("skip", p, exc)

OUT.parent.mkdir(parents=True, exist_ok=True)
with OUT.open("w", newline="") as f:
    writer = csv.DictWriter(f, fieldnames=rows[0].keys())
    writer.writeheader()
    writer.writerows(rows)
```

### A.2 Near-duplicate groups (small and medium datasets)

```python
import imagehash
import pandas as pd

df = pd.read_csv("data/manifests/manifest_v2.csv")
hashes = [imagehash.hex_to_hash(h) for h in df["phash"]]
parent = list(range(len(df)))

def find(i):
    while parent[i] != i:
        parent[i] = parent[parent[i]]
        i = parent[i]
    return i

THRESHOLD = 6  # Hamming distance; tune by reviewing examples
for i in range(len(hashes)):
    for j in range(i + 1, len(hashes)):
        if hashes[i] - hashes[j] <= THRESHOLD:
            parent[find(i)] = find(j)

df["group_id"] = [find(i) for i in range(len(df))]
df.to_csv("data/manifests/manifest_v2.csv", index=False)
```

This pairwise loop is quadratic, so it suits tens of thousands of images at most. For larger sets, compare image embeddings with an approximate nearest-neighbour index instead.

### A.3 Group-aware, stratified split

```python
import pandas as pd
from sklearn.model_selection import StratifiedGroupKFold

df = pd.read_csv("data/manifests/manifest_v2.csv")
sgkf = StratifiedGroupKFold(n_splits=10, shuffle=True, random_state=42)
df["split"] = "train"

folds = list(sgkf.split(df, df["class_index"], groups=df["group_id"]))
df.loc[folds[0][1], "split"] = "val"
df.loc[folds[1][1], "split"] = "test"
df.to_csv("data/manifests/manifest_v2.csv", index=False)
```

Before using this, remove v1 test images (and any group touching them) from the training pool, and keep field test images assigned to `test` only.

### A.4 Check that v1 class order is unchanged

```python
import yaml

v1 = [l.strip() for l in open("models/v1/labels.txt") if l.strip()]
v2 = yaml.safe_load(open("data/taxonomy/classes_v2.yaml"))["classes"]
v2_by_index = {c["index"]: f'{c["crop"]}_{c["disease"]}' for c in v2}

# Adjust the name comparison to match how labels.txt is written.
for i, name in enumerate(v1):
    assert i in v2_by_index, f"v1 class {i} missing from v2 taxonomy"
print("v1 class indices preserved:", len(v1))
```

Adapt the name comparison to your label format. The point is to fail the build if any existing index moves.

---

## Sources checked (18 Oct 2026)

- PlantDoc (Meta-Album / OpenML record): https://api.openml.org/d/44303
- PlantDoc (Roboflow public dataset page): https://public.roboflow.com/object-detection/plantdoc
- Improved PlantDoc (Roboflow blog): https://roboflow.com/blog/introducing-an-improved-plantdoc-dataset-for-plant-disease-object-detection
- PlantVillage dataset repository: https://github.com/spMohanty/PlantVillage-Dataset
- PlantVillage in TensorFlow Datasets: https://www.tensorflow.org/datasets/catalog/plant_village
- Downsampled PlantVillage (CC0 1.0 statement): https://github.com/attaullah/downsampled-plant-disease-dataset
- Hughes and Salathé paper summary (CC BY-SA 3.0 statement): https://alphaxiv.org/abs/1511.08060
- PlantWild dataset card (FiftyOne mirror): https://huggingface.co/datasets/harpreetsahota/PlantWild
- PlantSeg (Scientific Data, 2026): https://www.nature.com/articles/s41597-025-06513-4
- Field dataset for grapes, groundnut, guava, soybean (Mendeley): https://data.mendeley.com/datasets/bsr2vzhrzr/2
- Cotton leaf image dataset (Data in Brief): https://www.sciencedirect.com/science/article/pii/S2352340925008637
- Real Field Crop Image Dataset (IEEE Dataport): https://ieee-dataport.org/documents/real-field-crop-image-dataset
- Dataset survey listing 2025 regional crop datasets: https://arxiv.org/pdf/2508.17117
- Review comparing lab, mixed, field and crowd-sourced datasets: https://link.springer.com/article/10.1186/s13007-025-01450-0
- PlantWild use in a real-field study: https://doi.org/10.3390/agriculture16090918
