# Annotation Guide

This guide covers all AI-ATLAS annotations, their attributes, and practical usage patterns.

## @AgenticEntity

Marks an entity class for DTO generation. The annotation processor generates a Java record containing only fields annotated with `@AgenticField`.

**Target:** Classes only (not interfaces or enums)
**Retention:** Runtime (required for reflection by `AgentSafeSerializer`)

### Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `dtoName` | String | `"{ClassName}Dto"` | Name of the generated DTO record |
| `packageName` | String | `"{package}.generated"` | Package for the generated DTO |
| `name` | String | Simple class name | Display name used in enriched JSON `typeInfo` block |
| `description` | String | `""` | Class description included in OpenAPI schema and DTO metadata |
| `includeTypeInfo` | boolean | `true` | Whether enriched JSON output includes the `typeInfo` block |

### Example

```java
@AgenticEntity(
    name = "order",
    description = "A customer purchase order",
    dtoName = "OrderDto"  // optional — this is already the default
)
public class Order {
    // ...
}
```

### What Gets Generated

For each `@AgenticEntity`, the processor generates:
- A DTO record with only `@AgenticField` fields
- A `fromEntity()` null-safe factory method
- `CLASS_NAME`, `CLASS_DESCRIPTION`, `INCLUDE_TYPE_INFO` constants
- A `FIELD_METADATA` map with per-field descriptions, allowed values, and flags
- A nested `FieldMeta` record

---

## @AgenticField

Marks a field for inclusion in the generated DTO. Fields without this annotation are structurally excluded from all generated code.

**Target:** Fields only
**Retention:** Runtime (available for reflection by the serializer)

### Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `description` | String | **(required)** | Field description for LLM consumption and OpenAPI docs |
| `name` | String | Field name | Display name / alias in enriched JSON output |
| `sensitive` | boolean | `false` | Whether the field should be masked in audit logs |
| `checkCircularReference` | boolean | `true` | Enable identity-based cycle detection during serialization |
| `allowedValues` | String[] | `{}` | Explicit list of valid values (overrides automatic enum detection) |
| `type` | Class<?> | `void.class` | Element type hint for raw/wildcard collection fields (e.g., `Collection` without a type parameter) |
| `sinceVersion` | int | `1` | Minimum major API version where this field is included in the generated DTO |
| `removedInVersion` | int | `Integer.MAX_VALUE` | Major version at which this field is removed from the DTO (exclusive — half-open interval) |
| `deprecatedSinceVersion` | int | `0` | Major version at which this field became deprecated (`0` = not deprecated) |
| `deprecatedMessage` | String | `""` | Migration guidance for deprecated fields |

### Example

```java
@AgenticEntity(name = "order")
public class Order {

    @AgenticField(description = "Unique order identifier")
    private Long id;

    @AgenticField(description = "Current order status")
    private OrderStatus status;  // enum values auto-extracted

    @AgenticField(
        description = "Total amount in cents",
        name = "totalCents"  // alias in enriched JSON
    )
    private long totalAmountCents;

    @AgenticField(
        description = "Payment method",
        allowedValues = {"CARD", "BANK_TRANSFER", "CASH"}
    )
    private String paymentMethod;

    // NOT annotated — excluded from DTO
    private String creditCardNumber;
    private String customerSsn;
}
```

### Field Display Names

The `name` attribute creates an alias used in enriched JSON output. The Java field name is still used in the record component and `fromEntity()` method. Display names must be unique within a class — the processor emits a compile error on duplicates.

### Enum Detection

If a field's type is a Java enum, the processor automatically extracts the enum constant names and includes them in `FIELD_METADATA.validValues` and the OpenAPI `enum` constraint. You can override this by specifying `allowedValues` explicitly.

### Inheritance

The processor walks the superclass chain. `@AgenticField` fields from parent classes are included in the generated DTO, with subclass fields taking precedence on name collisions.

### Entity Cross-References

