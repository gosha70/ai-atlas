---
spec_mode: full
feature_id: contract-ir-gate
risk_category: schema
justification: "Introduces a persisted, versioned contract document and a compile-time gate that can fail consumers' builds, adds an annotation attribute, rewires every generator's input, and adds Gradle tasks — schema-level, multi-module, consumer-visible, so full SDD with review gating."
status: draft
date: 2026-09-25
collaboration_mode: single
origin:
  issue: gosha70/ai-atlas#23
  transcripts:
    - specs/contract-ir-gate/origin/2026-09-25-owner-decisions.md
  origin_claim: |
    Epic gosha70/ai-atlas#23 §1 asks to "introduce a distinct, framework-neutral Contract IR
    containing every declared contract element and its lifecycle metadata, then derive the
    active API version from that IR", persisted as `.atlas/api.ir.json` with "its own schema
    version", and a build that will "derive the fresh Contract IR; diff it against the
    committed baseline; classify changes; reconcile breaking changes with lifecycle annotations;
    fail with a precise diagnostic when a breaking delta is undeclared", with direction-aware
    rules, enum openness, and an optional "contract-lock mode" that requires "explicit
    acceptance of any delta via an atlasAccept-style task". Its design constraints require that
    "REST, MCP, DTOs, and OpenAPI must be projections of the same normalised contract model".
    On 2026-09-25 the owner asked to proceed with the epic and chose: one feature covering IR,
    gate, accept and lock; the gate protects the baseline's published major; response enums
    closed by default; and the generators rewired to project from the IR now.
---

# Implementation Plan: Contract IR and compatibility gate (epic #23, Phase 2)

**Branch**: `feature/contract-ir-gate`
**Input**: specs/contract-ir-gate/spec.md

## Summary

Add a Contract IR to the processor: a deterministic, version-neutral JSON record of every
entity, field and exposed operation with its lifecycle. Rewire the generators to consume the IR
projected at the configured major, with generated output proven byte-identical by a golden
snapshot captured before the rewire. Compare the IR against a committed baseline at the
baseline's published major, direction-aware, and fail the build on undeclared breaking changes.
Add `@AgenticField.openEnum`, the `atlasAccept` Gradle task, lock mode, a governed demo baseline
and the documentation.

## Technical Context

**Language/Version**: Java 17 toolchain (CI also builds on JDK 21), Gradle Kotlin DSL.
**Primary Dependencies**:
- `modules/annotations` (`AgenticField`);
- `modules/processor`: `AgenticProcessor`, `util/FieldScanner`, `util/VersionSelector`,
  `util/EntityRefResolver`, all generators, and a new `contract` package;
- `modules/gradle-plugin` (`AgenticExtension`, `AgenticPlugin`, `functionalTest`);
- `modules/cli`, `modules/mcp-stdio` (tests only), `demo`.

Jackson is already a processor dependency (OpenAPI serialization). No new dependency.
**Testing**: JUnit 5 + AssertJ; processor compile-testing; the golden snapshot under
`modules/processor/src/test/resources/golden/`; Gradle TestKit functional tests; demo tests.
Gate: `./gradlew build`, and `scripts/build-on-jdk-matrix.sh` for FR-020.
**Constraints**:
- generated output byte-identical (FR-007);
- the processor stays `aggregating` for Gradle incremental processing;
- `annotations` keeps zero dependencies;
- everything offline and deterministic.

## Constitution Check

| Rule file | Concern | Status |
|-----------|---------|--------|
| `coding-standards.md` | Option keys as constants beside `OPT_STRICT`; diagnostics prefixed `[ai-atlas]`; no magic strings for IR keys | OK |
| `safety.md` | The baseline path is read, never executed; option values validated (FR-014) | OK |
| `copilot-conventions.md` | One logical change per commit; no new dependency | OK |

## Architecture Decisions

### ADR-1: The IR is built from elements, independent of the configured major

**Context**: `FieldScanner.scan` drops fields inactive at `apiMajor`, and method models are
filtered at generation time. Persisting that model would lose the history the gate needs.
**Decision**: A new `contract` package holds:
- plain records for the IR document;
- an `IrBuilder` that scans elements without version filtering, reusing `FieldScanner`'s
  hierarchy walk and validation once its filter moves out;
