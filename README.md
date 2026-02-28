# AI-ADAM

**AI Auto-Discoverable API Management**

## The Problem

Enterprise Java applications contain sensitive data — SSNs, credit card numbers, passwords — mixed alongside business data in entity classes. When exposing APIs for AI agents (via [MCP](https://modelcontextprotocol.io/) or REST), developers must manually write DTOs, tool wrappers, and controllers that exclude PII fields. This is tedious, error-prone, and a single missed field can leak sensitive data to an LLM.

## The Solution

AI-ADAM is a **compile-time annotation processor** that generates PII-safe API layers from two simple annotations:

- **`@AgentVisible`** on entity fields — whitelist what AI agents can see
- **`@AgenticExposed`** on service classes — expose methods as MCP tools and REST endpoints

Everything else is structurally excluded. There is no way for unannotated fields to reach the generated API — the safety guarantee is enforced by the Java compiler, not runtime checks.

From these annotations, the processor generates four artifacts at compile time:

| Generated Artifact | Purpose |
|---|---|
| **Java record DTO** | Contains only `@AgentVisible` fields with a null-safe `fromEntity()` factory |
| **MCP tool class** | Spring AI `@Tool`-annotated service for AI agent interaction via [Model Context Protocol](https://modelcontextprotocol.io/) |
| **REST controller** | Spring `@RestController` with `@PostMapping`/`@GetMapping` endpoints returning DTOs |
| **OpenAPI 3.0 spec** | Machine-readable API description at `META-INF/openapi/openapi.json` |

## How It Works

Given an entity with mixed safe and sensitive fields:

```java
@AgentVisibleClass
public class Order {
    @AgentVisible(description = "Unique order identifier")
    private Long id;

    @AgentVisible(description = "Current order status")
    private String status;

    @AgentVisible(description = "Total order amount in cents")
    private long totalAmountCents;

    @AgentVisible(description = "Number of items in the order")
    private int itemCount;

    // PII — NOT annotated, structurally excluded from all generated code
    private String customerName;
    private String customerEmail;
    private String shippingAddress;
    private String creditCardNumber;
    private String customerSsn;
}
```

And a service:

```java
@AgenticExposed(description = "Order management operations", returnType = Order.class)
@Service
public class OrderService {
    public Order findById(Long id) { ... }
    public List<Order> findByStatus(String status) { ... }
}
```

Running `./gradlew build` generates:

**PII-safe DTO** — only the 4 whitelisted fields, no customer name, no SSN, no credit card:

```java
@Generated("ai.adam.processor")
public record OrderDto(Long id, String status, long totalAmountCents, int itemCount) {
    public static OrderDto fromEntity(Order entity) {
        if (entity == null) return null;
        return new OrderDto(
            entity.getId(), entity.getStatus(),
            entity.getTotalAmountCents(), entity.getItemCount());
    }
}
```

**MCP tool** — AI agents call this via MCP protocol, responses are always DTOs:

```java
@Generated("ai.adam.processor")
@Service
public class OrderServiceMcpTool {
    private final OrderService service;
    // constructor injection...

    @Tool(name = "findById", description = "Order management operations")
    public OrderDto findById(@ToolParam(description = "id") Long id) {
        return OrderDto.fromEntity(service.findById(id));
    }
}
```

**REST controller** — standard Spring endpoints, also returning DTOs:

```java
@Generated("ai.adam.processor")
@RestController
@RequestMapping("/api/v1/order-service")
public class OrderServiceRestController {
    @PostMapping("/find-by-id")
    public OrderDto findById(@RequestParam Long id) { ... }

    @PostMapping("/find-by-status")
    public List<OrderDto> findByStatus(@RequestParam String status) { ... }
}
```

## Demo Application

The `demo/` module is a working Spring Boot app that demonstrates the full pipeline. It contains an `Order` entity with 9 fields (4 safe, 5 PII) and an `OrderService` with two methods.

### Running the demo

```bash
# 1. Build everything (triggers annotation processing)
./gradlew build

# 2. Start the demo app
./gradlew :demo:bootRun
```

The app starts on port 8080 with:

- **REST API** at `http://localhost:8080/api/v1/order-service/`
- **MCP server** over SSE at `http://localhost:8080/sse` (for AI agent connections)

### Try the REST API

```bash
# Find order by ID — returns only safe fields (id, status, totalAmountCents, itemCount)
curl -X POST "http://localhost:8080/api/v1/order-service/find-by-id?id=1"
# → {"id":1,"status":"PENDING","totalAmountCents":9999,"itemCount":3}

# No customerSsn, no creditCardNumber, no customerEmail — they don't exist in the DTO
```

### Try the MCP tools

Connect an MCP client (e.g., [MCP Inspector](https://github.com/modelcontextprotocol/inspector)) to `http://localhost:8080/sse`. Two tools are registered:

- `findById(id)` — returns a single order DTO
- `findByStatus(status)` — returns a list of order DTOs

### Demo frontend

The `demo-frontend/` directory contains a Next.js app that consumes the generated REST API:

```bash
cd demo-frontend
npm install
npm run dev    # starts on http://localhost:3000
```

The frontend displays order data with only the 4 PII-safe fields. It includes a verification panel confirming that `creditCardNumber` and `customerSsn` are structurally excluded.

## Quickstart

### Option A: Gradle plugin (recommended)

```kotlin
plugins {
    id("ai.adam.gradle-plugin") version "0.1.0"
}
```

### Option B: Manual dependencies

```kotlin
dependencies {
    implementation("ai.adam:annotations:0.1.0")
    implementation("ai.adam:runtime:0.1.0")
    annotationProcessor("ai.adam:processor:0.1.0")
}
```

Then annotate your entities with `@AgentVisibleClass` + `@AgentVisible`, your services with `@AgenticExposed`, and build. Generated code appears in `build/generated/sources/annotationProcessor/`.

## Modules

| Module | Description |
|--------|-------------|
| `modules/annotations` | `@AgentVisible`, `@AgentVisibleClass`, `@AgenticExposed` — zero external dependencies |
| `modules/processor` | JSR 269 annotation processor — generates DTOs, MCP tools, REST controllers, OpenAPI specs using JavaPoet |
| `modules/runtime` | Spring Boot auto-configuration — MCP server wiring (SSE transport), PII audit interceptor, DTO response safety check |
| `modules/gradle-plugin` | Gradle plugin — auto-adds all framework dependencies and configures IntelliJ generated source dirs |
| `demo` | Spring Boot demo app with `Order` entity and `OrderService` |
| `demo-frontend` | Next.js frontend consuming the generated REST API |

## Requirements

- Java 17+ to run Gradle, Java 21 for compilation (auto-provisioned via Gradle toolchain)
- Gradle 8.12 (wrapper included)
- Spring Boot 3.4+ (for runtime module)

## Build Commands

```bash
./gradlew build                    # build all modules + run tests
./gradlew :demo:bootRun           # run demo app (REST + MCP SSE on port 8080)
./gradlew :demo:compileJava       # trigger annotation processing only
./gradlew :modules:processor:test  # run processor tests (33 compile-testing tests)
./gradlew publishToMavenLocal     # publish all modules to ~/.m2
```

## Annotation Reference

### `@AgentVisible`

Applied to fields. Marks a field for inclusion in the generated DTO.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `description` | `String` | `""` | Human-readable description for LLM tool parameters and OpenAPI docs |
| `sensitive` | `boolean` | `false` | If true, runtime interceptors may mask this field in audit logs |

### `@AgentVisibleClass`

Applied to classes. Triggers DTO record generation for the annotated entity.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `dtoName` | `String` | `{ClassName}Dto` | Custom name for the generated DTO record |
| `packageName` | `String` | `{pkg}.generated` | Override output package for the generated DTO |

### `@AgenticExposed`

Applied to types or individual methods. Triggers MCP tool and REST controller generation.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `toolName` | `String` | method name | Name for the generated MCP tool |
| `description` | `String` | `"Invokes {methodName}"` | Description for MCP tool and OpenAPI operation |
| `returnType` | `Class<?>` | `void.class` | Entity class to map to DTO in responses |

When applied to a type, all public methods are exposed. When applied to a method, only that method is exposed.

## PII Safety

**Compile-time guarantees:**
- Only `@AgentVisible` fields appear in generated DTOs — structural exclusion, not filtering
- The processor warns about fields matching PII patterns (`ssn`, `password`, `creditCard`, etc.) that are *not* annotated, helping developers confirm intentional exclusions
- Custom PII patterns: `-Aai.adam.pii.patterns=salary,homeAddress,phoneNumber`

**Runtime safety net:**
- `DtoResponseBodyAdvice` logs a warning if a generated controller somehow returns a non-DTO object
- `PiiAuditInterceptor` logs all `/api/v1/**` requests with SLF4J MDC correlation IDs

## Edge Case Handling

| Scenario | Behavior |
|----------|----------|
| Interface with `@AgentVisibleClass` | Warning emitted, skipped (interfaces have no fields) |
| Enum with `@AgentVisibleClass` | Warning emitted, skipped |
| Abstract class with `@AgentVisibleClass` | Warning emitted, DTO still generated |
| Static inner class | Fully supported |
| Inherited `@AgentVisible` fields | Superclass chain walked; parent fields appear first |
| Boolean fields | Uses `isX()` getter convention |
| Enum-typed fields | Preserved as-is in DTO |

## License

Copyright (c) 2026 egoge.com. All rights reserved.
