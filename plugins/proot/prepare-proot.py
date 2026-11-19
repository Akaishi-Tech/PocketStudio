#!/usr/bin/env python3
"""Refresh the APK-installed PRoot executable and loaders from the signed Termux archive."""
import gzip
import importlib.util
import subprocess
import tarfile
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location("termux_packages", ROOT / "scripts/build-codex-plugin.py")
repo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repo)

# Keep the package paths stable in the APK, including on releases that strip non-lib*.so files.
MAPPING = {
    "proot": {"bin/proot": "libproot_runtime.so", "libexec/proot/loader": "libproot_loader.so"},
    "libtalloc": {"lib/libtalloc.so.2.5.0": "libtalloc.so"},
    "libandroid-shmem": {"lib/libandroid-shmem.so": "libandroid-shmem.so",
                         "share/doc/libandroid-shmem/copyright": "libandroid-shmem.txt"},
}
VERSIONS = {"proot": "5.1.107.102", "libtalloc": "2.5.0", "libandroid-shmem": "0.7"}

def main():
    with tempfile.TemporaryDirectory() as scratch:
        cache = Path(scratch)
        for name in ("Release", "Release.gpg"):
            (cache / name).write_bytes(repo.metadata(repo.DIST + "/" + name, repo.BLOCK))
        repo.verify_signature(cache / "Release", cache / "Release.gpg")
        indexes = repo.indexes_for((cache / "Release").read_text())
        lock = []
        for arch, abi in repo.ABIS.items():
            name = f"main/binary-{arch}/Packages.gz"
            digest, length = indexes[name]
            data = repo.metadata(repo.DIST + "/" + name, length)
            repo.require(repo.sha(data) == digest, "Index does not match verified Release")
            for block in gzip.decompress(data).decode().split("\n\n"):
                fields = dict(row.split(": ", 1) for row in block.splitlines()
                              if row and not row.startswith(" ") and ": " in row)
                pkg = fields.get("Package")
                if pkg not in MAPPING or fields.get("Architecture") != arch:
                    continue
                repo.require(fields["Version"] == VERSIONS[pkg], f"Review the new version of {pkg}")
                row = {"size": int(fields["Size"]), "filename": fields["Filename"], "sha256": fields["SHA256"]}
                deb = repo.package_file(cache, row, False)
                found = set()
                with subprocess.Popen(["dpkg-deb", "--fsys-tarfile", str(deb)], stdout=subprocess.PIPE) as proc:
                    with tarfile.open(fileobj=proc.stdout, mode="r|") as archive:
                        for item in archive:
                            member = item.name.removeprefix("./").removeprefix(repo.PREFIX)
                            if member not in MAPPING[pkg] or not item.isfile():
                                continue
                            filename = MAPPING[pkg][member]
                            dest = (HERE / "src/main/jniLibs" / abi if filename.endswith(".so")
                                    else HERE / "src/main/assets/licenses/proot") / filename
                            repo.atomic_bytes(dest, archive.extractfile(item).read())
                            dest.chmod(0o644)
                            found.add(member)
                    proc.stdout.read()
                    repo.require(proc.wait() == 0, "Could not extract " + pkg)
                repo.require(found == MAPPING[pkg].keys(), "Missing file in " + pkg)
                lock.append(f"{row['sha256']}  {row['filename']}")
        repo.require(len(lock) == len(MAPPING) * len(repo.ABIS), "Missing packages in repository")
        (HERE / "package-sha256.txt").write_text("\n".join(sorted(lock)) + "\n")

if __name__ == "__main__":
    main()
