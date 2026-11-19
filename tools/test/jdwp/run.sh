#!/usr/bin/env sh
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/../../.." && pwd)
build_dir=$(mktemp -d)
trap 'rm -rf -- "$build_dir"' EXIT
javac -encoding UTF-8 -g -d "$build_dir" "$root"/plugin-api/src/main/java/com/hcgstudio/pocketstudio/protocol/*.java \
    "$root"/app/src/main/java/com/hcgstudio/pocketstudio/debug/*.java "$root"/tools/test/jdwp/*.java
java -cp "$build_dir" JdwpIntegration "$root/tools/test/jdwp/BreakpointTarget.java"
javac -encoding UTF-8 -g:source,lines -d "$build_dir" "$root/tools/test/jdwp/BreakpointTarget.java"
java -cp "$build_dir" JdwpIntegration "$root/tools/test/jdwp/BreakpointTarget.java" --no-variables
