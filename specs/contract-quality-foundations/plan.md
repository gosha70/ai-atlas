---
spec_mode: full
feature_id: contract-quality-foundations
risk_category: integration
justification: "Changes the generated OpenAPI contract, adds compile-time errors that can fail consumers' builds, and adds a runtime transport option across processor, annotations-adjacent plugin config, runtime and demo — multi-module and consumer-visible, so full SDD with review gating."
status: approved
date: 2026-09-24
collaboration_mode: single
origin:
  issue: gosha70/ai-atlas#23
  transcripts:
    - specs/contract-quality-foundations/origin/2026-09-24-owner-decisions.md
  origin_claim: |
    Epic gosha70/ai-atlas#23 moves AI-ATLAS "from deterministic interface generation to
    governed interface lifecycle management" and asks to "keep this as the epic and create
    focused child issues for implementation", in its stated order: Phase 0, "Fix generated
    REST ↔ OpenAPI parameter-contract mismatch" ("Immediate correctness prerequisite"), then
    the Phase 1 items "MCP tool-name collision detection", "Per-method AI tool description
    diagnostics" and "Streamable HTTP transport alongside SSE". Phase 0 "should state which
    representation is canonical and treat the correction as a declared change rather than a
    silent one". The owner asked on 2026-09-24 to set up Claude Code to implement the epic,
    approved splitting it with this feature (Phase 0 + the three Phase 1 items) first, and
    chose query parameters as canonical, SSE as the default transport with Streamable HTTP
    opt-in, and a single `ai.atlas.strict` option for strict mode.
---

# Implementation Plan: Contract quality foundations (epic #23, Phase 0 + Phase 1)

**Branch**: `feature/contract-quality-foundations`
**Input**: specs/contract-quality-foundations/spec.md

## Summary

Make the generated OpenAPI document describe the generated REST controllers exactly, taking
query parameters as the canonical argument representation and declaring the correction in the
changelog; along the way, stop operations sharing a path from overwriting each other, make
`operationId`s unique, reject ambiguous mappings at compile time, and make `void` methods
generate code that compiles. Then add three compile-time and runtime improvements from Phase 1 of epic #23: an
error when two AI tools would share a name, a warning (an error under `ai.atlas.strict`) when an
AI tool has no description of its own, and MCP Streamable HTTP as an opt-in transport next to
the default SSE. This is the prerequisite the epic sets for the Contract IR (Phase 2): the
contract that gets locked later must first be internally consistent.

## Technical Context

**Language/Version**: Java 17 toolchain (CI also builds on JDK 21), Gradle Kotlin DSL.
**Primary Dependencies**: `modules/processor` (`AgenticProcessor`, `RestControllerGenerator`,
`OpenApiGenerator`, `McpToolGenerator`, `util/AttributeResolver`), `modules/gradle-plugin`
(`AgenticExtension`, `AgenticPlugin`), `modules/runtime` (`mcp/AgenticMcpConfiguration`, Spring AI
1.1.7 `spring-ai-starter-mcp-server-webmvc`, which already ships
`McpServerStreamableHttpWebMvcAutoConfiguration`), `demo`. No new dependency.
**Testing**: JUnit 5 + AssertJ; processor tests compile sources in-process (existing
`compile-testing` style); demo tests use MockMvc; runtime transport tests start a context the
way `SseUnchangedTest` does. Gate: `./gradlew build`.
**Constraints**: generated controllers' REST wire format is unchanged (the only generated-code
change is the `void` fix, for code that does not compile today); existing unique tool
names are unchanged; `annotations` keeps zero dependencies; `processor` gains no runtime
dependency; everything offline and deterministic.

## Constitution Check

| Rule file | Concern | Status |
|-----------|---------|--------|
| `coding-standards.md` | No magic strings; diagnostics prefixed `[ai-atlas]` like the existing ones; option key as a named constant next to `OPT_API_MAJOR` | OK |
| `safety.md` | No credentials; option values validated (FR-013 rejects anything but `true`/`false`) | OK |
| `copilot-conventions.md` | One logical change per commit; version catalog untouched (no new dependency) | OK |

