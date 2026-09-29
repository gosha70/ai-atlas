#!/usr/bin/env bash
# Checks that the collection exposure safety documentation exists and names each governed concept.
# Verifier for issue #56's documentation criterion.
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

require_file docs/collection-safety.md || true
require_file CHANGELOG.md || true

for text in ai.atlas.collections Pageable maxResults "paging = LIMIT" CURSOR PageResult SliceResult \
        sortable ai.atlas.strict @AgenticBound returns.bound "irVersion 4" "never paginates or truncates" \
        "rejected, never clamped" max-page-size Optional; do
    require_text docs/collection-safety.md "$text"
done
for text in ai.atlas.collections maxResults PageResult sortable returns.bound; do
    require_text CHANGELOG.md "$text"
done

if [[ "$status" -ne 0 ]]; then
    echo "collection exposure safety documentation is incomplete (#56)" >&2
fi
exit "$status"
