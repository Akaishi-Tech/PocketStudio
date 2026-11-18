#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
destination="${1:-$project_root/upstream/release}"
mkdir -p "$destination"
cd "$destination"

if [[ "$(pwd -P)" == "$(cd "$project_root/upstream/release" && pwd -P)" ]]; then
    git -C "$project_root" submodule update --init --depth 1 --filter=blob:none --jobs 4 -- upstream/release
    exit 0
fi

# Keep the optional standalone Repo export available outside the Pocket Studio checkout.
repo init --depth=1 --partial-clone --clone-filter=blob:none -g default,platform-linux,layoutlib \
    -u https://android.googlesource.com/platform/manifest -b refs/tags/studio-2026.2.2
repo sync -c -j4 --retry-fetches=3 --no-tags --no-use-superproject
# A canceled initial checkout can leave an empty index even after Repo reports success.
repo forall -e -c 'git diff --cached --quiet HEAD -- && git diff --quiet -- || { echo "Incomplete or modified checkout: $REPO_PATH" >&2; exit 1; }'
repo manifest -r -o "$(pwd)/resolved-manifest.xml"
