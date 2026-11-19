#!/bin/bash
# Executed by the user inside the supported stock Terminal VM. The Android
# plugin stages the disk + public key in shared storage and authenticates this
# script before invoking it from a private temporary directory.
set -euo pipefail
exec python3 - "$@" <<'PY'
import ctypes
import errno
import fcntl
import gzip
import hashlib
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile

stock = """@STOCK_SHA256SUMS@"""
diskdir = Path('/mnt/internal/linux')
live = diskdir / 'root_part'
backup = diskdir / 'root_part_backup'
mode = sys.argv[1:]
if mode not in ([], ['--restore']):
    sys.exit('Usage: replace.sh [--restore]')


def stop(reason):
    raise SystemExit(reason)


def check_file(path):
    if not stat.S_ISREG(path.lstat().st_mode):
        stop(f'Expected a regular file: {path}')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def mount_info(path, field):
    return subprocess.check_output(['findmnt', '-nr', '-T', path, '-o', field], text=True).strip()


def check_profile():
    if os.geteuid() != 0 or Path('/sdcard').exists() or not Path('/run/systemd/system').is_dir():
        stop('Run from the Debian Terminal VM with sudo.')
    if mount_info('/mnt/internal', 'FSTYPE') != 'virtiofs' or mount_info('/mnt/internal', 'TARGET') != '/mnt/internal':
        stop('This VM does not expose the supported internal virtiofs share.')
    if mount_info('/', 'FSTYPE') != 'ext4' or Path(mount_info('/', 'SOURCE')).resolve() != Path('/dev/vda1'):
        stop('Unsupported Terminal root partition. The stock v4 /dev/vda1 layout is required.')
    if diskdir.resolve() != diskdir:
        stop('Unexpected system disk directory')
    check_file(live)
    for line in stock.splitlines():
        expected, filename = line.split('  ', 1)
        path = diskdir / filename
        check_file(path)
        if digest(path) != expected:
            stop(f'Unsupported Terminal build or modified {filename}. No disk was changed.')


def confirm(words, description):
    print(description, flush=True)
    with open('/dev/tty', 'r+') as tty:
        tty.write(f'Type {words} to proceed: ')
        tty.flush()
        if tty.readline().strip() != words:
            stop('Cancelled.')


def rename2(src, dst, flags):
    renamed = ctypes.CDLL(None, use_errno=True).renameat2
    if renamed(-100, os.fsencode(src), -100, os.fsencode(dst), flags) != 0:
        e = ctypes.get_errno()
        raise OSError(e, os.strerror(e), str(dst))


def swap(src, dst):
    # RENAME_EXCHANGE is one filesystem transaction. crosvm retains an open
    # descriptor to the old inode until the user powers off this VM.
    rename2(src, dst, 2)
    fd = os.open(diskdir, os.O_RDONLY | os.O_DIRECTORY)
    try:
        try:
            os.fsync(fd)
        except OSError as e:
            if e.errno not in (errno.EINVAL, errno.ENOTSUP):
                raise
            os.sync()
    finally:
        os.close(fd)


def verify_copy(src, dest, expected):
    if not re.fullmatch(r'[a-f0-9]{64}', expected):
        stop('Missing trusted install digest from Pocket Studio')
    with src.open('rb') as inp, dest.open('xb') as out:
        shutil.copyfileobj(inp, out, 1024 * 1024)
    if digest(dest) != expected:
        stop(f'Checksum mismatch: {src.name}')


def write_key(image, local, name, uid):
    field = ['debugfs', '-w', '-R']
    for command in (f'write {local} {name}', f'set_inode_field {name} uid {uid}',
                    f'set_inode_field {name} gid {uid}', f'set_inode_field {name} mode 0100600'):
        subprocess.run(field + [command, image], check=True, stdout=subprocess.DEVNULL)
    # debugfs may exit zero even when an individual write command failed.
    actual = subprocess.check_output(['debugfs', '-R', f'cat {name}', image], stderr=subprocess.DEVNULL)
    if actual != local.read_bytes():
        stop(f'Failed to enroll {name}')
    inode = subprocess.check_output(['debugfs', '-R', f'stat {name}', image], stderr=subprocess.DEVNULL, text=True)
    if not re.search(r'Mode:\s+0600\b', inode) or not re.search(rf'User:\s+{uid}\s+Group:\s+{uid}\b', inode):
        stop(f'Incorrect key ownership or mode: {name}')


