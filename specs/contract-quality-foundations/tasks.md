# Tasks: Contract quality foundations (epic #23, Phase 0 + Phase 1)

<!-- One owner per file. [P] = parallelizable within the story group. [US#] traces to spec.md. -->
<!-- Each `## US<n>:` group is one auto-build phase (fresh session). -->
<!-- Build sessions: tick a task's Done box in the same commit that completes it. -->

## US1: The OpenAPI document describes the controller that actually runs

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 1 | | Generate `void` service methods as `void` controller and MCP tool methods that call the service as a statement; leave every non-`void` method byte-identical (FR-006) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/RestControllerGenerator.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/McpToolGenerator.java` | Annotation Processor Engineer | [ ] |
| 2 | | Report a compile ERROR on every API-channel method, anywhere in the compilation, that maps to an (HTTP method, path) another method also maps to — overloads, or same-named services in different packages — naming path, method and the other `Class#method` (FR-002) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [ ] |
| 3 | | Merge each operation into the path's existing `PathItem` instead of replacing it (FR-001) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/OpenApiGenerator.java` | Annotation Processor Engineer | [ ] |
| 4 | | Describe method arguments as `in: query` parameters (`required: true`, name, mapped schema, description) and drop the `requestBody` (FR-003) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/OpenApiGenerator.java` | Annotation Processor Engineer | [ ] |
| 5 | | Assign `operationId`s in two passes: reserve method names used by one operation only; then, in (path, HTTP method) order, give each shared one `{ServiceSimpleName}_{methodName}_{httpMethod}`, or that plus the smallest free `_2`, `_3`, … if taken (FR-004) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/OpenApiGenerator.java` | Annotation Processor Engineer | [ ] |
| 6 | | Emit response content per return kind: DTO, collection of DTO, `void`, `String`, scalar, collection of scalar, other (FR-005) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/OpenApiGenerator.java` | Annotation Processor Engineer | [ ] |
| 7 | | Replace the `requestBody` expectation with query-parameter assertions | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/OpenApiGeneratorTest.java` | Annotation Processor Engineer | [ ] |
| 8 | | Compile-testing golden test, controller mapping beside OpenAPI operation, for: no arguments; one scalar; several arguments; DTO return; collection return; non-DTO return; `void` on the API channel and on the default channels (fixture compiles); an API-only service with `find()` and `find(Long id)` (both operations under one path, distinct `operationId`s); that service beside another declaring `OrderService_find_get()` (it keeps `OrderService_find_get`, the overloaded GET gets `OrderService_find_get_2`, all IDs distinct); two overloads on one POST path, and two same-named services in different packages (compile ERROR at every site). Assert the controllers' (HTTP method, path) set equals the document's operation set (FR-001..007) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/RestOpenApiConsistencyTest.java` | Annotation Processor Engineer | [ ] |
| 9 | | Read the demo's generated `openapi-v2.json`; assert every generated-controller request mapping in the running demo is documented and every documented operation is mapped; call every documented operation through MockMvc as the document describes and assert documented status and content type (FR-007) | `demo/src/test/java/com/egoge/ai/atlas/demo/OpenApiRuntimeConsistencyTest.java` | Team Lead / Framework Architect | [ ] |
| 10 | [P] | Changelog entry under `[Unreleased]` headed "REST parameters are query parameters", also covering shared-path operations and qualified `operationId`s (FR-008) | `CHANGELOG.md` | Team Lead / Framework Architect | [ ] |
| 11 | [P] | State in both docs that arguments are query parameters in the controller and the document (FR-008) | `docs/annotation-guide.md`, `docs/processor-internals.md` | Team Lead / Framework Architect | [ ] |

**Checkpoint US1** — verify before continuing:
- [ ] `./gradlew :modules:processor:test --tests '*RestOpenApiConsistencyTest' --tests '*OpenApiGeneratorTest'` green
- [ ] `./gradlew :demo:test --tests '*OpenApiRuntimeConsistencyTest'` green
- [ ] `./gradlew build` green

---

## US2: Two AI tools can never share a name

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 12 | | Collect effective tool names of AI-channel methods active at the configured major across all services; report an ERROR on every colliding method naming the tool, the other `Class#method` declarations and the `toolName` fix (FR-009, FR-010) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [ ] |
| 13 | | Tests: cross-service collision, overload collision, three-way collision, `toolName` resolves it, API-only and inactive methods ignored, non-colliding `@Tool(name)` unchanged (FR-009..011) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/ToolNameCollisionTest.java` | Annotation Processor Engineer | [ ] |
| 14 | [P] | Changelog: the new collision error | `CHANGELOG.md` | Team Lead / Framework Architect | [ ] |

**Checkpoint US2** — verify before continuing:
- [ ] `./gradlew :modules:processor:test --tests '*ToolNameCollisionTest'` green
- [ ] `./gradlew build` green (the demo still compiles: its tool names are unique)

---

## US3: AI tools are described well enough for a model to choose them

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 15 | | Add `ai.atlas.strict` to `@SupportedOptions`; read and validate it in `init` (`true`/`false`, case-insensitive; other values an ERROR naming option and value) (FR-013) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [ ] |
| 16 | | Report which description fallback applied, so the diagnostic can name it | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/AttributeResolver.java` | Annotation Processor Engineer | [ ] |
| 17 | | Emit the missing-description WARNING (ERROR under strict) on AI-channel methods active at the configured major; skip API-only methods (FR-012, FR-014) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [ ] |
| 18 | | Tests: class-description fallback warns, `Invokes` fallback warns, method description silent, API-only silent, strict makes it an error, bad strict value errors, MCP descriptions keep version/deprecation text (FR-012..014) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/ToolDescriptionDiagnosticTest.java` | Annotation Processor Engineer | [ ] |
| 19 | [P] | Expose `strict` on the `agentic { }` extension and pass `-Aai.atlas.strict` (FR-013) | `modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticExtension.java`, `modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticPlugin.java` | Team Lead / Framework Architect | [ ] |
| 20 | [P] | Give `OrderService.findByStatus` (and any other AI-exposed demo method without one) a method-level description (FR-015) | `demo/src/main/java/com/egoge/ai/atlas/demo/service/OrderService.java` | Team Lead / Framework Architect | [ ] |
| 21 | [P] | Changelog + `docs/annotation-guide.md`: the diagnostic and `ai.atlas.strict` | `CHANGELOG.md`, `docs/annotation-guide.md` | Team Lead / Framework Architect | [ ] |

**Checkpoint US3** — verify before continuing:
- [ ] `./gradlew :modules:processor:test --tests '*ToolDescriptionDiagnosticTest'` green
- [ ] `scripts/check-demo-no-atlas-warnings.sh` passes (the demo compiles with no `[ai-atlas]` warning)
- [ ] `./gradlew build` green

---

## US4: The runtime MCP server can speak Streamable HTTP

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 22 | | Test: with `spring.ai.mcp.server.protocol=STREAMABLE`, the Atlas tools are listed over the streamable endpoint, and the names equal those served over SSE for the same tool beans (FR-016) | `modules/runtime/src/test/java/com/egoge/ai/atlas/runtime/mcp/StreamableHttpTransportTest.java` | MCP & Spring AI Specialist | [ ] |
| 23 | | Only if task 22 shows the lazy provider is not picked up under Streamable HTTP: fix the wiring without changing the SSE path | `modules/runtime/src/main/java/com/egoge/ai/atlas/runtime/mcp/AgenticMcpConfiguration.java` | MCP & Spring AI Specialist | [ ] |
| 24 | [P] | Commented `protocol: STREAMABLE` example under `spring.ai.mcp.server` (FR-018) | `demo/src/main/resources/application.yml` | MCP & Spring AI Specialist | [ ] |
| 25 | [P] | Document both transports, the selecting property, SSE as default and the `/mcp` endpoint; changelog entry (FR-018) | `docs/harness-integration.md`, `CHANGELOG.md` | Team Lead / Framework Architect | [ ] |

**Checkpoint US4** — verify before continuing:
- [ ] `./gradlew :modules:runtime:test --tests '*StreamableHttpTransportTest' --tests '*SseUnchangedTest'` green
- [ ] `./gradlew :modules:mcp-stdio:test` green (STDIO unchanged)
- [ ] `./gradlew build` green

---

## Final Verification

- [ ] `scripts/build-on-jdk-matrix.sh` passes: the build is green with Gradle on JDK 17 and on JDK 21 (FR-019)
- [ ] No `[NEEDS CLARIFICATION]` markers remain in spec.md
- [ ] Every FR in `verification.yaml` has a passing verifier
- [ ] Review verdict `PASS` recorded in `collaboration/build-review.md` for every phase
