---
feature_id: constraints-and-hints
date: 2026-09-27
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
rounds_completed: 2
attempt_count: 1
bypass: false
---

# Peer Review: constraints-and-hints — Build Phase

**Reviewer**: deepseek
**Scope**: both
**Rounds**: 2
**Verdict**: PASS

## Summary

Round 2 review of the constraints-and-hints documentation. The builder addressed most Round 1 findings, but several remain: the looser-override warning still classifies any pattern replacement as a warning (f-80ed97d5 was marked fixed but the text still says "a replaced pattern set" is a looser case), the `notBlank` unanchored pattern is now explicitly justified (good), and the `@Size` implicit-bound ambiguity is now resolved. However, new issues surface in the migration section and the gate rules table.

## Findings

- [warning] f-b308d5fb: The looser-override warning still lists "a replaced pattern set" as a looser case, but replacing a pattern set is not inherently looser or tighter. The text now adds "even if the override is in fact tighter" for `@AgenticConstraints.pattern`, which clarifies the warning fires on any differing pattern, but the earlier bullet still frames it as "looser". This is internally inconsistent: the warning is described as firing when the override is "looser", yet pattern replacement fires regardless of tightness. (docs/constraints-and-hints.md)
- [warning] f-b9243891: The migration section says a version-1 baseline is migrated with slots "unknown: JSON `null`", and that "a document that declares `irVersion 2` in its file and has a missing or `null` slot is malformed." The distinction is now stated ("That check applies only when a file is read"), which addresses f-cc404801. However, the doc does not state what happens if a version-2 baseline is written by an older ai-atlas that wrote `irVersion 2` but omitted the new slots — the malformed check would reject it, but the doc does not say whether such a file can exist in practice. (docs/contract-governance.md)
- [warning] f-edbb9e56: The table says "An entity field's constraints change" is `informational`, and the prose now explains "Entity fields are always output: an operation's inputs are its parameters... ai-atlas generates no request body". This addresses f-95ee8f3f. But the doc does not state what happens if a future ai-atlas generates a request body, or if an entity is used as an input DTO. The blanket claim is correct for the current design but fragile. (docs/contract-governance.md)
- [note] f-b5aeeb56: The doc now states the negated form is valid with and without `u`, addressing f-3abe3c2c. However, the claim that "with `u`, the escapes `\uD800`–`\uDFFF` in a class denote lone surrogate code points" is only true in ECMAScript 2018+ with the `u` flag; older engines without `u` treat them as code units. The doc says the lookbehind requires ECMAScript 2018+, but does not state that the `u`-mode behavior also requires 2018+. (docs/constraints-and-hints.md)
- [note] f-f11a1ae5: The doc now says "This is not a general equivalence check between the two engines: a pattern outside the subset is not published, even if it would happen to behave the same." This addresses f-740792a1. Good. (docs/constraints-and-hints.md)
- [note] f-7982b481: The doc now states "in both published schemas: in the MCP JSON Schema 2020-12 dialect, and in OpenAPI 3.0.3, whose Schema Object takes them from JSON Schema." This addresses f-65f40e14. Good. (docs/constraints-and-hints.md)
- [note] f-b268dc29: The doc now states "`Requiredness.REQUIRED` is always allowed, including together with `@NotNull`, `@NotBlank` or `@NotEmpty` or on a primitive; since a parameter is required by default, it changes nothing." This addresses f-69a68efc. Good. (docs/constraints-and-hints.md)
- [note] f-d774773e: The table now says "The reverse of each of these, including a pattern removed from the set" is Compatible, addressing f-1a47927c. Good. (docs/contract-governance.md)
- [note] f-cbd6cbf3: The CHANGELOG claim is now backed by the governance doc's explicit statement that the malformed check applies only when a file is read. This addresses f-8d94a321 at the documentation level; the implementer should still verify the code matches. (CHANGELOG.md)
- [note] f-d14d1f3d: The doc now states "So `@Size(min = 3)` records only `minLength 3` and no `maxLength`, and `@Size(max = 10)` records only `maxLength 10` and no `minLength`." This addresses f-ef151f35. Good. (docs/constraints-and-hints.md)
