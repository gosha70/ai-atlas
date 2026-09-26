---
feature_id: contract-ir-gate
date: 2026-09-25
status: final
phase: build
mode: review
subject_provider: claude
peer_provider: deepseek
peer_profile: deepseek
runner_fingerprint: ae8dc147cf1264834bab35d116b6ab96b06618f438763cde3a90621d78036dba
verdict: PASS
blocking_findings_open: 0
target_ref: feature/contract-ir-gate
rounds_completed: 1
attempt_count: 1
bypass: false
---

# Peer Review: contract-ir-gate — Build Phase

**Reviewer**: deepseek
**Scope**: both
**Rounds**: 1
**Verdict**: PASS

## Summary

The change reworks the contract IR pipeline so that the IR is the single source of truth: entities and operations are recorded in the IR, then projected at `apiMajor` via a new `ContractProjection` class that supplies the models consumed by generators, and `operationId` assignment is centralized in the projection so the OpenAPI generator and the contract gate share one derivation. The refactor is largely coherent and well-tested, but there are a few correctness concerns around the projection's type parser, the `reportExcluded` null-safety, and the `operationKey`/`operationIds` contract between the projection and the OpenAPI generator.

## Findings

- [warning] f-dea7e7cd: The fallback splits a dotted name into package/class by scanning for the first uppercase-starting segment, but this misclassifies types whose package segment starts with an uppercase letter (e.g. `Com.example.Foo`) or whose class name starts lowercase (e.g. `a.b.lowercaseClass`). The test `parsesEveryCanonicalTypeForm` includes `lower.pkg.lowercaseClass` and passes only because the round-trip `toString()` happens to reproduce the same string; the resulting `ClassName` is `("lower.pkg", "lowercaseClass")` which is wrong for a class named `lowercaseClass` in package `lower.pkg` only by luck. More importantly, when `classNames` resolver returns null (e.g. `ContractProjection.of(ir, major)` without a resolver, or `parseType` static entry point), any type whose package contains an uppercase segment will be mis-split. Consider requiring the resolver for production paths, or documenting the heuristic's limits and asserting on it. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractProjection.java)
- [warning] f-2fb72d1e: `reportExcluded` calls `entities.get(entity.getQualifiedName().toString()).fields()` directly. If the entity was not recorded in the IR (e.g. it was rejected earlier, or the projection was built from a different IR), this throws NPE. The caller in `AgenticProcessor.processEntity` always passes a `typeElement` that was just added via `contractIr.addEntity`, so in practice it is safe today, but the method is public and its contract does not state the precondition. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractProjection.java)
- [warning] f-6f4dbbd7: The generator now throws `IllegalStateException("No operationId projected for " + entry.operationKey())` if the projection did not assign an id. This is a hard failure inside a generator that previously could not fail this way. The projection's `operationSites()` filters on `op.rest() != null && channels.contains("API") && isActive(...)`, while `collectServiceOperations` filters only on `method.channels().contains("API")`. If the two filters ever diverge (e.g. an operation with API channel but null `rest`, or a lifecycle mismatch), the generator will crash the compilation with an internal error rather than a diagnostic. The two filters should be derived from the same predicate. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/OpenApiGenerator.java)
- [warning] f-f8616c02: `operationKey` builds the key from `method.parameters().stream().map(p -> p.typeName().toString())`. This must match the `Operation#id()` produced by `IrBuilder`, which is built from `typeString(TypeMirror)` = `TypeName.get(type).toString()`. For most types these agree, but for type variables, wildcards, or nested parameterized types the two `toString()` forms can differ (e.g. `TypeVariableName.get("T").toString()` vs the canonical form used in the IR). The test `operationKeyOfAProjectedOperationIsItsIrIdentity` only covers the `lifecycle` fixture, which may not exercise these cases. If they diverge, `OpenApiGenerator` will throw the `IllegalStateException` above. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractProjection.java)
- [warning] f-7fcc5679: The projection assigns ids over `operationSites()` which iterates `ir.operations()` in IR order, but the OpenAPI generator iterates `services` in registry order and methods in declaration order. The `shared` list is sorted by `(path, httpMethod)` for suffix assignment, which matches the old generator behavior, but the "unique method name" branch assigns ids in IR order, not document order. If two operations share a method name and one is unique-by-name in the document but not in the IR (or vice versa), the assignment could differ from what the generator expects. The test `projectedOperationIdsAreThoseOfTheGeneratedOpenApiDocument` compares the two, which is good, but only for four fixtures. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractProjection.java)
- [note] f-290a1b06: This treats `removedInVersion` as exclusive, which is consistent with the doc comment, but the operation lifecycle uses `major <= apiUntil()` (inclusive). The asymmetry is intentional per the doc comments but is easy to misread; a field with `removedInVersion = 2` is inactive at major 2, while an operation with `apiUntil = 2` is active at major 2. Confirm this matches the spec's intent and consider a comment cross-referencing the two. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractProjection.java)
- [note] f-ee300b47: The cast `(ClassName) type` is safe only because the branch is entered when `name.indexOf('.') >= 0  (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractProjection.java)
- [note] f-08d67ce1: The test only asserts `parseType(type).toString().equals(type)`, which is a round-trip check. For `lower.pkg.lowercaseClass` the round-trip passes even though the split is heuristic. Add an explicit assertion on the resulting `ClassName`'s `packageName()` and `simpleName()` to lock in the intended behavior. (modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/ContractProjectionTest.java)
- [note] f-a9b94b20: The doc says "Take each operation's `operationId` from the projection, which assigns them over all its active API operations" — this is accurate but does not mention that the projection's assignment must match the generator's iteration order, which is the subtle invariant the code relies on. (docs/processor-internals.md)
- [note] f-bc578911: The projection stores `ir` and exposes it via `ir()`, but the projection is meant to be the filtered view. Exposing the full IR from the projection invites callers to bypass the projection and read unfiltered data. Consider removing `ir()` or documenting that it is the unfiltered source. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractProjection.java)
