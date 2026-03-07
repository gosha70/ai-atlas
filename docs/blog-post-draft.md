# Stop Hand-Writing MCP Tools: How Compile-Time Annotations Can Secure Your AI Agent APIs

*Your Java entity has 12 fields. Five of them are PII. An AI agent just asked for all of them. What happens next depends on whether you remembered to add `@JsonIgnore` to every single sensitive field -- or whether you designed for safety from the start.*

---

## The Problem Nobody Talks About

Enterprise Java teams are racing to integrate AI agents into their applications. The Model Context Protocol (MCP) has emerged as the standard for connecting LLMs to backend services, and Spring AI makes it approachable. But three hard problems remain unsolved.

### Problem 1: Scale

A typical enterprise application has dozens -- sometimes hundreds -- of CRUD services. Each one needs an MCP tool definition, a DTO that strips internal fields, a REST controller, and an OpenAPI spec. Multiply that across your service catalog and you are looking at months of mechanical boilerplate before a single agent can query your system.

### Problem 2: PII Exposure

Your `Order` entity has the fields an AI agent needs (`id`, `status`, `totalAmountCents`, `itemCount`), but it also has `customerName`, `customerEmail`, `creditCardNumber`, `customerSsn`, and `shippingAddress`. If you expose the entity directly to an MCP tool, you have just handed every AI agent in your pipeline access to PII. This is not a theoretical risk -- it maps directly to **OWASP API3:2023 (Excessive Data Exposure)**.

### Problem 3: No Existing Tool Does This

Spring Data REST generates REST endpoints but not MCP tools. JHipster scaffolds entire applications but does not address PII filtering or agent-safe APIs. Manual Spring AI `@Tool` writing works but does not scale and has no structural PII guardrails. You can convert Swagger specs to MCP tool definitions, but that only moves the problem -- your OpenAPI spec still exposes whatever fields your DTOs expose.

---

## Whitelist > Blacklist: Why `@JsonIgnore` Is the Wrong Model

The conventional approach to hiding sensitive fields is blacklisting: annotate every PII field with `@JsonIgnore` and hope nobody forgets one. The failure mode is catastrophic -- a missing annotation means a data leak.

AI-ATLAS inverts the model. Instead of marking what to **hide**, you mark what to **show**:

```java
@AgentVisibleClass(
    name = "order",
    description = "A customer order with status tracking and item summary"
)
public class Order {

    @AgentVisible(description = "Unique order identifier")
    private Long id;

    @AgentVisible(description = "Current order status")
    private OrderStatus status;

    @AgentVisible(name = "totalCents", description = "Total order amount in cents")
    private long totalAmountCents;

    @AgentVisible(description = "Number of items in the order")
    private int itemCount;

    // These fields exist in the entity but are NEVER exposed.
    // No annotation needed. Absence = exclusion. Safe by default.
    private String customerName;
    private String customerEmail;
    private String shippingAddress;
    private String creditCardNumber;
    private String customerSsn;
}
```

The failure mode is now benign: if a developer forgets `@AgentVisible` on a field, the field is simply absent from the generated DTO. No PII leaks. No incident. The system defaults to safe.

---

## Introducing AI-ATLAS

**AI-ATLAS** (AI Annotation-Driven Tooling & Layered API Synthesis) is a compile-time Java annotation processor that generates PII-safe API layers from two annotations:

1. **`@AgentVisible`** on entity fields -- declares which fields are safe for AI agents.
2. **`@AgenticExposed`** on service classes or methods -- declares which operations should become tools.

From those two inputs, the annotation processor generates four outputs at compile time:

| Generated Artifact | What It Is |
|---|---|
| **Java record DTO** | Contains only `@AgentVisible` fields, with a `fromEntity()` factory method |
| **MCP tool class** | Spring AI `@Tool`-annotated service that delegates to your code and returns the safe DTO |
| **REST controller** | Standard `@RestController` with GET/POST mappings returning safe DTOs |
| **OpenAPI 3.0 spec** | JSON spec describing only the exposed fields and operations |

All generated code is annotated with `@Generated("ai.atlas.processor")` and written to your build output. No source files to maintain. No manual wiring. Just build.

---

## How It Works

### Step 1: Annotate Your Entity

Mark the fields that AI agents are allowed to see:

