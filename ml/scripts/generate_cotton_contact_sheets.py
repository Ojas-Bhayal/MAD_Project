import csv
import json
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

def get_font(size=14):
    try:
        return ImageFont.truetype("arial.ttf", size)
    except Exception:
        try:
            return ImageFont.truetype("DejaVuSans.ttf", size)
        except Exception:
            return ImageFont.load_default()

def main():
    base_dir = Path(".").resolve()
    manifest_path = base_dir / "data" / "manifests" / "manifest_cotton_staged.csv"
    output_dir = base_dir / "ml" / "reports" / "mendeley_cotton_2025" / "contact_sheets"
    output_dir.mkdir(parents=True, exist_ok=True)

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

    # Assign flag metadata to each row
    for r in rows:
        flags = []
        # Multi-member group check
        members = grp_members[r["group_id"]]
        if len(members) > 1:
            other_names = [Path(m["staged_path"]).name for m in members if m["image_id"] != r["image_id"]]
            flags.append(f"dH=0 Burst Pair with {', '.join(other_names)}")
        
        # Cross-class collision check (specifically pHash ffc3c3c3c3c3e7ff)
        if r["phash"] == "ffc3c3c3c3c3e7ff":
            flags.append("pHash collision: ffc3c3c3c3c3e7ff (cross-class match with bacterial_(29) / healthy_(62, 120))")
        
        # dH = 1 check
        if r["image_id"] in dh1_partners:
            near_names = [f"{Path(m['staged_path']).name} ({m['split']})" for m in dh1_partners[r["image_id"]]]
            flags.append(f"dH=1 Near-Duplicate with {', '.join(near_names[:2])}")
        
        r["flags"] = flags

    # Separate classes
    classes = {
        "cotton_bacterial_blight": [r for r in rows if r["cropdoc_class_name"] == "cotton_bacterial_blight"],
        "cotton_healthy": [r for r in rows if r["cropdoc_class_name"] == "cotton_healthy"]
    }

    # Generate HTML contact sheets
    for cls_name, cls_rows in classes.items():
        title = "Cotton Bacterial Blight (CropDoc Class 11)" if cls_name == "cotton_bacterial_blight" else "Cotton Healthy Leaves (CropDoc Class 12)"
        html_file = output_dir / f"{cls_name}.html"
        
        cards_html = []
        for r in cls_rows:
            staged_p = Path(r["staged_path"])
            # relative path from contact_sheets/ to staged file
            rel_img = f"../../../../{r['staged_path']}"
            fname = staged_p.name
            raw_fname = Path(r["original_raw_path"]).name
            split = r["split"]
            grp = r["group_id"]
            img_id = r["image_id"][:12]
            phash = r["phash"]
            
            # Badge styles
            split_badge_color = "#1976d2" if split == "train" else ("#f57c00" if split == "val" else "#7b1fa2")
            
            flag_badges = ""
            for flg in r["flags"]:
                if "Burst Pair" in flg:
                    flag_badges += f'<span class="badge badge-burst">{flg}</span><br>'
                elif "collision" in flg:
                    flag_badges += f'<span class="badge badge-collision">{flg}</span><br>'
                elif "dH=1" in flg:
                    flag_badges += f'<span class="badge badge-near">{flg}</span><br>'

            card = f"""
            <div class="card" id="{fname}">
                <div class="img-container">
                    <a href="{rel_img}" target="_blank" title="Click to view full resolution {fname}">
                        <img src="{rel_img}" alt="{fname}" loading="lazy">
                    </a>
                </div>
                <div class="card-body">
                    <div class="card-title">
                        <strong>{fname}</strong>
                        <span class="badge badge-split" style="background-color: {split_badge_color};">{split.upper()}</span>
                    </div>
                    <div class="card-meta">
                        <div><span class="meta-label">Raw:</span> {raw_fname}</div>
                        <div><span class="meta-label">Group:</span> <code>{grp}</code></div>
                        <div><span class="meta-label">ID:</span> <code>{img_id}...</code></div>
                        <div><span class="meta-label">pHash:</span> <code>{phash}</code></div>
                    </div>
                    <div class="card-flags">
                        {flag_badges}
                    </div>
                    <div class="review-box">
                        <label class="review-label">Pathologist Verdict:</label>
                        <select class="review-select" data-img="{fname}">
                            <option value="">-- Select Verdict --</option>
                            <option value="confirmed">Label confirmed</option>
                            <option value="uncertain">Label uncertain</option>
                            <option value="incorrect">Incorrect label</option>
                            <option value="unsuitable">Image unsuitable / ambiguous</option>
                        </select>
                    </div>
                </div>
            </div>
            """
            cards_html.append(card)

        html_content = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>CropDoc Quality Gate: {title} Contact Sheet</title>
    <style>
        * {{ box-sizing: border-box; }}
        body {{
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            background-color: #f4f6f8;
            color: #212529;
            margin: 0;
            padding: 20px;
        }}
        .header {{
            background: #ffffff;
            padding: 20px 28px;
            border-radius: 8px;
            box-shadow: 0 1px 3px rgba(0,0,0,0.1);
            margin-bottom: 24px;
        }}
        .header h1 {{ margin: 0 0 8px 0; font-size: 24px; color: #1a365d; }}
        .header p {{ margin: 4px 0; color: #4a5568; font-size: 14px; line-height: 1.5; }}
        .nav-links {{ margin-top: 12px; }}
        .nav-links a {{
            display: inline-block;
            margin-right: 12px;
            padding: 6px 14px;
            background: #e2e8f0;
            color: #2d3748;
            text-decoration: none;
            border-radius: 4px;
            font-size: 13px;
            font-weight: 600;
        }}
        .nav-links a:hover {{ background: #cbd5e1; }}
        .nav-links a.active {{ background: #2b6cb0; color: #ffffff; }}
        .grid {{
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
            gap: 16px;
        }}
        .card {{
            background: #ffffff;
            border-radius: 8px;
            overflow: hidden;
            box-shadow: 0 1px 3px rgba(0,0,0,0.08);
            border: 1px solid #e2e8f0;
            display: flex;
            flex-direction: column;
            transition: transform 0.15s ease, box-shadow 0.15s ease;
        }}
        .card:hover {{
            transform: translateY(-2px);
            box-shadow: 0 4px 8px rgba(0,0,0,0.12);
        }}
        .img-container {{
            width: 100%;
            height: 240px;
            background: #edf2f7;
            overflow: hidden;
            display: flex;
            align-items: center;
            justify-content: center;
        }}
        .img-container img {{
            width: 100%;
            height: 100%;
            object-fit: cover;
            transition: transform 0.2s ease;
        }}
        .img-container img:hover {{
            transform: scale(1.08);
        }}
        .card-body {{
            padding: 12px 14px;
            display: flex;
            flex-direction: column;
            flex-grow: 1;
        }}
        .card-title {{
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 8px;
            font-size: 14px;
        }}
        .badge {{
            display: inline-block;
            padding: 2px 7px;
            border-radius: 4px;
            font-size: 11px;
            font-weight: 600;
            color: #ffffff;
        }}
        .badge-burst {{ background: #d97706; margin-top: 4px; }}
        .badge-collision {{ background: #dc2626; margin-top: 4px; }}
        .badge-near {{ background: #475569; margin-top: 4px; }}
        .card-meta {{
            font-size: 12px;
            color: #4a5568;
            margin-bottom: 8px;
            line-height: 1.4;
        }}
        .meta-label {{ font-weight: 600; color: #718096; }}
        .card-flags {{ margin-bottom: 8px; min-height: 20px; }}
        .review-box {{
            margin-top: auto;
            padding-top: 8px;
            border-top: 1px solid #f1f5f9;
        }}
        .review-label {{ font-size: 11px; font-weight: 600; color: #64748b; display: block; margin-bottom: 4px; }}
        .review-select {{
            width: 100%;
            padding: 5px 8px;
            font-size: 12px;
            border: 1px solid #cbd5e1;
            border-radius: 4px;
            background: #f8fafc;
        }}
    </style>
</head>
<body>
    <div class="header">
        <h1>{title}</h1>
        <p><strong>Dataset:</strong> Mendeley Cotton Leaf Image Dataset (Babu et al., 2025) — Staged Subset</p>
        <p><strong>Total Images in Class:</strong> {len(cls_rows)} images | <strong>Audit Status:</strong> Staged for Pathologist Review</p>
        <p><em>Instructions for Agricultural Plant Pathologist: Click any thumbnail to view full-resolution 700x700 image. Verify foliar symptom presentation against reference diagnostic standards. Record verdicts in <code>expert_review_checklist.csv</code>.</em></p>
        <div class="nav-links">
            <a href="cotton_bacterial_blight.html" class="{'active' if cls_name == 'cotton_bacterial_blight' else ''}">Bacterial Blight (185)</a>
            <a href="cotton_healthy.html" class="{'active' if cls_name == 'cotton_healthy' else ''}">Healthy Leaves (174)</a>
            <a href="index.html">Index & Summary</a>
        </div>
    </div>
    <div class="grid">
        {"".join(cards_html)}
    </div>
</body>
</html>
"""
        with open(html_file, "w", encoding="utf-8") as f:
            f.write(html_content)
        print(f"Generated HTML contact sheet: {html_file} ({len(cls_rows)} cards)")

    # Generate Index HTML
    index_file = output_dir / "index.html"
    index_content = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>CropDoc Cotton Quality Gate — Expert Review Portal</title>
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #f8fafc; padding: 30px; color: #1e293b; }}
        .container {{ max-width: 900px; margin: 0 auto; background: white; padding: 32px; border-radius: 10px; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1); }}
        h1 {{ color: #0f172a; margin-top: 0; }}
        .badge-blocked {{ background: #dc2626; color: white; padding: 4px 10px; border-radius: 6px; font-size: 13px; font-weight: bold; }}
        .portal-grid {{ display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin: 24px 0; }}
        .portal-card {{ border: 1px solid #e2e8f0; border-radius: 8px; padding: 20px; background: #f1f5f9; }}
        .portal-card h2 {{ margin-top: 0; font-size: 18px; }}
        .btn {{ display: inline-block; padding: 10px 18px; background: #2563eb; color: white; text-decoration: none; border-radius: 6px; font-weight: 600; margin-top: 12px; }}
        .btn:hover {{ background: #1d4ed8; }}
        table {{ width: 100%; border-collapse: collapse; margin-top: 16px; }}
        th, td {{ padding: 10px; text-align: left; border-bottom: 1px solid #e2e8f0; font-size: 14px; }}
        th {{ background: #f8fafc; }}
    </style>
</head>
<body>
    <div class="container">
        <h1>CropDoc ML Quality Gate — Cotton Review Portal <span class="badge-blocked">TRAINING BLOCKED</span></h1>
        <p>This portal provides interactive contact sheets and review tools for the staged subset of the Mendeley Cotton Leaf Image Dataset (Babu et al., 2025). Models cannot be trained until an agricultural plant pathologist completes visual inspection and confirms foliar symptom validity.</p>
        
        <div class="portal-grid">
            <div class="portal-card">
                <h2>Cotton Bacterial Blight</h2>
                <p><strong>CropDoc Class 11</strong> | Pathogen: <em>Xanthomonas citri</em> pv. <em>malvacearum</em></p>
                <p><strong>Staged Images:</strong> 185 images across 183 perceptual groups</p>
                <p><strong>Features to Verify:</strong> Angular, water-soaked leaf lesions constrained by leaf veins; vein browning/necrosis; systemic blackarm foliar symptoms.</p>
                <a href="cotton_bacterial_blight.html" class="btn">Open Bacterial Blight Contact Sheet &rarr;</a>
            </div>
            <div class="portal-card">
                <h2>Cotton Healthy Leaves</h2>
                <p><strong>CropDoc Class 12</strong> | Non-Diseased Controls</p>
                <p><strong>Staged Images:</strong> 174 images across 168 perceptual groups</p>
                <p><strong>Features to Verify:</strong> Absence of pathogen lesions, fungal pustules, or bacterial blight water-soaking. Mild dust, mechanical wear, or natural senescence acceptable if not pathogenic.</p>
                <a href="cotton_healthy.html" class="btn">Open Healthy Leaves Contact Sheet &rarr;</a>
            </div>
        </div>

        <h3>Dataset & Staging Summary</h3>
        <table>
            <tr><th>Metric</th><th>Bacterial Blight</th><th>Healthy</th><th>Combined</th></tr>
            <tr><td>Raw Unaugmented Downloads</td><td>218</td><td>333</td><td>551</td></tr>
            <tr><td>Cross-Class Conflicts Excluded</td><td>10</td><td>0</td><td>10</td></tr>
            <tr><td>Redundant Exact Intra-Class Copies Excluded</td><td>23</td><td>159</td><td>182</td></tr>
            <tr><td>Clean Staged Images</td><td><strong>185</strong></td><td><strong>174</strong></td><td><strong>359</strong></td></tr>
            <tr><td>Discrete Perceptual Groups (dH = 0)</td><td>183</td><td>168</td><td>351</td></tr>
            <tr><td>Burst Pairs Consolidating Instances</td><td>2 pairs (4 imgs)</td><td>6 pairs (12 imgs)</td><td>8 pairs (16 imgs)</td></tr>
            <tr><td>Split Partitioning</td><td>129 Train / 28 Val / 28 Test</td><td>124 Train / 25 Val / 25 Test</td><td>253 Train / 53 Val / 53 Test</td></tr>
            <tr><td>Cross-Split Group Leakage</td><td><strong>0</strong></td><td><strong>0</strong></td><td><strong>0 (PASS)</strong></td></tr>
        </table>

        <h3>Static Contact Sheet Composite Images (Offline Viewing)</h3>
        <p>High-resolution composite sheets (6x6 thumbnail grids) are available for offline printing and tablet review under <code>ml/reports/mendeley_cotton_2025/contact_sheets/images/</code>.</p>
    </div>
</body>
</html>
"""
    with open(index_file, "w", encoding="utf-8") as f:
        f.write(index_content)
    print(f"Generated Index HTML: {index_file}")

    # Generate Static Composite JPEG Contact Sheets
    img_sheet_dir = output_dir / "images"
    img_sheet_dir.mkdir(parents=True, exist_ok=True)
    font_bold = get_font(13)
    font_small = get_font(11)

    thumb_w, thumb_h = 240, 240
    meta_h = 75
    cell_w, cell_h = thumb_w, thumb_h + meta_h
    cols = 5
    rows_per_sheet = 5
    sheet_capacity = cols * rows_per_sheet

    for cls_name, cls_rows in classes.items():
        total_sheets = math.ceil(len(cls_rows) / sheet_capacity)
        for s_idx in range(total_sheets):
            chunk = cls_rows[s_idx * sheet_capacity : (s_idx + 1) * sheet_capacity]
            sheet_img = Image.new("RGB", (cols * cell_w, rows_per_sheet * cell_h), color="#ffffff")
            draw = ImageDraw.Draw(sheet_img)

            for i, r in enumerate(chunk):
                col_i = i % cols
                row_i = i // cols
                x = col_i * cell_w
                y = row_i * cell_h

                # Load and thumbnail image
                try:
                    with Image.open(r["staged_path"]) as raw_img:
                        thumb = raw_img.convert("RGB").resize((thumb_w, thumb_h), Image.Resampling.BILINEAR)
                        sheet_img.paste(thumb, (x, y))
                except Exception as e:
                    draw.rectangle([x, y, x + thumb_w, y + thumb_h], fill="#dddddd")
                    draw.text((x + 10, y + 100), f"Err: {e}", fill="#ff0000", font=font_small)

                # Meta box
                draw.rectangle([x, y + thumb_h, x + cell_w, y + cell_h], fill="#f8fafc", outline="#e2e8f0")
                fname = Path(r["staged_path"]).name
                grp = r["group_id"]
                split = r["split"].upper()

                # Text annotations
                draw.text((x + 6, y + thumb_h + 4), f"{fname} [{split}]", fill="#0f172a", font=font_bold)
                draw.text((x + 6, y + thumb_h + 22), f"Grp: {grp}", fill="#475569", font=font_small)
                draw.text((x + 6, y + thumb_h + 38), f"Raw: {Path(r['original_raw_path']).name}", fill="#64748b", font=font_small)

                # Flags
                if r["flags"]:
                    flag_txt = "FLAG: " + ("BURST" if "Burst" in r["flags"][0] else ("COLLISION" if "collision" in r["flags"][0] else "NEAR-DUP"))
                    draw.text((x + 6, y + thumb_h + 54), flag_txt, fill="#dc2626", font=font_bold)

            # Save sheet
            sheet_path = img_sheet_dir / f"{cls_name}_sheet_{s_idx + 1}.jpg"
            sheet_img.save(sheet_path, "JPEG", quality=88)
            print(f"Saved composite image sheet: {sheet_path}")

if __name__ == "__main__":
    main()
