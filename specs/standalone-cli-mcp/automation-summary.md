
### Phase 1 complete — US1: Programmatic generation driver (2026-08-09T15:02:51Z)
Review: PASS (see specs/standalone-cli-mcp/collaboration/build-review.md).

### Phase 2 complete — US2: CLI generate / openapi with JSON mode (2026-08-09T18:14:11Z)
Review: PASS (see specs/standalone-cli-mcp/collaboration/build-review.md).

## Milestone checkpoint — after phase 2 (US2: CLI generate / openapi with JSON mode)

Paused 2026-08-09T18:14:11Z for batched manual testing + retro.

- [x] Manual testing of phases up to 2 complete
- [x] Retro notes recorded

To resume: append a line 'approved-by: <name> <date>' below, then run:
`scripts/auto-build-loop.sh standalone-cli-mcp --resume`

### Manual testing — 7/7 against the shadow jar (JDK 17)

Run through `java -jar modules/cli/build/libs/atlas.jar`, with the demo's own compile classpath and its
processor options (`-Aai.atlas.api.major=2` etc. — omitting them legitimately changes output).

| # | Requirement | Result |
|---|---|---|
| T1 | FR-011 — fat jar runs standalone | pass (`generate`/`openapi`; no `inspect`, that is US3) |
| T2 | FR-004 — `--json` manifest | pass — DTO 4, MCP_TOOL 1, REST_CONTROLLER 2, OPENAPI 2, +2 |
| T3 | **FR-002 — byte-identity vs `:demo:compileJava`** | **pass — sources byte-identical** |
| T4 | FR-004 — error path | pass — exit 1, `status:"error"`, errors[] set, message on stderr |
| T5 | failed compile preserves last good output | pass — `status:"error"`, `files:[]`, 4 DTOs still on disk |
| T6 | `openapi`, incl. `--out` | pass — and confirms `f-43539867` is genuinely fixed |
| T7 | FR-005 — no Spring in the tool's own jar | pass — 0 Spring classes bundled |

### Retro

- **One defect found, filed as ai-atlas#17:** `--json` diagnostics leak the internal
  `.atlas-staging<random>/` path in `source` and `message`. The path no longer exists when the consumer reads
  it, and the random suffix makes `--json` non-deterministic across identical runs — both at odds with
  FR-004's stable-schema promise. Not a generation defect: artifacts are byte-identical to the build.
- **One false alarm retracted:** an apparent trailing-newline difference in the OpenAPI document was an
  artifact of `jq -r` appending a newline. The written file matches the build byte-for-byte (8010 bytes).
- **Review depth:** phase 2 passed on round 5 (rounds 1-4 FAIL, converging). Neither the staging leak nor
  phase 1's two P2s were caught by the gating reviewer — both came from out-of-process review and manual
  testing. Worth keeping both in the loop for US3-US5 rather than relying on the gating round alone.
- **Provider:** build sessions now inherit `--provider deepseek`; the last fix session cost $0.96 against
  $12.52 for a comparable claude-opus-5 session earlier in the same phase. The $2/round codex review
  estimate is now the dominant cost term, not the builder.
- **Bookkeeping gap:** `tasks.md` still shows US2's tasks 4-7 unchecked despite the phase being complete and
  passing review — build sessions are not updating the checkboxes, so that file is not a reliable progress
  signal.

<!-- checkpoint-after-phase: 2 -->

approved-by: gosha 2026-08-09

### Phase 3 complete — US3: CLI inspect (dry run) (2026-08-11T20:45:01Z)
Review: PASS (see specs/standalone-cli-mcp/collaboration/build-review.md).

### Phase 4 complete — US4: STDIO MCP server (2026-08-11T22:18:37Z)
Review: PASS (see specs/standalone-cli-mcp/collaboration/build-review.md).

## Milestone checkpoint — after phase 4 (US4: STDIO MCP server)

Paused 2026-08-11T22:18:37Z for batched manual testing + retro.

- [x] Manual testing of phases up to 4 complete
- [x] Retro notes recorded

To resume: append a line 'approved-by: <name> <date>' below, then run:
`scripts/auto-build-loop.sh standalone-cli-mcp --resume`

### Manual testing — US4 STDIO MCP server, real client round-trip

Driven by an independent minimal MCP client (not the module's own test): the packaged
`atlas-mcp.jar` was spawned as `java -jar atlas-mcp.jar` — exactly the `.mcp.json` launch — and spoken
to over stdio, with **no Spring Boot app running**.

| Step | Result |
|---|---|
| `initialize` | `ai-atlas v0.1.0-SNAPSHOT` |
| `tools/list` | 3 tools: `atlas_inspect_services`, `atlas_generate`, `atlas_openapi` (FR-006) |
| `tools/call atlas_inspect_services` | `isError=false`, 14,481 chars — *"Discovered 2 @AgenticExposed service(s); 11 artifact(s) would be generated. Nothing was written to disk."* (FR-007 manifest + summary; dry-run no-write contract holds) |

This exercises issue #12's core promise directly: a harness invoking ai-atlas as a spawned process, no
SSE, no host web app.

### Retro — phases 3-4

- **US4 quality was high.** Review rounds 1-5 returned **0 blocking** throughout; the PASS on round 5
  cleared warnings only. The module ships a round-trip test that spawns the real jar via
  `StdioClientTransport` rather than mocking the transport — the strongest test in the feature so far.
- **Argument-type asymmetry (worth fixing or documenting in US5):** `sources` must be an **array** of
  path strings while `classpath` must be a **single** path-separator-separated string. Two consecutive
  wrong guesses while writing the probe; an LLM calling these tools will hit the same. Mitigated by
  genuinely good error handling — each failure returned `isError:true` with a precise, actionable
  message instead of a stack trace, which is the right shape for agent consumption.
- **Phase 3 needed a bypass**, phase 4 passed cleanly. Phase 3's round-6 reviewer caught real scope
  drift — an earlier fix session had wired a `ServiceManifestGenerator` into `AgenticProcessor`, which
  would have made *every* annotation-processing build emit a new artifact, violating issue #12's "no
  changes to generated-code shapes". The following fix session reverted it (`39c81a8`); the bypass was
  taken because the round ceiling hit before a confirming round, not because findings were unresolved.
- **Second scope-drift incident** (after phase 1's `@Generated` literal). Both were caught by the
  gating reviewer, but the pattern suggests fix sessions optimise for "make the finding go away"
  rather than "stay inside the contract" — worth restating the constraints in the fix-session prompt.
- **Cost:** $144.73 of $200 at phase-4 close, of which **$44 is the `$2/invocation` review estimate**
  for 22 unmetered codex rounds — real metered spend is ~$100. The estimate, not the builder, has been
  the binding constraint on every `cap_exceeded` park this run; worth revisiting
  `unattended.budget.estimate_usd_per_invocation` against codex's actual cost.
- **Harness defects found and fixed this phase pair:** code-copilot-team #229 (runner crashed with an
  undocumented exit 5 mid state-update), #231 (the fix), #233 (crash-parked runs unresumable — the
  driver wanted `decision.json`, `/review-decide` wanted `breaker-tripped.json`, a crash writes
  neither). All merged.

<!-- checkpoint-after-phase: 4 -->

approved-by: gosha 2026-08-11
