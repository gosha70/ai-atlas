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
rounds_completed: 2
attempt_count: 1
bypass: false
---

# Peer Review: contract-ir-gate — Build Phase

**Reviewer**: deepseek
**Scope**: both
**Rounds**: 2
**Verdict**: PASS

## Summary

Round 2 addresses the Round 1 findings substantively: the argument-provider copy, multi-classes-dir ambiguity, constant-pool parsing, `Files.isRegularFile` consistency, unresolved-type recursion, and lock-mode plumbing are all fixed with tests. The remaining concerns are mostly about the new `ContractCheck` driver path (which duplicates the empty-contract logic outside the processor), the `documentDifferences` lock-mode semantics, and a few edge cases in the new constant-pool reader and `resolved` recursion.

## Findings

- [warning] f-c92b231d: `ContractCheck.run` re-implements the processor's defaults (`DEFAULT_API_MAJOR = "1"`, `DEFAULT_API_BASE_PATH = "/api"`) as string literals. If the processor's defaults ever change (or are configured via a different mechanism), the CLI/MCP empty-contract check will silently disagree with the in-compile gate. The processor's defaults are not exposed as constants, so this is a latent divergence. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/driver/ContractCheck.java)
- [warning] f-663e91a8: The path-based overload uses `Files.isRegularFile(classOutput.resolve(RESOURCE_PATH))`, which is correct, but the `Set<String>` overload used by the in-memory path checks `classOutput.contains(RESOURCE_PATH)` — a set membership test that cannot distinguish a directory entry from a file. In-memory output is always files, so this is fine today, but the two overloads now have subtly different semantics for the same conceptual check. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/driver/ContractCheck.java)
- [warning] f-894bcebd: `documentDifferences` reports `DOCUMENT_PATH` when `apiMajor` differs. A legitimate major bump (the documented way to introduce breaking changes) therefore fails lock mode with a `document` element path that gives the user no hint that the major bump is the cause. The Round 1 builder disputed this as intended by FR-014, but the message still does not distinguish "you bumped the major" from "you changed the base path", which is exactly the actionable information a user needs. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractGate.java)
- [warning] f-b7e74ce5: The reader trusts `in.readUTF()` to consume exactly the Utf8 payload. `DataInputStream.readUTF` reads a 2-byte length then that many bytes — correct for `CONSTANT_Utf8` — but it also validates the modified-UTF-8 encoding and will throw `UTFDataFormatException` on a class file whose Utf8 entry contains bytes that are not valid modified UTF-8 (legal per JVMS 4.4.7 for some producers). That would surface as an `UncheckedIOException` and fail the build rather than treating the class as non-declaring. (modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/ContractDeclarations.java)
- [warning] f-b1dd191b: The map omits tag 2 (`CONSTANT_Integer` is tag 3, but tag 2 is unused/reserved in modern class files). More importantly, tag 14 (`CONSTANT_MethodHandle` is 15, `MethodType` is 16, `Dynamic` is 17, `InvokeDynamic` is 18, `Module` is 19, `Package` is 20) — the map covers 3,4,5,6,7,8,9,10,11,12,15,16,17,18,19,20 but not 13 (`CONSTANT_String` is 8; 13 is unused). If a future JVMS adds a tag, the reader throws `unknown constant pool tag` and fails the build. That is arguably correct (fail loud), but the error message does not name the class file's producer. (modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/ContractDeclarations.java)
- [warning] f-43e8e69d: `unresolved` handles `ERROR`, `DECLARED`, `ARRAY`, `WILDCARD` but not `TYPEVAR`. A type variable whose bound is an unresolved type (e.g. `<T extends Missing>`) will not be detected, and `TypeName.get(type).toString()` for a type variable is just `T`, which `parseType` accepts. The declared `TypeMirror` for a field of type `T` is a `TypeVariable`, so the recursion stops there. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/IrBuilder.java)
- [warning] f-78d41bbe: `signatureTypes` includes the return type and parameter types, but not the *type parameters* of the method itself (e.g. `<T extends Missing> T find()`). A method whose type parameter bound is unresolved will pass the check and later fail in the projection. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/IrBuilder.java)
- [warning] f-8b52039d: The Round 1 note about the fragile ~10-property copy was deferred. The Round 2 fix added `compilerArgumentProviders` but the copy still omits `options.getForkOptions()`, `options.getAnnotationProcessorGeneratedSourcesDirectory()`, `options.getDebugOptions()`, `options.getIncremental()` (set explicitly to false), and `options.getCompilerArgs()` filtering only by prefix. Any future `JavaCompile` option added by another plugin will silently diverge. (modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticPlugin.java)
- [warning] f-6521a580: The test asserts the accept compile receives `-Aother.option=1` and not the contract options, but does not assert that the accept compile *succeeds* in producing the IR (i.e. that the accept compile actually ran the processor and emitted `api.ir.json`). A regression where the accept compile silently produces no IR would still pass this test. (modules/gradle-plugin/src/functionalTest/java/com/egoge/ai/atlas/plugin/AgenticPluginFunctionalTest.java)
- [warning] f-d8570b8a: The new try/catch around `compare` returns `notCompared` with an "Internal error" message. But `compare` can also throw `IllegalArgumentException` for a *legitimate* baseline problem that `ContractProjection.of(baseline, published)` did not catch (e.g. a baseline whose operations reference entities not in the baseline). The message blames the compilation's IR, which may be wrong. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractGate.java)
- [note] f-0952f23f: The javadoc says the task "runs after" `compileJava` and is "a dependency of `classes`", but does not mention that it is *not* a dependency of `compileJava` itself — so `./gradlew compileJava` alone will not run the empty-contract check. The functional test `compileJavaAloneDoesNotRunTheEmptyContractCheckButClassesDoes` documents this behavior, but the javadoc does not. (modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AtlasContractCheck.java)
- [note] f-58dd39e7: The javadoc says the check runs "whenever a baseline or lock option is passed", but the `run` method also requires `success` to be true (via the `after` overloads). A failed compilation with a baseline option will not run the empty-contract check, which is correct but not stated. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/driver/ContractCheck.java)
- [note] f-01a39785: The test asserts `at 2 element(s): document, field shop.Order#legacy` and the Round 1 builder added a comment naming the major bump as the document-level difference. The test still does not assert *why* `document` differs, so a future change to `documentDifferences` that reports `document` for a different reason would not be caught. (modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/ContractLockTest.java)
- [note] f-7fe8c923: The loop strips trailing slashes but stops at length 1, so `"/"` stays `"/"` and `""` stays `""`. The processor's own normalization is not shown here; if the processor strips differently (e.g. also collapses `//`), the empty document can differ from the processor's document for the same configured base path. (modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/EmptyContract.java)
- [note] f-784c5617: The Round 1 note asked to confirm `@Internal` is intended. The builder confirmed it is (the task declares no outputs, so it is never up-to-date). The javadoc on `getBaseline` explains this. No change needed; noting that the `@DisableCachingByDefault` annotation is now redundant with `@Internal` for up-to-date purposes but still correct for caching. (modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AtlasAccept.java)
