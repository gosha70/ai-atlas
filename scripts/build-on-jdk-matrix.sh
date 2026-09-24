#!/usr/bin/env bash
# Runs `./gradlew build` twice, with Gradle on JDK 17 and then on JDK 21 — the two legs of the
# CI matrix (.github/workflows/ci.yml) — and fails if either build fails or either JDK cannot
# be found. A missing JDK is a failure, never a skip: the point is evidence from both legs.
# Verifier for contract-quality-foundations FR-019.
#
# JDK lookup, per version: $JDK17_HOME / $JDK21_HOME; then the setup-java variables
# $JAVA_HOME_17_X64 / $JAVA_HOME_17_ARM64 (and _21_); then macOS /usr/libexec/java_home -v N;
# then /usr/lib/jvm/java-N-openjdk-*.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

is_jdk() {
    local home="$1" version="$2"
    [[ -n "$home" && -x "$home/bin/java" && -x "$home/bin/javac" ]] || return 1
    "$home/bin/java" -version 2>&1 | grep -Eq "version \"${version}([.\"])"
}

find_jdk() {
    local version="$1" var candidate
    for var in "JDK${version}_HOME" "JAVA_HOME_${version}_X64" "JAVA_HOME_${version}_ARM64"; do
        candidate="${!var:-}"
        if is_jdk "$candidate" "$version"; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    if [[ -x /usr/libexec/java_home ]]; then
        candidate="$(/usr/libexec/java_home -v "$version" 2>/dev/null || true)"
        if is_jdk "$candidate" "$version"; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi
    for candidate in /usr/lib/jvm/java-"${version}"-openjdk*; do
        if is_jdk "$candidate" "$version"; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

# Plain variables, not an associative array: macOS still ships bash 3.2 as /bin/bash.
if ! JDK17="$(find_jdk 17)"; then
    echo "JDK 17 not found — set JDK17_HOME; this verifier needs both legs" >&2
    exit 1
fi
if ! JDK21="$(find_jdk 21)"; then
    echo "JDK 21 not found — set JDK21_HOME; this verifier needs both legs" >&2
    exit 1
fi

# Tell Gradle's toolchain resolution about both JDKs, so the build needs no JDK download.
installations="$JDK17,$JDK21"

build_on() {
    local version="$1" home="$2"
    echo "=== ./gradlew build with Gradle on JDK $version ($home)"
    if ! JAVA_HOME="$home" "$ROOT_DIR/gradlew" -p "$ROOT_DIR" \
            "-Porg.gradle.java.installations.paths=$installations" build; then
        echo "build failed with Gradle on JDK $version" >&2
        exit 1
    fi
}

build_on 17 "$JDK17"
build_on 21 "$JDK21"

echo "build passed with Gradle on JDK 17 and JDK 21"
