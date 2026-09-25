#!/usr/bin/env bash
# Recompiles the demo and fails if the ai-atlas annotation processor printed any warning.
# Verifier for contract-quality-foundations FR-012: every AI-exposed demo method carries its
# own description, so the missing-description diagnostic stays silent on the demo.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if ! output="$("$ROOT_DIR/gradlew" -p "$ROOT_DIR" :demo:compileJava --rerun-tasks 2>&1)"; then
    printf '%s\n' "$output" >&2
    echo "demo compilation failed" >&2
    exit 1
fi

if warnings="$(grep -F 'warning: [ai-atlas]' <<<"$output")"; then
    printf '%s\n' "$warnings" >&2
    echo "the ai-atlas processor warned while compiling the demo" >&2
    exit 1
fi

echo "demo compiled with no ai-atlas warning"
