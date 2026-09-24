---
spec_mode: full
feature_id: contract-quality-foundations
risk_category: integration
justification: "Changes the generated OpenAPI contract, adds compile-time errors that can fail consumers' builds, and adds a runtime transport option across processor, annotations-adjacent plugin config, runtime and demo — multi-module and consumer-visible, so full SDD with review gating."
status: draft
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
changelog. Then add three compile-time and runtime improvements from Phase 1 of epic #23: an
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
**Constraints**: generated controllers' REST wire format is unchanged; existing unique tool
names are unchanged; `annotations` keeps zero dependencies; `processor` gains no runtime
dependency; everything offline and deterministic.

## Constitution Check

| Rule file | Concern | Status |
|-----------|---------|--------|
| `coding-standards.md` | No magic strings; diagnostics prefixed `[ai-atlas]` like the existing ones; option key as a named constant next to `OPT_API_MAJOR` | OK |
| `safety.md` | No credentials; option values validated (FR-010 rejects anything but `true`/`false`) | OK |
| `copilot-conventions.md` | One logical change per commit; version catalog untouched (no new dependency) | OK |

## Architecture Decisions

### ADR-1: Correct the document, not the controllers

**Context**: The controllers bind arguments with `@RequestParam`; the OpenAPI document describes
an `application/json` request body. One side must move, and whichever moves breaks its own
existing consumers. The epic requires the choice to be explicit.
**Decision**: Query parameters are canonical (owner, 2026-09-24). `OpenApiGenerator` emits
`in: query` parameters and no `requestBody`; `RestControllerGenerator` is not changed.
**Consequences**: Every running REST caller keeps working (the demo's own tests already call with
`.param(...)`). Only consumers who generated clients from the old document are affected; the
changelog tells them to regenerate. Long argument lists and complex objects in query strings stay
awkward; explicit REST metadata (epic §9) is the later fix.

### ADR-2: Consistency is proven by calling the controllers the document describes

**Context**: Unit tests on each generator separately are how the drift went unnoticed: each side
matched its own expectations.
**Decision**: Add a demo integration test that reads the generated `openapi-v{major}.json`, calls
every operation through MockMvc exactly as the document says, and checks status and content type
(FR-004), plus a processor golden test pinning controller and operation side by side.
**Consequences**: Any future change to either generator that breaks the pairing fails the build.
The test reads what the build produced, so it needs no second model of the contract.

### ADR-3: Collision check runs over the whole compilation, on effective names

**Context**: `McpToolGenerator` runs per service, so no single generator call sees every tool
name. Spring AI would only find duplicates at runtime, if at all.
**Decision**: `AgenticProcessor` collects `(effective tool name → declaring method elements)` for
every AI-channel method active at the configured major while building the service models, and
reports collisions before generating MCP tools. Errors go through `Messager` on each method
element (FR-007).
**Consequences**: Collisions across services compiled in *separate* compilations (different
modules) are not detected; that needs the Contract IR (Phase 2) and is recorded as out of scope.

### ADR-4: One strict-mode option, validated once

