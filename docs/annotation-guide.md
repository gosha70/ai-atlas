# Annotation Guide

This guide covers all AI-ATLAS annotations, their attributes, and practical usage patterns.

## @AgentVisibleClass

Marks an entity class for DTO generation. The annotation processor generates a Java record containing only fields annotated with `@AgentVisible`.

**Target:** Classes only (not interfaces or enums)
**Retention:** Source (discarded after compilation)

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
@AgentVisibleClass(
    name = "order",
    description = "A customer purchase order",
    dtoName = "OrderDto"  // optional — this is already the default
)
public class Order {
    // ...
}
```

### What Gets Generated

For each `@AgentVisibleClass`, the processor generates:
- A DTO record with only `@AgentVisible` fields
- A `fromEntity()` null-safe factory method
- `CLASS_NAME`, `CLASS_DESCRIPTION`, `INCLUDE_TYPE_INFO` constants
- A `FIELD_METADATA` map with per-field descriptions, allowed values, and flags
- A nested `FieldMeta` record

---

## @AgentVisible

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

### Example

```java
@AgentVisibleClass(name = "order")
public class Order {

    @AgentVisible(description = "Unique order identifier")
    private Long id;

    @AgentVisible(description = "Current order status")
    private OrderStatus status;  // enum values auto-extracted

    @AgentVisible(
        description = "Total amount in cents",
        name = "totalCents"  // alias in enriched JSON
    )
    private long totalAmountCents;

    @AgentVisible(
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

The processor walks the superclass chain. `@AgentVisible` fields from parent classes are included in the generated DTO, with subclass fields taking precedence on name collisions.

---

## @AgenticExposed

Marks a service class or individual method for MCP tool, REST controller, and OpenAPI spec generation.

**Target:** Types and methods
**Retention:** Source

### Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `toolName` | String | Method or class name | MCP tool name |
| `description` | String | `"Invokes {methodName}"` | Tool and OpenAPI description |
| `returnType` | Class<?> | `void.class` | Entity class for DTO mapping |

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

The processor emits NOTE-level compiler diagnostics for fields that match known PII patterns but are not annotated with `@AgentVisible`. This is a heuristic safety net — the primary protection is the whitelist model itself.

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
