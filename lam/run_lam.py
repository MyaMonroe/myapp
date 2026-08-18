import base64
import json
import os
import time
import zipfile
from pathlib import Path

import requests
from gradio_client import Client, handle_file

SPACE = "3DAIGC/LAM_h5"
BASE = "https://3daigc-lam-h5.hf.space"
ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "artifacts"
ARTIFACTS.mkdir(exist_ok=True)

portrait = ARTIFACTS / "akuji_portrait.jpg"
portrait.write_bytes(base64.b64decode((ROOT / "lam/akuji_portrait.jpg.b64").read_text().strip()))
print(f"Portrait ready: {portrait.name} ({portrait.stat().st_size} bytes)", flush=True)

config = requests.get(f"{BASE}/config", timeout=90)
config.raise_for_status()
components = config.json()["components"]
video_samples = next(c["props"]["samples"] for c in components if c.get("id") == 17)
motion_url = video_samples[1][0]["video"]["url"]
motion = ARTIFACTS / "Look_In_My_Eyes.mp4"
with requests.get(motion_url, stream=True, timeout=120) as response:
    response.raise_for_status()
    with motion.open("wb") as output:
        for chunk in response.iter_content(1024 * 1024):
            output.write(chunk)
print(f"Motion ready: {motion.name} ({motion.stat().st_size} bytes)", flush=True)

token = os.getenv("HF_TOKEN") or None
print(f"Hugging Face authentication present: {bool(token)}", flush=True)
image_arg = handle_file(str(portrait))
video_arg = {"video": handle_file(str(motion)), "subtitles": None}

client = None
result = None
last_error = None
for generation_attempt in range(1, 4):
    try:
        print(f"Opening LAM session (attempt {generation_attempt}/3)", flush=True)
        client = Client(SPACE, token=token, verbose=False, download_files=ARTIFACTS)
        client.predict(image_arg, api_name="/assert_input_image")
        client.predict(api_name="/prepare_working_dir")

        print("Generating AKUJI 3D avatar", flush=True)
        job = client.submit(image_arg, video_arg, api_name="/core_fn")
        last = None
        while not job.done():
            status = job.status()
            current = (str(status.code), status.rank, status.queue_size, status.success)
            if current != last:
                print(f"LAM status: {current}", flush=True)
                last = current
            time.sleep(5)

        final_status = job.status()
        print(f"LAM final status: {final_status!r}", flush=True)
        result = job.result()
        print(f"LAM result: {result!r}", flush=True)
        break
    except Exception as error:
        last_error = error
        print(f"LAM attempt {generation_attempt} ended with {type(error).__name__}: {error!r}", flush=True)
        if generation_attempt == 3:
            raise
        delay = 20 * generation_attempt
        print(f"Retrying after {delay} seconds", flush=True)
        time.sleep(delay)

(ARTIFACTS / "result.json").write_text(json.dumps({"result": repr(result)}, indent=2))

print("Activating web renderer package", flush=True)
client.predict(api_name="/doRender")

zip_url = f"{BASE}/gradio_api/file=runtime_data/h5_render_data.zip"
archive = ARTIFACTS / "AKUJI-h5-render-data.zip"
last_status = None
for attempt in range(20):
    response = client.session.get(zip_url, timeout=90)
    last_status = response.status_code
    print(f"ZIP fetch attempt {attempt + 1}: HTTP {response.status_code}", flush=True)
    if response.status_code == 200 and response.content.startswith(b"PK"):
        archive.write_bytes(response.content)
        break
    time.sleep(3)
else:
    raise RuntimeError(f"LAM generated its preview but the package endpoint stayed unavailable (HTTP {last_status}).")

with zipfile.ZipFile(archive) as package:
    names = package.namelist()
    bad = package.testzip()
    if bad:
        raise RuntimeError(f"Corrupt file inside avatar ZIP: {bad}")
    (ARTIFACTS / "AKUJI-h5-contents.txt").write_text("\n".join(names))

print(f"VERIFIED: {archive.name}, {archive.stat().st_size} bytes, {len(names)} files", flush=True)