**Context**: FR-009 is the first of several epic diagnostics with a "strict mode" (collections,
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
over Streamable HTTP when the property is set (FR-013), keep `SseUnchangedTest` as the SSE guard
(FR-014), and document the switch (FR-015). If the lazy `ToolCallbackProvider` turns out not to
be picked up by the streamable auto-configuration, the fix belongs in `AgenticMcpConfiguration`
and is in scope.
**Consequences**: The default stays SSE with no change for deployments; one property opts in.

## Project Structure

```
modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/OpenApiGenerator.java   — query parameters, response content per FR-003
modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java             — ai.atlas.strict option, collision check, description diagnostic
modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/AttributeResolver.java       — report which description fallback applied
modules/processor/src/test/java/com/egoge/ai/atlas/processor/RestOpenApiConsistencyTest.java   — new: golden pairs of controller + operation (FR-001..003)
modules/processor/src/test/java/com/egoge/ai/atlas/processor/OpenApiGeneratorTest.java         — updated: requestBody expectation becomes query parameters
modules/processor/src/test/java/com/egoge/ai/atlas/processor/ToolNameCollisionTest.java        — new (FR-006..008)
modules/processor/src/test/java/com/egoge/ai/atlas/processor/ToolDescriptionDiagnosticTest.java — new (FR-009..011)
modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticExtension.java           — strict property
modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticPlugin.java              — pass -Aai.atlas.strict
modules/runtime/src/test/java/com/egoge/ai/atlas/runtime/mcp/StreamableHttpTransportTest.java  — new (FR-013)
demo/src/main/java/com/egoge/ai/atlas/demo/service/OrderService.java                          — method description for findByStatus (FR-012)
demo/src/test/java/com/egoge/ai/atlas/demo/OpenApiRuntimeConsistencyTest.java                 — new (FR-004)
demo/src/main/resources/application.yml                                                       — commented protocol property (FR-015)
scripts/check-demo-no-atlas-warnings.sh                                                       — FR-012 verifier: demo compiles with no ai-atlas warning (added with this spec)
docs/harness-integration.md                                                                   — both transports (FR-015)
docs/annotation-guide.md, docs/processor-internals.md                                        — canonical parameters (FR-005)
CHANGELOG.md                                                                                  — declared correction (FR-005), new diagnostics and option
```

## Scope

### Task 1: REST ↔ OpenAPI consistency (US1, FR-001..005)

**Files**: `OpenApiGenerator.java`, `OpenApiGeneratorTest.java`, `RestOpenApiConsistencyTest.java`,
`OpenApiRuntimeConsistencyTest.java`, `CHANGELOG.md`, docs
**Acceptance criteria**:
- [ ] Operations use `in: query` parameters, `required: true`, no `requestBody` (FR-002)
- [ ] Response content per return kind (FR-003)
- [ ] Demo test calls every documented operation successfully (FR-004)
- [ ] Changelog entry "REST parameters are query parameters" (FR-005)

### Task 2: Tool-name collision detection (US2, FR-006..008)

**Files**: `AgenticProcessor.java`, `ToolNameCollisionTest.java`
**Acceptance criteria**:
- [ ] Cross-service and overload collisions fail with an error at every site (FR-006, FR-007)
- [ ] Unique names unchanged; inactive/API-only methods ignored (FR-008)

### Task 3: Description diagnostics and strict mode (US3, FR-009..012)

**Files**: `AgenticProcessor.java`, `AttributeResolver.java`, `AgenticExtension.java`,
`AgenticPlugin.java`, `ToolDescriptionDiagnosticTest.java`, `OrderService.java`, `CHANGELOG.md`
**Acceptance criteria**:
- [ ] Warning names method, tool and fallback (FR-009)
- [ ] `ai.atlas.strict=true` makes it an error; bad value is an error (FR-010)
- [ ] API-only methods silent; MCP descriptions unchanged (FR-011)
- [ ] Demo compiles with no ai-atlas warning (FR-012)

### Task 4: Streamable HTTP (US4, FR-013..015)

**Files**: `StreamableHttpTransportTest.java`, possibly `AgenticMcpConfiguration.java`,
`application.yml`, `docs/harness-integration.md`
**Acceptance criteria**:
- [ ] Same tools listed over Streamable HTTP as over SSE (FR-013)
- [ ] `SseUnchangedTest` passes unmodified (FR-014)
- [ ] Docs and demo config updated; STDIO untouched (FR-015)

## Constraints / What NOT to Build

- No change to generated controllers' REST wire format (ADR-1).
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
| Team Lead / Framework Architect | `modules/gradle-plugin/**`, `demo/src/main/java/**`, `demo/src/test/**`, `docs/**`, `CHANGELOG.md` |

## Collaboration (Dual Mode)

Single-provider build; review gating is handled by the auto-build-loop reviewer (DeepSeek, per
`automation.json`), not the dual peer-review protocol. `collaboration_mode: single`.
