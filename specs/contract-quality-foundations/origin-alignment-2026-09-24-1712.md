# Origin alignment check — contract-quality-foundations

Origin: https://github.com/gosha70/ai-atlas/issues/23 (read in full this session, including the "Suggested issue split" and "Priority / recommended sequencing" sections) + specs/contract-quality-foundations/origin/2026-09-24-owner-decisions.md

Origin claim:
> Epic gosha70/ai-atlas#23 moves AI-ATLAS "from deterministic interface generation to governed interface lifecycle management" and asks to "keep this as the epic and create focused child issues for implementation", in its stated order: Phase 0, "Fix generated REST ↔ OpenAPI parameter-contract mismatch" ("Immediate correctness prerequisite"), then the Phase 1 items "MCP tool-name collision detection", "Per-method AI tool description diagnostics" and "Streamable HTTP transport alongside SSE". Phase 0 "should state which representation is canonical and treat the correction as a declared change rather than a silent one". The owner asked on 2026-09-24 to set up Claude Code to implement the epic, approved splitting it with this feature (Phase 0 + the three Phase 1 items) first, and chose query parameters as canonical, SSE as the default transport with Streamable HTTP opt-in, and a single `ai.atlas.strict` option for strict mode.

Working claim:
> spec.md delivers the epic's Phase 0 and all three Phase 1 items as one feature. Phase 0: the generated OpenAPI describes the generated controllers exactly (same method/path, arguments as required `in: query` parameters with no requestBody, responses per return kind), proven by a demo test that calls every documented operation and a processor golden test, with the correction declared in CHANGELOG and docs (FR-001..005). Phase 1: a compile ERROR at every site when effective MCP tool names collide across the compilation, existing unique names unchanged (FR-006..008, epic §2 acceptance criteria); a WARNING — ERROR under the new `ai.atlas.strict` — for AI methods without their own description, API-only methods unaffected, MCP descriptions keep version/deprecation guidance (FR-009..012, epic §3); Streamable HTTP served when `spring.ai.mcp.server.protocol=STREAMABLE`, SSE default unchanged, STDIO unchanged, documented (FR-013..015, epic §4). Phases 2–5 are explicitly out of scope, per the epic's split.

Mismatches:
  - none. The feature is a strict subset of the epic by design: the epic itself asks for implementation through focused child features in the stated order, and the owner approved this cut and its three open decisions on 2026-09-24. Cross-compilation collision detection is noted as needing the Phase 2 Contract IR (plan ADR-3); the epic's §2 criteria ("Duplicate effective MCP tool names fail compilation") are met within a compilation.

Verdict: aligned
Confidence: high