When an `@AgenticField` field's type is another `@AgenticEntity` entity, the generated DTO maps it to the corresponding DTO type:

```java
@AgenticEntity
public class Customer {
    @AgenticField(description = "Customer ID") private Long id;
    @AgenticField(description = "Addresses") private List<Address> addresses;
}
```

The generated `CustomerDto` contains `List<AddressDto>`, with `fromEntity()` mapping via `AddressDto.fromEntity()` and cycle detection for bidirectional relationships.

For raw or wildcard collection types, use the `type` hint:

```java
@AgenticField(description = "Addresses", type = Address.class)
private Collection addresses;  // raw type — hint resolves element type
```

---

## @AgenticExposed

Marks a service class or individual method for MCP tool, REST controller, and OpenAPI spec generation.

**Target:** Types and methods
**Retention:** Runtime

### Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `toolName` | String | Method or class name | MCP tool name |
| `description` | String | `"Invokes {methodName}"` | Tool and OpenAPI description |
| `returnType` | Class<?> | `void.class` | Entity class for DTO mapping |
| `channels` | Channel[] | `{INHERIT}` | Which channels to generate: `AI` (MCP tools), `API` (REST + OpenAPI), or both. `INHERIT` inherits from class-level or framework default `{AI, API}`. |
| `apiSince` | int | `-1` (inherit) | Minimum major API version (framework default: `1`) |
| `apiUntil` | int | `-1` (inherit) | Maximum major API version, inclusive (framework default: `Integer.MAX_VALUE`) |
| `apiDeprecatedSince` | int | `-1` (inherit) | Major version at which this method became deprecated (framework default: `0`) |
| `apiReplacement` | String | `"\0"` (inherit) | Migration guidance for deprecated methods (framework default: `""`) |

### Type-Level Usage

Annotating a class exposes all its public methods:

```java
@Service
@AgenticExposed(
    toolName = "orderService",
    description = "Order management operations",
    returnType = Order.class
)
public class OrderService {
    public Order findById(Long id) { ... }
    public List<Order> findByStatus(String status) { ... }
}
```

### Method-Level Usage

For selective exposure:

```java
@Service
public class OrderService {

    @AgenticExposed(
        toolName = "getOrder",
        description = "Find an order by its ID",
        returnType = Order.class
    )
    public Order findById(Long id) { ... }

    // Not exposed — no annotation
    public void internalReconcile() { ... }
}
```

### Channel Filtering

Control which artifacts are generated per method:

```java
@Service
public class OrderService {

    @AgenticExposed(description = "Find order", returnType = Order.class,
                    channels = { AgenticExposed.Channel.AI })
    public Order findById(Long id) { ... }  // MCP tool only

    @AgenticExposed(description = "List orders", returnType = Order.class,
                    channels = { AgenticExposed.Channel.API })
    public List<Order> listAll() { ... }    // REST + OpenAPI only
}
```

### What Gets Generated

For each `@AgenticExposed` service, the processor generates:

1. **MCP Tool** — a `@Service` class with `@Tool`-annotated methods that delegate to the original service and return DTOs
2. **REST Controller** — a `@RestController` with endpoints under `/api/v1/{service-name}/`
3. **OpenAPI Spec** — paths and schemas appended to `META-INF/openapi/openapi.json`

### REST Endpoint Conventions

- Base path: service class name in kebab-case (`OrderService` → `/api/v1/order-service/`)
- Method path: method name in kebab-case (`findById` → `/find-by-id`)
- Methods with no parameters → `@GetMapping`
- Methods with parameters → `@PostMapping` with `@RequestParam`

---

## PII Detection

The processor emits NOTE-level compiler diagnostics for fields that match known PII patterns but are not annotated with `@AgenticField`. This is a heuristic safety net — the primary protection is the whitelist model itself.

### Default Patterns

Shipped in `META-INF/ai-atlas/pii-patterns.conf` inside the processor JAR:

