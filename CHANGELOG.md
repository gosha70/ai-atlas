# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0] — 2026-02-27

Initial release of AI-ADAM (AI Auto-Discoverable API Management).

### Annotations (`modules/annotations`)
- `@AgentVisible` — field-level annotation for DTO inclusion with `description` and `sensitive` attributes
- `@AgentVisibleClass` — class-level annotation triggering DTO generation with `dtoName` and `packageName` overrides
- `@AgenticExposed` — type/method-level annotation triggering MCP tool + REST controller generation with `toolName`, `description`, and `returnType` attributes

### Annotation Processor (`modules/processor`)
- **DTO Generator** — generates Java record DTOs with only `@AgentVisible` fields and a null-safe `fromEntity()` factory method
- **MCP Tool Generator** — generates Spring AI `@Tool`-annotated classes that delegate to the original service and map entity responses to DTOs
- **REST Controller Generator** — generates `@RestController` classes with `@GetMapping`/`@PostMapping` endpoints returning PII-safe DTOs
- **OpenAPI Generator** — generates `META-INF/openapi/openapi.json` (OpenAPI 3.0.3) with paths from `@AgenticExposed` methods and schemas from entity models
- **Superclass chain walking** — inherited `@AgentVisible` fields from parent classes are included in generated DTOs
- **Edge case handling** — interfaces and enums produce warnings (skipped), abstract classes produce warnings but still generate DTOs, static inner classes fully supported
- **PII detection** — heuristic warnings for fields matching patterns like `ssn`, `password`, `creditCard`
- **Configurable PII patterns** — additional patterns via `-Aai.adam.pii.patterns=keyword1,keyword2`
- **Boolean getter convention** — `boolean` fields use `isX()` getters, others use `getX()`

### Runtime (`modules/runtime`)
- **Spring Boot auto-configuration** — `AgenticAutoConfiguration` activates on servlet web applications
- **MCP server integration** — auto-discovers `@Service` beans with `@Tool` methods and registers them as MCP tools via `ToolCallbackProvider`
- **SSE transport** — MCP server uses Server-Sent Events (default for `spring-ai-starter-mcp-server-webmvc`)
- **PII audit interceptor** — logs all `/api/v1/**` requests with SLF4J MDC correlation IDs
- **DTO response body advice** — runtime safety net warning if generated controllers return non-DTO types
- **Configuration properties** — `ai.adam.mcp.enabled` and `ai.adam.audit.enabled` (both default `true`)

### Gradle Plugin (`modules/gradle-plugin`)
- Plugin ID: `ai.adam.gradle-plugin`
- Auto-adds `annotations` to `implementation`, `processor` to `annotationProcessor`, `runtime` to `implementation`
- Configures IntelliJ IDEA generated source directories
- Extension with `version`, `group`, `mcpEnabled`, `restEnabled`, `openApiEnabled` properties

### Demo Application (`demo`)
- Spring Boot app with `Order` entity and `OrderService`
- Demonstrates PII exclusion: `creditCardNumber` and `customerSsn` are not in the generated DTO
- REST endpoints: `/api/v1/order-service/find-by-id`, `/api/v1/order-service/find-by-status`
- MCP tools registered and accessible via SSE transport

### Demo Frontend (`demo-frontend`)
- Next.js 15 application with TypeScript
- API client generated from OpenAPI spec
- Displays PII-safe order data from generated REST endpoints

### Publishing
- Maven Central publishing via Sonatype OSSRH (with GPG signing)
- CI workflow: build + test on push/PR (Ubuntu + macOS matrix)
- Release workflow: tag-triggered build, test, sign, publish

### Test Coverage
- 33 processor compile-testing tests covering: DTO generation, MCP tools, REST controllers, OpenAPI spec, PII warnings, inheritance, edge cases, configurable patterns
- 3 Gradle Plugin TestKit functional tests
- Annotation retention/target unit tests
