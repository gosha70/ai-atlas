# Origin alignment check — standalone-cli-mcp

Origin: GitHub issue gosha70/ai-atlas#12 (re-read fresh via `gh issue view 12`, 2026-08-08, state OPEN);
user directive at `specs/standalone-cli-mcp/origin/2026-08-07-user-directive.md`.

Trigger: the phase-1 build session edited `spec.md` and `plan.md` (FR-009 erratum), which re-stales the
prior record (`origin-alignment-2026-08-07-2350.md`) by design. This check re-verifies the *edited* working
artifact against the origin.

Origin claim:
> Add a standalone, process-spawnable entry point to ai-atlas — an `atlas` CLI and a STDIO MCP server — so an
> AI coding harness (Claude Code, PI.dev, Cursor, IntelliJ) can invoke the generator directly via a hook
> script or `.mcp.json` command, **without** running a host Spring Boot web app and connecting over SSE.
> Two new modules: `modules/cli` (picocli executable with `generate`/`inspect`/`openapi` and a `--json`
> hook-friendly mode) and `modules/mcp-stdio` (standalone STDIO server on the MCP Java SDK, not Spring AI),
> both reusing the existing `processor` generators. The issue is explicit that this is
> "a thin, **Spring-optional** surface over the existing modules. **No changes to the annotation contract
> or generated-code shapes**" (#12 L30-31), that "All generated files keep the `@Generated(...)` marker;
> **behavior identical to the annotation-processing path**" (#12 L78), and that the existing SSE runtime
> path stays unchanged.

Working claim:
> Unchanged in scope, surface, and deliverables since the last aligned record: US1 `AtlasGenerator` driver
> (in-process `JavaCompiler`, reuse not reimplementation), US2/US3 the `atlas` picocli CLI with
> `generate`/`openapi`/`inspect` and `--json`, US4 the standalone `mcp-stdio` server on the MCP Java SDK,
> US5 docs plus an SSE regression guard; FR-005 classpath contract and FR-011 Shadow fat-jar packaging
> intact. The only changes since the prior record are corrective, not scope-bearing:
> (a) **FR-009 erratum** — the requirement's literal `@Generated("ai.atlas.processor")` is documented as a
> stale carry-over; the value the generators have emitted since the `ai.atlas` → `com.egoge.ai.atlas`
> package rename (predating this feature) is `@Generated("com.egoge.ai.atlas.processor")`, which
> `AtlasGeneratorGoldenTest` now asserts. Emitted output is **unchanged**.
> (b) `plan.md` restated to match, and one Task-1 acceptance box ticked.

Mismatches:
  - none that constitute drift.
  - **Noted, and resolved in favour of the origin's intent:** the origin issue #12 (L78) carries the *same*
    stale literal `@Generated("ai.atlas.processor")` as the original FR-009, because the issue was drafted
    from the same stale source (`CLAUDE.md`, "Annotation Processing Rules"). Read literally, the working
    artifact now differs from that one string in the origin text. Read for intent — which the same issue
    states twice, at L30-31 ("No changes to … generated-code shapes") and L78 ("behavior identical to the
    annotation-processing path") — the working artifact is faithful, and **changing the emitted string to
    match the stale literal would itself violate the origin**. The erratum documents this rather than
    silently diverging. Verdict follows intent, per the binding requirement.

Verdict: aligned
Confidence: high

## Maintainer follow-ups (not blocking this gate)

The stale `ai.atlas.processor` literal exists in three places; one is now corrected:

1. `specs/standalone-cli-mcp/spec.md` FR-009 — **corrected** via the erratum (this session).
2. `CLAUDE.md`, "Annotation Processing Rules" — still stale. The build session correctly declined to edit a
   project rules file; a one-word maintainer change.
3. GitHub issue gosha70/ai-atlas#12, L78 — still stale. Worth a short comment on the issue so the origin of
   record does not keep re-seeding the error into future specs.

<!-- Record created 2026-08-08-2204 after the phase-1 build session's FR-009 erratum edits to spec.md and
     plan.md. Origin re-read in full this session; scope, surface, and deliverables unchanged. -->