## Architecture Decisions

### ADR-1: Correct the document, not the controllers — except where they do not compile

**Context**: The controllers bind arguments with `@RequestParam`; the OpenAPI document describes
an `application/json` request body. One side must move, and whichever moves breaks its own
existing consumers. The epic requires the choice to be explicit. Separately, owner review found
that a `void` service method makes both `RestControllerGenerator` and `McpToolGenerator` emit
`return service.method(...)`, which does not compile.
**Decision**: Query parameters are canonical (owner, 2026-09-24). `OpenApiGenerator` emits
`in: query` parameters and no `requestBody`. `RestControllerGenerator` and `McpToolGenerator`
change only for `void` methods: the generated method is `void` and calls the service as a
statement (FR-006). Every non-`void` method is generated byte-identically to before.
**Consequences**: Every running REST caller keeps working (the demo's own tests already call with
`.param(...)`). Only consumers who generated clients from the old document are affected; the
changelog tells them to regenerate. The `void` change alters no working output, because no working
output existed for those methods. Long argument lists and complex objects in query strings stay
awkward; explicit REST metadata (epic §9) is the later fix.

### ADR-2: Consistency is proven in both directions, at compile time and at runtime

**Context**: Unit tests on each generator separately are how the drift went unnoticed: each side
matched its own expectations. Owner review added that calling every documented operation cannot
detect an operation the document is *missing*: `OpenApiGenerator.addServicePaths` creates a new
`PathItem` per method and `Paths.addPathItem` replaces any earlier one, so `find()` (GET) and
`find(Long)` (POST) on the same path keep only one operation.
**Decision**: `addServicePaths` merges each operation into the path's existing `PathItem` (FR-001).
Coverage is asserted both ways: the processor golden test compares the set of (HTTP method, path)
pairs the generated controllers map with the document's operations, over a fixture that includes
the shared-path overloads and `void` methods; the demo integration test does the same against the
running application's generated-controller mappings, and also calls every documented operation
(FR-007).
**Consequences**: A dropped or invented operation fails the build at both levels. The runtime test
reads what the build produced, so it needs no second model of the contract.

### ADR-3: Collisions are compile errors, detected over the whole compilation

**Context**: Three kinds of name clash reach consumers today. Two AI methods with one effective tool
name (possibly on different services): `McpToolGenerator` runs per service, so no generator call
sees them all, and Spring AI finds duplicates only at runtime, if at all. Two API methods of one
methods on the same HTTP method and path — overloads of one service that both take arguments, or
two services with the same simple name in different packages, since paths use the simple name:
Spring fails at startup with an ambiguous mapping. Two operations with one method name: the
document gets duplicate `operationId`s, which OpenAPI forbids.
**Decision**: `AgenticProcessor` collects effective tool names and (HTTP method, path) pairs while
building the service models and reports tool-name collisions (FR-009, FR-010) and duplicate
mappings (FR-002) as ERRORs on each method element, before generating. `OpenApiGenerator` assigns
`operationId`s in two passes (FR-004): method names used by one operation only are kept and
reserved first; then the shared ones, in (path, HTTP method) order, get
`{ServiceSimpleName}_{methodName}_{httpMethod}`, or that plus the smallest free `_2`, `_3`, … when
the candidate is already taken. Owner review showed why the second pass must check the whole
document: a service may legitimately declare a method literally named `OrderService_find_get`,
whose unchanged ID would otherwise equal a qualified one.
**Consequences**: Collisions across services compiled in *separate* compilations (different modules)
are not detected; that needs the Contract IR (Phase 2). Documents whose `operationId`s were
duplicated (already invalid OpenAPI) get qualified IDs, deterministically; unique IDs are
unchanged. The changelog says so.

### ADR-4: One strict-mode option, validated once

