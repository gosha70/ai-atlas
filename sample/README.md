# AI-ATLAS Sample Project

Standalone sample demonstrating the `com.egoge.ai-atlas` Gradle plugin.
This project applies the published plugin and uses the `agentic { }` extension
DSL — no manual `annotationProcessor` dependencies or `-A` compiler args needed.

## Prerequisites

- **Java 21+** — the Gradle daemon must run on Java 21 (the plugin is compiled for Java 21)
- AI-ATLAS artifacts in `mavenLocal` (for development) or Maven Central (for released versions)

## Quick Start

### 1. Publish framework locally (from monorepo root)

```bash
cd /path/to/ai-atlas
./gradlew publishToMavenLocal
```

### 2. Build the sample

```bash
cd sample
JAVA_HOME=/path/to/java-21 ./gradlew build
```

Or use the validation script (auto-detects Java 21):

```bash
./scripts/validate-sample.sh
```

### 3. Run

```bash
JAVA_HOME=/path/to/java-21 ./gradlew bootRun
```

### 4. Test endpoints

```bash
# Active endpoint (GET — zero params)
curl -X GET http://localhost:8080/api/v2/product-service/list-all

# Deprecated endpoint (check Deprecation header in response)
curl -v -X POST 'http://localhost:8080/api/v2/product-service/find-by-id?id=1'

# New v2 endpoint
curl -X POST 'http://localhost:8080/api/v2/product-service/find-by-id-v2?id=1'

# Version negotiation — matching version passes
curl -H "Accept-Version: 2" -X GET http://localhost:8080/api/v2/product-service/list-all

# Version negotiation — mismatched version returns 400
curl -H "Accept-Version: 1" -X GET http://localhost:8080/api/v2/product-service/list-all
```

## What the Plugin Configures

The key sections in `build.gradle.kts`:

```kotlin
plugins {
    id("com.egoge.ai-atlas") version "0.1.0-SNAPSHOT"
}

// Required — the plugin derives Maven coordinates for framework modules
// (com.egoge:ai-atlas-annotations:{version}, etc.) from project.version.
// Without this, standalone builds resolve "unspecified".
version = "0.1.0-SNAPSHOT"

agentic {
    apiMajorVersion.set(2)
    mcpEnabled.set(false)
}
```

The plugin + `agentic { }` block automatically:
- Adds `annotations`, `processor`, and `runtime` dependencies using `project.version`
- Wires `-Aai.atlas.api.major=2` and related compiler options
- Generates DTOs, REST controllers, OpenAPI specs, and version metadata

## Features Demonstrated

| Feature | How |
|---------|-----|
| **Plugin DSL** | `agentic { apiMajorVersion.set(2) }` — zero manual wiring |
| **Field versioning** | `priceCents` deprecated in v2, `priceMajor`/`priceMinor` added in v2, `legacySku` removed in v2 |
| **Method versioning** | `findById` deprecated with replacement, `findByIdV2` new in v2, `findByTag` future (v3) |
| **PII exclusion** | `supplierContact` not annotated — excluded from generated DTO |
| **Deprecation headers** | `Deprecation: true` + `Link` on deprecated endpoints |
| **Version negotiation** | `Accept-Version: 2` passes, `Accept-Version: 1` returns 400 |
| **Versioned OpenAPI** | `openapi-v2.json` + `openapi.json` alias |
