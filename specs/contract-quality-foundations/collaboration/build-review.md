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

The change correctly aligns the generated OpenAPI document with the generated REST controllers: parameters become query parameters, response content matches return types, shared paths are merged, and ambiguous mappings become compile errors. The implementation is well-tested with golden tests covering the main scenarios. I found a few correctness concerns around `isScalar` semantics, `void` handling in MCP tools, and the `RestMappingRegistry`'s interaction with the `reported` set.

## Findings

- [warning] f-e0f26761: `isScalar` is defined as "not string" via `mapJavaTypeToSchema(type.toString()).getType()`, which is fragile: any unmapped type (e.g. `Object`, `Map`, custom types) that falls through to a default schema will be treated as scalar. This can misclassify non-scalar returns as scalars and emit a scalar schema instead of the intended `object` fallback. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/OpenApiGenerator.java)
- [warning] f-c7a35791: The `void` branch emits `service.$L($L)` but the surrounding method signature must also be `void`. If the method builder still declares a return type (e.g. from `method.returnType()`), the generated code will not compile. Verify the method return type is set to `void` when `returnType().equals(TypeName.VOID)`. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/McpToolGenerator.java)
- [warning] f-2916ced9: The `reported` set is keyed by `ExecutableElement` identity and is never cleared. If the processor runs across multiple rounds (incremental compilation), a method reported in round 1 will be silently skipped in round 2, potentially hiding a duplicate introduced by a later-discovered service. Also, `reportDuplicates` is called once at the end of `process`, so cross-round duplicates may be missed entirely. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/RestMappingRegistry.java)
- [warning] f-f611dc40: `record()` filters by `model.channels().contains("API")` and `VersionSelector.isActive`, but the OpenAPI generator's `collectServiceOperations` uses a different filter (`method.channels().contains("API")` plus its own version check). If these two filters ever diverge, the registry may report a duplicate for a mapping that isn't actually generated, or miss one that is. The two code paths should share the same predicate. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/RestMappingRegistry.java)
- [warning] f-a4ad223a: The `taken` set is populated with unique method names first, then shared ones are assigned. However, if a unique method name happens to equal a candidate qualified id (e.g. a method literally named `OrderService_find_get`), the shared assignment will append `_2` — which is correct — but the unique name itself is not checked against later-assigned qualified ids. Since unique names are added to `taken` before shared assignment, this is actually safe. Still, the ordering dependency is subtle and undocumented. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/OpenApiGenerator.java)
- [note] f-78c8a532: For `String` returns, the code emits `text/plain` with schema `type: string`. But the generated controller returns `String` directly, and Spring's default `StringHttpMessageConverter` will produce `text/plain` — consistent. However, if the service returns `String` that is actually JSON, callers may be surprised. This is a design choice, not a bug, but worth documenting. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/OpenApiGenerator.java)
- [note] f-b81847b5: `elementType` handles `ARRAY` and single-type-argument `ParameterizedTypeName`, but for `ReturnKind.COLLECTION`/`ITERABLE` with a raw type (no type arguments), it returns null and falls through to `jsonContent(new Schema<>().type("object"))`. This is a reasonable fallback but may not match the controller's actual return shape. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/OpenApiGenerator.java)
- [note] f-175ba6af: The test asserts `OrderService_find_get_2` for the shared GET, but does not verify that the unique `OrderService_find_get` from `LegacyService` is not accidentally reassigned. The assertion on `operationIds` not having duplicates covers this indirectly, but an explicit assertion would be clearer. (modules/processor/src/test/java/com/egoge/ai/atlas/processor/RestOpenApiConsistencyTest.java)
- [note] f-32e29977: The doc says "anything else as a JSON object" for response content, but the code's `isScalar` negation means some non-scalar types may be misclassified. Once `isScalar` is fixed, update the doc to enumerate the exact fallback cases. (docs/processor-internals.md)
- [note] f-52b5995b: The registry reports duplicates as compile errors, which is a good fail-fast behavior. However, the error message suggests "rename the method or remove it from the API channel" — but for same-named services in different packages, renaming the method may not be sufficient if the service name is also shared. Consider mentioning that the service class name (kebab-cased) also contributes to the path. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/RestMappingRegistry.java)