**Context**: FR-012 is the first of several epic diagnostics with a "strict mode" (collections,
behavioural hints come later).
**Decision**: `ai.atlas.strict` is read once in `AgenticProcessor.init`, validated (`true`/`false`,
case-insensitive; anything else is an ERROR naming the option), and passed to the checks as a
`Diagnostic.Kind` to use for quality warnings. The Gradle plugin exposes it as
`agentic { strict = true }`.
**Consequences**: Later diagnostics reuse the same switch; no per-check options to document.

### ADR-5: Select the transport with Spring AI's own property

**Context**: Spring AI 1.1.7 already provides Streamable HTTP, selected by
`spring.ai.mcp.server.protocol=STREAMABLE`; SSE is its default. ai-atlas registers tools through a
`ToolCallbackProvider`, which both transports consume.
**Decision**: No ai-atlas transport property. Prove with a test that the Atlas tools are served
over Streamable HTTP when the property is set (FR-016), keep `SseUnchangedTest` as the SSE guard
(FR-017), and document the switch (FR-018). If the lazy `ToolCallbackProvider` turns out not to
be picked up by the streamable auto-configuration, the fix belongs in `AgenticMcpConfiguration`
and is in scope.
**Consequences**: The default stays SSE with no change for deployments; one property opts in.

### ADR-6: Both JDK legs are verified by one executable, not assumed from CI

**Context**: FR-019 requires the build to pass on JDK 17 and JDK 21, the CI matrix. A verifier that
runs `./gradlew build` once proves only whichever JDK happens to be installed.
**Decision**: `scripts/build-on-jdk-matrix.sh` finds JDK 17 and JDK 21 (explicit `JDK17_HOME` /
`JDK21_HOME`, the variables `actions/setup-java` sets, macOS `java_home`, `/usr/lib/jvm`), runs
`./gradlew build` with `JAVA_HOME` set to each, and fails when either JDK is missing or either
build fails. It is written for bash 3.2, so it runs on macOS's stock shell.
**Consequences**: Local admission needs both JDKs installed. CI still runs its own matrix; the
script is the evidence the verifier records.

## Project Structure

```
modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/OpenApiGenerator.java       — query parameters, merged path items, unique operationIds, response content (FR-001, FR-003..005)
modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/RestControllerGenerator.java — void methods only (FR-006)
modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/McpToolGenerator.java        — void methods only (FR-006)
modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java                 — duplicate-mapping check, tool-name collision check, ai.atlas.strict, description diagnostic
modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/AttributeResolver.java           — report which description fallback applied
modules/processor/src/test/java/com/egoge/ai/atlas/processor/RestOpenApiConsistencyTest.java       — new: compile-testing golden + two-way coverage (FR-001..007)
modules/processor/src/test/java/com/egoge/ai/atlas/processor/OpenApiGeneratorTest.java             — updated: requestBody expectation becomes query parameters
modules/processor/src/test/java/com/egoge/ai/atlas/processor/ToolNameCollisionTest.java            — new (FR-009..011)
modules/processor/src/test/java/com/egoge/ai/atlas/processor/ToolDescriptionDiagnosticTest.java    — new (FR-012..014)
modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticExtension.java               — strict property
modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticPlugin.java                  — pass -Aai.atlas.strict
modules/runtime/src/test/java/com/egoge/ai/atlas/runtime/mcp/StreamableHttpTransportTest.java      — new (FR-016)
demo/src/main/java/com/egoge/ai/atlas/demo/service/OrderService.java                              — method description for findByStatus (FR-015)
demo/src/test/java/com/egoge/ai/atlas/demo/OpenApiRuntimeConsistencyTest.java                     — new: two-way coverage + calls (FR-007)
demo/src/main/resources/application.yml                                                           — commented protocol property (FR-018)
scripts/check-demo-no-atlas-warnings.sh                                                           — FR-015 verifier (added with this spec)
scripts/build-on-jdk-matrix.sh                                                                    — FR-019 verifier (added with this spec)
docs/harness-integration.md                                                                       — both transports (FR-018)
docs/annotation-guide.md, docs/processor-internals.md                                            — canonical parameters (FR-008)
CHANGELOG.md                                                                                      — declared correction (FR-008), new diagnostics and option
```

