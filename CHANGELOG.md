# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### REST parameters are query parameters (OpenAPI correction)
- **Query parameters are canonical.** Generated REST controllers have always bound method arguments with `@RequestParam`; the generated OpenAPI document now describes them the same way (`in: query`, `required: true`) instead of as an `application/json` request body.
- **The REST wire format of running controllers is unchanged.** Existing callers keep working. Clients generated from an earlier OpenAPI document (which described a JSON request body) must be regenerated.
- **Operations previously lost on shared paths now appear.** A GET and a POST on the same path (e.g. `find()` and `find(Long id)`) are both documented under that path.
- **Duplicate method names now yield qualified `operationId`s.** Unique method names keep their `operationId`; shared ones become `{Service}_{method}_{httpMethod}` (with a `_2`, `_3`, … suffix if that is taken), so every `operationId` is unique.
- **Response content matches the controller:** `String` returns are `text/plain`, number/boolean returns carry their scalar schema, `void` returns have no content.
- **Ambiguous REST mappings are a compile error.** Two methods mapping to the same HTTP method and path — overloads that both take arguments, or same-named services in different packages — are reported at each method instead of failing at application startup.
- **`void` service methods now compile.** The generated controller and MCP tool methods are `void`; previously they did not compile.

### MCP tool-name collisions are a compile error
- Two AI-channel methods active at the configured `ai.atlas.api.major` that share an effective MCP tool name (explicit `toolName`, else the method name) — on different services, or overloads of one method — are now reported as an ERROR at each method, naming the tool and the other `fully.qualified.Service#method` declarations. Set an explicit `toolName` on `@AgenticExposed` to resolve it.
- Tool names that were already unique are unchanged. API-only methods and methods inactive at the configured major are not checked. Collisions between services compiled in separate modules are not detected.

### AI tools without a description of their own warn; `ai.atlas.strict`
- An AI-channel method active at the configured `ai.atlas.api.major` whose own `@AgenticExposed` has no `description` now produces a WARNING at the method, naming it as `fully.qualified.Service#method`, giving its MCP tool name and the fallback used (the class-level description, or `"Invokes <method>"`). API-only methods are not checked; generated tool descriptions, including their version and deprecation prefixes, are unchanged.
- New processor option `ai.atlas.strict` (`true`/`false`, case-insensitive, default `false`): when `true`, this warning is a compile ERROR. Any other value is a compile ERROR naming the option and the value. The Gradle plugin exposes it as `agentic { strict.set(true) }`.
- Demo: `OrderService.findByStatus` has its own description, so the demo compiles with no ai-atlas warning.

---

## [1.1.0] — 2026-03-05

### Annotations
- `@AgenticField` — added `name` attribute for custom display names in metadata and enriched JSON
- `@AgenticField` — added `checkCircularReference` attribute (default `true`) for controlling circular reference detection during serialization
- `@AgenticField` — added `allowedValues` attribute for explicit value constraints on non-enum fields
- `@AgenticEntity` — added `name` attribute for entity display name in enriched JSON `typeInfo` block
- `@AgenticEntity` — added `description` attribute for class-level descriptions
- `@AgenticEntity` — added `includeTypeInfo` attribute (default `true`) for controlling `typeInfo` block in enriched output

### Processor
- **Compile-time metadata** — generated DTOs now include `FieldMeta` nested record, `CLASS_NAME`, `CLASS_DESCRIPTION`, `INCLUDE_TYPE_INFO` constants, and `FIELD_METADATA` map
- **Enum field detection** — enum field types are auto-detected; their constant names are extracted into `FIELD_METADATA.validValues` and OpenAPI `enum` constraints
- **Duplicate name validation** — compile error emitted when two `@AgenticField` fields share the same display name within a class
- **OpenAPI enrichment** — class descriptions used in schema docs; enum constraints in field schemas
- **Externalised PII patterns** — default PII detection patterns moved from hardcoded regex to `META-INF/ai-atlas/pii-patterns.conf` resource file; customers can replace the defaults with a custom file via `-Aai.atlas.pii.patterns.file=path`

### Runtime
- **AgentSafeModule** — Jackson module that registers the `AgentSafeSerializer` for all `@AgenticEntity` entities
- **AgentSafeSerializer** — Hibernate-safe JSON serializer with enriched and flat output modes, circular reference detection, and PII-safe whitelisting
- **HibernateSupport** — reflection-based Hibernate proxy unwrapping and uninitialized collection handling (no compile dependency on Hibernate)
- **SerializationContext** — ThreadLocal-based circular reference tracker using object identity
- **Configuration** — `ai.atlas.json.enriched`, `ai.atlas.json.include-descriptions`, `ai.atlas.json.include-valid-values` properties

### Gradle Plugin
- `piiPatternsFile` extension property — wires custom PII patterns file path to the annotation processor

### Demo
- `Order` entity updated with class-level metadata, `OrderStatus` enum, and custom field name (`totalCents`)

### Project
- Renamed from AI-ADAM to AI-ATLAS

---

## [1.0.0] — 2026-02-27

Initial release of AI-ATLAS (AI Annotation-Driven Tooling & Layered API Synthesis).

### Annotations (`modules/annotations`)
- `@AgenticField` — field-level annotation for DTO inclusion with `description` and `sensitive` attributes
- `@AgenticEntity` — class-level annotation triggering DTO generation with `dtoName` and `packageName` overrides
- `@AgenticExposed` — type/method-level annotation triggering MCP tool + REST controller generation with `toolName`, `description`, and `returnType` attributes

### Annotation Processor (`modules/processor`)
- **DTO Generator** — generates Java record DTOs with only `@AgenticField` fields and a null-safe `fromEntity()` factory method
- **MCP Tool Generator** — generates Spring AI `@Tool`-annotated classes that delegate to the original service and map entity responses to DTOs
- **REST Controller Generator** — generates `@RestController` classes with `@GetMapping`/`@PostMapping` endpoints returning PII-safe DTOs
- **OpenAPI Generator** — generates `META-INF/openapi/openapi.json` (OpenAPI 3.0.3) with paths from `@AgenticExposed` methods and schemas from entity models
- **Superclass chain walking** — inherited `@AgenticField` fields from parent classes are included in generated DTOs
- **Edge case handling** — interfaces and enums produce warnings (skipped), abstract classes produce warnings but still generate DTOs, static inner classes fully supported
- **PII detection** — heuristic warnings for fields matching patterns like `ssn`, `password`, `creditCard`
- **Configurable PII patterns** — additional patterns via `-Aai.atlas.pii.patterns=keyword1,keyword2`
- **Boolean getter convention** — `boolean` fields use `isX()` getters, others use `getX()`

### Runtime (`modules/runtime`)
- **Spring Boot auto-configuration** — `AgenticAutoConfiguration` activates on servlet web applications
- **MCP server integration** — auto-discovers `@Service` beans with `@Tool` methods and registers them as MCP tools via `ToolCallbackProvider`
- **SSE transport** — MCP server uses Server-Sent Events (default for `spring-ai-starter-mcp-server-webmvc`)
- **PII audit interceptor** — logs all `/api/v1/**` requests with SLF4J MDC correlation IDs
- **DTO response body advice** — runtime safety net warning if generated controllers return non-DTO types
- **Configuration properties** — `ai.atlas.mcp.enabled` and `ai.atlas.audit.enabled` (both default `true`)

### Gradle Plugin (`modules/gradle-plugin`)
- Plugin ID: `com.egoge.ai-atlas`
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
