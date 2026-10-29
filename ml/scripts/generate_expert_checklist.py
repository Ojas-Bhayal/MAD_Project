import csv
from pathlib import Path

def main():
    base_dir = Path(".").resolve()
    manifest_path = base_dir / "data" / "manifests" / "manifest_cotton_staged.csv"
    checklist_csv = base_dir / "ml" / "reports" / "mendeley_cotton_2025" / "expert_review_checklist.csv"
    checklist_md = base_dir / "ml" / "reports" / "mendeley_cotton_2025" / "EXPERT_REVIEW_CHECKLIST.md"

    with open(manifest_path, "r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    # Identify multi-member groups
    grp_members = {}
    for r in rows:
        grp_members.setdefault(r["group_id"], []).append(r)

    # Identify dH = 1 near duplicates
    dh1_partners = {}
    for i in range(len(rows)):
        h1 = int(rows[i]["phash"], 16)
        for j in range(i + 1, len(rows)):
            h2 = int(rows[j]["phash"], 16)
            if (h1 ^ h2).bit_count() == 1:
                dh1_partners.setdefault(rows[i]["image_id"], []).append(rows[j])
                dh1_partners.setdefault(rows[j]["image_id"], []).append(rows[i])

    checklist_rows = []
    for r in rows:
        staged_name = Path(r["staged_path"]).name
        raw_name = Path(r["original_raw_path"]).name
        flags = []
        if len(grp_members[r["group_id"]]) > 1:
            other_names = [Path(m["staged_path"]).name for m in grp_members[r["group_id"]] if m["image_id"] != r["image_id"]]
            flags.append(f"dH=0 Burst Pair ({', '.join(other_names)})")
        if r["phash"] == "ffc3c3c3c3c3e7ff":
            flags.append("Cross-Class pHash Collision")
        if r["image_id"] in dh1_partners:
            near_names = [Path(m["staged_path"]).name for m in dh1_partners[r["image_id"]]]
            flags.append(f"dH=1 Near-Dup ({', '.join(near_names[:2])})")

        flag_str = "; ".join(flags) if flags else "NONE"

        rec = {
            "image_id": r["image_id"],
            "staged_filename": staged_name,
            "raw_filename": raw_name,
            "cropdoc_class_name": r["cropdoc_class_name"],
            "cropdoc_class_index": r["cropdoc_class_index"],
            "group_id": r["group_id"],
            "split": r["split"],
            "sha256": r["sha256"],
            "phash": r["phash"],
            "staged_path": r["staged_path"],
            "quality_gate_flags": flag_str,
            "pathologist_verdict": "",  # Allowed: Label confirmed | Label uncertain | Incorrect label | Image unsuitable or ambiguous
            "pathologist_confidence": "",  # High / Medium / Low
            "pathologist_notes": "",
            "reviewer_name": "",
            "review_timestamp": "",
        }
        checklist_rows.append(rec)

    # Write CSV
    fieldnames = [
        "image_id", "staged_filename", "raw_filename", "cropdoc_class_name", "cropdoc_class_index",
        "group_id", "split", "sha256", "phash", "staged_path", "quality_gate_flags",
        "pathologist_verdict", "pathologist_confidence", "pathologist_notes",
        "reviewer_name", "review_timestamp"
    ]
    with open(checklist_csv, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(checklist_rows)

    print(f"Generated Checklist CSV: {checklist_csv} ({len(checklist_rows)} rows)")

    # Write Markdown Document
    md_content = """# CropDoc Cotton Leaf Quality Gate — Expert Review Checklist

**Protocol Version:** 1.0  
**Target Crop:** Cotton (*Gossypium hirsutum* / *Gossypium barbadense*)  
**Target Classes:**
- `cotton_bacterial_blight` (Class 11, Pathogen: *Xanthomonas citri* pv. *malvacearum*)
- `cotton_healthy` (Class 12, Non-Diseased Foliage)  
**Dataset Source:** Mendeley Cotton Leaf Image Dataset (Babu et al., 2025; East West University)  
**Total Images Staged for Review:** 359 images (185 Bacterial Blight, 174 Healthy)  
**Current ML Safety Status:** **BLOCKED** (No models may be trained until human pathologist review is formally completed)  

---

## 1. Objective & Human-in-the-Loop Safeguards

Automated AI models and raw dataset creators frequently mislabel visual leaf symptoms or confuse secondary fungal colonization with primary bacterial blight. Furthermore, web datasets commonly include non-cotton leaves or non-foliar debris.

Per CropDoc ML Safety Gate rules:
1. **No AI Confirmation:** Predictions from computer vision models (including prior versions of CropDoc, MobileNet, ResNet, or Gemini) **must NOT** be used to confirm labels. Only qualified agricultural plant pathologists or senior agronomists may sign off on image diagnostic ground truth.
2. **Four-Tier Diagnostic Rubric:** Every image must be evaluated and assigned exactly one of the four standardized verdicts:
   - **`Label confirmed`**: High-confidence diagnosis matching the assigned class. Symptoms strictly adhere to pathological presentation.
   - **`Label uncertain`**: Symptoms are ambiguous, obscured, atypical, or indistinguishable from other foliar disorders (e.g., fungal leaf spot, nutrient deficiency, or early senescence).
   - **`Incorrect label`**: Foliage clearly displays symptoms of a different pathogen (e.g., *Alternaria*, *Fusarium*, *Verticillium*, or insect damage) or is misclassified as healthy despite visible blight lesions.
   - **`Image unsuitable or ambiguous`**: Image contains excessive blur, occlusion, non-cotton foliage, severe overexposure/underexposure, or insufficient leaf area to permit pathological diagnosis.

---

## 2. Pathological Evaluation Rubric

### Class 11: `cotton_bacterial_blight`
*Pathogen:* *Xanthomonas citri* pv. *malvacearum* (syn. *Xanthomonas axonopodis* pv. *malvacearum*)

| Diagnostic Criterion | Acceptable Presentation (`Label confirmed`) | Unacceptable / Conflicting Presentation (`Incorrect label` or `Uncertain`) |
| :--- | :--- | :--- |
| **Lesion Morphology** | Angular, polygonal lesions strictly delimited by secondary and tertiary leaf veins. | Circular, zonate concentric rings (typical of *Alternaria macrospora* or *Alternaria alternata*). |
| **Water-Soaking** | Initial water-soaked, translucent, greasy spots on abaxial/adaxial surfaces turning dark brown to black. | Dry, papery, non-water-soaked chlorotic blotches without angular borders. |
| **Vein Necrosis ("Blackarm")** | Systemic spreading along major leaf veins, resulting in blackened, necrotic main veins and petiole discoloration. | Generalized interveinal chlorosis (nutrient deficiency like Magnesium or Potassium) without vein necrosis. |
| **Concomitant Pathology** | Primary angular water-soaked lesions are prominent, even if secondary saprophytic fungi are beginning to colonize old necrotic tissue. | Severe insect chewing, thrips silvering, spider mite stippling, or whitefly soot without distinct bacterial lesions. |

### Class 12: `cotton_healthy`
*Controls:* Non-diseased cotton foliage

| Diagnostic Criterion | Acceptable Presentation (`Label confirmed`) | Unacceptable / Conflicting Presentation (`Incorrect label` or `Unsuitable`) |
| :--- | :--- | :--- |
| **Pathogen Absence** | Complete absence of active bacterial water-soaking, fungal spots, rust pustules, or systemic vascular wilts. | Visible angular bacterial lesions, circular target spots, or fungal fruiting structures. |
| **Acceptable Physiological Variation** | Normal field dust, minor mechanical tears, hail nicks, gentle sun bronzing, or age-related lower canopy senescence. | Severe systemic viral mosaic, leaf curl virus enations, or active viral distortion. |
| **Botanical Integrity** | Recognizable cotton leaf morphology (3-5 palmate lobes, typical *Gossypium* venation and petiole). | Non-cotton foliage (weeds, surrounding ground cover, or foreign debris). |

---

## 3. High-Priority Quality Gate Flags Requiring Special Review

During automated staging audits, specific image subsets were flagged for focused human scrutiny:

### A. Burst Shot Pairs (dH = 0, 8 pairs / 16 images)
These image pairs exhibit identical 64-bit perceptual hashes (dH = 0) and very small pixel differences (mean diff < 24). Pathologists must verify whether both images represent the identical leaf lesion:
- **`cotton_mendeley_bb_017`**: `bacterial_(114).jpg` and `bacterial_(119).jpg` (Train)
- **`cotton_mendeley_bb_091`**: `bacterial_(195).jpg` and `bacterial_(196).jpg` (Train)
- **`cotton_mendeley_hl_001`**: `healthy_(1).jpg` and `healthy_(223).jpg` (Train)
- **`cotton_mendeley_hl_005`**: `healthy_(102).jpg` and `healthy_(22).jpg` (Train)
- **`cotton_mendeley_hl_008`**: `healthy_(108).jpg` and `healthy_(95).jpg` (Train)
- **`cotton_mendeley_hl_014`**: `healthy_(120).jpg` and `healthy_(62).jpg` (Train)
- **`cotton_mendeley_hl_100`**: `healthy_(275).jpg` and `healthy_(277).jpg` (Train)
- **`cotton_mendeley_hl_151`**: `healthy_(60).jpg` and `healthy_(93).jpg` (Val)

### B. Cross-Class Coarse pHash Collision
- `bacterial_(29).jpg` (Train, Group `cotton_mendeley_bb_113`) shares the coarse 8x8 average hash `ffc3c3c3c3c3e7ff` with `healthy_(120).jpg` and `healthy_(62).jpg` (Train, Group `cotton_mendeley_hl_014`).
- **Pathologist Verification Required:** Pathologist must inspect `bacterial_(29).jpg` to confirm distinct angular bacterial lesions, and verify that `healthy_(120).jpg` and `healthy_(62).jpg` are completely free from bacterial symptoms.

### C. Near-Duplicate Cluster Across Splits (dH = 1)
- `bacterial_(114).jpg` / `(119).jpg` (Train) vs `bacterial_(120).jpg` (Test, dH = 1). Pathologist must verify whether `bacterial_(120)` is an angle variation of the same plant or an independent field specimen.
- `healthy_(84).jpg` (Val) vs `healthy_(95).jpg` (Train, dH = 1). Pathologist must verify whether `healthy_(84)` is from the same plant as `healthy_(95)`.

---

## 4. Review Process & Workflow Instructions

1. **Access the Review Tools:**
   - **Interactive Web Portal:** Open [`ml/reports/mendeley_cotton_2025/contact_sheets/index.html`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/reports/mendeley_cotton_2025/contact_sheets/index.html) in a web browser.
   - **Class Contact Sheets:**
     - [Bacterial Blight Contact Sheet (185 images)](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/reports/mendeley_cotton_2025/contact_sheets/cotton_bacterial_blight.html)
     - [Healthy Leaves Contact Sheet (174 images)](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/reports/mendeley_cotton_2025/contact_sheets/cotton_healthy.html)
   - **Offline Composite Sheets:** View high-resolution multi-page sheets in [`ml/reports/mendeley_cotton_2025/contact_sheets/images/`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/reports/mendeley_cotton_2025/contact_sheets/images/).

2. **Record Review Results in CSV:**
   - Open [`ml/reports/mendeley_cotton_2025/expert_review_checklist.csv`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/reports/mendeley_cotton_2025/expert_review_checklist.csv).
   - For each record, set `pathologist_verdict` to one of:
     - `Label confirmed`
     - `Label uncertain`
     - `Incorrect label`
     - `Image unsuitable or ambiguous`
   - Record `pathologist_confidence` (`High`, `Medium`, or `Low`), optional clinical `pathologist_notes`, `reviewer_name`, and `review_timestamp`.

3. **Post-Review Quality Action:**
   - Any image marked `Incorrect label` or `Image unsuitable or ambiguous` will be excluded from the dataset prior to any training gate release.
   - Images marked `Label uncertain` will be held in quarantine pending secondary review.
   - Any near-duplicate pairs confirmed by the pathologist to share the same physical plant will have their splits reconciled to prevent cross-split leakage.

---

## 5. Review Summary Template (To be completed upon review)

| Category | Bacterial Blight (Target: 185) | Healthy (Target: 174) | Combined (Target: 359) |
| :--- | :---: | :---: | :---: |
| **`Label confirmed`** | _[Pending Review]_ | _[Pending Review]_ | _[Pending Review]_ |
| **`Label uncertain`** | _[Pending Review]_ | _[Pending Review]_ | _[Pending Review]_ |
| **`Incorrect label`** | _[Pending Review]_ | _[Pending Review]_ | _[Pending Review]_ |
| **`Image unsuitable or ambiguous`** | _[Pending Review]_ | _[Pending Review]_ | _[Pending Review]_ |
| **Total Evaluated** | 0 / 185 | 0 / 174 | 0 / 359 |
| **Review Completion Date** | _[Pending]_ | _[Pending]_ | _[Pending]_ |
| **Lead Pathologist Signature** | _[Pending]_ | _[Pending]_ | _[Pending]_ |
"""
    with open(checklist_md, "w", encoding="utf-8") as f:
        f.write(md_content)

    print(f"Generated Checklist Markdown: {checklist_md}")

if __name__ == "__main__":
    main()
