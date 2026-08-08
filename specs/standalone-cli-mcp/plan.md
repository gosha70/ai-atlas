---
spec_mode: full
feature_id: standalone-cli-mcp
risk_category: integration
justification: "New public surface (CLI + standalone STDIO MCP server) integrating ai-atlas with external AI harnesses; multi-module and behaviour-preserving, so full SDD with review gating is warranted."
status: approved
date: 2026-08-07
collaboration_mode: single
origin:
  issue: gosha70/ai-atlas#12
  transcripts:
    - specs/standalone-cli-mcp/origin/2026-08-07-user-directive.md
  origin_claim: |
    The user asked, in their own words: "write a new issue feature for ai-atlas to
    support a similar standalone CLI so AI Harness can call" — i.e. ai-atlas should be
    invocable directly by an AI coding harness as a process-spawnable tool (CLI and/or
    STDIO MCP), the same integration shape planned for the sibling ai-anvil tool, rather
    than only as an SSE endpoint inside a running Spring Boot app. The canonical, expanded
    statement of this is GitHub issue gosha70/ai-atlas#12.
---

# Implementation Plan: Standalone CLI + STDIO MCP Server

**Branch**: `feature/standalone-cli-mcp`
**Input**: specs/standalone-cli-mcp/spec.md

## Summary

Add two thin, Spring-free surfaces over ai-atlas's existing code generators so an AI harness can invoke them
directly: an `atlas` picocli CLI and a standalone STDIO MCP server. Both reuse a new `AtlasGenerator` driver
in the `processor` module that runs the existing `AgenticProcessor` in-process via the platform
`JavaCompiler` — no generator is reimplemented and output stays byte-identical to the build. The runtime
SSE MCP path is untouched.

## Technical Context

**Language/Version**: Java 17 (per `gradle/libs.versions.toml` — `java = "17"`), Gradle Kotlin DSL.
**Primary Dependencies**: `modules/processor` (existing `AgenticProcessor` + generators), platform
`javax.tools.JavaCompiler`, **picocli** (new, CLI), **MCP Java SDK** `io.modelcontextprotocol.sdk` (new,
STDIO server). Reuses google `compile-testing`-style in-process compilation already proven in processor tests.
**Testing**: JUnit 5 + AssertJ (catalog). New tests: `AtlasGenerator` golden-output vs the demo build; CLI
subcommand + `--json` + exit-code tests; a STDIO MCP round-trip test (list tools + call each). `./gradlew build`.
**Constraints**: annotations module keeps ZERO deps; `processor` stays runtime-dep-free (the driver uses only
JDK `javax.tools` + existing processor code); the `cli`/`mcp-stdio`/`driver` modules declare **no Spring
dependency of their own**, but the generation **compile classpath** the caller passes in (`--classpath`) must
carry Spring AI + Spring Web types so the emitted `@Tool`/`@Service`/`@RestController` wrappers compile — same
as the processor `compile-testing` tests; generated-code shapes and the `@Generated("ai.atlas.processor")`
marker are unchanged. `cli` and `mcp-stdio` are packaged as Shadow fat jars (`atlas.jar`, `atlas-mcp.jar`).

## Constitution Check

| Rule file | Concern | Status |
|-----------|---------|--------|
| `coding-standards.md` | No magic strings, named constants for tool/command names, no secrets, lint-clean | OK — tool names (`atlas_generate`…) and CLI option keys defined as constants in the lowest common module |
| `safety.md` | No credentials; validate external inputs (paths, classpath) at the CLI/MCP boundary | OK — path/classpath args validated before compile; no shell interpolation of untrusted input |
| `copilot-conventions.md` | One logical change per commit; repo is source of truth; version catalog is sole version authority | OK — picocli + MCP SDK versions added to `libs.versions.toml`, not inline |

## Architecture Decisions

### ADR-1: Drive generation via the platform JavaCompiler, not a reimplementation

**Context**: The generators run inside `javac`'s annotation-processing round (they use `ProcessingEnvironment`,
`Filer`, `TypeElement`). To generate outside a Gradle `compileJava` task we must still give them a real
processing environment.
**Decision**: `AtlasGenerator` invokes `ToolProvider.getSystemJavaCompiler()` over the caller's source set with
`AgenticProcessor` registered and generated output captured to the target dir — the same in-process compilation
pattern already used by the processor's `compile-testing` tests.
**Consequences**: Zero generator changes; output is byte-identical (FR-002); requires a JDK (not just a JRE) at
run time — documented in `harness-integration.md`. The caller must supply the target app's compile classpath
(`--classpath`) carrying Spring AI/Web types so the generated wrappers compile — the driver module itself
stays Spring-free (FR-005). Rejected: re-parsing sources with a standalone AST library (would fork generation
logic and drift).