```
ssn, password, passwd, credit.?card, card.?number,
cvv, cvc, tax.?id, driver.?license, passport, secret
```

### Customizing PII Patterns

**Add keywords** (on top of defaults):
```kotlin
// build.gradle.kts
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Aai.atlas.pii.patterns=salary,homeAddress,dateOfBirth")
}
```

**Replace the entire pattern file** — create a custom file (one regex per line, `#` for comments):
```
# my-pii-patterns.conf
ssn
password
salary
home.?address
date.?of.?birth
```

Then point the processor to it:
```kotlin
// With the Gradle plugin:
agentic {
    piiPatternsFile.set(layout.projectDirectory.file("config/pii-patterns.conf").asFile.absolutePath)
}

// Or without the plugin:
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Aai.atlas.pii.patterns.file=${projectDir}/config/pii-patterns.conf")
}
```

When a custom file is provided, it **replaces** the built-in defaults entirely. The additive `-Aai.atlas.pii.patterns` option still works on top of whichever base set is active.

---

## API Versioning

AI-ATLAS generates versioned API artifacts based on a configured major version. The default is version 1 with base path `/api`, which produces REST endpoints at `/api/v1/...` and an OpenAPI spec at `META-INF/openapi/openapi-v1.json`.

### Version Configuration

**Gradle plugin:**

```kotlin
agentic {
    apiMajorVersion.set(2)
    apiBasePath.set("/services")
    openApiInfoVersion.set("2.0.0")
}
```

**Processor options (without the plugin):**

```kotlin
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf(
        "-Aai.atlas.api.major=2",
        "-Aai.atlas.api.basePath=/services",
        "-Aai.atlas.openapi.infoVersion=2.0.0"
    ))
}
```

| Option | Default | Description |
|--------|---------|-------------|
| `ai.atlas.api.major` | `1` | Major API version used for path prefixes, version filtering, and all generated artifacts |
| `ai.atlas.api.basePath` | `/api` | Base path prefix for generated REST controllers |
| `ai.atlas.openapi.infoVersion` | `"{major}.0.0"` | Version string in the OpenAPI `info.version` field |

### Generated Artifacts

| Artifact | Path | Description |
|----------|------|-------------|
| REST controllers | `/api/v{N}/{service-name}/...` | Versioned endpoint paths |
| OpenAPI spec | `META-INF/openapi/openapi-v{N}.json` | Per-major spec file |
| OpenAPI alias | `META-INF/openapi/openapi.json` | Backward-compatible copy |
| Version properties | `META-INF/ai-atlas/api-version.properties` | Compile-time config for runtime mismatch detection |
| Deprecation manifest | `META-INF/ai-atlas/deprecation-manifest.json` | Per-endpoint deprecation metadata for runtime headers |

---

## Method Version Lifecycle (`@AgenticExposed`)

Version attributes on `@AgenticExposed` use sentinel-based inheritance. A method inherits from the class-level annotation, which inherits from the framework default. Setting a value at any level overrides the inherited value for that attribute only.

| Attribute | Sentinel | Framework Default | Semantics |
|-----------|----------|-------------------|-----------|
| `apiSince` | `-1` | `1` | Method available from this major version |
| `apiUntil` | `-1` | `Integer.MAX_VALUE` | Method available through this major version (inclusive) |
| `apiDeprecatedSince` | `-1` | `0` (not deprecated) | Method deprecated starting at this major version |
| `apiReplacement` | `"\0"` | `""` | Migration guidance text |

### Active predicate

A method is **active** when: `apiSince <= configuredMajor <= apiUntil`

A method is **deprecated** when: `apiDeprecatedSince > 0 && apiDeprecatedSince <= configuredMajor`

Inactive methods are excluded from all generated artifacts (REST controller, MCP tool, OpenAPI spec). Deprecated methods are still generated but marked:
- REST controller: `@Deprecated` annotation on the method
- MCP tool: `[DEPRECATED since vN, use X]` prefix in description
- OpenAPI: `deprecated: true` on the operation

