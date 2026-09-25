---
feature_id: contract-quality-foundations
date: 2026-09-24
status: final
phase: build
mode: review
subject_provider: claude
peer_provider: deepseek
peer_profile: deepseek
runner_fingerprint: ae8dc147cf1264834bab35d116b6ab96b06618f438763cde3a90621d78036dba
verdict: PASS
blocking_findings_open: 0
target_ref: feature/contract-quality-foundations
rounds_completed: 1
attempt_count: 1
bypass: false
---

# Peer Review: contract-quality-foundations — Build Phase

**Reviewer**: deepseek
**Scope**: both
**Rounds**: 1
**Verdict**: PASS

## Summary

The change adds a `ToolNameRegistry` that collects effective MCP tool names across the whole compilation and reports collisions as compile errors, wired into `AgenticProcessor` after method processing. The implementation is small, focused, and well-tested for the primary scenarios (cross-service, overloads, three-way, explicit `toolName` resolution, API-only exclusion, version-inactive exclusion). A few correctness concerns remain around the `reported` set semantics, the `record` filter's reliance on `model.channels()`/`VersionSelector`, and the `OpenApiGeneratorTest` change that silently narrows test coverage.

## Findings

- [warning] f-879fc260: The `reported` set is keyed by `ExecutableElement` identity and is only consulted inside `reportCollisions`. Since `reportCollisions` is called once per processing round (from `AgenticProcessor`), if the processor runs multiple rounds (e.g., due to generated sources triggering another round), `record` will append duplicate `Site` entries for the same element, and `reported` will suppress re-reporting — but the `others` list will then include the same declaration twice (e.g., "X is also used by X, X"). This produces confusing diagnostics on multi-round compilations. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/ToolNameRegistry.java)
- [warning] f-4ce0506f: The filter uses a raw string literal `"AI"` to test channel membership. If `MethodModel.channels()` returns an enum-backed set or a differently-cased representation, this silently excludes all methods and the collision check becomes a no-op. The test suite would not catch this because the tests exercise the happy path where the string matches. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/ToolNameRegistry.java)
- [warning] f-bb719c6a: The registry relies on `VersionSelector.isActive` matching the exact predicate used by `McpToolGenerator` when deciding whether to emit a tool. If the generator uses a different activity predicate (e.g., also considering deprecation or channel-specific versioning), the registry can either miss real collisions or flag non-collisions. This coupling is implicit and untested for divergence. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/ToolNameRegistry.java)
- [warning] f-36b321f9: The test fixtures were changed from default channels to `channels = { AgenticExposed.Channel.API }`. This is a behavior-narrowing change to an existing test: it now no longer exercises the default-channel path for OpenAPI generation. If the intent was to avoid triggering the new collision check (both services have `findById`), the change masks a real interaction between OpenAPI generation and AI-channel methods rather than testing it. (modules/processor/src/test/java/com/egoge/ai/atlas/processor/OpenApiGeneratorTest.java)
- [note] f-2b09c167: The `reported` set is redundant with the per-entry iteration: each `Site` appears in exactly one `tools` entry (keyed by its tool name), so a given element can only be visited once per `reportCollisions` call. The set only matters across multiple calls, which the current wiring does not do. This is dead complexity unless multi-round behavior is intended. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/ToolNameRegistry.java)
- [note] f-3ec4ff55: `others` is built by iterating `sites` and skipping `other != site` (reference inequality). Since `Site` is a record, two distinct `Site` instances with equal fields would both be included, and the same element recorded twice would appear twice. Reference equality is correct only if each element is recorded at most once. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/ToolNameRegistry.java)
- [note] f-f32908e7: The changelog states "Collisions between services compiled in separate modules are not detected." This is accurate but worth cross-referencing in the spec/plan as a known limitation, since it is a real correctness gap for multi-module users. (CHANGELOG.md)