- a canonical JSON writer and reader.

The processor collects the IR across rounds and writes `api.ir.json` in the same round it
writes the OpenAPI document. Java types are canonical source-form strings (FR-005).
**Consequences**: One scan feeds both the IR and generation; the version-range validation stays
where it is.

### ADR-2: The generators consume a projection of the IR (owner decision)

**Context**: The owner chose to rewire the generators now, so that every surface derives from one
model, the epic's design constraint.
**Decision**: A `ContractProjection` takes the IR and a major N. It returns the existing
`EntityModel`, `FieldModel` and `ServiceModel` records (and the entity registry `DtoGenerator`
needs), containing only elements active at N, with deprecation resolved. JavaPoet `TypeName`s are
parsed from the IR's canonical type strings, which covers generics, arrays, primitives and nested
types. The generators' own `VersionSelector` checks become redundant but may remain as
assertions. Diagnostics that depended on filtering (the "excluded … not active" note, the empty
entity warning and error) move to where the projection is taken, with identical text and kind.
**Consequences**: The generators need no API change, since they still take the same models. The
risk is in the projection and the type-string round trip, and the golden snapshot (ADR-3) bounds
it. Because the IR is self-sufficient for code generation, later release snapshots (Phase 5) can
regenerate a published major from a stored IR.

### ADR-3: A golden snapshot, captured before the rewire, proves nothing changed

**Context**: "Byte-identical" needs a fixed reference from before the change, not a comparison of
two post-change paths.
**Decision**: The first build phase captures, from the unchanged generators, every generated
source and resource into `modules/processor/src/test/resources/golden/ir-rewire/`. It covers the
demo's sources at the demo's options, plus a fixture set exercising lifecycle filtering,
deprecation, enums, collections, nested entities, type hints, `void`, overloads and #25's
fixtures. A test compares the current output file-for-file and byte-for-byte, ignoring only the
new `META-INF/ai-atlas/` files. The capture is committed in that phase, and no generator change
may land in the same phase.
**Consequences**: Any drift the rewire introduces fails a test with a file-level diff.

### ADR-4: The gate compares projections at the baseline's published major (owner decision)

**Context**: Compatibility must be judged against what is deployed, and lifecycle declarations
must be able to legitimise a change.
**Decision**: Let M be the baseline document's `apiMajor`. The gate compares
`project(baseline, M)` with `project(fresh, M)`, and classifies differences by direction:
- outputs are entity fields and operation returns;
- inputs are operations and parameters.

A declared transition such as `removedInVersion = M+1` leaves the projection at M unchanged and
passes. Deleting the declaration changes it and fails. Deleting something already inactive at M
leaves it unchanged, so cleanup passes. A configured major above M is a major bump: the new
major's surface may differ, but M's projection still must not break unless the change is
declared. A configured major below M is an error.
**Consequences**: The rule is simple enough to explain in one table. Majors earlier than M are not
protected, per the owner's choice.

### ADR-5: Closed enums by default; operation identity includes parameter types

**Context**: The epic asks the classifier to know whether response enums are tolerant.
Overloads (`find()` / `find(Long)`) need distinct identities.
**Decision**: `@AgenticField(openEnum = false)` is the default, and a value added to a closed
output enum or `allowedValues` is breaking. Operation identity is the service qualified name,
method name and parameter Java types. Any parameter-list change is therefore a removal plus an
addition, and is breaking; its declared form is a new method with `apiSince = M+1`.
**Consequences**: Conservative by default. Teams opt into tolerant enums per field.

### ADR-6: Only `atlasAccept` writes the baseline; the gate runs inside compilation

**Context**: The gate must fail the ordinary build, but acceptance must work while the build is
failing.
**Decision**: The gate runs in the processor, reporting compile errors on the offending
elements. `atlasAccept` obtains the fresh IR without the gate failing the build. For example, it
can run the compilation with the baseline and lock options unset into a separate output, then
copy `META-INF/ai-atlas/api.ir.json` to the baseline path. The mechanism is the builder's choice
within FR-015. The plugin declares the baseline file as a compile input, so accepting it
re-triggers the gate.
**Consequences**: The build never writes to source control. Every contract change reaches review
as a diff of one committed file.

