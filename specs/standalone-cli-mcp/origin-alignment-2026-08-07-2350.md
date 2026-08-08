# Origin alignment check — standalone-cli-mcp

Origin: GitHub issue gosha70/ai-atlas#12 (read fresh via `gh issue view 12`, 2026-08-07); user directive at
`specs/standalone-cli-mcp/origin/2026-08-07-user-directive.md`.

Origin claim:
> Add a standalone, process-spawnable entry point to ai-atlas — an `atlas` CLI and a STDIO MCP server — so an
> AI coding harness (Claude Code, PI.dev, Cursor, IntelliJ) can invoke the generator directly via a hook
> script or `.mcp.json` command, **without** running a host Spring Boot web app and connecting over SSE.
> Two new modules: `modules/cli` (picocli executable with `generate`/`inspect`/`openapi` and a `--json`
> hook-friendly mode) and `modules/mcp-stdio` (standalone STDIO server on the MCP Java SDK, not Spring AI),
> both reusing the existing `processor` generators with no change to the annotation contract or
> generated-code shapes, and leaving the existing SSE-in-Spring-Boot runtime path unchanged.

Working claim:
> The spec (`spec.md`) delivers exactly this: US1 an `AtlasGenerator` driver in `processor` that runs
> `AgenticProcessor` in-process via the platform `JavaCompiler` (reuse, not reimplementation, output
> byte-identical); US2/US3 an `atlas` picocli CLI with `generate`/`openapi`/`inspect` and a documented stable
> `--json` mode + exit codes; US4 a standalone `modules/mcp-stdio` server on `io.modelcontextprotocol.sdk`
> exposing `atlas_inspect_services`/`atlas_generate`/`atlas_openapi` over stdio with a file-manifest result;
> US5 `docs/harness-integration.md` + a sample hook, with an explicit regression guard that the runtime SSE
> path is unchanged. The dependency contract is precise (FR-005): the CLI/STDIO/driver modules carry no
> Spring dependency of their own, while the caller-supplied generation compile classpath (`--classpath`,
> consistently in every `generate` example including the Success Criteria) must carry Spring AI/Web types so
> generated wrappers compile. Packaging is explicit (FR-011/ADR-4): Shadow fat jars `atlas.jar`/`atlas-mcp.jar`.

Mismatches:
  - none. (The `gradle-plugin` `atlasGenerate` task the issue lists only as an optional convenience is
    explicitly deferred in both the issue's "Module(s) Affected" framing and the spec's Constraints — a scope
    agreement, not a divergence.)

Verdict: aligned
Confidence: high

<!-- Regenerated 2026-08-07-2350 at the Plan Approval Gate: plan.md flipped draft -> approved (user launched
     `claude-code build standalone-cli-mcp`), which re-stales the prior record. Content unchanged; alignment
     still aligned/high. -->
