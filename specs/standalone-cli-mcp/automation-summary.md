
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
