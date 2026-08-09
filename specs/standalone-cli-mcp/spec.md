---
spec_mode: full
feature_id: standalone-cli-mcp
risk_category: integration
status: draft
date: 2026-08-07
---

# Spec: Standalone CLI + STDIO MCP Server

<!-- Project constitution: shared/skills/ — copilot-conventions, coding-standards, safety -->
<!-- Origin: GitHub issue gosha70/ai-atlas#12. See plan.md `origin:` frontmatter. -->

Give ai-atlas a process-spawnable surface — an `atlas` CLI and a STDIO MCP server — so an AI coding
harness (Claude Code, PI.dev, Cursor, IntelliJ assistants) can invoke the generator directly via a hook
script or `.mcp.json` command, **without** running a host Spring Boot web app and connecting over SSE.
The existing SSE-in-Spring-Boot runtime path is preserved and remains the default for live tool serving.

## User Scenarios

### US1: Programmatic generation driver (Priority: HIGH)

**Given** a directory of `@AgenticExposed`-annotated Java sources and a compile classpath
**When** a caller invokes the new `AtlasGenerator` driver API (no Spring context, no `javac` round of its own)
**Then** it compiles the sources with `AgenticProcessor` registered and returns a structured
`GenerationResult` — generated file paths, the OpenAPI spec, and diagnostics — with output **byte-identical**
to the annotation-processing build.

### US2: CLI generate / openapi with JSON mode (Priority: HIGH)

**Given** the `atlas` executable jar
**When** a developer or hook runs `atlas generate --sources <dir> --classpath <cp> --out <dir>` (optionally `--json`)
**Then** generated sources + OpenAPI are written to `--out`, a documented stable JSON summary is emitted on
stdout under `--json`, diagnostics go to stderr, and the exit code is non-zero on any error.

### US3: CLI inspect (dry run) (Priority: MEDIUM)

**Given** annotated sources
**When** a developer or agent runs `atlas inspect --sources <dir> --json`
**Then** the CLI reports, without writing files, the exposed services and the MCP tools / REST endpoints /
DTOs that *would* be generated, as machine-readable JSON.

### US4: STDIO MCP server (Priority: HIGH)

**Given** a copilot with `ai-atlas` registered in `.mcp.json` as `{"command":"java","args":["-jar","atlas-mcp.jar",...]}`
**When** the copilot lists and calls tools over stdio, with **no Spring Boot app running**
**Then** the standalone server (MCP Java SDK, not Spring AI) exposes `atlas_inspect_services`,
`atlas_generate`, and `atlas_openapi`, each returning a file manifest + summary so the agent can review
before writing.

### US5: Harness integration docs + SSE regression guard (Priority: MEDIUM)

**Given** the two new surfaces
**When** a developer reads `docs/harness-integration.md`
**Then** they find both invocation paths (CLI/STDIO for build-time/harness-time; SSE for live runtime) and a
sample Claude Code hook shelling to `atlas ... --json`; and the existing runtime SSE MCP path is verified
unchanged.

## Requirements

- **FR-001**: The `processor` module MUST expose a public `AtlasGenerator` driver API that runs
  `AgenticProcessor` over a caller-supplied source set + classpath (in-process, via the platform
  `javax.tools.JavaCompiler`) and returns a `GenerationResult`, **without** requiring a Spring context.
- **FR-002**: For identical inputs, `AtlasGenerator` MUST produce byte-identical generated sources and
  OpenAPI output to the standard annotation-processing build (`./gradlew :demo:compileJava`).
- **FR-003**: The `atlas` CLI MUST provide `generate`, `openapi`, and `inspect` subcommands (picocli).
- **FR-004**: The CLI MUST support a `--json` mode emitting a documented, stable JSON object on stdout,
  human diagnostics on stderr, and a non-zero exit code on any error (hook-friendly, deterministic).
- **FR-005**: The `cli`, `mcp-stdio`, and processor `driver` modules MUST NOT declare their own dependency on
  Spring (Spring Web, Spring AI, or the servlet stack) — the driver only orchestrates the JDK compiler.
  **This does not mean generation runs without Spring on the classpath:** because the generated wrappers
  reference `@Tool`, `@Service`, and `@RestController`, the caller MUST supply those Spring AI + Spring Web
  types on the generation **compile classpath** (`--classpath`) for the emitted code to compile — exactly as
  the demo build and the existing processor `compile-testing` tests do. The contract is: *no Spring in the
  tool's own module dependencies; Spring types required on the target compile classpath.*
