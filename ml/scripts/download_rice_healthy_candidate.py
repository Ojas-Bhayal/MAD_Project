import os
import sys
import time
import hashlib
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / "data" / "raw" / "rice_mendeley_healthy_candidate"
RAW_DIR.mkdir(parents=True, exist_ok=True)
ARCHIVE_PATH = RAW_DIR / "Original Images.zip"

URL = "https://data.mendeley.com/public-files/datasets/hx6f852hw4/files/bb4d2847-1cab-4e56-aa7a-4aa73f6dcdfc/file_downloaded"
EXPECTED_SHA256 = "eb1e804ae414250d7a333a1c3820c49a520c2f09e2e6a38f280e90f58a4f62fb"
EXPECTED_SIZE = 1664385692

def download_and_extract():
    print(f"Target archive: {ARCHIVE_PATH}")
    print(f"Target size: {EXPECTED_SIZE} bytes (~1.55 GB)")
    print(f"Target SHA-256: {EXPECTED_SHA256}")

    # Check if archive already downloaded and verified
    if ARCHIVE_PATH.exists() and ARCHIVE_PATH.stat().st_size == EXPECTED_SIZE:
        print("Archive already exists on disk. Verifying SHA-256...")
        hasher = hashlib.sha256()
        with open(ARCHIVE_PATH, "rb") as f:
            while chunk := f.read(1024 * 1024 * 4):
                hasher.update(chunk)
        calc_sha = hasher.hexdigest()
        print(f"Computed SHA-256: {calc_sha}")
        if calc_sha == EXPECTED_SHA256:
            print("Archive verification PASSED!")
        else:
            print("Hash mismatch! Re-downloading...")
            ARCHIVE_PATH.unlink()

    if not ARCHIVE_PATH.exists():
        print(f"\nStarting download from {URL}...")
        req = urllib.request.Request(URL, headers={"User-Agent": "Mozilla/5.0"})
        start_t = time.time()
        hasher = hashlib.sha256()
        downloaded = 0
        last_log = start_t

        with urllib.request.urlopen(req) as resp, open(ARCHIVE_PATH, "wb") as out_f:
            total_size = int(resp.headers.get("content-length", EXPECTED_SIZE))
            chunk_size = 1024 * 1024 * 4 # 4MB chunks
            while True:
                chunk = resp.read(chunk_size)
                if not chunk:
                    break
                out_f.write(chunk)
                hasher.update(chunk)
                downloaded += len(chunk)
                now = time.time()
                if now - last_log >= 3.0 or downloaded == total_size:
                    elapsed = now - start_t
                    speed_mb = (downloaded / (1024 * 1024)) / max(elapsed, 0.1)
                    pct = (downloaded / total_size) * 100
                    print(f"  Downloaded: {downloaded / (1024*1024):.1f} / {total_size / (1024*1024):.1f} MB ({pct:.1f}%) | Speed: {speed_mb:.2f} MB/s | Elapsed: {elapsed:.1f}s")
                    last_log = now

        total_time = time.time() - start_t
        print(f"\nDownload finished in {total_time:.1f}s!")
        final_sha = hasher.hexdigest()
        print(f"Downloaded SHA-256: {final_sha}")
        assert final_sha == EXPECTED_SHA256, f"Hash mismatch: expected {EXPECTED_SHA256}, got {final_sha}"

    # Extract archive
    print("\nExtracting archive into", RAW_DIR)
    extract_start = time.time()
    with zipfile.ZipFile(ARCHIVE_PATH, "r") as z:
        namelist = z.namelist()
        print(f"Total entries in zip: {len(namelist)}")
        z.extractall(RAW_DIR)
    print(f"Extraction completed in {time.time() - extract_start:.1f}s!")

if __name__ == "__main__":
    download_and_extract()