### ADR-7: Derived identifiers and effective schemas are compared, not just declarations

**Context**: Review of PR #31 found two changes a declaration-by-declaration comparison misses.
- `operationId` is not a property of one operation. `OpenApiGenerator.assignOperationIds` keeps
  a method name used once and qualifies names shared by several operations. So adding a second
  API-exposed `find()` renames the existing `find` without touching its declaration.
- The DTO a response uses can change while the Java signature stays the same. A `List<?>`
  method can move from `returnType = Order.class` to `returnType = Customer.class`, and a field
  can change only its type hint. If both entities remain declared, no field removal is seen.

**Decision**:
- `ContractProjection` assigns operation IDs over the whole projection, with the rule moved
  unchanged out of `OpenApiGenerator`, which then consumes them. The golden snapshot (ADR-3)
  proves the move changed nothing.
- The gate compares, per operation present in both projections at M, every client-facing
  identifier: `operationId`, REST path and method, and MCP tool name.
- The IR records each field's type hint and each return's effective `returnType`, plus the
  referenced entity and DTO. The gate compares the whole effective schema of every field and
  return, and the DTO name and package of every active entity. `apiBasePath` is compared too,
  because it is part of every path.

**Consequences**: Adding an operation is compatible only when it disturbs no existing
identifier. The error names the new operation that caused the rename. Identifiers are derived
with one algorithm on both sides, so a future change to that algorithm in ai-atlas itself is
not seen by the gate; the golden snapshot guards that instead.

### ADR-8: An empty contract is checked by the tools that run the processor

**Context**: `AgenticProcessor` declares only `AgenticEntity` and `AgenticExposed`. javac
discovers and calls a processor only when one of its supported annotations is present. If a
module removes its last ai-atlas annotation, the processor never runs, and the published
contract disappears without a comparison.

**Rejected: declaring `*`.** It would make javac call the processor on every compilation, but
Gradle 8.14.4's `AggregatingProcessingStrategy` forces a full recompilation whenever an
annotation presented to an aggregating processor has `SOURCE` retention. With `*`, that means
`@Override` and `@SuppressWarnings`, so nearly every edit would recompile the whole module.
Returning `false` from `process` does not help, because the annotations are still presented.

**Decision**:
- The processor keeps its two supported annotation types and stays `aggregating`.
- The processor module exposes one public empty-contract check (`contract/EmptyContract`). It
  builds the empty IR document for the configured `apiBasePath` and `apiMajor` and runs the
  gate's own comparison and lock logic against the baseline, so its rules and messages are the
  gate's. An empty baseline, or one with nothing active at M, passes unless lock mode sees a
  document difference.
- A missing `META-INF/ai-atlas/api.ir.json` after a successful compilation means the processor
  was not invoked. Each tool that runs the processor detects that and calls the check:
  - Gradle plugin: an `atlasContractCheck` task after `compileJava`, which `classes` depends
    on. It runs `EmptyContract` through the Worker API with class-loader isolation on the
    `annotationProcessor` classpath, so the check matches the processor version that compiled.
    It does nothing when the IR exists, because the processor has already gated it.
  - CLI and MCP server: after a successful generation, with a baseline or lock option set.
- `atlasAccept` writes `EmptyContract`'s document when the sources declare nothing.
- Stale output: Gradle deletes the resources an aggregating processor generated whenever it
  recompiles, so removing the last annotation leaves no stale IR behind. The TestKit fixture
  exercises exactly that incremental path, so the builder does not rely on this unproven.
- The TestKit suite also includes ordinary sources carrying `SOURCE`-retention annotations
  (`@Override`, `@SuppressWarnings`, and a custom `@Retention(SOURCE)` annotation). Editing one
  of them after a successful build must compile incrementally, with no "Full recompilation is
  required" line in `--info` output. That guards against this regression.

**Consequences**: The check runs outside javac, so it cannot place a diagnostic on a source
element. It names the baseline and the removed element paths instead, as FR-012 already allows
for elements that no longer exist.

## Project Structure

