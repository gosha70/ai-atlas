#!/usr/bin/env bash
# Checks that the contract-ir-gate documentation exists and names each governed concept.
# Verifier for contract-ir-gate FR-019.
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

require_file docs/contract-governance.md || true
require_file docs/annotation-guide.md || true
require_file CHANGELOG.md || true

for text in irVersion openEnum atlasAccept ai.atlas.contract.baseline ai.atlas.contract.locked apiMajor; do
    require_text docs/contract-governance.md "$text"
done
require_text docs/annotation-guide.md openEnum
for text in api.ir.json openEnum atlasAccept ai.atlas.contract.locked; do
    require_text CHANGELOG.md "$text"
done

if [[ "$status" -ne 0 ]]; then
    echo "contract documentation is incomplete (FR-019)" >&2
fi
exit "$status"
