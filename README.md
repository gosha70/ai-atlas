# AI-ADAM

**AI Auto-Discoverable API Management** — a Java annotation processor framework that generates PII-safe DTOs, MCP tools, REST controllers, and OpenAPI specs at compile time.

## What It Does

Annotate your service methods with `@AgenticExposed` and your entity fields with `@AgentVisible` — the compiler auto-generates everything an AI agent needs to safely interact with your API. Unannotated fields are structurally excluded (whitelist = safe by default).

```java
@AgentVisibleClass
public class Order {

    @AgentVisible(description = "Unique order identifier")
    private Long id;

    @AgentVisible(description = "Current order status")
    private String status;

    // Not annotated — excluded from generated DTO
    private String customerSsn;
    private String creditCardNumber;
}
```

At compile time, the processor generates:

1. **PII-safe DTO record** with only whitelisted fields
2. **MCP tool** for AI agent interaction (Spring AI `@Tool`)
3. **REST controller** with request mapping
4. **OpenAPI 3.0 spec** at `META-INF/openapi/openapi.json`

```java
// Generated DTO — only safe fields, no SSN or credit card
@Generated("ai.adam.processor")
public record OrderDto(Long id, String status, Long totalAmountCents, int itemCount) {
    public static OrderDto fromEntity(Order entity) {
        if (entity == null) return null;
        return new OrderDto(entity.getId(), entity.getStatus(),
                entity.getTotalAmountCents(), entity.getItemCount());
    }
}
```

## Quickstart

### 1. Add dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.adam:annotations:0.1.0")
    implementation("ai.adam:runtime:0.1.0")
    annotationProcessor("ai.adam:processor:0.1.0")
}
```

Or use the Gradle plugin:

```kotlin
plugins {
    id("ai.adam.gradle-plugin") version "0.1.0"
}
```

### 2. Annotate your entities

```java
@AgentVisibleClass
public class Order {
    @AgentVisible(description = "Order ID") private Long id;
    @AgentVisible(description = "Status")   private String status;
    private String customerSsn; // excluded — not annotated
}
```

### 3. Annotate your services

```java
@AgenticExposed(description = "Order management", returnType = Order.class)
@Service
public class OrderService {
    public Order findById(Long id) { ... }
    public List<Order> findByStatus(String status) { ... }
}
```

### 4. Build and run

```bash
./gradlew build         # generates DTOs, MCP tools, REST controllers, OpenAPI
./gradlew bootRun       # starts Spring Boot with MCP server + REST API
```

Generated artifacts:
- `OrderDto.java` — PII-safe record
- `OrderServiceMcpTool.java` — MCP tool with `@Tool` annotations
- `OrderServiceRestController.java` — REST endpoints at `/api/v1/order-service/`
- `META-INF/openapi/openapi.json` — OpenAPI 3.0 spec

## Modules

| Module | Description |
|--------|-------------|
| `modules/annotations` | `@AgentVisible`, `@AgentVisibleClass`, `@AgenticExposed` — zero dependencies |
| `modules/processor` | JSR 269 annotation processor with JavaPoet code generation |
| `modules/runtime` | Spring Boot auto-configuration, MCP server wiring, PII audit interceptor |
| `modules/gradle-plugin` | Gradle plugin for one-liner adoption |
| `demo` | Demo Spring Boot application consuming the framework |
| `demo-frontend` | Next.js frontend generated from the OpenAPI spec |

## Requirements

- Java 21+ (auto-provisioned via Gradle toolchain if not installed)
- Gradle 8.12 (wrapper included)
- Spring Boot 3.4+ (for runtime module)

## Build

```bash
./gradlew build                    # build all modules + run tests
./gradlew :demo:compileJava       # trigger annotation processing in demo
./gradlew :demo:bootRun           # run demo app (REST + MCP SSE)
./gradlew :modules:processor:test  # processor tests only
./gradlew publishToMavenLocal     # publish all modules to ~/.m2
```

## Annotations

### `@AgentVisible`
Applied to fields. Marks a field for inclusion in the generated DTO.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `description` | `String` | `""` | Human-readable field description for LLM consumption |
| `sensitive` | `boolean` | `false` | Whether the field should be masked in audit logs |

### `@AgentVisibleClass`
Applied to classes. Triggers DTO generation for the annotated entity.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `dtoName` | `String` | `{ClassName}Dto` | Custom name for the generated DTO |
| `packageName` | `String` | `{pkg}.generated` | Override package for the generated DTO |

### `@AgenticExposed`
Applied to types or methods. Triggers MCP tool and REST controller generation.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `toolName` | `String` | `""` | Name for the generated MCP tool |
| `description` | `String` | `""` | What this tool does and when to use it |
| `returnType` | `Class<?>` | `void.class` | Entity type for DTO response mapping |

## PII Safety

The framework provides compile-time and runtime PII protection:

**Compile time:**
- Only `@AgentVisible` fields appear in generated DTOs (structural exclusion)
- Heuristic PII detection warns about fields matching patterns like `ssn`, `password`, `creditCard`
- Configurable custom patterns via `-Aai.adam.pii.patterns=salary,homeAddress`

**Runtime:**
- `DtoResponseBodyAdvice` warns if generated controllers return non-DTO types
- `PiiAuditInterceptor` logs all API requests with correlation IDs
- MDC-based structured logging for audit trails

## Processor Options

| Option | Description |
|--------|-------------|
| `-Aai.adam.pii.patterns=keyword1,keyword2` | Additional PII field name patterns (comma-separated) |

## Edge Case Handling

| Scenario | Behavior |
|----------|----------|
| Interface with `@AgentVisibleClass` | Warning, skipped (no fields) |
| Enum with `@AgentVisibleClass` | Warning, skipped |
| Abstract class with `@AgentVisibleClass` | Warning, DTO still generated |
| Static inner class | Fully supported |
| Inherited `@AgentVisible` fields | Walked up superclass chain |
| Boolean fields | Uses `isX()` getter convention |
| Enum-typed fields | Preserved as-is in DTO |

## Publishing

Modules are configured for Maven Central publishing:

```bash
# Local testing
./gradlew publishToMavenLocal

# Maven Central (requires OSSRH credentials + GPG key)
OSSRH_USERNAME=... OSSRH_PASSWORD=... GPG_SIGNING_KEY=... GPG_SIGNING_PASSWORD=... \
  ./gradlew publishMavenJavaPublicationToOssrhRepository
```

## License

Copyright (c) 2026 egoge.com. All rights reserved.
