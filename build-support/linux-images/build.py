#!/usr/bin/env python3
"""Build a native-architecture Pocket Studio Debian image on a Linux Actions runner."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import time
from urllib.request import urlopen

HERE = Path(__file__).resolve().parent
OUTPUT = HERE.parents[1] / 'build' / 'linux-images'
DEBIAN = 'docker.io/library/debian@sha256:'
# arm64/amd64 manifests of the Debian 13 slim base, official SDK release SHA-512,
# and the SHA-256 of the entire original Google Ferrochrome v4 archive.
ARCHES = {
    'arm64-v8a': ('aarch64', '24a01011d10eba3a023bc8585b9650b8b29021240d4217c05b905e09ef4a5232', 'arm64',
                  '3533a3869da219cac45c567aadac3d8e96cdf1c6a04fe1c1d5fdeefea5d45af8424488f0144730d11b4a751f7ad546fb11e4c2b2725cd8401efee726711f8f11',
                  '3c903a333edcb2d329b149eaaf9c27fc1b590a0eddead0fd749569f5fd0fdfa5'),
    'x86_64': ('x86_64', '7378260c0a098d27ac206225db5e7d92b8c7c84ea7afccc86eeb513756bbd392', 'x64',
               '577d0eb17fec33d029104c5c63a1ab0accc3e28776274abef2218dbac5af18d2109b5406d26b31d76f5c245215e3ba0926a94c20246c672c41847c9e1eb1dc50',
               'e5267d30d4b50915b9c3816b348a3a929dafffa54da5e12a421b03094ebd52a3'),
}


def checksum(path, algorithm='sha256'):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def download(job):
    path, url, algorithm, expected = job
    if path.is_file() and checksum(path, algorithm) == expected:
        return
    print('Downloading', path.name, flush=True)
    for attempt in range(3):
        try:
            with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as output:
                temp = Path(output.name)
                try:
                    with urlopen(url, timeout=90) as stream:
                        shutil.copyfileobj(stream, output, 1024 * 1024)
                    output.flush()
                    if checksum(temp, algorithm) != expected:
                        raise ValueError(f'Invalid {algorithm} for {url}')
                    output.close()
                    os.chmod(temp, 0o644)
                    temp.replace(path)
                    return
                finally:
                    temp.unlink(missing_ok=True)
        except (OSError, ValueError) as err:
            last_error = err
            time.sleep(2 ** attempt)
    raise RuntimeError(f'Download failed for {url}') from last_error


def sources(abi, variant, cache):
    arch, _, sdk_arch, sdk_hash, google_hash = ARCHES[abi]
    sdk = f'dotnet-sdk-10.0.404-linux-{sdk_arch}.tar.gz'
    stock = f'ferrochrome-4000000-{arch}.tar.gz'
    jobs = [
        (cache / sdk, f'https://builds.dotnet.microsoft.com/dotnet/Sdk/10.0.404/{sdk}', 'sha512', sdk_hash),
        (cache / 'csharp-ls-0.27.0.nupkg', 'https://api.nuget.org/v3-flatcontainer/csharp-ls/0.27.0/csharp-ls.0.27.0.nupkg', 'sha512',
         '5322dc87a6c01641dc1dc993f8a797b0e7b301ba447338a6894c69b065ef6285347568695fe80d34a96a34d5579d42bc399f951e261d3cfc70ed4be0407b0903'),
        (cache / 'jdtls-1.48.0.tar.gz', 'https://download.eclipse.org/jdtls/milestones/1.48.0/jdt-language-server-1.48.0-202506271502.tar.gz', 'sha256',
         'b0a7fa1240e2caf1296d59ea709c525d4a631fbefda49e7182e07e00c1de62c9'),
    ]
    for pkg, version, digest in [
            ('typescript-language-server', '4.3.4', '0pQ4ffAarsf10VwFhyyyqNJhuDd4r6f4dSsQN38WdbQ3WVxqi08p/bI//v1VnOV5CSVd4AeOUY/jfJYheHTQPQ=='),
            ('typescript', '5.8.3', 'p1diW6TqL9L07nNxvRMM7hMMw4c5XOo/1ibL4aAIGmSAt9slTE1Xgw5KWuof2uTOvCg9BY7ZRi+GaF+7sfgPeQ==')]:
        jobs.append((cache / f'{pkg}.tgz', f'https://registry.npmjs.org/{pkg}/-/{pkg}-{version}.tgz',
                     'sha512', base64.b64decode(digest).hex()))
    if variant == 'avf':
        jobs.append((cache / stock, f'https://dl.google.com/android/ferrochrome/4000000/{arch}/images.tar.gz',
                     'sha256', google_hash))
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(download, jobs))
    return sdk, cache / stock


def build_proot(cache, stage, sdk, digest, ca):
    # Executing tar inside the container preserves Debian numeric owner and mode.
    # Never export Android device nodes, host DNS, mounts, or container config.
    cid = subprocess.check_output(['podman', 'run', '-d', '-v', f'{HERE}:/recipe:ro',
                                   '-v', f'{cache}:/cache:ro', '-v', f'{ca}:/build-ca.crt:ro',
                                   DEBIAN + digest, 'sleep', 'infinity'], text=True).strip()
    try:
        subprocess.run(['podman', 'exec', '-e', f'SDK_ARCHIVE={sdk}', cid, 'sh', '/recipe/prepare-debian.sh', 'proot'], check=True)
        # Preserve empty mount-point directories for PRoot's Android bind mounts.
        cmd = ['podman', 'exec', cid, 'tar', '--format=gnu', '--numeric-owner', '--sort=name', '--hard-dereference',
               '--one-file-system', '--mtime=@1795046400', '-C', '/']
        excluded = ['dev/*', 'proc/*', 'sys/*', 'run/*', 'recipe', 'cache', 'build-ca.crt', 'etc/resolv.conf', 'etc/hosts', 'etc/hostname',
                    '.containerenv', '.dockerenv']
        cmd.extend('--exclude=./' + path for path in excluded)
        # AAPT treats .gz assets as expansion requests. Keep the gzip stream opaque.
        archive = stage / 'debian-rootfs.bin'
        with archive.open('wb') as out, subprocess.Popen(cmd + ['-cf', '-', '.'], stdout=subprocess.PIPE) as tar:
            assert tar.stdout is not None
            subprocess.run(['pigz', '-6', '-n'], stdin=tar.stdout, stdout=out, check=True)
            tar.stdout.close()
            if tar.wait() != 0:
                raise RuntimeError('Cannot archive Debian rootfs')
        with tarfile.open(archive, 'r|gz') as image:
            root = image.next()
            if root is None or root.name != '.' or not root.isdir() or (root.mode & 0o700) != 0o700:
                raise RuntimeError('Debian archive root must allow its owner to extract files on Android')
        (stage / 'debian-rootfs.sha256').write_text(checksum(archive) + '\n')
    finally:
        subprocess.run(['podman', 'rm', '--time', '0', '-f', cid], check=True)


def build_avf(cache, stage, sdk, stock, ca):
    # Generate installation constants only from the verified, immutable release archive.
    digests = {}
    image = stage / 'root_part'
    expected = {'root_part', 'build_id', 'vm_config.json', 'vmlinuz', 'initrd.img', 'cidata.iso'}
    seen = set()
    with tarfile.open(stock, 'r|gz') as upstream:
        for member in upstream:
            name = member.name.removeprefix('./')
            if name not in expected or not member.isfile() or name in seen:
                raise ValueError(f'Unrecognized Google image layout: {name}')
            seen.add(name)
            source = upstream.extractfile(member)
            if name == 'root_part':
                with image.open('wb') as disk:
                    shutil.copyfileobj(source, disk, 1024 * 1024)
            else:
                digests[name] = hashlib.file_digest(source, 'sha256').hexdigest()
    if seen != expected:
        raise ValueError('Incomplete Google image')
    script = (HERE / 'replace.sh').read_text()
    lines = [f'{digests[name]}  {name}' for name in sorted(digests)]
    (stage / 'replace.sh').write_text(script.replace('@STOCK_SHA256SUMS@', '\n'.join(lines)))
    subprocess.run(['sudo', 'unshare', '--mount', '--pid', '--fork', '--mount-proc',
                    'bash', str(HERE / 'build-avf.sh'), str(image), str(cache), str(HERE), sdk, str(ca)], check=True)
    archive = stage / 'debian-image.bin'
    with archive.open('wb') as compressed:
        subprocess.run(['pigz', '-6', '-n', '-c', image], stdout=compressed, check=True)
    image.unlink()
    (stage / 'debian.img.sha256').write_text(checksum(archive) + '\n')


def build(abi, variant):
    arch, digest, *_ = ARCHES[abi]
    if platform.machine() != arch:
        raise SystemExit(f'{abi} requires a native {arch} Linux runner')
    cache = OUTPUT / 'cache'
    cache.mkdir(parents=True, exist_ok=True)
    final = OUTPUT / abi / variant
    if variant == 'proot':
        final = final / 'assets'
    final.mkdir(parents=True, exist_ok=True)
    sdk, stock = sources(abi, variant, cache)
    ca = Path(os.environ.get('SSL_CERT_FILE') or '/etc/ssl/certs/ca-certificates.crt').resolve(strict=True)
    with tempfile.TemporaryDirectory(dir=final.parent, prefix='staging-') as work:
        stage = Path(work)
        if variant == 'proot':
            build_proot(cache, stage, sdk, digest, ca)
        else:
            build_avf(cache, stage, sdk, stock, ca)
        for artifact in stage.iterdir():
            print(f'{artifact.name}  {artifact.stat().st_size:,} bytes', flush=True)
            artifact.replace(final / artifact.name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('abi', choices=ARCHES)
    parser.add_argument('--backend', choices=['avf', 'proot', 'both'], default='both')
    args = parser.parse_args()
    for backend in ('avf', 'proot') if args.backend == 'both' else (args.backend,):
        build(args.abi, backend)
