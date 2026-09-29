#!/usr/bin/env bash
# Checks that the REST mapping documentation exists and names each governed concept.
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

require_file docs/rest-mapping.md || true
require_file docs/annotation-guide.md || true
require_file CHANGELOG.md || true

for text in ai.atlas.rest "agentic { rest = true }" "@Rest(" "@AgenticParam(in" "style = CRUD" resource \
        findById deleteById "204" "Location" "input = false" "<Entity>Input" "toEntity()" PathPattern \
        "{var}" WARNING NOTE; do
    require_text docs/rest-mapping.md "$text"
done
for text in "input" "rest"; do
    require_text docs/annotation-guide.md "| \`$text\` |"
done
for text in ai.atlas.rest "@AgenticParam(in" "Input" "PathPattern"; do
    require_text CHANGELOG.md "$text"
done

if [[ "$status" -ne 0 ]]; then
    echo "REST mapping documentation is incomplete" >&2
fi
exit "$status"
