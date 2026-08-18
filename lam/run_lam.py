import base64
import os
import time
from pathlib import Path

import requests
from gradio_client import Client, handle_file

SPACE = "3DAIGC/LAM_h5"
BASE = "https://3daigc-lam-h5.hf.space"
ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "artifacts"
ARTIFACTS.mkdir(exist_ok=True)

akuji_portrait = ARTIFACTS / "akuji_portrait.jpg"
akuji_portrait.write_bytes(base64.b64decode((ROOT / "lam/akuji_portrait.jpg.b64").read_text().strip()))
print(f"AKUJI portrait retained privately: {akuji_portrait.stat().st_size} bytes", flush=True)

config = requests.get(f"{BASE}/config", timeout=90)
config.raise_for_status()
components = config.json()["components"]

sample_url = next(c["props"]["samples"][0][0]["url"] for c in components if c.get("id") == 11)
diagnostic_portrait = ARTIFACTS / "official_lam_sample.png"
response = requests.get(sample_url, timeout=120)
response.raise_for_status()
diagnostic_portrait.write_bytes(response.content)
print(f"Official diagnostic portrait ready: {diagnostic_portrait.stat().st_size} bytes", flush=True)

video_samples = next(c["props"]["samples"] for c in components if c.get("id") == 17)
motion_url = video_samples[1][0]["video"]["url"]
motion = ARTIFACTS / "Look_In_My_Eyes.mp4"
response = requests.get(motion_url, timeout=120)
response.raise_for_status()
motion.write_bytes(response.content)
print(f"Official motion ready: {motion.stat().st_size} bytes", flush=True)

token = os.getenv("HF_TOKEN") or None
print(f"Hugging Face authentication present: {bool(token)}", flush=True)
client = Client(SPACE, token=token, verbose=False, download_files=ARTIFACTS)
image_arg = handle_file(str(diagnostic_portrait))
video_arg = {"video": handle_file(str(motion)), "subtitles": None}

client.predict(image_arg, api_name="/assert_input_image")
client.predict(api_name="/prepare_working_dir")
print("Generating with LAM official sample", flush=True)
job = client.submit(image_arg, video_arg, api_name="/core_fn")
last = None
while not job.done():
    status = job.status()
    current = (str(status.code), status.rank, status.queue_size, status.success)
    if current != last:
        print(f"LAM diagnostic status: {current}", flush=True)
        last = current
    time.sleep(5)

print(f"LAM diagnostic final status: {job.status()!r}", flush=True)
result = job.result()
print(f"DIAGNOSTIC_SUCCESS: official LAM sample completed: {result!r}", flush=True)
raise RuntimeError("DIAGNOSTIC_SUCCESS_REACHED")
