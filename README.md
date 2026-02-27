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

At compile time, the processor generates a PII-safe DTO record:

```java
@Generated("ai.adam.processor")
public record OrderDto(Long id, String status) {
    public static OrderDto fromEntity(Order entity) {
        if (entity == null) return null;
        return new OrderDto(entity.getId(), entity.getStatus());
    }
}
```

## Modules

| Module | Description |
|--------|-------------|
| `modules/annotations` | `@AgentVisible`, `@AgentVisibleClass`, `@AgenticExposed` — zero dependencies |
| `modules/processor` | JSR 269 annotation processor with JavaPoet code generation |
| `modules/runtime` | Spring Boot auto-configuration and MCP server wiring |
| `modules/gradle-plugin` | Gradle plugin for one-liner adoption |
| `demo` | Demo application consuming the framework |

## Requirements

- Java 21+ (auto-provisioned via Gradle toolchain if not installed)
- Gradle 8.12 (wrapper included)

## Build

```bash
./gradlew build                    # build all modules + run tests
./gradlew :demo:compileJava       # trigger annotation processing in demo
./gradlew :modules:processor:test  # processor tests only
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
| `returnType` | `Class<?>` | `void.class` | Entity type for response mapping |

## PII Detection

The processor emits compile-time notes for fields matching common PII patterns (`ssn`, `password`, `creditCard`, etc.) that are **not** annotated with `@AgentVisible`, helping developers confirm intentional exclusions.

## License

Copyright (c) 2026 egoge.com. All rights reserved.