def install():
    if os.path.lexists(backup):
        stop(f'Existing Terminal backup at {backup}; export and manage it before installing.')
    directory = Path(os.environ['STUDIO_SOURCE_DIR']).resolve(strict=True)
    if Path('/mnt/shared') not in directory.parents or mount_info(directory, 'FSTYPE') != 'virtiofs':
        stop('Choose a Terminal shared folder containing the staged image.')
    confirm('REPLACE', 'This replaces the Terminal system disk on next boot. Existing files remain on the original disk '
            'as root_part_backup. Export important files separately before continuing.')
    os.umask(0o077)
    # Use the internal share for staging, including the verified compressed copy,
    # so the stock guest root partition can be smaller than the installer.
    with tempfile.TemporaryDirectory(prefix='.studio-image-', dir=diskdir) as folder, tempfile.TemporaryDirectory() as keys:
        stage = Path(folder)
        keys = Path(keys)
        candidate = stage / 'root_part'
        archive = stage / 'debian.img.gz'
        authorized = keys / 'authorized_keys'
        host = keys / 'host'
        verify_copy(directory / 'debian.img.gz', archive, os.environ['STUDIO_IMAGE_SHA256'])
        verify_copy(directory / 'authorized_keys', authorized, os.environ['STUDIO_KEY_SHA256'])
        lines = authorized.read_text().splitlines()
        if len(lines) != 1 or not lines[0].startswith('ecdsa-sha2-nistp256 '):
            stop('Expected one Pocket Studio ECDSA P-256 public key')
        subprocess.run(['ssh-keygen', '-lf', authorized], check=True)
        expected_bytes = 8 * 1024**3
        total = 0
        expanded_hash = hashlib.sha256()
        with gzip.open(archive, 'rb') as stream, candidate.open('xb') as out:
            while chunk := stream.read(1024 * 1024):
                total += len(chunk)
                expanded_hash.update(chunk)
                if total > expected_bytes:
                    stop('Unexpected image length')
                if chunk.count(0) == len(chunk):
                    out.seek(len(chunk), os.SEEK_CUR)
                else:
                    out.write(chunk)
            out.truncate(total)
            out.flush()
            os.fsync(out.fileno())
            if hasattr(os, 'posix_fadvise'):
                try:
                    os.posix_fadvise(out.fileno(), 0, 0, os.POSIX_FADV_DONTNEED)
                except OSError:
                    pass
        if total != expected_bytes:
            stop('Truncated image')
        if digest(candidate) != expanded_hash.hexdigest():
            stop('The internal share failed the full system disk read-back check')
        archive.unlink()
        subprocess.run(['ssh-keygen', '-q', '-t', 'ecdsa', '-b', '256', '-N', '', '-f', host], check=True)
        write_key(candidate, host, '/etc/pocket-studio/ssh_host_ecdsa_key', 0)
        write_key(candidate, authorized, '/home/studio/.ssh/authorized_keys', 1100)
        with candidate.open('rb') as out:
            os.fsync(out.fileno())
        # Verify actual image contents before changing either boot or recovery.
        subprocess.run(['e2fsck', '-fn', candidate], check=True)
        fingerprint = subprocess.check_output(['ssh-keygen', '-lf', str(host) + '.pub'], text=True).split()[1]
        print(f'Pin the following SSH host fingerprint in Pocket Studio: {fingerprint}', flush=True)
        os.chmod(candidate, stat.S_IMODE(live.stat().st_mode))
        candidate_identity = candidate.stat()
        rename2(candidate, backup, 1)  # RENAME_NOREPLACE, including concurrent or interrupted installs
        try:
            swap(live, backup)
        except OSError:
            # Before exchange, backup still contains our candidate. Avoid touching
            # either disk if an fsync error followed an already successful swap.
            if not candidate.exists() and os.path.samestat(backup.stat(), candidate_identity):
                backup.unlink()
            raise


check_profile()
with (diskdir / '.pocket-studio-install.lock').open('a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    if mode:
        if not Path('/etc/pocket-studio/image-version').is_file():
            stop('Restore must run from the installed Studio image')
        check_file(backup)
        confirm('RESTORE', 'Restore the original Terminal system disk. The current Studio disk becomes root_part_backup.')
        swap(live, backup)
    else:
        install()
print('System disk switched. Shut down this VM with sudo poweroff, then relaunch Terminal. '
      'Open port 2222 in Terminal settings after Debian starts.')
PY
