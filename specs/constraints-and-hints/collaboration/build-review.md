---
feature_id: constraints-and-hints
date: 2026-09-26
status: final
phase: build
mode: review
subject_provider: claude
peer_provider: deepseek
peer_profile: deepseek
runner_fingerprint: ae8dc147cf1264834bab35d116b6ab96b06618f438763cde3a90621d78036dba
verdict: PASS
blocking_findings_open: 0
target_ref: feature/constraints-and-hints
rounds_completed: 1
attempt_count: 1
bypass: false
---

# Peer Review: constraints-and-hints — Build Phase

**Reviewer**: deepseek
**Scope**: both
**Rounds**: 1
**Verdict**: PASS

## Summary

The change adds constraint/requiredness comparison to the contract gate, introducing a new `INFORMATIONAL` classification for output constraints and hints, and masking unknown baseline slots for lock mode. The core narrowing/widening logic is well-structured and the test coverage is thorough. I found one correctness concern around the `knownIn` masking semantics and a few smaller issues.

## Findings

- [warning] f-8c4f4976: When `old.parameters().size() != now.parameters().size()`, the method returns `now` unchanged, so a v1 baseline with a different parameter count would not mask unknown slots and could spuriously fail lock mode. The size mismatch is presumably already handled elsewhere, but the early return silently disables masking rather than failing safe. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ConstraintComparison.java)
- [warning] f-68138066: The masking only nulls `constraints` when `old.constraints() == null`, but does not mask `required` (which is a parameter-level concept, so N/A for fields) nor any other slot. If a v1 baseline omits other field-level slots that v2 populates, lock mode will still flag them. Confirm the v1 schema only omits `constraints` at field level. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ConstraintComparison.java)
- [warning] f-e199f69d: The `tighter` expression `e0 == null  (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ConstraintComparison.java)
- [warning] f-a833d5e4: `!c0.patterns().containsAll(c1.patterns())` classifies as narrowing when the new pattern set is not a superset of the old. But pattern semantics are conjunctive (all must match), so adding a pattern narrows and removing widens. `containsAll` on the new set relative to old is the right direction, but `PatternConstraint` equality must include flags — verify `PatternConstraint` implements `equals` over both `regex` and `flags`, otherwise a flag-only change would be missed. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ConstraintComparison.java)
- [note] f-56a17cf2: `old.required() != null && now.required() != null` skips comparison when either side is null. For a v1 baseline this is intentional (unknown slot). But for a v2 baseline where `required` is genuinely absent (not unknown), this silently skips a real difference. Confirm the IR always populates `required` for v2 parameters. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ConstraintComparison.java)
- [note] f-12095d28: `render(Map)` uses `Map.toString()`, which is order-dependent for non-`LinkedHashMap` implementations. If the IR's constraint/hint maps are `HashMap`, two semantically equal maps could render differently and produce spurious informational diffs. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractComparison.java)
- [note] f-edd4bd9c: `target.substring(close + 2)` assumes exactly one character (`.`) between `)` and the parameter name. If the signature rendering ever changes (e.g., whitespace), this silently produces a wrong name and returns null. Also `lastIndexOf(')')` could match a `)` inside a generic type argument. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractGate.java)
- [note] f-8334bfee: The test strips `constraints`, `required`, and `hints` recursively, but does not verify that a v1 baseline with a *widening* change also passes (only narrowing is tested). A widening change on a v1 baseline should also pass since the slot is unknown. (modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/ConstraintGateTest.java)
- [note] f-8a91ddc4: The javadoc says "a slot unknown on either side ... is skipped", but the implementation only skips when the *baseline* slot is unknown (`old.constraints() == null`). If the fresh side is null but baseline is not, the comparison proceeds and may report a difference. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ConstraintComparison.java)