### Inheritance example

```java
@AgenticExposed(description = "Order ops", returnType = Order.class,
                apiSince = 1, apiDeprecatedSince = 3)
@Service
public class OrderService {
    // Inherits apiSince=1, apiDeprecatedSince=3 from class
    public Order findById(Long id) { ... }

    // Overrides apiSince only; inherits everything else from class
    @AgenticExposed(apiSince = 2)
    public List<Order> search(String q) { ... }

    // Overrides both description and version; inherits returnType from class
    @AgenticExposed(description = "Legacy find", apiUntil = 1)
    public Order legacyFind(Long id) { ... }
}
```

### Channel inherit-or-narrow

The `channels` attribute defaults to `{INHERIT}`, meaning:
- If the class declares explicit channels, the method inherits them
- If no class-level annotation exists, the framework default `{AI, API}` applies
- A method may **narrow** (subset) the class channels but **never widen** them
- Mixing `INHERIT` with explicit values (e.g., `{INHERIT, AI}`) is a compile error

```java
@AgenticExposed(returnType = Order.class,
                channels = { Channel.AI, Channel.API })
@Service
public class OrderService {
    // Inherits {AI, API} from class
    public Order findById(Long id) { ... }

    // Narrows to API only — valid (subset of class channels)
    @AgenticExposed(channels = { Channel.API })
    public List<Order> listAll() { ... }

    // COMPILE ERROR — AI is not in the class-level {API} set
    // @AgenticExposed(channels = { Channel.AI })
    // public Order aiOnly(Long id) { ... }
}
```

### Validation rules

The processor emits compile errors for invalid version ranges:

| Condition | Error |
|-----------|-------|
| `apiSince < 1` | Version must be positive |
| `apiDeprecatedSince < 0` | Deprecation version must not be negative |
| `apiSince > apiUntil` | Cannot be introduced after removal |
| `apiDeprecatedSince > 0 && apiDeprecatedSince < apiSince` | Cannot be deprecated before introduced |
| `apiDeprecatedSince > apiUntil` | Cannot be deprecated after removal |

---

## Field Version Lifecycle (`@AgenticField`)

Field version attributes are direct values (no sentinel inheritance — `@AgenticField` has no class/method hierarchy).

| Attribute | Default | Semantics |
|-----------|---------|-----------|
| `sinceVersion` | `1` | Field present from this major version |
| `removedInVersion` | `Integer.MAX_VALUE` | Field removed starting at this version (exclusive) |
| `deprecatedSinceVersion` | `0` | Field deprecated from this version (`0` = not deprecated) |
| `deprecatedMessage` | `""` | Migration guidance |

### Active predicate

A field is **active** when: `sinceVersion <= configuredMajor && configuredMajor < removedInVersion`

Note the **exclusive** upper bound: `removedInVersion = 3` means the field is present in v1 and v2 but absent in v3. This differs from `@AgenticExposed.apiUntil` (inclusive). The naming signals the difference — `removedInVersion` means "removed IN that version" (not present), whereas `apiUntil` means "available THROUGH that version" (still present).

Half-open intervals enable clean field replacement at version boundaries with no overlap or gap:

```java
@AgenticEntity
public class Order {
    @AgenticField(description = "ID") private Long id;

    // Present in v1, v2 — removed in v3
    @AgenticField(description = "Old name", sinceVersion = 1, removedInVersion = 3)
    private String oldName;

    // Introduced in v3
    @AgenticField(description = "New name", sinceVersion = 3)
    private String newName;
}
```

Building with `apiMajor=2` generates `OrderDto(Long id, String oldName)`. Building with `apiMajor=3` generates `OrderDto(Long id, String newName)`.

### Deprecation

A field is **deprecated** when: `deprecatedSinceVersion > 0 && deprecatedSinceVersion <= configuredMajor`

