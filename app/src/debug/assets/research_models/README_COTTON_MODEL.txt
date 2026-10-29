Cotton binary pilot model is not included in this patch because a valid TFLite export has not been generated here.
To enable Cotton Pilot in the Debug Scan screen, export cotton_binary_fp32.tflite and
cotton_binary_fp32_manifest.json with CottonResearchModelExport.ipynb or
ml/scripts/export_cotton_pilot_to_tflite.py, then copy both files into this directory.
The app validates the manifest, checkpoint provenance, SHA-256, and tensor shape before use.
Production approval remains BLOCKED; this is research-only, and source labels are unvalidated.
