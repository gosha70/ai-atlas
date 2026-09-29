# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Per-channel field projections
Documented in `docs/channel-projections.md` and `docs/contract-governance.md`.
- **`@AgenticField(channels)`.** A field can be eligible for the `API` channel only, the `AI` channel only, or both. The default, `INHERIT`, means every channel, so an undeclared field behaves as before. An empty array, or `INHERIT` mixed with explicit values, is a compile error. Eligibility only narrows the `@AgenticField` whitelist: an unannotated field is still on no channel.
- **`ai.atlas.projections` flag** (`true`/`false`, default `false`; Gradle `agentic { projections }`, which also reaches `atlasAcceptCompile`). With it on, each response carries the fields eligible for its channel, applied after the version projection. REST controllers and OpenAPI component schemas keep each entity's DTO, now its API projection, under the existing name. MCP tool classes return the AI projection. It is a separate record, `XAiDto` or `@AgenticEntity(aiDtoName)`, only for an entity whose projections differ, directly or through a referenced entity; an entity that does not differ shares one record. With the flag off, an explicit `@AgenticField(channels)` is a compile error. With the flag off, or on with no declaration, every generated file except the Contract IR is byte-identical to before.
- **New compile errors with the flag on.** An operation whose channel leaves its returned entity no field; a channel-eligible reference to an entity with no field for that channel; an AI record name colliding with another type in its package; and an exposed method returning an `@AgenticEntity`, or a collection, iterable or array of one, without a resolvable `returnType`, whose raw entity the projection cannot reach (the runtime keeps such an entity to its `@AgenticField` whitelist since the #50 fix below, but not to its channel). So is an `@AgenticField` whose type, or collection, iterable or array element type, is an unannotated subtype of an entity, unless `@AgenticField(type = <Entity>.class)` names the entity; with the flag on, that hint also takes effect on a direct field, mapping it through the entity's records. An AI-eligible field named like PII is a warning.
- **Contract IR `irVersion 3`.** Every field records its effective `channels`, sorted and always present: `[AI, API]` with the flag off. Version 1 and 2 baselines migrate exactly, every field to `[AI, API]`, with no lock-mode difference; `atlasAccept` writes version 3. The demo baseline is regenerated as version 3.
- **Gate rules.** A field losing a channel is a breaking output change when its entity is reachable, through fields on that channel, from an operation active on it, and compatible otherwise. Gaining a channel is compatible. An entity's AI record appearing or disappearing is `informational`. The DTO name keeps its breaking rule.

### Constraints and MCP behavioural hints
Documented in `docs/constraints-and-hints.md` and `docs/contract-governance.md`.
- **Constraint model.** The processor reads Jakarta Bean Validation constraints (`@Min`, `@Max`, `@DecimalMin`, `@DecimalMax`, `@Positive`, `@PositiveOrZero`, `@Negative`, `@NegativeOrZero`, `@Size`, `@Pattern`, `@NotNull`, `@NotBlank`, `@NotEmpty`, default group only) on `@AgenticField` fields and exposed-method parameters, by qualified name, with no dependency on the validation API. They are intersected order-independently, then overridden per key.
- **New annotations.** `@AgenticConstraints` overrides individual constraint keys on a field or parameter; `@AgenticParam` sets a parameter's `description` and `required` (`Requiredness { DEFAULT, REQUIRED, OPTIONAL }`). Contradictory constraints are a compile error; an input override looser than Bean Validation is a warning.
- **Behavioural hints.** `@AgenticExposed` gains `readOnly`, `destructive`, `idempotent` and `openWorld` (`Hint { UNSET, TRUE, FALSE }`); method-level values override class-level ones, and nothing is inferred. Hints are client guidance, not authorization.
- **Contract IR `irVersion 2`.** Fields and parameters record their constraints, parameters their requiredness, and operations their hints, whether or not the flag below is on. An `irVersion` 1 baseline is migrated in memory with those slots unknown, which never fails the gate, in gate or lock mode. The demo baseline is regenerated as version 2.
- **Gate rules.** Narrowing an input constraint (a tighter bound, compared as an endpoint; a tighter or new length or item bound; an added pattern; `notBlank`) or making a parameter required is breaking, naming the parameter, the key and before → after. Widening is compatible. Output constraint and hint changes are classified `informational` in `contract-diff.json`, and fail only in lock mode.
- **`ai.atlas.constraints` flag** (`true`/`false`, default `false`; Gradle `agentic { constraints }`). With it on, OpenAPI parameters and DTO properties carry constraints and `required`; REST controllers bind `OPTIONAL` parameters with `required = false`; MCP tool classes carry the effective constraints as Bean Validation annotations and are `@Validated`; and `META-INF/ai-atlas/mcp-tools.json` lists each tool's JSON Schema 2020-12 input schema and declared hints. Patterns are published only when Java and ECMAScript agree on them; others stay enforced but are omitted from the schemas, with a warning. With the flag off, generated output is unchanged.
- **Runtime.** On a SYNC MCP server, tools are registered as tool specifications whose Spring AI-derived input schema is merged with the `mcp-tools.json` constraints and requiredness, and which carry the declared hints (`readOnlyHint` and the rest). A duplicate tool name across resources fails startup. While Spring AI's tool-callback conversion is on (`spring.ai.mcp.server.tool-callback-converter`, the default), an application's own `ToolCallbackProvider` bean takes precedence: a tool it also provides is registered once, through it, without the generated constraints and hints, and with a WARNING; with the conversion off, AI-ATLAS registers the tool itself, with its generated schema and hints. ASYNC and STATELESS servers keep the derived registration. Enforcement needs `spring-boot-starter-validation` in the application; without method validation, the runtime warns that the constraints are advisory.

### Contract IR and compatibility gate
Documented in `docs/contract-governance.md`.
- **Contract IR.** Every compilation that declares an `@AgenticEntity` or `@AgenticExposed` writes `META-INF/ai-atlas/api.ir.json`: every entity, field and exposed method with its lifecycle, including those inactive at the configured major. The document carries `"irVersion": 1` and is deterministic — the same sources and options produce a byte-identical file. A baseline with a newer `irVersion` is a compile error; each future `irVersion` will ship a documented in-memory migration from the previous one.
- **Generators project from the IR.** DTOs, MCP tools, REST controllers, OpenAPI documents and the deprecation manifest are produced from the IR projected at `ai.atlas.api.major`. Their output is byte-identical to before, except for the fix below.
- **Later-round declarations now appear in the OpenAPI documents and deprecation manifest.** These aggregate resources are now written after the final processing round. An entity or service first seen in a later round — for example, generated by another annotation processor — was previously missing from them; it is now included.
- **Compatibility gate.** With the processor option `ai.atlas.contract.baseline` naming a committed baseline, the build compares it with the current IR at the baseline's published major and fails on a breaking change no lifecycle declaration explains: a removed field or operation, a changed effective schema or return DTO, a changed parameter, tool name, REST mapping or `operationId`, a removed input enum value, a value added to a closed response enum, or a changed `apiBasePath`, DTO name or DTO package. Each error names the element, the change, why it breaks clients, and the declaration that would legitimise it. Compatible changes pass silently. Every comparison writes `META-INF/ai-atlas/contract-diff.json`.
- **`openEnum`.** New `@AgenticField(openEnum = true)` declares that clients tolerate unknown values, so adding an enum constant or allowed value to the field is compatible. Response enums are closed by default. Setting it on a field that is neither an enum nor has `allowedValues` is a warning.
- **`atlasAccept`.** New Gradle task that writes the current sources' IR to the baseline (`.atlas/api.ir.json` by default), even while the gate fails, and prints the accepted differences. It is the only way the baseline is written; `build` never writes it.
- **Lock mode.** New processor option `ai.atlas.contract.locked` (`true`/`false`, default `false`): when `true`, any difference from the baseline — compatible ones included — and a missing baseline fail the build until accepted.
- **Gradle plugin.** `agentic { contractBaseline; contractLocked }` pass the options to the main `compileJava` only, with the baseline as a compile input. New `atlasContractCheck` task, which `classes` depends on, fails the build when a module with a baseline removes its last ai-atlas annotation (javac does not run the processor then); running `compileJava` alone does not run it.
- **CLI and MCP server.** `ai.atlas.contract.baseline` and `ai.atlas.contract.locked` pass through unchanged; a gate failure, or an empty contract against a baseline, is a failed generation.
- **Demo.** The demo commits its baseline as `demo/.atlas/api.ir.json`, and a test asserts it matches the IR the demo build emits.

### Fixed
- **MCP tool results keep the `@AgenticField` whitelist** (#50). Spring AI serialized `@Tool` results with its own `ObjectMapper`, without `AgentSafeModule`, so a tool returning an `@AgenticEntity` raw (a method without a resolvable `returnType`), or inside a shape the processor does not check (`Map<String, Order>`, `Stream<Order>`, `List<List<Order>>`, `Iterable<? super Order>`), sent every getter to the MCP client, fields without `@AgenticField` included. The runtime now writes every tool result it registers, on SYNC, ASYNC and STATELESS servers, through `AgentSafeToolCallResultConverter`: Spring AI's own tool-result mapper with `AgentSafeModule` registered, so every other result, generated DTOs included, is byte-identical, and `void` and `String` results are unchanged. A `@Tool` naming its own `resultConverter` keeps it. Tools the application serves itself are covered too: every `MethodToolCallback` and `FunctionToolCallback` of its own `ToolCallbackProvider`, `ToolCallback` and `List<ToolCallback>` beans that would use Spring AI's default converter is rebuilt around the agent-safe one, with the same definition, `returnDirect`, method and target, so a generated tool left to the application's provider (the precedence WARNING now says its results keep the whitelist) no longer leaks. A provider bean becomes a proxy of its interfaces. A callback of any other class serving a tool AI-ATLAS registers fails startup, naming the tool, the bean and the fix; any other is served as is, with one DEBUG line. The results use the application's own `AgentSafeModule` bean, which now replaces the auto-configured one instead of failing startup, or the `ai.atlas.json.*` settings, also in a converter Spring AI instantiates from `@Tool(resultConverter = AgentSafeToolCallResultConverter.class)`. An entity used as a `Map` key is still written with its `toString()`, and `ChatClient.tools(bean)` in process is outside the MCP runtime; the README says how to cover it. No generated source changes.
- **An unannotated subtype of an `@AgenticEntity` serializes as its entity** (#50). `@AgenticEntity` is not inherited, so a `VipCustomer extends Customer` was written with every getter, on REST and MCP, including inside a generated DTO that holds it (an `@AgenticField VipCustomer customer` with `ai.atlas.projections` off). `AgentSafeModule` now serializes it through the nearest entity's `@AgenticField` getters, searching supertypes breadth first as the processor's `ReturnedTypes.entityOf` does: the class, then its superclass and interfaces, then theirs, so a directly implemented entity interface wins over a grandparent class. With `ai.atlas.json.enriched=true`, its `typeInfo` names that entity.
- MCP tools on proxied services (for example `@Validated` or `@Transactional`) are registered; they were silently skipped.
- **`compileJava` is relocatable again.** The contract baseline is fingerprinted by content only, so `compileJava` in checkouts at different paths shares build cache entries; its absolute path is passed to javac but is no longer part of the cache key.
- **`atlasAccept` compiles with `compileJava`'s final configuration.** Compiler arguments and argument providers, encoding, `release`, source and target compatibility, fork options and the Java compiler are read from `compileJava` when the accept compilation runs, so options the build adds later still reach it and the accepted baseline is byte-identical to the IR `compileJava` emits. `compileJava`'s JVM argument providers, memory settings and forked Java home are inputs of the accept compilation, so a change to them re-runs it instead of accepting stale IR.
- **A processor version the plugin cannot run fails with a clear message.** When `agentic { version }` pins a processor that lacks the API the plugin calls, `atlasContractCheck` and `atlasAccept` fail naming the plugin version, the processor version on the `annotationProcessor` classpath and the remedy, instead of a raw `NoSuchMethodError` or `NoClassDefFoundError`.
- **An ai-atlas annotation inside an anonymous or local class no longer hides an empty contract.**
- **`void` methods compile in services with a class-level `returnType`** (#28). A `void` method no longer inherits the class-level `@AgenticExposed(returnType = X)`, which failed with `is not compatible with method return type void`; its generated controller and MCP tool methods are `void` and its OpenAPI operation has no response content. A method-level `returnType` on a `void` method is still a compile error.
- **`List<String>` and `String[]` returns are documented as arrays of strings** (#29). The generated OpenAPI document described a collection or array of `String` as `type: object`, while the controller returns a JSON array of strings; it is now `application/json` with `type: array, items: {type: string}`. Clients generated from an earlier document should be regenerated. A plain `String` return stays `text/plain`.

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

### Runtime MCP server: Streamable HTTP alongside SSE
- Setting Spring AI's `spring.ai.mcp.server.protocol=STREAMABLE` serves the Atlas-generated tools over MCP Streamable HTTP at `/mcp`. No ai-atlas property is involved; the same tools are listed as over SSE.
- SSE stays the default: with no property set, the server serves `GET /sse` / `POST /mcp/message` exactly as before. The standalone STDIO server is unchanged.
- Documented in `docs/harness-integration.md`; the demo's `application.yml` shows the property, commented out.

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