```java
@AgentVisibleClass(
    name = "order",
    description = "A customer order with status tracking and item summary"
)
public class Order {

    public enum OrderStatus {
        PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
    }

    @AgentVisible(description = "Unique order identifier")
    private Long id;

    @AgentVisible(description = "Current order status")
    private OrderStatus status;

    @AgentVisible(name = "totalCents", description = "Total order amount in cents")
    private long totalAmountCents;

    @AgentVisible(description = "Number of items in the order")
    private int itemCount;

    // PII fields -- no annotation, no exposure
    private String customerName;
    private String customerEmail;
    private String shippingAddress;
    private String creditCardNumber;
    private String customerSsn;

    // getters and setters...
}
```

Notice that `@AgentVisible` carries a `description` attribute. These descriptions flow into the generated MCP tool parameter descriptions and OpenAPI schema docs, giving LLMs the context they need to use fields correctly.

Enum fields like `OrderStatus` are automatically detected -- the processor extracts enum constant names and includes them as valid values in the generated metadata and OpenAPI schemas.

### Step 2: Annotate Your Service

Tell the processor which service operations to expose:

```java
@Service
@AgenticExposed(
    toolName = "orderService",
    description = "Order management operations",
    returnType = Order.class
)
public class OrderService {

    public Order findById(Long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    public List<Order> findByStatus(String status) {
        return orderRepository.findByStatus(OrderStatus.valueOf(status));
    }
}
```

### Step 3: Build

```bash
./gradlew compileJava
```

That is it. The annotation processor runs during compilation and generates four files.

### What Gets Generated

**1. The DTO record** -- only whitelisted fields, with a null-safe factory method:

```java
@Generated("ai.atlas.processor")
public record OrderDto(
    Long id,
    OrderStatus status,
    long totalAmountCents,
    int itemCount
) {
    public static OrderDto fromEntity(Order entity) {
        if (entity == null) return null;
        return new OrderDto(
            entity.getId(),
            entity.getStatus(),
            entity.getTotalAmountCents(),
            entity.getItemCount()
        );
    }
}
```

Five PII fields from the entity (`customerName`, `customerEmail`, `shippingAddress`, `creditCardNumber`, `customerSsn`) are structurally absent. They do not exist in the record. They cannot be serialized. They cannot leak.

**2. The MCP tool** -- a Spring `@Service` with `@Tool` methods that delegate to your service and return the safe DTO:

```java
@Generated("ai.atlas.processor")
@Service
public class OrderServiceMcpTool {

    private final OrderService service;

    public OrderServiceMcpTool(OrderService service) {
        this.service = service;
    }

    @Tool(name = "findById", description = "Find order by ID")
    public OrderDto findById(@ToolParam(description = "id") Long id) {
        return OrderDto.fromEntity(service.findById(id));
    }

    @Tool(name = "findByStatus", description = "Find orders by status")
    public List<OrderDto> findByStatus(@ToolParam(description = "status") String status) {
        return service.findByStatus(status).stream()
            .map(OrderDto::fromEntity)
            .toList();
    }
}
```

Spring AI auto-discovers `@Tool` methods via `MethodToolCallbackProvider`. No registration code needed.

**3. A REST controller** with standard Spring MVC mappings returning the same safe DTOs.

**4. An OpenAPI 3.0 spec** describing only the exposed operations and fields -- ready for documentation or client generation.

---

## Comparison: AI-ATLAS vs. Alternatives

| Capability | AI-ATLAS | Manual Spring AI `@Tool` | Spring Data REST | JHipster | swagger-to-mcp |
|---|:---:|:---:|:---:|:---:|:---:|
| MCP tool generation | Automatic | Manual per method | No | No | Partial |
| REST controller generation | Automatic | No | Automatic | Automatic | No |
| OpenAPI spec generation | Automatic | No | No | Automatic | Input required |
| PII filtering (whitelist) | Compile-time structural | Manual DTO per entity | No | No | No |
| DTO generation | Automatic (records) | Manual per entity | No | Automatic (no PII filter) | No |
| Works on existing codebases | Add 2 annotations | Full rewrite | Requires Spring Data repos | Full scaffold | Requires existing spec |
| Compile-time safety | Yes (JSR 269) | N/A | N/A | N/A | N/A |
| Lines of code per entity | ~5 annotations | ~50-100 per entity | ~10 (repo + entity) | ~0 (generated) | Varies |

