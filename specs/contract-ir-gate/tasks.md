# Tasks: Contract IR and compatibility gate (epic #23, Phase 2)

<!-- One owner per file. [P] = parallelizable within the story group. [US#] traces to spec.md. -->
<!-- Each `## US<n>:` group is one auto-build phase (fresh session). -->
<!-- Build sessions: tick a task's Done box in the same commit that completes it. -->

## US1: Every build writes the contract IR

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 1 | | FIRST, before touching any generator: capture the golden snapshot. Record every generated source and resource (excluding `META-INF/ai-atlas/`) for the demo sources at the demo's options and for a fixture set covering inactive fields and methods, deprecation, enums, collections, nested entities, type hints, `void`, overloads and the #25 `RestOpenApiConsistencyTest` fixtures. Add a comparison test (file set and bytes) (FR-007) | `modules/processor/src/test/resources/golden/ir-rewire/**`, `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/IrRewireGoldenTest.java` | Annotation Processor Engineer | [ ] |
| 2 | | IR records: document (`irVersion`, `apiBasePath`, `apiMajor`), entity, field, operation, parameter, return, lifecycle (FR-002, FR-005) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/*.java` | Annotation Processor Engineer | [ ] |
| 3 | | `IrBuilder`: build the IR from elements without version filtering, reusing `FieldScanner`'s hierarchy walk and validation; resolve class-level inheritance for methods (FR-001) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/IrBuilder.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/FieldScanner.java` | Annotation Processor Engineer | [ ] |
| 4 | | Canonical JSON writer and reader with fixed ordering and formatting, `irVersion` checks, malformed-file errors (FR-003, FR-004) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/IrJson.java` | Annotation Processor Engineer | [ ] |
| 5 | | Collect the IR across rounds in `AgenticProcessor` and write `META-INF/ai-atlas/api.ir.json` in the round that writes the OpenAPI document (FR-003) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [ ] |
| 6 | | Tests: IR completeness including inactive elements; byte-identical across two compilations; `irVersion` above supported is an error; malformed baseline is an error; no absolute paths or timestamps (FR-001..005) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/ContractIrTest.java` | Annotation Processor Engineer | [ ] |

**Checkpoint US1** — verify before continuing:
- [ ] `./gradlew :modules:processor:test --tests '*ContractIrTest' --tests '*IrRewireGoldenTest'` green
- [ ] No file under `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/` changed in this phase
- [ ] `./gradlew build` green

---

## US2: Generated code comes from the IR, unchanged

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 7 | | `ContractProjection`: IR + major → `EntityModel`/`FieldModel`/`ServiceModel` and the entity registry, active elements only, deprecation resolved, `TypeName` parsed from canonical type strings (FR-006) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractProjection.java` | Annotation Processor Engineer | [ ] |
| 8 | | Feed every generator from the projection; remove the `apiMajor` filter from `FieldScanner`; emit the "excluded … not active" note and the empty-entity warning and error from the projection, with identical text and kind (FR-006) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/FieldScanner.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/*.java` | Annotation Processor Engineer | [ ] |
| 9 | | Tests: the projection round-trips every canonical type form used by the fixtures; the golden comparison stays green (FR-006, FR-007) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/ContractProjectionTest.java` | Annotation Processor Engineer | [ ] |

**Checkpoint US2** — verify before continuing:
- [ ] `./gradlew :modules:processor:test` green, including `IrRewireGoldenTest` with the snapshot unchanged
- [ ] `./gradlew build` green

---

## US3: A silent breaking change fails the build

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 10 | | `@AgenticField(openEnum = false)`, recorded in the IR; WARNING when set on a field that is neither an enum nor has `allowedValues` (FR-011) | `modules/annotations/src/main/java/com/egoge/ai/atlas/annotations/AgenticField.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/IrBuilder.java` | Annotation Processor Engineer | [ ] |
| 11 | | `ContractGate`: read the baseline named by `ai.atlas.contract.baseline`; project both at M; classify output and input differences per FR-009 and FR-010; NOTE when there is no baseline; ERROR when the configured major is below M (FR-008..010) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractGate.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [ ] |
| 12 | | Diagnostics with element path, before → after, direction, reason, legitimising declaration and `atlasAccept`; on the element when present (FR-012); write `META-INF/ai-atlas/contract-diff.json` (FR-013) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractGate.java` | Annotation Processor Engineer | [ ] |
| 13 | | Tests, each against a baseline produced by compiling a fixture:<br>- field deleted fails;<br>- `removedInVersion = M+1` passes;<br>- deleting a field inactive at M passes;<br>- field renamed (Java name or display name) fails;<br>- field type change fails;<br>- `sensitive` false→true fails;<br>- field added passes;<br>- closed response enum gains a value fails;<br>- open response enum gains a value passes;<br>- output enum value removed passes;<br>- operation removed fails;<br>- parameter added or type changed fails;<br>- parameter renamed fails;<br>- channel removed fails;<br>- tool name changed fails;<br>- input enum value removed fails;<br>- input enum value added passes;<br>- operation added passes;<br>- description change passes silently;<br>- configured major below M errors;<br>- no baseline notes;<br>- `contract-diff.json` content and ordering;<br>- `openEnum` warning (FR-008..013) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/ContractGateTest.java` | Annotation Processor Engineer | [ ] |

**Checkpoint US3** — verify before continuing:
- [ ] `./gradlew :modules:processor:test --tests '*ContractGateTest'` green
- [ ] `./gradlew build` green (the demo has no baseline yet, so it only notes)

---

## US4: Changes are accepted explicitly; lock mode governs every change

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 14 | | Lock mode: `ai.atlas.contract.locked` validated; any whole-document difference is an ERROR listing element paths; a missing baseline is an ERROR (FR-014) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractGate.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [ ] |
| 15 | | Tests: locked with a description change fails; locked with an identical baseline passes; locked with no baseline fails; bad option value errors (FR-014) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/ContractLockTest.java` | Annotation Processor Engineer | [ ] |
| 16 | | Plugin: `contractBaseline` (default `<projectDir>/.atlas/api.ir.json`) and `contractLocked`; pass both options; declare the baseline as an optional compile input; register `atlasAccept`, which writes the fresh IR even when the gate fails and prints the accepted differences grouped by classification (FR-015, FR-016) | `modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticExtension.java`, `modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticPlugin.java` | Team Lead / Framework Architect | [ ] |
| 17 | | Functional tests:<br>- no baseline → build passes with the note;<br>- `atlasAccept` creates a baseline byte-identical to the emitted IR;<br>- deleting a field fails the build;<br>- `atlasAccept` then makes it pass;<br>- lock mode with a description change fails until `atlasAccept`;<br>- editing the baseline re-runs compilation (FR-015, FR-016) | `modules/gradle-plugin/src/functionalTest/java/com/egoge/ai/atlas/plugin/AgenticPluginFunctionalTest.java` | Team Lead / Framework Architect | [ ] |
| 18 | [P] | CLI and MCP tests: a baseline passed through `-A` / `options` that the sources break yields exit 1 with `status: error` (CLI) and an `isError` result (MCP), carrying the gate diagnostic (FR-017) | `modules/cli/src/test/java/com/egoge/ai/atlas/cli/ContractGatePassThroughTest.java`, `modules/mcp-stdio/src/test/java/com/egoge/ai/atlas/mcp/ContractGatePassThroughTest.java` | Team Lead / Framework Architect | [ ] |

**Checkpoint US4** — verify before continuing:
- [ ] `./gradlew :modules:processor:test --tests '*ContractLockTest'` green
- [ ] `./gradlew :modules:gradle-plugin:functionalTest` green
- [ ] `./gradlew :modules:cli:test :modules:mcp-stdio:test --tests '*ContractGatePassThroughTest'` green
- [ ] `./gradlew build` green

---

## US5: The demo is governed and the rules are documented

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 19 | | Commit the demo baseline produced by the processor from the demo sources and options; pass `ai.atlas.contract.baseline` in the demo build (FR-018) | `demo/.atlas/api.ir.json`, `demo/build.gradle.kts` | Team Lead / Framework Architect | [ ] |
| 20 | | Demo test: the committed baseline is byte-identical to the IR the demo build emits (FR-018) | `demo/src/test/java/com/egoge/ai/atlas/demo/ContractBaselineTest.java` | Team Lead / Framework Architect | [ ] |
| 21 | [P] | `docs/contract-governance.md`: IR document and `irVersion`, rule tables per direction, projection at the published major, `openEnum`, accept, lock mode, baseline in version control, the migration policy; must name `irVersion`, `apiMajor`, `openEnum`, `atlasAccept`, `ai.atlas.contract.baseline` and `ai.atlas.contract.locked` (checked by `scripts/check-contract-docs.sh`) (FR-019) | `docs/contract-governance.md` | Team Lead / Framework Architect | [ ] |
| 22 | [P] | `openEnum` in the annotation guide; changelog entries for the IR, gate, `openEnum`, `atlasAccept`, lock mode; the changelog names `api.ir.json`, `openEnum`, `atlasAccept` and `ai.atlas.contract.locked` (FR-019) | `docs/annotation-guide.md`, `CHANGELOG.md` | Team Lead / Framework Architect | [ ] |

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