### ADR-2: STDIO server on the MCP Java SDK, not Spring AI

**Context**: MCP clients spawn a local stdio child process; Spring AI's `spring-ai-starter-mcp-server-webmvc`
requires a servlet container and serves SSE.
**Decision**: `modules/mcp-stdio` is a standalone `main()` using `io.modelcontextprotocol.sdk` stdio transport,
wrapping `AtlasGenerator`. It shares no code with the runtime SSE server.
**Consequences**: Spring-free, spawnable, lightweight; a second (small) MCP wiring exists — acceptable because
the two serve different lifecycles (build-time vs deployed). Rejected: adding STDIO to the Spring AI server
(couples the harness surface to a Spring context — see FR-008).

### ADR-3: New surfaces are additive; runtime SSE path is frozen

**Context**: The deployed-app SSE MCP server must keep working exactly as today.
**Decision**: No edits to `modules/runtime` MCP wiring beyond, if needed, a test asserting the SSE path is
unchanged. All new code lands in `modules/cli`, `modules/mcp-stdio`, and a new driver package in
`modules/processor`.
**Consequences**: Clear separation; low regression risk; `settings.gradle.kts` gains two `include(...)` entries.

### ADR-4: Package the CLI and STDIO server as Shadow fat jars

**Context**: The spec promises `java -jar atlas.jar` and a `.mcp.json` `java -jar atlas-mcp.jar` launch. Gradle's
`application` plugin yields a start script + a thin jar (dependencies alongside, not inside), **not** a
self-contained runnable jar; this repo has no existing non-Boot fat-jar pattern.
**Decision**: Apply the Gradle Shadow plugin (`com.gradleup.shadow`) to `modules/cli` and `modules/mcp-stdio` to
produce `atlas.jar` / `atlas-mcp.jar` with their dependencies bundled and a `Main-Class` manifest; pin the
plugin version in `libs.versions.toml`.
**Consequences**: The advertised `java -jar` invocation and the `.mcp.json` example both work. Two fat jars
become build outputs. The user's *application* classes are NOT bundled — they are supplied at run time via
`--classpath` (see ADR-1/FR-005). Rejected: the `application` plugin's `installDist` (invocation is via a
generated start script, not `java -jar`, and would not match the spec's `.mcp.json` snippet).

## Project Structure

```
modules/processor/src/main/java/com/egoge/ai/atlas/processor/driver/AtlasGenerator.java   — new: in-process compile driver
modules/processor/src/main/java/com/egoge/ai/atlas/processor/driver/GenerationResult.java  — new: result model (files, openapi, diagnostics)
modules/processor/src/main/java/com/egoge/ai/atlas/processor/driver/GeneratedFile.java      — new: path + kind
modules/processor/src/main/java/com/egoge/ai/atlas/processor/driver/Diagnostic.java         — new: severity + message + source
modules/processor/src/test/java/.../driver/AtlasGeneratorGoldenTest.java                    — new: byte-identical vs demo build
modules/cli/build.gradle.kts                                                                — new module (picocli + Shadow fat jar → atlas.jar)
modules/cli/src/main/java/com/egoge/ai/atlas/cli/AtlasCli.java                              — new: picocli root
modules/cli/src/main/java/com/egoge/ai/atlas/cli/GenerateCommand.java                       — new: generate
modules/cli/src/main/java/com/egoge/ai/atlas/cli/OpenApiCommand.java                        — new: openapi
modules/cli/src/main/java/com/egoge/ai/atlas/cli/InspectCommand.java                        — new: inspect (dry run)
modules/cli/src/main/java/com/egoge/ai/atlas/cli/JsonOutput.java                            — new: stable JSON schema + writer
modules/cli/src/test/java/.../cli/AtlasCliTest.java                                         — new: subcommands, --json, exit codes
modules/mcp-stdio/build.gradle.kts                                                          — new module (MCP Java SDK + Shadow fat jar → atlas-mcp.jar)
modules/mcp-stdio/src/main/java/com/egoge/ai/atlas/mcp/AtlasMcpServer.java                  — new: stdio main() + tool registration
modules/mcp-stdio/src/test/java/.../mcp/AtlasMcpServerTest.java                             — new: list + call each tool round-trip
docs/harness-integration.md                                                                 — new: CLI + STDIO + sample hook + SSE note
gradle/libs.versions.toml                                                                    — add: picocli, mcp-sdk, shadow-plugin versions/libraries
settings.gradle.kts                                                                          — add: include :modules:cli, :modules:mcp-stdio
```

