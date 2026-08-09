---
feature_id: standalone-cli-mcp
date: 2026-08-09
status: final
phase: build
mode: review
subject_provider: claude
peer_provider: codex
peer_profile: codex
runner_fingerprint: n/a-bypass
verdict: FAIL
bypass: true
breaker_type: max_rounds
blocking_findings_open: 1
target_ref: feature/standalone-cli-mcp
rounds_completed: 5
attempt_count: 4
---

# Peer Review: standalone-cli-mcp — Build Phase (BYPASSED)

**Reviewer**: codex
**Scope**: both (correctness specialization)
**Rounds**: 5 of a maximum 5
**Verdict**: FAIL — bypassed by human decision on 2026-08-09

## Summary

The review loop never reached a PASS. It terminated on the `max_rounds` circuit
breaker when round 6 would have exceeded the limit of 5. The human resolved the
breaker with `/review-decide approve`, which records a bypass rather than a
passing review. CI will flag this artifact as a bypass.

This artifact was written by the approve path, not by `review-round-runner.sh`,
because no runner round executed after the breaker fired. `runner_fingerprint`
is therefore `n/a-bypass` rather than a real digest.

## What the loop actually covered

| Round | Verdict | Findings | Outcome |
|-------|---------|----------|---------|
| 1 | FAIL | 0 | Reviewer never ran — `Not inside a trusted directory and --skip-git-repo-check was not specified` |
| 2 | FAIL | 0 | Same invocation failure |
| 3 | FAIL | 5 | `findings-round-3.json` is empty on disk; no `resolution-round-3.json` exists |
| 4 | FAIL | 7 (3 blocking) | All 7 dispositioned `fixed` — commit `3008ea9` |
| 5 | FAIL | 3 (1 blocking) | All 3 dispositioned `fixed` — commit `853fbf7` |
| 6 | — | — | Never ran; breaker fired |

Two of the five rounds performed no review at all, so the effective review depth
is three rounds, of which one (round 3) left no findings file and no resolution
record.

## Open at bypass

**`f-ff3dad8a` (blocking, correctness)** —
`modules/processor/src/test/java/com/egoge/ai/atlas/processor/driver/AtlasGeneratorGoldenTest.java`

The reviewer held that `AGENTS.md` requires `@Generated("ai.atlas.processor")`
while the code emits and the test codifies `@Generated("com.egoge.ai.atlas.processor")`,
and that a feature-local spec erratum cannot override the authoritative rule.

The builder dispositioned this `fixed` by taking the reviewer's second offered
remedy — correcting the rule files rather than the emitted output — on the
grounds that the generators have emitted `com.egoge.ai.atlas.processor` since the
package rename, that README and `docs/processor-internals.md` already document
that value, and that changing the emitted string would itself be the breaking
change FR-009 exists to prevent. No reviewer round ever evaluated that argument.

Three items the builder explicitly escalated for maintainer attention, none of
which a reviewer round has since confirmed:

1. The round-5 fix edited project rule files, which round 4 had deliberately
   declined to do. If the intent was genuinely to migrate the emitted marker,
   the doc edits should be reverted instead.
2. `AGENTS.md` and `CLAUDE.md` are git-ignored (`.gitignore:35-36`), so those two
   corrections are machine-local and were **not** carried by the fix commit. They
   must be re-applied on other checkouts, or the files un-ignored.
3. GitHub issue `gosha70/ai-atlas#12` L78 still carries the stale value. It is the
   origin of record and will keep re-seeding the error into future specs until
   corrected.

**Warnings dispositioned `fixed` in round 5, never re-reviewed** —
`f-b2f9e1fb` (concurrent runs sharing an output directory interleaving publish
and collection) and `f-4b3310be` (partially published output when the second
tree replacement fails). Both were addressed in `853fbf7` with regression tests
that the builder reports fail against the pre-fix driver.

**Round 3's five findings** carry no resolution record. Their descriptions in
`state.json` are near-identical to round 4's first five, which were dispositioned
`fixed`, so they are most likely the same defects under earlier wording — but
that is an inference from the text, not a recorded disposition.

## Verification state at bypass

From `resolution-round-5.json`, self-reported by the builder and not
independently confirmed by a reviewer round:

- `./gradlew build` — green, exit 0
- 346 tests, 0 skipped, 0 failures, 0 errors
- Driver tests 15 (was 13); both new regression tests executed, neither skipped
- Teeth check: both new tests were run against the pre-fix driver and failed

Commits after the round-5 fix (`7e5a62f`, `e156fd0`, and merge `8814a79`) landed
with no review round covering them at all.

## Loop mechanics worth fixing before the next feature

Two defects in the review harness shaped this outcome and will recur:

1. **`consecutive_fixed` never increments.** The runner reads dispositions with
   `.resolutions[] | select(.finding_id == $id)` (`review-round-runner.sh:821`),
   but `resolution-round-4.json` and `resolution-round-5.json` key each entry as
   `id`, not `finding_id`. Every counter in `state.json` is therefore `0` despite
   ten `fixed` dispositions, so the stale-findings breaker could never fire. The
   `/review-submit` command spec documents the field as `finding_id`; the builder
   wrote `id`. One side must change.
2. **`retry` on a `max_rounds` breaker is a no-op by default.** The runner
   computes `NEXT_ROUND = current_round + 1` and trips when that exceeds
   `MAX_ROUNDS` (default 5), while `/review-decide retry` mandates monotonic round
   numbering and raises no ceiling. Retrying without setting
   `CCT_REVIEW_MAX_ROUNDS` re-trips the breaker before the reviewer is invoked.

Neither is a defect in the feature under review.
