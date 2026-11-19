#!/usr/bin/env python3
"""Package the pinned Android OpenJDK images into this application's APK inputs."""
import argparse
import hashlib
import shutil
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = "MojoLauncher/android-openjdk-build-17-25"
# Published action artifact IDs and digests from run 39842631198.
IMAGES = {
    "arm64-v8a": (12454357645, "d135745881690964084ad371ada15034e773e4c4d7b4ac8a6ad6a4ac9e1f1ef7"),
    "armeabi-v7a": (12453713095, "df030d2de38af588ce172555e74348280b1335b1f672e59c608bd918603fd188"),
    "x86": (12455086250, "d1bc683ea68a4d2317dd01e3f23566b96c95821fde6cb7a267e6ea2b8d7eae24"),
    "x86_64": (12453493984, "c4030ca1db758820b76f7cb59f51ae2e5e1e7176d777f6ee9d8b3b684a46fa69"),
}


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fetch(work, abi):
    artifact, digest = IMAGES[abi]
    archive = work / (abi + ".zip")
    if not archive.exists():
        with tempfile.NamedTemporaryFile(dir=work) as download:
            subprocess.run(["gh", "api", f"repos/{REPO}/actions/artifacts/{artifact}/zip"],
                           stdout=download, check=True)
            download.flush()
            if sha256(Path(download.name)) != digest:
                raise ValueError(f"Invalid upstream digest for {abi}")
            shutil.copyfile(download.name, archive)
    if sha256(archive) != digest:
        raise ValueError(f"Invalid upstream digest for {abi}")
    image = work / abi
    image.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as bundle, bundle.open(bundle.namelist()[0]) as packed:
        with tarfile.open(mode="r|xz", fileobj=packed) as source:
            source.extractall(image, filter="data")
    return image


def retained(path):
    return (path.startswith(("bin/", "lib/", "conf/", "legal/", "jmods/")) or path == "release") and not (
        path.endswith((".debuginfo", ".diz", "/src.zip", ".jsa"))
    )


def zip_contents(destination, files, image):
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name in sorted(files):
            entry = zipfile.ZipInfo(name)
            entry.compress_type = zipfile.ZIP_DEFLATED
            with (image / name).open("rb") as src, archive.open(entry, "w") as dest:
                shutil.copyfileobj(src, dest, 256 * 1024)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Directory for cached upstream artifacts")
    parser.add_argument("--from-images", action="store_true",
                        help="Use unpacked, locally rebuilt JDKs in directory/<Android ABI>/")
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    assets = ROOT / "build-support/assets/local-jdk"
    assets.mkdir(parents=True, exist_ok=True)
    abi_assets = {abi: ROOT / "build-support/abi-assets" / abi / "local-jdk" for abi in IMAGES}
    for folder in abi_assets.values():
        folder.mkdir(parents=True, exist_ok=True)
    images = {abi: args.directory / abi if args.from_images else fetch(args.directory, abi) for abi in IMAGES}
    inventories = {}
    for abi, image in images.items():
        for name in ("bin/java", "bin/javac", "bin/jlink", "lib/modules", "jmods/java.base.jmod"):
            if not (image / name).is_file():
                raise ValueError(f"Incomplete JDK: {image / name}")
        native = ROOT / "app/src/main/jniLibs" / abi
        native.mkdir(parents=True, exist_ok=True)
        files = {}
        links = []
        for path in sorted(image.rglob("*")):
            name = path.relative_to(image).as_posix()
            if not retained(name) or not path.is_file():
                continue
            if name.startswith("bin/") or name in ("lib/jspawnhelper", "lib/jexec"):
                installed = "libstudio_jdk_" + name.replace("/", "_") + ".so"
                shutil.copyfile(path, native / installed)
                links.append(name + "\t" + installed)
            else:
                files[name] = sha256(path)
        inventories[abi] = files
        (abi_assets[abi] / (abi + ".links")).write_text("\n".join(links) + "\n")
    first = next(iter(images))
    shared = {name for name, digest in inventories[first].items()
              if all(entries.get(name) == digest for entries in inventories.values())}
    # Module images and JMODs mainly contain the same Java classes. Keep one
    # baseline, then encode ABI-specific bytes as standard RFC 3284 deltas.
    base = {name for name in inventories[first] if all(name in entries for entries in inventories.values())
            and (name.startswith("jmods/") or name in ("lib/modules", "lib/ct.sym"))}
    jmods = {name for name in base if name.startswith("jmods/")}
    zip_contents(assets / "common.zip", (shared | base) - jmods, images[first])
    zip_contents(assets / "common-jmods.zip", jmods, images[first])
    with tempfile.TemporaryDirectory() as work:
        for abi, image in images.items():
            extra = inventories[abi].keys() - shared - base
            package = abi_assets[abi] / (abi + ".zip")
            zip_contents(package, extra, image)
            if abi == first:
                continue
            with zipfile.ZipFile(package, "a") as archive:
                for name in sorted(base):
                    if inventories[abi][name] == inventories[first][name]:
                        continue
                    patch = Path(work) / "change"
                    subprocess.run(["xdelta3", "-f", "-D", "-R", "-A", "-n", "-S", "none",
                                    "-W", "1048576", "-e", "-s", images[first] / name, image / name,
                                    patch], check=True)
                    entry = zipfile.ZipInfo(name + ".vcdiff")
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    archive.writestr(entry, patch.read_bytes())


if __name__ == "__main__":
    main()
