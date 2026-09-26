# Tasks: Contract IR and compatibility gate (epic #23, Phase 2)

<!-- One owner per file. [P] = parallelizable within the story group. [US#] traces to spec.md. -->
<!-- Each `## US<n>:` group is one auto-build phase (fresh session). -->
<!-- Spec US1 was built by the first run and merged in PR #32; spec US2 and US3 by the second run,
     merged in PR #34. The remaining groups are numbered US1..US2 so the auto-build driver runs them
     as phases 1..2; each heading names the spec user story it implements. Task numbers and FR
     references are unchanged. -->
<!-- Build sessions: tick a task's Done box in the same commit that completes it. -->

## Done (PR #32) — spec US1: Every build writes the contract IR

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 1 | | FIRST, before touching any generator: capture the golden snapshot. Record every generated source and resource (excluding `META-INF/ai-atlas/`) for the demo sources at the demo's options and for a fixture set covering inactive fields and methods, deprecation, enums, collections, nested entities, type hints, `void`, overloads, two services each exposing `find()`, a compilation with no ai-atlas annotation (expected: no generated file) and the #25 `RestOpenApiConsistencyTest` fixtures. Add a comparison test (file set and bytes) (FR-007) | `modules/processor/src/test/resources/golden/ir-rewire/**`, `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/IrRewireGoldenTest.java` | Annotation Processor Engineer | [x] |
| 2 | | IR records: document (`irVersion`, `apiBasePath`, `apiMajor`), entity, field (including type hint, referenced entity and DTO), operation, parameter, return (including the effective `returnType` after method-then-class resolution, referenced entity and DTO), lifecycle (FR-002, FR-005) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/*.java` | Annotation Processor Engineer | [x] |
| 3 | | `IrBuilder`: build the IR from elements without version filtering, reusing `FieldScanner`'s hierarchy walk and validation; resolve class-level inheritance for methods (FR-001) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/IrBuilder.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/FieldScanner.java` | Annotation Processor Engineer | [x] |
| 4 | | Canonical JSON writer and reader with fixed ordering and formatting, `irVersion` checks, malformed-file errors (FR-003, FR-004) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/IrJson.java` | Annotation Processor Engineer | [x] |
| 5 | | Collect the IR across rounds in `AgenticProcessor` and write `META-INF/ai-atlas/api.ir.json` in the round that writes the OpenAPI document (FR-003) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [x] |
| 6 | | Tests: IR completeness including inactive elements; a field's type hint and a return's method-level and class-level `returnType` are recorded; byte-identical across two compilations; `irVersion` above supported is an error; malformed baseline is an error; no absolute paths or timestamps (FR-001..005) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/ContractIrTest.java` | Annotation Processor Engineer | [x] |

**Checkpoint US1** — verify before continuing:
- [x] `./gradlew :modules:processor:test --tests '*ContractIrTest' --tests '*IrRewireGoldenTest'` green
- [x] No file under `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/` changed in this phase
- [x] `./gradlew build` green

---

## Done (PR #34) — spec US2: Generated code comes from the IR, unchanged

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 7 | | `ContractProjection`: IR + major → `EntityModel`/`FieldModel`/`ServiceModel` and the entity registry, active elements only, deprecation resolved, `TypeName` parsed from canonical type strings; assigns every active operation's `operationId` with the rule moved unchanged from `OpenApiGenerator.assignOperationIds` (FR-006) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractProjection.java` | Annotation Processor Engineer | [x] |
| 8 | | Feed every generator from the projection; remove the `apiMajor` filter from `FieldScanner`; emit the "excluded … not active" note and the empty-entity warning and error from the projection, with identical text and kind; `OpenApiGenerator` uses the projection's operation IDs (FR-006) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/FieldScanner.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/*.java` | Annotation Processor Engineer | [x] |
| 9 | | Tests: the projection round-trips every canonical type form used by the fixtures; its operation IDs equal those in the generated OpenAPI document for the shared-`find()` fixture; the golden comparison stays green (FR-006, FR-007) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/ContractProjectionTest.java` | Annotation Processor Engineer | [x] |
| 10 | | Harden the IR from the phase-1 review (PR #32), before the gate relies on it:<br>- `IrJson.parse` rejects trailing content after the document and duplicate keys as malformed (FR-004);<br>- canonical type strings exclude TYPE_USE annotations, so adding `@Nullable` does not change them (FR-005);<br>- record an operation in the IR only when its method model is valid, matching how invalid fields are left out (FR-001);<br>- collect declarations from every round and write `api.ir.json` in the final round, so entities and services generated by another processor in a later round are included (FR-001, FR-003);<br>- remove or document `FieldScanner.scan` now that it has no production caller, and update `docs/processor-internals.md`.<br>Tests for each in `ContractIrTest` | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/IrJson.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/IrBuilder.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/FieldScanner.java`, `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/ContractIrTest.java`, `docs/processor-internals.md` | Annotation Processor Engineer | [x] |

**Checkpoint US2** — verify before continuing:
- [x] `./gradlew :modules:processor:test` green, including `IrRewireGoldenTest` with the snapshot unchanged and the task 10 hardening tests
- [x] `./gradlew build` green

---

## Done (PR #34) — spec US3: A silent breaking change fails the build

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 11 | | `@AgenticField(openEnum = false)`, recorded in the IR; WARNING when set on a field that is neither an enum nor has `allowedValues` (FR-011) | `modules/annotations/src/main/java/com/egoge/ai/atlas/annotations/AgenticField.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/IrBuilder.java` | Annotation Processor Engineer | [x] |
| 12 | | `ContractGate`: read the baseline named by `ai.atlas.contract.baseline`; project both at M; classify output and input differences per FR-009 and FR-010, comparing effective field and return schemas, entity DTO names and packages, `apiBasePath`, and each shared operation's `operationId`, REST path and method and MCP tool name; NOTE when there is no baseline (check the file exists before parsing, so a missing file never reaches `IrJson`'s malformed-file error); ERROR when the configured major is below M (FR-008..010) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractGate.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [x] |
| 13 | | `EmptyContract` (ADR-8): public check that compares an empty fresh IR (configured `apiBasePath`, `apiMajor`) with the baseline through `ContractGate`'s comparison, returning its diagnostics; and the empty IR document in canonical form. `AgenticProcessor`'s supported annotation types stay `AgenticEntity` and `AgenticExposed` (no `*`) (FR-008) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/EmptyContract.java` | Annotation Processor Engineer | [x] |
| 14 | | Diagnostics with element path, before → after, direction, reason, legitimising declaration and `atlasAccept`; on the element when present (FR-012); write `META-INF/ai-atlas/contract-diff.json` (FR-013) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractGate.java` | Annotation Processor Engineer | [x] |
| 15 | | Tests, each against a baseline produced by compiling a fixture:<br>- field deleted fails;<br>- `removedInVersion = M+1` passes;<br>- deleting a field inactive at M passes;<br>- field renamed (Java name or display name) fails;<br>- field type change fails;<br>- field type-hint change alone (`List<Object>` field, hint `Order` → `Customer`, both declared) fails;<br>- `List<?>` method changing method-level `returnType` `Order` → `Customer` (both declared) fails;<br>- the same change through a class-level `returnType` fails;<br>- entity DTO name change fails;<br>- `apiBasePath` change fails;<br>- adding an API-exposed `find()` in a second service fails, naming the existing operation, its `operationId` before → after, and the added operation;<br>- the same addition with `apiSince = M+1` passes;<br>- `EmptyContract` against a baseline with elements active at M fails, reporting each as removed in FR-012 format;<br>- `EmptyContract` against a baseline with nothing active at M passes;<br>- `EmptyContract`'s empty document is byte-identical across calls and parses as valid IR;<br>- `AgenticProcessor.getSupportedAnnotationTypes()` is exactly the two ai-atlas annotations;<br>- no baseline plus no ai-atlas annotation produces no generated file and no diagnostic;<br>- `sensitive` false→true fails;<br>- field added passes;<br>- closed response enum gains a value fails;<br>- open response enum gains a value passes;<br>- output enum value removed passes;<br>- operation removed fails;<br>- parameter added or type changed fails;<br>- parameter renamed fails;<br>- channel removed fails;<br>- tool name changed fails;<br>- input enum value removed fails;<br>- input enum value added passes;<br>- operation added passes;<br>- description change passes silently;<br>- configured major below M errors;<br>- no baseline notes;<br>- `contract-diff.json` content and ordering;<br>- `openEnum` warning (FR-008..013) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/ContractGateTest.java` | Annotation Processor Engineer | [x] |

**Checkpoint US3** — verify before continuing:
- [x] `./gradlew :modules:processor:test --tests '*ContractGateTest'` green
- [x] `./gradlew build` green (the demo has no baseline yet, so it only notes)

---

## US1: Changes are accepted explicitly; lock mode governs every change (spec US4)

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 16 | | Follow-ups from the PR #34 review (Annotation Processor Engineer; do these first in this phase):<br>- golden snapshot: add a fixture where another processor generates an `@AgenticExposed` service in a later round, captured from the current code; assert the service is in the OpenAPI document and the deprecation manifest (FR-007, ADR-9);<br>- a declaration whose type cannot be resolved (javac renders it as `<any>`, for example a type generated by another processor that is not yet available) MUST produce a compile ERROR on the element, never an uncaught exception from `ContractProjection` (FR-006);<br>- the `operationId` remedy names only the operations that cause the rename: those added, or newly exposed through the API channel, whose method name collides with the renamed operation. Unrelated additions are not named, and an existing operation that gains the API channel is named (FR-012);<br>- a baseline path that is not a regular file (for example a directory) is treated as missing and gives the NOTE; an internal failure comparing the fresh IR is not blamed on the baseline (FR-008);<br>- `ContractGateTest` cases: entity DTO package change fails; `includeTypeInfo` change fails; `sensitive` true→false passes silently; the remedy naming above; the unresolved-type ERROR | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/*.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java`, `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/*.java`, `modules/processor/src/test/resources/golden/ir-rewire/**` | Annotation Processor Engineer | [ ] |
| 17 | | Lock mode: `ai.atlas.contract.locked` validated; any whole-document difference is an ERROR listing element paths; a missing baseline is an ERROR; `EmptyContract` applies the same lock rules (FR-014) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractGate.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/EmptyContract.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [ ] |
| 18 | | Tests: locked with a description change fails; locked with an identical baseline passes; locked with no baseline fails; bad option value errors; in lock mode `EmptyContract` fails against any non-empty baseline document and against a missing baseline, and passes against an identical empty one (FR-014) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/ContractLockTest.java` | Annotation Processor Engineer | [ ] |
| 19 | | Plugin: `contractBaseline` (default `<projectDir>/.atlas/api.ir.json`) and `contractLocked`; pass both options; declare the baseline as an optional compile input; register `atlasContractCheck` after `compileJava` (a dependency of `classes`), which runs `EmptyContract` through the Worker API with class-loader isolation on the `annotationProcessor` classpath when a baseline or lock option is set and the compile output has no fresh `META-INF/ai-atlas/api.ir.json` (a stale one from an earlier build must not count); the task is never up-to-date or cached as a success while the contract is empty; register `atlasAccept`, which writes the fresh IR (or `EmptyContract`'s document when nothing is declared) even when the gate fails and prints the accepted differences grouped by classification (FR-015, FR-016) | `modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticExtension.java`, `modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticPlugin.java` | Team Lead / Framework Architect | [ ] |
| 20 | | Functional tests:<br>- no baseline → build passes with the note;<br>- `atlasAccept` creates a baseline byte-identical to the emitted IR;<br>- deleting a field fails the build;<br>- `atlasAccept` then makes it pass;<br>- lock mode with a description change fails until `atlasAccept`;<br>- after a successful build of a fixture with a baseline, removing every ai-atlas annotation while keeping its ordinary Java sources, then building again without `clean`, fails with the removals (no stale IR hides it); `atlasAccept` then writes the empty document and the build passes. Mandatory. Assert that the failed build ran `compileJava` incrementally (no `clean`), and that a further unchanged `build` fails again;<br>- `compileJava` alone with an empty contract does not run `atlasContractCheck`, while `classes` does;<br>- the fixture's ordinary sources carry `@Override`, `@SuppressWarnings` and a custom `@Retention(SOURCE)` annotation; after a successful build, editing one of them compiles incrementally: `--info` output has no "Full recompilation is required" line;<br>- editing the baseline re-runs compilation;<br>- `compileTestJava` receives neither contract option (FR-015, FR-016) | `modules/gradle-plugin/src/functionalTest/java/com/egoge/ai/atlas/plugin/AgenticPluginFunctionalTest.java` | Team Lead / Framework Architect | [ ] |
| 21 | [P] | CLI and MCP tests: a baseline passed through `-A` / `options` that the sources break yields exit 1 with `status: error` (CLI) and an `isError` result (MCP), carrying the gate diagnostic; sources with no ai-atlas annotation plus a baseline with active elements fail the same way through `EmptyContract` (FR-017) | `modules/cli/src/main/java/com/egoge/ai/atlas/cli/**`, `modules/mcp-stdio/src/main/java/com/egoge/ai/atlas/mcp/**`, `modules/cli/src/test/java/com/egoge/ai/atlas/cli/ContractGatePassThroughTest.java`, `modules/mcp-stdio/src/test/java/com/egoge/ai/atlas/mcp/ContractGatePassThroughTest.java` | Team Lead / Framework Architect | [ ] |

**Checkpoint US4** — verify before continuing:
- [ ] `./gradlew :modules:processor:test` green, including task 16's cases and the later-round golden fixture
- [ ] `./gradlew :modules:processor:test --tests '*ContractLockTest'` green
- [ ] `./gradlew :modules:gradle-plugin:functionalTest` green, including the mandatory remove-the-last-annotation-without-`clean` case and its unchanged re-run
- [ ] `./gradlew :modules:cli:test :modules:mcp-stdio:test --tests '*ContractGatePassThroughTest'` green
- [ ] `./gradlew build` green

---

## US2: The demo is governed and the rules are documented (spec US5)

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 22 | | Commit the demo baseline produced by the processor from the demo sources and options; pass `ai.atlas.contract.baseline` in the demo build (FR-018) | `demo/.atlas/api.ir.json`, `demo/build.gradle.kts` | Team Lead / Framework Architect | [ ] |
| 23 | | Demo test: the committed baseline is byte-identical to the IR the demo build emits (FR-018) | `demo/src/test/java/com/egoge/ai/atlas/demo/ContractBaselineTest.java` | Team Lead / Framework Architect | [ ] |
| 24 | [P] | `docs/contract-governance.md`: IR document and `irVersion`, rule tables per direction, projection at the published major, `openEnum`, accept, lock mode, baseline in version control, the migration policy; the empty-contract check and that it runs through `classes` and its consumers, not `compileJava` alone; must name `irVersion`, `apiMajor`, `openEnum`, `atlasAccept`, `atlasContractCheck`, `ai.atlas.contract.baseline` and `ai.atlas.contract.locked` (checked by `scripts/check-contract-docs.sh`) (FR-019) | `docs/contract-governance.md` | Team Lead / Framework Architect | [ ] |
| 25 | [P] | `openEnum` in the annotation guide; changelog entries for the IR, the ADR-9 later-round OpenAPI/deprecation-manifest fix, gate, `openEnum`, `atlasAccept`, lock mode; the changelog names `api.ir.json`, `openEnum`, `atlasAccept` and `ai.atlas.contract.locked` (FR-019) | `docs/annotation-guide.md`, `CHANGELOG.md` | Team Lead / Framework Architect | [ ] |

**Checkpoint US5** — verify before continuing:
- [ ] `./gradlew :demo:test --tests '*ContractBaselineTest'` green
- [ ] `scripts/check-contract-docs.sh` passes (FR-019)
- [ ] `scripts/build-on-jdk-matrix.sh` passes (FR-020)

---

## Final Verification

- [ ] `scripts/build-on-jdk-matrix.sh` passes: the build is green with Gradle on JDK 17 and on JDK 21 (FR-020)
- [ ] No `[NEEDS CLARIFICATION]` markers remain in spec.md
- [ ] Every FR in `verification.yaml` has a passing verifier
- [ ] Review verdict `PASS` recorded in `collaboration/build-review.md` for every phase
