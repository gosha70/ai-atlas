#!/usr/bin/env bash
# Checks that the constraints-and-hints documentation exists and names each governed concept.
# Verifier for constraints-and-hints FR-021.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
status=0

require_file() {
    if [[ ! -f "$ROOT_DIR/$1" ]]; then
        echo "missing file: $1" >&2
        status=1
        return 1
    fi
}

require_text() {
    local file="$1" text="$2"
    if [[ -f "$ROOT_DIR/$file" ]] && ! grep -qF -- "$text" "$ROOT_DIR/$file"; then
        echo "$file does not mention '$text'" >&2
        status=1
    fi
}

require_file docs/constraints-and-hints.md || true
require_file docs/contract-governance.md || true
require_file CHANGELOG.md || true

for text in @AgenticParam @AgenticConstraints ai.atlas.constraints readOnlyHint @Validated \
        spring-boot-starter-validation SYNC UTF-16; do
    require_text docs/constraints-and-hints.md "$text"
done
for text in "irVersion 2" informational; do
    require_text docs/contract-governance.md "$text"
done
for text in ai.atlas.constraints @AgenticParam "irVersion 2" mcp-tools.json; do
    require_text CHANGELOG.md "$text"
done

if [[ "$status" -ne 0 ]]; then
    echo "constraints-and-hints documentation is incomplete (FR-021)" >&2
fi
exit "$status"
