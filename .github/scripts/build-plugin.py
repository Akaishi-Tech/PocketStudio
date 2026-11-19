"""Build a signed plugin for each supported ABI and describe the resulting artifacts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
STORE = "repos/Akaishi-Tech/PocketStudio-Plugins/releases"
IDS = {name: f"com.hcgstudio.pocketstudio.{name}plugin" for name in ("android", "codex", "avf", "proot")}
NAMES = {"android": "Android", "codex": "Codex", "avf": "AVF", "proot": "PRoot"}
FLAVORS = {"arm64-v8a": "arm64V8a", "x86_64": "x86_64", "armeabi-v7a": "armeabiV7a", "x86": "x86"}


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def current_entry(package):
    release = json.loads(subprocess.check_output(["gh", "api", f"{STORE}/tags/plugin-store"]))
    item, = [asset for asset in release["assets"] if asset["name"] == "plugin-store.json"]
    data = subprocess.check_output(["gh", "api", "-H", "Accept: application/octet-stream", f"{STORE}/assets/{item['id']}"])
    digest = hashlib.sha256(data).hexdigest()
    if item["digest"] != f"sha256:{digest}":
        raise ValueError("Published plugin catalog digest mismatch")
    return next((p for p in json.loads(data)["plugins"] if p["package"] == package), {})


def run(plugin):
    os.chdir(ROOT)
    out = ROOT / "build/plugin-artifacts" / plugin
    out.mkdir(parents=True, exist_ok=True)
    package = IDS[plugin]
    previous = current_entry(package)
    code = int(previous.get("versionCode", 0)) + 1
    abis = list(FLAVORS) if plugin == "android" else ["arm64-v8a", "x86_64"]
    metadata = out / f"{plugin}-build.json"
    if plugin == "codex":
        resolver = ROOT / "scripts/build-codex-plugin.py"
        lock = ROOT / "build/codex-lock.json"
        subprocess.run(["python3", resolver, "resolve", "--output", lock], check=True)
        version = json.loads(lock.read_text())["version"]
        parts = [int(n) for n in re.split(r"[.-]", version)]
        major, minor, patch = parts[:3]
        code = max(code, 1700003, major * 100000000 + minor * 10000 + patch * 100 + sum(parts[3:]) + 2)
        subprocess.run(["python3", resolver, "build", "--lock", lock, "--version-code", str(code), "--out", out], check=True)
        info = json.loads(metadata.read_text())
    else:
        version = f"1.0.{code - 1}"
        env = dict(os.environ, POCKETSTUDIO_VERSION_NAME=version, POCKETSTUDIO_VERSION_CODE=str(code))
        module = "android-plugin" if plugin == "android" else f"linux-{plugin}-plugin"
        subprocess.run(["./gradlew", "--no-daemon", f":{module}:assembleRelease"], env=env, check=True)
        artifacts = []
        for abi in abis:
            folder = ROOT / "plugins" / plugin / "build/outputs/apk" / FLAVORS[abi] / "release"
            apk, = json.loads((folder / "output-metadata.json").read_text())["elements"]
            dest = out / f"PocketStudio-{NAMES[plugin]}-Plugin-{abi}.apk"
            shutil.copyfile(folder / apk["outputFile"], dest)
            artifacts.append(dict(abi=abi, file=dest.name, size=dest.stat().st_size, sha256=sha(dest)))
        signer = subprocess.check_output(["python3", ROOT / "scripts/plugin_signer.py"], text=True).strip()
        info = dict(package=package, version=version, versionCode=code, signerSha256=signer, artifacts=artifacts)
    info["sourceCommit"] = os.environ["GITHUB_SHA"]
    metadata.write_text(json.dumps(info, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("plugin", choices=IDS)
    run(parser.parse_args().plugin)