## Scope

### Task 1: REST ↔ OpenAPI consistency (US1, FR-001..008)

**Files**: `OpenApiGenerator.java`, `RestControllerGenerator.java`, `McpToolGenerator.java`,
`AgenticProcessor.java`, `OpenApiGeneratorTest.java`, `RestOpenApiConsistencyTest.java`,
`OpenApiRuntimeConsistencyTest.java`, `CHANGELOG.md`, docs
**Acceptance criteria**:
- [ ] Shared-path operations merged; duplicate mappings a compile error (FR-001, FR-002)
- [ ] Operations use `in: query` parameters, `required: true`, no `requestBody` (FR-003)
- [ ] `operationId`s unique; unique ones unchanged (FR-004)
- [ ] Response content per return kind (FR-005)
- [ ] `void` methods compile on every channel; other methods byte-identical (FR-006)
- [ ] Two-way coverage at compile time and at runtime; every documented call succeeds (FR-007)
- [ ] Changelog entry "REST parameters are query parameters" (FR-008)

### Task 2: Tool-name collision detection (US2, FR-009..011)

**Files**: `AgenticProcessor.java`, `ToolNameCollisionTest.java`
**Acceptance criteria**:
- [ ] Cross-service and overload collisions fail with an error at every site (FR-009, FR-010)
- [ ] Unique names unchanged; inactive/API-only methods ignored (FR-011)

### Task 3: Description diagnostics and strict mode (US3, FR-012..015)

**Files**: `AgenticProcessor.java`, `AttributeResolver.java`, `AgenticExtension.java`,
`AgenticPlugin.java`, `ToolDescriptionDiagnosticTest.java`, `OrderService.java`, `CHANGELOG.md`
**Acceptance criteria**:
- [ ] Warning names method, tool and fallback (FR-012)
- [ ] `ai.atlas.strict=true` makes it an error; bad value is an error (FR-013)
- [ ] API-only methods silent; MCP descriptions unchanged (FR-014)
- [ ] Demo compiles with no ai-atlas warning (FR-015)

### Task 4: Streamable HTTP (US4, FR-016..018)

**Files**: `StreamableHttpTransportTest.java`, possibly `AgenticMcpConfiguration.java`,
`application.yml`, `docs/harness-integration.md`
**Acceptance criteria**:
- [ ] Same tools listed over Streamable HTTP as over SSE (FR-016)
- [ ] `SseUnchangedTest` passes unmodified (FR-017)
- [ ] Docs and demo config updated; STDIO untouched (FR-018)

## Constraints / What NOT to Build

- No change to generated controllers' REST wire format; the only generated-code change is the
  `void` fix (ADR-1).
- No tool renaming or qualified default names (1.x compatibility).
- No Contract IR, compatibility gate, lock mode, constraint model, behavioural hints, projections,
  collection policy, REST verb/path metadata or release workflow — later epic features.
- No cross-module collision detection (ADR-3; needs the Contract IR).
- No ai-atlas transport property (ADR-5).

## File Ownership (Non-Overlapping)

| Owner | Files |
|-------|-------|
| Annotation Processor Engineer | `modules/processor/**` |
| MCP & Spring AI Specialist | `modules/runtime/**`, `demo/src/main/resources/application.yml` |
| Team Lead / Framework Architect | `modules/gradle-plugin/**`, `demo/src/main/java/**`, `demo/src/test/**`, `docs/**`, `scripts/**`, `CHANGELOG.md` |

## Collaboration (Dual Mode)

Single-provider build; review gating is handled by the auto-build-loop reviewer (DeepSeek, per
`automation.json`), not the dual peer-review protocol. `collaboration_mode: single`.
