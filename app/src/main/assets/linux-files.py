"""Studio filesystem operations. The environment starts this process at the project root."""
import ctypes
import json
import os
from pathlib import Path
import shutil
import stat
import sys
import tempfile

root = Path.cwd().resolve()


def entry(relative):
    if relative == "":
        return root
    if any(p in ("", ".", "..") for p in relative.split("/")):
        raise ValueError("Expected a project-relative path")
    path = root / relative
    path.parent.resolve(strict=True).relative_to(root)
    return path


def contents(path):
    target = path.resolve(strict=True)
    target.relative_to(root)
    if not target.is_file():
        raise ValueError("Expected a regular file")
    return target


def rename_without_overwrite(source, destination):
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.renameat2(-100, os.fsencode(source), -100, os.fsencode(destination), 1):
        code = ctypes.get_errno()
        raise OSError(code, os.strerror(code), str(destination))


def run(op, path, args):
    if op == "list":
        path.resolve(strict=True).relative_to(root)
        items = []
        with os.scandir(path) as children:
            for item in children:
                # Avoid following directory links during recursive project search.
                if item.is_symlink():
                    try:
                        contents(Path(item.path))
                    except (ValueError, OSError, RuntimeError):
                        continue
                elif not item.is_dir() and not item.is_file():
                    continue
                items.append((item.name, item.is_dir(follow_symlinks=False)))
        print(json.dumps(items))
        return
    if op == "stat":
        path.resolve(strict=True).relative_to(root)
        if path.is_dir() and not path.is_symlink():
            print("directory")
        else:
            contents(path)
            print("file")
        return
    if path == root:
        raise ValueError("Select a project entry")
    if op == "read":
        with contents(path).open("rb") as src:
            shutil.copyfileobj(src, sys.stdout.buffer)
    elif op == "write":
        target = contents(path)
        fd, temporary = tempfile.mkstemp(dir=target.parent)
        try:
            with os.fdopen(fd, "wb") as out:
                shutil.copyfileobj(sys.stdin.buffer, out)
                os.fchmod(out.fileno(), stat.S_IMODE(target.stat().st_mode))
                out.flush()
                os.fsync(out.fileno())
            os.replace(temporary, target)
        finally:
            Path(temporary).unlink(missing_ok=True)
    elif op == "mkdir":
        path.mkdir()
    elif op == "create":
        path.open("xb").close()
    elif op == "delete":
        if path.is_symlink() or not path.is_dir():
            path.unlink()
        else:
            shutil.rmtree(path)
    elif op in ("copy", "move"):
        dest = entry(args[0])
        if dest == root or path in dest.parents or os.path.lexists(dest):
            raise FileExistsError(str(dest))
        if op == "move":
            rename_without_overwrite(path, dest)
        else:
            # Only publish the copy once all bytes and descendants have been written.
            # Resolve both parents before checking containment, including any symlink aliases.
            source = path.resolve(strict=True)
            source.relative_to(root)
            if source.is_dir() and dest.parent.resolve().is_relative_to(source):
                raise ValueError("Cannot copy a directory into itself")
            with tempfile.TemporaryDirectory(dir=dest.parent) as scratch:
                target = Path(scratch) / "entry"
                if path.is_symlink():
                    target.symlink_to(os.readlink(path))
                elif path.is_dir():
                    shutil.copytree(path, target, symlinks=True)
                else:
                    shutil.copy2(contents(path), target)
                rename_without_overwrite(target, dest)
    else:
        raise ValueError("Unknown operation")


try:
    run(sys.argv[1], entry(sys.argv[2]), sys.argv[3:])
except (OSError, ValueError, RuntimeError) as exc:
    print(str(exc), file=sys.stderr)
    sys.exit(2 if isinstance(exc, FileNotFoundError) else 1)
