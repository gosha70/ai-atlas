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

The change replaces the SYNC MCP registration path with a lazy `List<SyncToolSpecification>` bean that merges generated constraint keywords and hints into Spring AI's derived input schema, keeps the old `ToolCallbackProvider` for ASYNC/STATELESS, and adds a Gradle `constraints` option. The merge logic is careful about preserving derived keywords and warns on mismatches, and the test coverage is unusually thorough. A few correctness concerns remain around the `required` handling, the `allOf` keyword mapping, and the `SyncServerCondition` default.

## Findings

- [warning] f-07f2703d: The merge unconditionally removes a property from `required` when the generated schema does not list it, even if the derived schema marked it required (e.g. a non-nullable primitive or a `@ToolParam(required=true)`). This can silently weaken the served schema's requiredness relative to what Spring AI derived. (modules/runtime/src/main/java/com/egoge/ai/atlas/runtime/mcp/AgenticMcpConfiguration.java)
- [warning] f-ae2e41e5: `allOf` is mapped to `STRING_TYPES` only, but `allOf` is a general JSON Schema combinator that applies to any type (objects, arrays, numbers). A generated `allOf` on a non-string property will be dropped with a misleading warning. (modules/runtime/src/main/java/com/egoge/ai/atlas/runtime/mcp/AgenticMcpConfiguration.java)
- [warning] f-dab754e4: `SyncServerCondition` defaults `spring.ai.mcp.server.type` to `SYNC` and treats a missing protocol as non-STATELESS, so the new specification path is active by default. If Spring AI's own default differs (or changes), the two paths can disagree and tools may be registered twice or not at all. (modules/runtime/src/main/java/com/egoge/ai/atlas/runtime/mcp/AgenticMcpConfiguration.java)
- [warning] f-2fef8d5c: `tool.path(K_NAME).asText()` yields an empty string when `name` is missing, and two malformed entries would then collide on `""` with a confusing duplicate-name error. A missing `name` should be a distinct, clear failure. (modules/runtime/src/main/java/com/egoge/ai/atlas/runtime/mcp/AgenticMcpConfiguration.java)
- [warning] f-3a6ec535: `(ObjectNode) JSON.readTree(derivedSchema)` will throw `ClassCastException` (not the intended `UncheckedIOException`) if the derived schema is a non-object JSON value, and `derived.path(K_PROPERTIES)` silently yields a missing node if `properties` is absent, so constraints are dropped without a warning. (modules/runtime/src/main/java/com/egoge/ai/atlas/runtime/mcp/AgenticMcpConfiguration.java)
- [note] f-9028af5b: `new McpSchema.ToolAnnotations(null, ...)` passes `null` for the title; if the SDK's constructor or serialization does not tolerate a null title, this can NPE or emit `"title": null`. (modules/runtime/src/main/java/com/egoge/ai/atlas/runtime/mcp/AgenticMcpConfiguration.java)
- [note] f-b07be6d9: `specifications` is `volatile` and the double-checked locking is correct, but `resolveSpecifications` calls `readToolSpecifications` which can throw; a thrown exception leaves `specifications` null and every subsequent `get`/`size` retries the scan, which is fine but means a transient failure is not cached. Acceptable, but worth a comment. (modules/runtime/src/main/java/com/egoge/ai/atlas/runtime/mcp/AgenticMcpConfiguration.java)
- [note] f-69a305ec: The test asserts the failure message contains both resource paths, but the `duplicate` fixture lists `find_orders` with an empty `properties`/`required`, so it does not exercise the case where the duplicate entry itself is well-formed and would otherwise merge cleanly. The failure path is covered, but the "both resources are named" assertion depends on `getDescription()` formatting that may vary by loader. (modules/runtime/src/test/java/com/egoge/ai/atlas/runtime/mcp/McpToolSpecificationTest.java)
- [note] f-77dfc2b4: `asArguments` now always emits `-Aai.atlas.constraints=...`, including `false`. This is consistent with the existing baseline/locked options, but it means the processor option is present on every main compile even when the feature is off, which slightly widens the surface for the processor to mis-handle. (modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/ContractArguments.java)
- [note] f-b084d354: The class javadoc describes the SYNC/ASYNC split well but does not mention the duplicate-name startup failure or the advisory warning, both of which are user-visible behaviors. (modules/runtime/src/main/java/com/egoge/ai/atlas/runtime/mcp/AgenticMcpConfiguration.java)
