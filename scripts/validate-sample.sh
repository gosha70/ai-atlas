#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Detect Java 21 — check JAVA_HOME first, then foojay-downloaded JDK
if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21\.'; then
    JAVA21_HOME="$JAVA_HOME"
else
    # Look for foojay-provisioned JDK in Gradle's cache
    FOOJAY_DIR="$HOME/.gradle/jdks"
    JAVA21_HOME=$(find "$FOOJAY_DIR" -maxdepth 2 -name "jdk-21*" -type d 2>/dev/null | head -1)
    if [ -n "$JAVA21_HOME" ] && [ -d "$JAVA21_HOME/Contents/Home" ]; then
        JAVA21_HOME="$JAVA21_HOME/Contents/Home"
    elif [ -n "$JAVA21_HOME" ] && [ -f "$JAVA21_HOME/bin/java" ]; then
        : # already correct
    else
        echo "ERROR: Java 21 not found. Set JAVA_HOME to a Java 21 installation." >&2
        echo "       The monorepo build auto-downloads one via foojay — run ./gradlew build first." >&2
        exit 1
    fi
fi

export JAVA_HOME="$JAVA21_HOME"
GRADLE_JVM_ARG="-Dorg.gradle.java.home=$JAVA21_HOME"
echo "Using JAVA_HOME=$JAVA_HOME"

echo "=== Publishing framework to mavenLocal ==="
cd "$ROOT_DIR"
./gradlew publishToMavenLocal "$GRADLE_JVM_ARG"

echo "=== Building sample project ==="
cd "$ROOT_DIR/sample"
./gradlew clean build "$GRADLE_JVM_ARG"

echo "=== Sample validation passed ==="