```
modules/annotations/src/main/java/com/egoge/ai/atlas/annotations/AgenticField.java        — openEnum (FR-011)
modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/                    — new: IR records, IrBuilder, IrJson (writer/reader, irVersion), ContractProjection, ContractGate (diff, classification, report)
modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java        — collect IR, project, gate, options ai.atlas.contract.baseline / .locked (supported annotation types unchanged)
modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/EmptyContract.java  — public empty-contract check and empty IR document (ADR-8)
modules/processor/src/main/java/com/egoge/ai/atlas/processor/util/FieldScanner.java       — no apiMajor filter; filtering moves to the projection
modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/*.java             — models from the projection; OpenApiGenerator takes operation IDs from it (no output change)
modules/processor/src/test/resources/golden/ir-rewire/                                    — new: pre-rewire snapshot (FR-007)
modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/                    — new: ContractIrTest, IrRewireGoldenTest, ContractGateTest, ContractLockTest
modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticExtension.java      — contractBaseline, contractLocked
modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/AgenticPlugin.java         — options, compile input, atlasAccept and atlasContractCheck tasks
modules/gradle-plugin/src/functionalTest/java/com/egoge/ai/atlas/plugin/AgenticPluginFunctionalTest.java — gate, accept, lock cases
modules/cli/src/test/java/…, modules/mcp-stdio/src/test/java/…                            — gate-failure pass-through tests (FR-017)
demo/.atlas/api.ir.json                                                                  — new: committed demo baseline
demo/build.gradle.kts                                                                    — baseline option
demo/src/test/java/com/egoge/ai/atlas/demo/ContractBaselineTest.java                     — new (FR-018)
docs/contract-governance.md                                                              — new (FR-019)
docs/annotation-guide.md, CHANGELOG.md                                                   — openEnum, entries (FR-019)
scripts/check-contract-docs.sh                                                           — FR-019 verifier (committed with the spec)
```

## Scope

### Task 1: IR, and the pre-rewire golden snapshot (US1, FR-001..005, FR-007 capture)

**Acceptance criteria**:
- [ ] `api.ir.json` emitted, complete, byte-stable, `irVersion` 1 (FR-001..005)
- [ ] Golden snapshot of current generator output committed; comparison test green; no generator change

### Task 2: Rewire generators to the projection (US2, FR-006..007)

**Acceptance criteria**:
- [ ] Models come from `ContractProjection`; `FieldScanner` no longer filters
- [ ] Golden comparison and every existing test green; diagnostics unchanged

### Task 3: The gate (US3, FR-008..013)

**Acceptance criteria**:
- [ ] Projection comparison at M; output and input rules including effective schemas and derived operation IDs; `openEnum`; messages; `contract-diff.json`
- [ ] `EmptyContract` check and empty IR document (ADR-8)

### Task 4: Accept, lock, pass-through (US4, FR-014..017)

**Acceptance criteria**:
- [ ] Lock mode; `atlasAccept`; plugin properties and compile input; CLI/MCP pass-through
- [ ] `atlasContractCheck`; CLI/MCP empty-contract check; TestKit: removing every annotation fails the build incrementally, and edits to sources with `SOURCE`-retention annotations stay incremental

### Task 5: Demo baseline and documentation (US5, FR-018..020)

**Acceptance criteria**:
- [ ] Demo baseline committed and tested; docs and changelog; two-JDK build

## Constraints / What NOT to Build

- No change to generated output other than the IR files (ADR-3).
- No automatic baseline writes during `build` (ADR-6).
- No rename detection, no cross-module contracts, no Phase 3–5 features.

## File Ownership (Non-Overlapping)

| Owner | Files |
|-------|-------|
| Annotation Processor Engineer | `modules/annotations/**`, `modules/processor/**` |
| Team Lead / Framework Architect | `modules/gradle-plugin/**`, `modules/cli/**`, `modules/mcp-stdio/**`, `demo/**`, `docs/**`, `CHANGELOG.md` |

## Collaboration (Dual Mode)

Single-provider build; review gating is handled by the auto-build-loop reviewer (DeepSeek, per
`automation.json`), not the dual peer-review protocol. `collaboration_mode: single`.