## Scope

### Task 1: AtlasGenerator driver (US1, FR-001/002/009)
**Files**: `modules/processor/.../driver/*.java`, `AtlasGeneratorGoldenTest.java`
**Acceptance criteria**:
- [ ] `AtlasGenerator.generate(sources, classpath, outDir)` returns a populated `GenerationResult`; the driver *module* declares no Spring dependency, while the passed-in `classpath` supplies Spring AI/Web types so the generated wrappers compile (as the processor `compile-testing` tests do)
- [ ] Golden test asserts output equals `./gradlew :demo:compileJava` generated sources byte-for-byte (FR-002), compiling against a classpath that includes Spring AI + Spring Web
- [ ] Emitted files carry `@Generated("ai.atlas.processor")` (FR-009)

### Task 2: `atlas` CLI generate/openapi + JSON (US2, FR-003/004/005/011)
**Files**: `modules/cli/**`, `gradle/libs.versions.toml`, `settings.gradle.kts`
**Acceptance criteria**:
- [ ] `generate` and `openapi` subcommands write to `--out`; picocli usage/help present
- [ ] `--json` emits a documented stable object on stdout, diagnostics on stderr, non-zero exit on error
- [ ] The `cli` module declares no Spring dependency of its own (FR-005) — Spring types come from the caller's `--classpath`
- [ ] Shadow `atlas.jar` built and runnable: `java -jar modules/cli/build/libs/atlas.jar --help` works (FR-011)

### Task 3: `atlas inspect` dry run (US3, FR-003)
**Files**: `modules/cli/src/main/java/.../InspectCommand.java`, test
**Acceptance criteria**:
- [ ] `inspect --json` lists exposed services + would-be tools/endpoints/DTOs without writing files

### Task 4: STDIO MCP server (US4, FR-006/007/005/011)
**Files**: `modules/mcp-stdio/**`, `gradle/libs.versions.toml`, `settings.gradle.kts`
**Acceptance criteria**:
- [ ] `AtlasMcpServer` `main()` speaks MCP over stdio (MCP Java SDK), exposes the three tools
- [ ] Round-trip test lists tools and calls each; results carry a file manifest + summary (FR-007)
- [ ] The `mcp-stdio` module declares no Spring dependency of its own (FR-005) — Spring types come from the caller's `--classpath`
- [ ] Shadow `atlas-mcp.jar` built and runnable via `java -jar` for the `.mcp.json` launch (FR-011)

### Task 5: Docs + SSE regression guard (US5, FR-008/010)
**Files**: `docs/harness-integration.md`, a runtime test asserting SSE wiring unchanged
**Acceptance criteria**:
- [ ] `harness-integration.md` documents CLI + STDIO + a sample Claude Code hook and the SSE path
- [ ] Test/assertion confirms the runtime SSE MCP path is unchanged (FR-008); `./gradlew build` green

## Constraints / What NOT to Build

- No annotation-contract or generated-shape changes — driver-only reuse.
- No generator reimplementation — everything routes through `AtlasGenerator`.
- No STDIO transport bolted onto the Spring AI runtime server — new module only.
- No `gradle-plugin` `atlasGenerate` task in this cut — deferred follow-up.
- No LLM / network I/O on either surface.

## File Ownership (Non-Overlapping)

| Owner | Files |
|-------|-------|
| Annotation Processor Engineer | `modules/processor/.../driver/**` |
| MCP & Spring AI Specialist | `modules/mcp-stdio/**` |
| Team Lead / Framework Architect | `modules/cli/**`, `gradle/libs.versions.toml`, `settings.gradle.kts`, `docs/harness-integration.md` |

## Collaboration (Dual Mode)

Single-provider build; review gating is handled by the auto-build-loop reviewer panel (see
`automation.json`), not the dual peer-review protocol. `collaboration_mode: single`.