Deprecated fields are still present in the DTO but marked in metadata:
- DTO: `FieldMeta` has `deprecated = true` and `deprecatedMessage` populated
- OpenAPI: schema property has `deprecated: true` and description prefixed with `[DEPRECATED since vN: message]`

Deprecation state is computed at generation time for the configured major. A field with `deprecatedSinceVersion = 3` is not marked as deprecated when building for major 2 — future deprecations do not leak into earlier-major builds.

### Full lifecycle example

```java
@AgenticField(
    description = "Legacy reference",
    sinceVersion = 1,
    deprecatedSinceVersion = 2,
    removedInVersion = 3,
    deprecatedMessage = "Use newRef instead"
)
private String legacyRef;
```

| Major | State |
|-------|-------|
| 1 | Present, not deprecated |
| 2 | Present, deprecated (with message in FieldMeta and OpenAPI) |
| 3 | Absent (filtered out of DTO) |

### Empty entities

When all fields of an entity are filtered out by version, no DTO is generated. If a service method references that entity, the processor emits a compile error — preventing silent fallback to raw entity types that would break PII safety.

### Validation rules

| Condition | Severity |
|-----------|----------|
| `sinceVersion < 1` | ERROR |
| `removedInVersion < 1` | ERROR |
| `sinceVersion >= removedInVersion` | ERROR |
| `deprecatedSinceVersion < 0` | ERROR |
| `deprecatedSinceVersion > 0 && deprecatedSinceVersion < sinceVersion` | ERROR |
| `deprecatedSinceVersion > 0 && deprecatedSinceVersion >= removedInVersion` | ERROR |
| `deprecatedMessage` non-empty but `deprecatedSinceVersion = 0` | WARNING |

---

## Runtime Version Awareness

The runtime module provides version-aware behavior when the application starts.

### Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `ai.atlas.api.base-path` | `/api` | API base path (should match build-time `apiBasePath`) |
| `ai.atlas.api.major` | `1` | API major version (should match build-time `apiMajorVersion`) |
| `ai.atlas.api.deprecation-headers-enabled` | `true` | Emit `Deprecation` and `Link` headers on deprecated endpoints |
| `ai.atlas.api.deprecation-doc-url` | `""` | URL for `Link: <url>; rel="deprecation"` header |
| `ai.atlas.api.version-negotiation.enabled` | `false` | Validate `Accept-Version` request header |

### Compile-Time/Runtime Mismatch Detection

At startup, `VersionMismatchDetector` reads `META-INF/ai-atlas/api-version.properties` and compares against the runtime configuration. If `api.major` or `api.basePath` differ, a structured WARNING log is emitted. This is a warning, not a hard failure — intentional overrides (e.g., staged rollouts) are legitimate.

### Deprecation Headers

`DeprecationHeaderFilter` reads `META-INF/ai-atlas/deprecation-manifest.json` at startup and adds HTTP headers to responses from deprecated REST endpoints:

| Header | Value | Condition |
|--------|-------|-----------|
| `Deprecation` | `true` | Always on deprecated endpoints (RFC 9745) |
| `Link` | `<url>; rel="deprecation"` | When `ai.atlas.api.deprecation-doc-url` is configured |

The filter is keyed by HTTP method + path, so a deprecated POST and an active GET on the same path are distinguished correctly. The `Link` header is appended (not replaced), preserving any existing pagination or canonical link headers.

### Version Negotiation

When `ai.atlas.api.version-negotiation.enabled=true`, the `VersionNegotiationFilter` validates the `Accept-Version` request header:

| Header Value | Response |
|-------------|----------|
| Absent or blank | Passes through (no validation) |
| Matches configured major | Passes through |
| Valid integer but different major | `400` with `{"error": "version_mismatch", ...}` |
| Non-numeric or `< 1` | `400` with `{"error": "invalid_version", ...}` |

This is a guardrail, not a router — single-major generation means only one version exists at runtime.
