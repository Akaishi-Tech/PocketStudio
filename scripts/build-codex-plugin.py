#!/usr/bin/env python3
"""Resolve signed Termux packages and build the standalone Pocket Studio Codex APK."""
import argparse
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SUPPORT = ROOT / "build-support/codex-runtime"
PIN = ROOT / "app/src/main/res/raw/codex_plugin_signer.der"
KEYRING = SUPPORT / "termux-autobuilds.gpg"
MANIFEST = SUPPORT / "AndroidManifest.xml"
RESOURCES = SUPPORT / "res"
APACHE = SUPPORT / "termux-Apache-2.0.txt"
FINGERPRINT = "CC72CF8BA7DBFA0182877D045A897D96E57CF20C"
REPO = "https://packages.termux.dev/apt/termux-main"
DIST = REPO + "/dists/stable"
ABIS = {"aarch64": "arm64-v8a", "x86_64": "x86_64"}
FILES = {
    "codex": {"bin/codex": "libcodex_app_server.so", "bin/codex-code-mode-host": "codex-code-mode-host"},
    "openssl": {"lib/libcrypto.so.3": "libcrypto.so.3", "lib/libssl.so.3": "libssl.so.3"},
}
NOTICES = {"codex": {"LICENSE", "NOTICE", "license.md"}, "openssl": {"copyright"}}
APK_NAME = "PocketStudio-Codex.apk"
PACKAGE = "com.hcgstudio.pocketstudio.codexruntime"
PREFIX = "data/data/com.termux/files/usr/"
DIGEST = re.compile(r"[0-9a-f]{64}")
BLOCK = 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def digest_file(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(BLOCK), b""):
            value.update(block)
    return value.hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def atomic_bytes(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temp:
        try:
            temp.write(data)
            temp.close()
            os.replace(temp.name, path)
        finally:
            Path(temp.name).unlink(missing_ok=True)


def write_json(path, data):
    atomic_bytes(path, (json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode())


class TermuxOnly(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        destination = urlparse(newurl)
        require(destination.scheme == "https" and destination.netloc == "packages.termux.dev",
                "Unexpected repository redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(url):
    return build_opener(TermuxOnly).open(Request(url, headers={"User-Agent": "PocketStudio-plugin-build",
                                                                "Accept-Encoding": "identity"}), timeout=60)


def metadata(url, limit):
    with fetch(url) as source:
        data = source.read(limit + 1)
    require(0 < len(data) <= limit, "Unexpected metadata length for " + url)
    return data


def verify_signature(release, sig):
    result = subprocess.run(["gpgv", "--keyring", str(KEYRING), "--status-fd", "1", str(sig), str(release)],
                            capture_output=True, text=True)
    signatures = [line.split() for line in result.stdout.splitlines() if line.startswith("[GNUPG:] VALIDSIG ")]
    # VALIDSIG includes the signing fingerprint, hash algorithm ID and signature class.
    require(result.returncode == 0 and any(len(s) >= 11 and s[2] == FINGERPRINT and
            s[9] in {"8", "9", "10"} and s[10] == "00" for s in signatures), "Termux Release signature failed")


def indexes_for(text):
    lines = text.splitlines()
    require("Origin: termux-main stable" in lines and "Suite: stable" in lines, "Unexpected apt origin/suite")
    checks = {}
    for line in lines[lines.index("SHA256:") + 1:]:
        if not line.startswith(" "):
            break
        checksum, size, name = line.split()
        if name in (f"main/binary-{arch}/Packages.gz" for arch in ABIS):
            require(name not in checks and DIGEST.fullmatch(checksum) and 0 < int(size) <= 8 * BLOCK,
                    "Invalid signed index descriptor")
            checks[name] = (checksum, int(size))
    require(len(checks) == len(ABIS), "The signed Release is missing an ABI index")
    return checks


def selected_packages(data, arch):
    packages = {}
    for record in data.decode().split("\n\n"):
        fields = dict(line.split(": ", 1) for line in record.splitlines() if not line.startswith(" ") and ": " in line)
        name = fields.get("Package")
        if name not in FILES or fields.get("Architecture") != arch:
            continue
        filename = fields["Filename"]
        size = int(fields["Size"])
        require(name not in packages and filename.startswith("pool/main/") and filename.endswith(f"_{arch}.deb") and
                all(re.fullmatch(r"[A-Za-z0-9_+:~.-]+", part) and part not in {".", ".."} for part in filename.split("/"))
                and DIGEST.fullmatch(fields["SHA256"]) and 0 < size <= 600 * BLOCK, "Invalid apt package descriptor")
        packages[name] = {"name": name, "architecture": arch, "version": fields["Version"],
                          "filename": filename, "sha256": fields["SHA256"], "size": size}
    require(set(packages) == set(FILES), f"Missing Codex dependencies for {arch}")
    return [packages[name] for name in sorted(packages)]


def lock_for(release_hash, packages):
    version = packages[0]["version"]
    require(re.fullmatch(r"\d+\.\d+\.\d+(?:-\d+)?", version) and
            all(row["version"] == version for row in packages if row["name"] == "codex"),
            "Codex ABIs do not have one common Android version")
    inputs = [Path(__file__).resolve(), MANIFEST, KEYRING, APACHE, SUPPORT / "OpenSSL-NOTICE.txt", PIN]
    inputs.extend(p for p in RESOURCES.rglob("*") if p.is_file())
    recipe = {p.relative_to(ROOT).as_posix(): digest_file(p) for p in inputs}
    return {"version": version, "releaseSha256": release_hash, "packages": packages,
            "buildId": sha(canonical({"recipe": recipe, "packages": packages}))}


def verify_snapshot(cache, release_hash, online=False):
    require(isinstance(release_hash, str) and DIGEST.fullmatch(release_hash), "Invalid Release SHA-256")
    snapshot = cache / "snapshots" / release_hash
    release = snapshot / "Release"
    require(digest_file(release) == release_hash, "Release digest mismatch")
    verify_signature(release, snapshot / "Release.gpg")
    text = release.read_text()
    checks = indexes_for(text)
    packages = []
    for arch in ABIS:
        name = f"main/binary-{arch}/Packages.gz"
        checksum, size = checks[name]
        path = cache / "indexes" / checksum
        if not path.exists() and online:
            data = metadata(DIST + "/" + name, size)
            require(len(data) == size and sha(data) == checksum, "Package index changed during download")
            atomic_bytes(path, data)
        require(path.stat().st_size == size and digest_file(path) == checksum, "Package index checksum mismatch")
        with gzip.open(path, "rb") as compressed:
            data = compressed.read(24 * BLOCK + 1)
        require(len(data) <= 24 * BLOCK, "Oversized apt index")
        packages.extend(selected_packages(data, arch))
    return lock_for(release_hash, packages)


def resolve(cache):
    cache.mkdir(parents=True, exist_ok=True)
    # Retry the complete signed pair if apt publishes new indexes during resolution.
    for attempt in range(3):
        try:
            body = metadata(DIST + "/Release", BLOCK)
            date_line = next(line for line in body.decode().splitlines() if line.startswith("Date: "))
            published = int(parsedate_to_datetime(date_line[6:].replace(" UTC", " GMT")).timestamp())
            head = cache / "latest.json"
            previous = json.loads(head.read_text())["published"] if head.exists() else 0
            require(previous <= published <= int(datetime.now(timezone.utc).timestamp()) + 86400,
                    "Unexpected repository timestamp or rollback")
            release_hash = sha(body)
            # Validate temporary files before replacing an already verified cached Release/signature.
            with tempfile.TemporaryDirectory(dir=cache) as temp:
                tmp = Path(temp)
                (tmp / "Release").write_bytes(body)
                (tmp / "Release.gpg").write_bytes(metadata(DIST + "/Release.gpg", 16 * 1024))
                verify_signature(tmp / "Release", tmp / "Release.gpg")
                snapshot = cache / "snapshots" / release_hash
                for name in ("Release", "Release.gpg"):
                    atomic_bytes(snapshot / name, (tmp / name).read_bytes())
            lock = verify_snapshot(cache, release_hash, online=True)
            write_json(head, {"published": published, "releaseSha256": release_hash})
            return lock
        except (OSError, ValueError, StopIteration) as error:
            if attempt == 2:
                raise RuntimeError("Unable to resolve verified Termux metadata") from error
            time.sleep(1 + attempt)


def package_file(cache, row, offline):
    path = cache / "packages" / (row["sha256"] + ".deb")
    if path.exists() and path.stat().st_size == row["size"] and digest_file(path) == row["sha256"]:
        return path
    require(not offline, f"Missing or corrupt offline package: {row['filename']}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temp:
        try:
            count = 0
            with fetch(REPO + "/" + row["filename"]) as source:
                for block in iter(lambda: source.read(BLOCK), b""):
                    count += len(block)
                    require(count <= row["size"], "Package exceeds signed size")
                    temp.write(block)
            temp.close()
            require(count == row["size"] and digest_file(Path(temp.name)) == row["sha256"], "Invalid package digest")
            os.replace(temp.name, path)
        finally:
            Path(temp.name).unlink(missing_ok=True)
    return path


def unpack(deb, stage, row):
    # Never extract an archive-supplied filesystem path. Only copy the four known native objects and notices.
    name = row["name"]
    abi = ABIS[row["architecture"]]
    binaries = FILES[name]
    notices = {f"share/doc/{name}/{notice}": notice for notice in NOTICES[name]}
    seen = set()
    with subprocess.Popen(["dpkg-deb", "--fsys-tarfile", str(deb)], stdout=subprocess.PIPE) as process:
        try:
            with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
                for member in archive:
                    path = member.name.removeprefix("./")
                    if not path.startswith(PREFIX):
                        continue
                    path = path[len(PREFIX):]
                    if path not in binaries and path not in notices:
                        continue
                    require(path not in seen, "Duplicate package member")
                    seen.add(path)
                    if path in notices:
                        if name == "openssl" and member.issym() and member.linkname == "../../LICENSES/Apache-2.0.txt":
                            data = APACHE.read_bytes()
                        else:
                            require(member.isfile() and 0 < member.size < BLOCK, "Invalid legal notice")
                            data = archive.extractfile(member).read()
                        target = stage / "assets/licenses" / name / notices[path]
                        target.parent.mkdir(parents=True, exist_ok=True)
                        require(not target.exists() or target.read_bytes() == data, "Conflicting notices across ABIs")
                        target.write_bytes(data)
                    else:
                        require(member.isfile() and 0 < member.size < 800 * BLOCK, "Unexpected native binary")
                        target = stage / "lib" / abi / binaries[path]
                        target.parent.mkdir(parents=True, exist_ok=True)
                        with target.open("xb") as out:
                            shutil.copyfileobj(archive.extractfile(member), out, BLOCK)
                        audit_elf(target, row["architecture"], path.startswith("bin/"))
            require(process.wait() == 0, "Unable to read Debian package")
        finally:
            process.stdout.close()
            if process.poll() is None:
                process.kill()
    require(seen == set(binaries) | set(notices), f"Incomplete {name}/{abi} package")


def audit_elf(path, arch, executable):
    header = subprocess.check_output(["readelf", "-W", "-h", "-l", "-d", str(path)],
                                     env={**os.environ, "LC_ALL": "C"}, text=True)
    machine = "AArch64" if arch == "aarch64" else "Advanced Micro Devices X86-64"
    require(re.search(r"Machine:\s+" + re.escape(machine) + r"\s*$", header, re.MULTILINE) and
            re.search(r"Class:\s+ELF64", header), f"Wrong ELF architecture: {path.name}")
    loads = re.findall(r"^\s*LOAD\b.*?\s(0x[0-9a-fA-F]+)$", header, re.MULTILINE)
    require(loads and all(int(align, 16) >= 16384 for align in loads), "ELF lacks Android 16 KiB alignment")
    if executable:
        require("Requesting program interpreter: /system/bin/linker64]" in header, "Expected Android Bionic executable")
    needed = set(re.findall(r"Shared library: \[([^\]]+)\]", header))
    supported = {"libc.so", "libdl.so", "liblog.so", "libm.so", *FILES["openssl"].values()}
    require(not needed - supported, f"{path.name} needs unbundled libraries: {sorted(needed - supported)}")


def sdk_tools():
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    candidates = [p for p in (sdk / "build-tools").iterdir() if re.fullmatch(r"\d+\.\d+\.\d+", p.name)]
    build = (sdk / "build-tools" / os.environ["ANDROID_BUILD_TOOLS_VERSION"] if "ANDROID_BUILD_TOOLS_VERSION" in os.environ
             else max(candidates, key=lambda p: tuple(map(int, p.name.split(".")))))
    platforms = [p for p in (sdk / "platforms").iterdir() if re.fullmatch(r"android-\d+(?:\.\d+)?", p.name)]
    platform = (sdk / "platforms" / os.environ["ANDROID_PLATFORM"] if "ANDROID_PLATFORM" in os.environ
                else max(platforms, key=lambda p: tuple(map(int, p.name[8:].split(".")))))
    return build, platform / "android.jar"


def assemble(stage, lock, code, output):
    tools, android = sdk_tools()
    work = stage.parent
    template = work / "manifest.apk"
    compiled_resources = work / "resources.zip"
    subprocess.run([tools / "aapt2", "compile", "--dir", RESOURCES, "-o", compiled_resources], check=True)
    subprocess.run([tools / "aapt2", "link", "-I", android, "--manifest", MANIFEST, "--version-code", str(code),
                    "--version-name", lock["version"], "-o", template, compiled_resources], check=True)
    with zipfile.ZipFile(template) as compiled:
        compiled.extractall(stage)
    # Fixed ZIP metadata also makes offline rebuilds deterministic with the same SDK and signing key.
    with zipfile.ZipFile(work / "unsigned.apk", "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as apk:
        for filename in sorted(p for p in stage.rglob("*") if p.is_file()):
            key = filename.relative_to(stage).as_posix()
            entry = zipfile.ZipInfo(key, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (0o100755 if key.startswith("lib/") else 0o100644) << 16
            entry.compress_type = zipfile.ZIP_STORED if key == "resources.arsc" else zipfile.ZIP_DEFLATED
            with filename.open("rb") as src, apk.open(entry, "w") as dst:
                shutil.copyfileobj(src, dst, BLOCK)
    aligned = work / "aligned.apk"
    subprocess.run([tools / "zipalign", "-P", "16", "-f", "4", work / "unsigned.apk", aligned], check=True)
    signed = work / APK_NAME
    subprocess.run([tools / "apksigner", "sign", "--ks", os.environ["ANDROID_KEYSTORE_PATH"], "--ks-key-alias",
                    os.environ["ANDROID_KEY_ALIAS"], "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD",
                    "--key-pass", "env:ANDROID_KEY_PASSWORD", "--v4-signing-enabled", "false", "--out", signed, aligned],
                   check=True)
    verified = subprocess.check_output([tools / "apksigner", "verify", "--verbose", "--print-certs", signed], text=True)
    signers = re.findall(r"^(?:Signer #\d+|V[\d.]+ Signer:) certificate SHA-256 digest: ([0-9a-f]{64})$",
                         verified, re.MULTILINE)
    require("Number of signers: 1" in verified.splitlines() and set(signers) == {digest_file(PIN)},
            "The plugin does not have the project's pinned release signer")
    require("Verified using v2 scheme (APK Signature Scheme v2): true" in verified, "APK v2 signature missing")
    subprocess.run([tools / "zipalign", "-c", "-P", "16", "4", signed], check=True)
    info = {"package": PACKAGE, "version": lock["version"], "versionCode": code, "buildId": lock["buildId"],
            "file": APK_NAME, "size": signed.stat().st_size, "sha256": digest_file(signed), "signerSha256": signers[0]}
    os.replace(signed, output / APK_NAME)
    write_json(output / "codex-plugin.json", info)
    print(json.dumps(info, ensure_ascii=False))


def build_plugin(args):
    wanted = json.loads(args.lock.read_text())
    try:
        lock = verify_snapshot(args.cache, wanted["releaseSha256"])
    except FileNotFoundError:
        require(not args.offline, "Verified release metadata is missing from offline cache")
        lock = resolve(args.cache)
    require(all(wanted.get(k) == lock[k] for k in ("version", "packages", "buildId")),
            "Lock does not match verified repository metadata and packaging inputs; run resolve again")
    require(0 < args.version_code <= 2_100_000_000, "Invalid Android versionCode")
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".plugin-", dir=args.out) as temp:
        stage = Path(temp) / "contents"
        for row in lock["packages"]:
            unpack(package_file(args.cache, row, args.offline), stage, row)
        assets = stage / "assets"
        (assets / "codex-version").write_text(lock["version"], encoding="ascii")
        (assets / "codex-build").write_text(lock["buildId"], encoding="ascii")
        shutil.copyfile(SUPPORT / "OpenSSL-NOTICE.txt", assets / "licenses/openssl/NOTICE.txt")
        assemble(stage, lock, args.version_code, args.out)


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    actions = cli.add_subparsers(dest="command", required=True)
    resolve_args = actions.add_parser("resolve", help="Validate the upstream indexes and write a package lock")
    build_args = actions.add_parser("build", help="Verify and build both ABI payloads, then sign the universal APK")
    for action in (resolve_args, build_args):
        action.add_argument("--cache", type=Path, default=Path.home() / ".cache/pocketstudio-codex-plugin")
        action.add_argument("--offline", action="store_true")
    resolve_args.add_argument("--output", type=Path, required=True)
    build_args.add_argument("--lock", type=Path, required=True)
    build_args.add_argument("--out", type=Path, required=True)
    build_args.add_argument("--version-code", type=int, required=True)
    args = cli.parse_args()
    if args.command == "resolve":
        if args.offline:
            head = json.loads((args.cache / "latest.json").read_text())
            lock = verify_snapshot(args.cache, head["releaseSha256"])
        else:
            lock = resolve(args.cache)
        write_json(args.output, lock)
        print(f"{lock['version']} {lock['buildId']}")
    else:
        build_plugin(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, RuntimeError, subprocess.CalledProcessError) as failure:
        sys.exit(f"Plugin build failed: {failure}")