The key differentiator: AI-ATLAS is the only tool that combines **automatic MCP tool generation** with **structural PII exclusion** at compile time. Every other approach either requires manual work per entity, lacks PII safety, or does not generate MCP tools at all.

---

## Getting Started (5 Minutes)

### Prerequisites

- Java 21+
- Gradle with Kotlin DSL (Maven support coming soon)
- Spring Boot 3.4+

### 1. Add the Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.atlas:annotations:1.1.0")
    implementation("ai.atlas:runtime:1.1.0")
    annotationProcessor("ai.atlas:processor:1.1.0")

    // Spring Boot (required for MCP tool and REST controller generation)
    implementation("org.springframework.boot:spring-boot-starter-web")
}
```

### 2. Annotate an Entity

```java
import ai.atlas.annotations.AgentVisible;
import ai.atlas.annotations.AgentVisibleClass;

@AgentVisibleClass(name = "product", description = "A product listing")
public class Product {

    @AgentVisible(description = "Product ID")
    private Long id;

    @AgentVisible(description = "Product name")
    private String name;

    @AgentVisible(description = "Price in cents")
    private long priceCents;

    // Internal fields -- not exposed
    private String supplierContactEmail;
    private BigDecimal costPrice;

    // getters and setters...
}
```

### 3. Annotate a Service

```java
import ai.atlas.annotations.AgenticExposed;

@Service
@AgenticExposed(
    toolName = "productService",
    description = "Product catalog operations",
    returnType = Product.class
)
public class ProductService {

    public Product findById(Long id) { /* ... */ }
    public List<Product> search(String query) { /* ... */ }
}
```

### 4. Build

```bash
./gradlew compileJava
```

Check `build/generated/sources/annotationProcessor/java/main/` -- you will find `ProductDto.java`, `ProductServiceMcpTool.java`, `ProductServiceRestController.java`, and an OpenAPI spec. All compile-ready, all PII-safe.

### 5. Run

```bash
./gradlew bootRun
```

Your MCP tools are now registered and discoverable by any MCP-compatible AI agent. Your REST endpoints are live. Your OpenAPI spec is served. Zero manual wiring.

---

## Architecture at a Glance

AI-ATLAS is a multi-module Gradle project with strict dependency boundaries:

- **`ai.atlas:annotations`** -- Zero external dependencies. Just annotation definitions. Add to your `implementation` configuration.
- **`ai.atlas:processor`** -- JSR 269 annotation processor using JavaPoet for code generation. Add to your `annotationProcessor` configuration. Runs at compile time only -- zero runtime footprint.
- **`ai.atlas:runtime`** -- Spring Boot auto-configuration for MCP server setup, PII interceptors, and audit logging. Add to your `implementation` configuration.

The processor supports Gradle incremental compilation in `AGGREGATING` mode and integrates cleanly with Lombok, MapStruct, and other annotation processors.

---

## What's Next

AI-ATLAS is under active development. Here is what is on the roadmap:

- **Maven plugin** -- first-class Maven support alongside Gradle.
- **Field-level access control** -- role-based visibility so different agents see different field subsets.
- **Audit trail generation** -- auto-generated logging for every agent tool invocation.
- **OAuth 2.1 / PKCE support** -- MCP transport security with token-scoped tool access.
- **Streamable HTTP transport** -- support for all three MCP transport modes (STDIO, SSE, Streamable HTTP).

---

## Try It Now

AI-ATLAS is open source and available today.

**GitHub**: [github.com/gosha70/ai-atlas](https://github.com/gosha70/ai-atlas)

**Maven Central coordinates**:

```
ai.atlas:annotations:1.1.0
ai.atlas:processor:1.1.0
ai.atlas:runtime:1.1.0
```

**Built with**: Java 21, Spring Boot 3.4, Spring AI 1.0, JavaPoet, JSR 269

Two annotations. One build step. Every service in your codebase gets a PII-safe MCP tool, REST endpoint, DTO, and OpenAPI spec -- generated at compile time, with zero runtime overhead.

Stop hand-writing MCP tools. Start annotating.

---

*AI-ATLAS is maintained by the [AI-ATLAS contributors](https://github.com/gosha70/ai-atlas/graphs/contributors). Contributions, issues, and feedback are welcome.*