- **FR-011**: The `cli` and `mcp-stdio` modules MUST each produce a self-contained, runnable jar
  (`atlas.jar`, `atlas-mcp.jar`) invocable via `java -jar` — a fat/shadow jar bundling their own dependencies.
  The repo has no Spring Boot fat-jar packaging for these non-Boot apps, so a packaging strategy (Gradle
  Shadow plugin) MUST be added explicitly. The user's *application* classes are not bundled — they are passed
  at run time via `--classpath` (see FR-005).
- **FR-006**: The STDIO MCP server MUST expose `atlas_inspect_services`, `atlas_generate`, and
  `atlas_openapi` over stdio using the MCP Java SDK (`io.modelcontextprotocol.sdk`), NOT the Spring AI
  WebMvc starter.
- **FR-007**: Each MCP tool result MUST include a generated-file manifest + summary so an agent can review
  before writing to disk.
- **FR-008**: The existing runtime SSE MCP server path MUST remain unchanged and continue to be the default
  for live/deployed tool serving.
- **FR-009**: All files emitted through the new surfaces MUST retain the `@Generated(...)` marker the
  annotation-processing path emits — behaviour identical to it, i.e. no change to the generated shape.
  > **Erratum (2026-08-09, review round 4).** This requirement was written as
  > `@Generated("ai.atlas.processor")`. The value the generators have actually emitted since the
  > `ai.atlas` → `com.egoge.ai.atlas` package rename — long before this feature — is
  > **`@Generated("com.egoge.ai.atlas.processor")`** (see `DtoGenerator`, `McpToolGenerator`,
  > `RestControllerGenerator`, and `docs/processor-internals.md`). The literal in the original FR-009
  > text was a stale carry-over, not a request to change output. FR-009's binding intent is *retain /
  > identical behaviour*, which the Constraints section reinforces ("No … changes to generated-code
  > shapes"); changing the emitted string would itself be the breaking change. The canonical value is
  > therefore `com.egoge.ai.atlas.processor`, and `AtlasGeneratorGoldenTest` asserts it.
  > **Still open for the maintainer:** `CLAUDE.md` ("Annotation Processing Rules") carries the same
  > stale `@Generated("ai.atlas.processor")` line. It is a project rules file, so this build session
  > did not edit it; correcting that line is a one-word maintainer change.
- **FR-010**: The change MUST add `docs/harness-integration.md` documenting the CLI + STDIO paths and a
  sample Claude Code hook, alongside the existing SSE path.

## Constraints / What NOT to Build

- No changes to the annotation contract (`@AgenticExposed`, `@AgenticField`, …) or to generated-code shapes —
  this feature is purely a new *driver* over existing generators.
- No re-implementation of any generator — the CLI/MCP surfaces MUST reuse the existing generators via the
  `AtlasGenerator` driver.
- No new transport added to the runtime Spring AI MCP server — STDIO lives only in the new standalone module.
- No `gradle-plugin` `atlasGenerate` task in this cut — deferred to a follow-up (optional convenience wrapper).
- No LLM calls and no network I/O anywhere in these surfaces — generation is deterministic and offline.
- No Spring dependency in the CLI / STDIO / driver *module* dependencies (they only drive the JDK compiler) —
  but Spring types are still required on the caller-supplied generation compile classpath so the generated
  `@Tool`/`@Service`/`@RestController` wrappers compile.

## Key Entities

- **AtlasGenerator**: public driver (in `processor`) that compiles target sources with `AgenticProcessor`
  registered and returns a `GenerationResult`. The single reuse point for both new surfaces.
- **GenerationResult**: structured output — list of `GeneratedFile` (path + kind: DTO/MCP-tool/REST/OpenAPI),
  the OpenAPI document, and a list of `Diagnostic` (severity, message, source).
- **AtlasCli**: picocli root command exposing `generate` / `openapi` / `inspect` subcommands and `--json`.
- **AtlasMcpServer**: standalone `main()` STDIO MCP server wrapping `AtlasGenerator`, exposing three tools.

## Success Criteria

1. **US1/FR-002**: `java -jar atlas.jar generate --sources demo/src/main/java --classpath <demo compile classpath>
   --out build/atlas-gen` (the `--classpath` carrying Spring AI + Spring Web types, per FR-005) produces the
   same generated files (path + content) as `./gradlew :demo:compileJava`.
2. **US2/FR-004**: `atlas generate --json` and `atlas inspect --json` emit documented, stable JSON and correct
   exit codes; a shell hook can consume stdout and branch on exit status.
3. **US4/FR-006**: An MCP client (e.g. MCP Inspector) registers `atlas-mcp.jar` over stdio via `.mcp.json`,
   lists the three tools, and calls each successfully with no Spring Boot app running.
4. **FR-008**: The existing runtime SSE MCP path is unchanged; `demo:bootRun` still serves tools at `/sse`.
5. **No regressions**: `./gradlew build` (all modules + tests) is green.
