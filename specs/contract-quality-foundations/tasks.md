# Tasks: Contract quality foundations (epic #23, Phase 0 + Phase 1)

<!-- One owner per file. [P] = parallelizable within the story group. [US#] traces to spec.md. -->
<!-- Each `## US<n>:` group is one auto-build phase (fresh session). -->
<!-- Build sessions: tick a task's Done box in the same commit that completes it. -->

## US1: The OpenAPI document describes the controller that actually runs

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 1 | | Describe method arguments as `in: query` parameters (`required: true`, name, mapped schema, description) and drop the `requestBody` (FR-002) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/OpenApiGenerator.java` | Annotation Processor Engineer | [ ] |
| 2 | | Emit response content per return kind: DTO, collection of DTO, `void`, `String`, scalar, collection of scalar, other (FR-003) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/OpenApiGenerator.java` | Annotation Processor Engineer | [ ] |
| 3 | | Replace the `requestBody` expectation with query-parameter assertions | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/OpenApiGeneratorTest.java` | Annotation Processor Engineer | [ ] |
| 4 | | Golden pairs of generated controller + OpenAPI operation for: no arguments, one scalar, several arguments, DTO return, collection return, non-DTO return; plus "no operation without a mapping" (FR-001..003) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/RestOpenApiConsistencyTest.java` | Annotation Processor Engineer | [ ] |
| 5 | | Read the demo's generated `openapi-v2.json`, call every operation through MockMvc as the document describes, assert documented status and content type (FR-004) | `demo/src/test/java/com/egoge/ai/atlas/demo/OpenApiRuntimeConsistencyTest.java` | Team Lead / Framework Architect | [ ] |
| 6 | [P] | Changelog entry under `[Unreleased]` headed "REST parameters are query parameters" (FR-005) | `CHANGELOG.md` | Team Lead / Framework Architect | [ ] |
| 7 | [P] | State in both docs that arguments are query parameters in the controller and the document (FR-005) | `docs/annotation-guide.md`, `docs/processor-internals.md` | Team Lead / Framework Architect | [ ] |

**Checkpoint US1** — verify before continuing:
- [ ] `./gradlew :modules:processor:test --tests '*RestOpenApiConsistencyTest' --tests '*OpenApiGeneratorTest'` green
- [ ] `./gradlew :demo:test --tests '*OpenApiRuntimeConsistencyTest'` green
- [ ] `./gradlew build` green

---

## US2: Two AI tools can never share a name

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 8 | | Collect effective tool names of AI-channel methods active at the configured major across all services; report an ERROR on every colliding method naming the tool, the other `Class#method` declarations and the `toolName` fix (FR-006, FR-007) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [ ] |
| 9 | | Tests: cross-service collision, overload collision, three-way collision, `toolName` resolves it, API-only and inactive methods ignored, non-colliding `@Tool(name)` unchanged (FR-006..008) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/ToolNameCollisionTest.java` | Annotation Processor Engineer | [ ] |
| 10 | [P] | Changelog: the new collision error | `CHANGELOG.md` | Team Lead / Framework Architect | [ ] |

**Checkpoint US2** — verify before continuing:
- [ ] `./gradlew :modules:processor:test --tests '*ToolNameCollisionTest'` green
- [ ] `./gradlew build` green (the demo still compiles: its tool names are unique)

---

## US3: AI tools are described well enough for a model to choose them

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 11 | | Add `ai.atlas.strict` to `@SupportedOptions`; read and validate it in `init` (`true`/`false`, case-insensitive; other values an ERROR naming option and value) (FR-010) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [ ] |
| 12 | | Report which description fallback applied, so the diagnostic can name it | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/AttributeResolver.java` | Annotation Processor Engineer | [ ] |
| 13 | | Emit the missing-description WARNING (ERROR under strict) on AI-channel methods active at the configured major; skip API-only methods (FR-009, FR-011) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [ ] |
| 14 | | Tests: class-description fallback warns, `Invokes` fallback warns, method description silent, API-only silent, strict makes it an error, bad strict value errors, MCP descriptions keep version/deprecation text (FR-009..011) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/ToolDescriptionDiagnosticTest.java` | Annotation Processor Engineer | [ ] |
| 15 | [P] | Expose `strict` on the `agentic { }` extension and pass `-Aai.atlas.strict` (FR-010) | `modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticExtension.java`, `modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticPlugin.java` | Team Lead / Framework Architect | [ ] |
| 16 | [P] | Give `OrderService.findByStatus` (and any other AI-exposed demo method without one) a method-level description (FR-012) | `demo/src/main/java/com/egoge/ai/atlas/demo/service/OrderService.java` | Team Lead / Framework Architect | [ ] |
| 17 | [P] | Changelog + `docs/annotation-guide.md`: the diagnostic and `ai.atlas.strict` | `CHANGELOG.md`, `docs/annotation-guide.md` | Team Lead / Framework Architect | [ ] |

**Checkpoint US3** — verify before continuing:
- [ ] `./gradlew :modules:processor:test --tests '*ToolDescriptionDiagnosticTest'` green
- [ ] `scripts/check-demo-no-atlas-warnings.sh` passes (the demo compiles with no `[ai-atlas]` warning)
- [ ] `./gradlew build` green

---

## US4: The runtime MCP server can speak Streamable HTTP

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 18 | | Test: with `spring.ai.mcp.server.protocol=STREAMABLE`, the Atlas tools are listed over the streamable endpoint, and the names equal those served over SSE for the same tool beans (FR-013) | `modules/runtime/src/test/java/com/egoge/ai/atlas/runtime/mcp/StreamableHttpTransportTest.java` | MCP & Spring AI Specialist | [ ] |
| 19 | | Only if task 18 shows the lazy provider is not picked up under Streamable HTTP: fix the wiring without changing the SSE path | `modules/runtime/src/main/java/com/egoge/ai/atlas/runtime/mcp/AgenticMcpConfiguration.java` | MCP & Spring AI Specialist | [ ] |
| 20 | [P] | Commented `protocol: STREAMABLE` example under `spring.ai.mcp.server` (FR-015) | `demo/src/main/resources/application.yml` | MCP & Spring AI Specialist | [ ] |
| 21 | [P] | Document both transports, the selecting property, SSE as default and the `/mcp` endpoint; changelog entry (FR-015) | `docs/harness-integration.md`, `CHANGELOG.md` | Team Lead / Framework Architect | [ ] |

**Checkpoint US4** — verify before continuing:
- [ ] `./gradlew :modules:runtime:test --tests '*StreamableHttpTransportTest' --tests '*SseUnchangedTest'` green
- [ ] `./gradlew :modules:mcp-stdio:test` green (STDIO unchanged)
- [ ] `./gradlew build` green

---

## Final Verification

- [ ] `./gradlew build` green on JDK 17 and JDK 21 (FR-016)
- [ ] No `[NEEDS CLARIFICATION]` markers remain in spec.md
- [ ] Every FR in `verification.yaml` has a passing verifier
- [ ] Review verdict `PASS` recorded in `collaboration/build-review.md` for every phase
