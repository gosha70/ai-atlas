---
spec_mode: full
feature_id: contract-ir-gate
risk_category: schema
status: draft
date: 2026-09-25
---

# Spec: Contract IR and compatibility gate (epic #23, Phase 2)

<!-- Project constitution: shared/skills/ — copilot-conventions, coding-standards, safety -->
<!-- Origin: GitHub issue gosha70/ai-atlas#23 §1 + specs/contract-ir-gate/origin/2026-09-25-owner-decisions.md. See plan.md `origin:` frontmatter. -->

The generated contract has no memory. Deleting an `@AgenticField` removes it from the generated
DTO and OpenAPI document, and nothing can tell that a published field disappeared: the
processor's models are already filtered to the configured major, so there is no history to
compare against.

This feature gives ai-atlas that memory:

- A **Contract IR** records every declaration with its lifecycle, as a deterministic,
  version-neutral JSON document.
- The **generators project from it**, so every generated surface comes from one model.
- A **compatibility gate** compares a committed baseline of the IR with the IR of the current
  build, direction-aware, and fails the build on a breaking change that no lifecycle
  declaration explains.
- An explicit **accept** step, and an optional **lock mode**, let a team govern changes through
  a one-file reviewable diff.

Owner decisions of 2026-09-25, recorded in the origin transcript:
- one feature covering IR, gate, accept and lock;
- the gate protects the baseline's published major;
- response enums are closed by default;
- the generators are rewired to the IR now.

## User Scenarios

### US1: Every build writes the contract IR (Priority: HIGH)

**Given** annotated entities and services, including fields and methods inactive at the configured major
**When** the sources are compiled
**Then** the processor writes `META-INF/ai-atlas/api.ir.json` listing every declaration with its lifecycle, and compiling the same sources again produces a byte-identical file

### US2: Generated code comes from the IR, unchanged (Priority: HIGH)

**Given** any existing ai-atlas project
**When** it is compiled after this feature
**Then** its DTOs, MCP tools, REST controllers, OpenAPI documents and manifests are byte-identical to before, but are produced from the IR projected at the configured major

### US3: A silent breaking change fails the build (Priority: HIGH)

**Given** a committed baseline `.atlas/api.ir.json` for published major M
**When** a developer deletes a published `@AgenticField`, removes an operation, changes a parameter, changes the DTO a response refers to, adds an operation that renames an existing `operationId`, removes an accepted input enum value, adds a value to a closed response enum, or removes the last ai-atlas annotation from the module
**Then** the build fails, naming the element, the change, why it breaks, and the declaration that would make it legitimate; declared lifecycle transitions and compatible additions pass

### US4: Changes are accepted explicitly; lock mode governs every change (Priority: MEDIUM)

**Given** a project using the Gradle plugin
**When** the developer runs `atlasAccept`
**Then** the baseline is rewritten from the current build, even if the gate currently fails, and the build then passes; with lock mode on, any difference from the baseline fails the build until accepted

### US5: The demo is governed and the rules are documented (Priority: MEDIUM)

**Given** the repository's demo and documentation
**When** a contributor changes the demo's contract or reads the docs
**Then** the demo's committed baseline guards it, and the docs state the IR format, the gate's rules, accept, lock mode and the `irVersion` migration policy

## Requirements

### The Contract IR (US1)

- **FR-001**: The processor MUST build a Contract IR from every `@AgenticEntity` and every
  `@AgenticExposed` declaration in the compilation, independent of the configured
  `ai.atlas.api.major`. It MUST include every entity; every `@AgenticField` with its lifecycle
  (`sinceVersion`, `removedInVersion`, `deprecatedSinceVersion`, `deprecatedMessage`); and every
  exposed method with its lifecycle (`apiSince`, `apiUntil`, `apiDeprecatedSince`,
  `apiReplacement`), after class-level inheritance is resolved. Elements inactive at the
  configured major MUST be included.
- **FR-002**: The IR MUST record, per entity: the source class's qualified name (its identity),
  DTO name, DTO package, display name, description, `includeTypeInfo`, and its fields in the order
  the generated DTO declares them. Per field: Java name, display name, Java type, collection kind
  and element type, type hint (`@AgenticField(type = …)`) if any, referenced entity and its DTO
  (qualified names) if any, allowed values or enum constants, `openEnum`,
  `sensitive`, `checkCircularReference`, description, type hint, and lifecycle. Per operation:
  identity (service qualified name, method name, parameter Java types), MCP tool name, channels,
  description, REST HTTP method and path, parameters (name, Java type, description, allowed
  enum constants when the type is an enum), return (Java type, return kind, the effective
  `@AgenticExposed(returnType = …)` after method-then-class resolution if any, and the referenced
  entity and its DTO, qualified names, if any), and lifecycle. At document level: `irVersion`, `apiBasePath`, and `apiMajor` (the
  configured major the document was emitted for).
- **FR-003**: The processor MUST write the IR to the class output as
  `META-INF/ai-atlas/api.ir.json`, deterministically. The same input sources and processor
  options MUST produce a byte-identical file:
  - entities ordered by qualified name, operations by service qualified name then method
    identity, fields and parameters in declaration order;
  - a fixed key order within every object;
  - UTF-8, `\n` line endings, two-space indentation, and a trailing newline;
  - no timestamps, absolute paths, host names or other environment data.
- **FR-004**: The IR MUST carry `"irVersion": 1`. When the processor reads a baseline, it MUST
  handle three cases:
  - an `irVersion` above the one it supports is a compile ERROR naming both versions and saying
    the baseline was written by a newer ai-atlas;
  - a lower `irVersion` is migrated in memory by the documented migration step for that version;
  - a baseline that is not valid IR JSON is a compile ERROR naming the file and the parse
    failure.
- **FR-005**: The IR MUST contain only plain JSON values. Java types are recorded as canonical
  source-form strings (for example `java.util.List<com.x.Order>`, `long`, `java.lang.String[]`),
  and no framework runtime type is serialized.

### Generators project from the IR (US2)

- **FR-006**: `DtoGenerator`, `McpToolGenerator`, `RestControllerGenerator`, `OpenApiGenerator`
  and `DeprecationManifestGenerator` MUST receive their entity and service models from a
  projection of the IR at the configured major. Selecting active fields and methods and marking
  deprecation MUST happen in that projection, and `FieldScanner` MUST no longer filter fields by
  `apiMajor`. The projection MUST also assign each active operation's OpenAPI `operationId`,
  using today's rule from `OpenApiGenerator` over the whole projection, and `OpenApiGenerator`
  MUST use those IDs, so that the generator and the gate (FR-010) share one derivation.
  Existing diagnostics MUST be preserved with the same kind and text: the "excluded
  from … DTO — not active for apiMajor" note, the empty-entity warning and error, the
  version-range validation errors, and #25's collision, mapping and description diagnostics.
- **FR-007**: For every input, the generated sources and resources MUST be byte-identical to
  those produced before this feature, excluding the new IR files. A golden snapshot captured
  before the rewire MUST prove it. The snapshot covers the demo module's sources at its
  configured options and a processor fixture set with:
  - fields and methods inactive at the configured major;
  - deprecated fields and methods;
  - enum, collection, nested-entity and type-hint fields;
  - `void` methods, overloads, and every #25 fixture in `RestOpenApiConsistencyTest`;
  - two services each exposing a `find()`, so that shared operation IDs are qualified;
  - a compilation with no ai-atlas annotation, which produces no generated file.

### The compatibility gate (US3)

- **FR-008**: When the processor option `ai.atlas.contract.baseline` names an existing file, the
  processor MUST compare the baseline IR with the fresh IR, both projected at M, the baseline
  document's `apiMajor`. It MUST classify every difference between the two projections as
  breaking or compatible, using FR-009 and FR-010. When the option is unset or the file does not
  exist, the processor MUST NOT compare. It MUST emit one NOTE naming the expected baseline path
  and `atlasAccept`, except in lock mode (FR-014). A configured `ai.atlas.api.major` lower than M
  MUST be a compile ERROR naming both majors. The comparison reads only the baseline file and the
  compilation: no network, database or model call.

  The gate MUST also run when the compilation declares no `@AgenticEntity` or `@AgenticExposed`
  at all (an empty contract), because removing a module's last ai-atlas annotation removes its
  whole published contract:
  - the processor MUST declare `*` as its supported annotation types, so that javac invokes it
    for every compilation it is on the processor path of, and `process` MUST return `false` so
    that it never claims annotations from other processors;
  - it MUST remain registered with Gradle as `aggregating`, and a change to an ordinary source
    file MUST NOT cause Gradle to report a full recompilation because of it;
  - when a baseline is configured and the compilation declares nothing, the fresh IR is an empty
    document carrying the configured `apiBasePath` and `apiMajor`. The processor MUST write it as
    `api.ir.json` and compare it, so every element active at M in the baseline is reported as
    removed (FR-009, FR-010, FR-012). Apart from `api.ir.json` and `contract-diff.json` it MUST
    generate nothing, as before this feature;
  - when no baseline is configured, the lock option is false, and the compilation declares
    nothing, the processor MUST write no file and emit no diagnostic.
- **FR-009**: Output differences (entity fields and operation returns) MUST be classified as
  follows. A field's **effective schema** is its Java type, collection kind, element type, type
  hint, and referenced entity and DTO. An operation's **effective return schema** is its return
  Java type, return kind, effective `returnType`, and referenced entity and DTO. Both matter because
  the DTO a response refers to can change while the Java signature stays the same: a method
  returning `List<?>` can move from `returnType = Order.class` to `returnType = Customer.class`
  with both entities still declared.
  - Breaking:
    - a field present in the old projection is absent from the new;
    - a field's Java name or display name changed;
    - any part of a field's effective schema changed, including only its type hint or the
      entity or DTO it refers to;
    - its `sensitive` changed from false to true;
    - an entity's DTO name or DTO package changed, or its `includeTypeInfo` changed;
    - a value was added to a field's enum constants or allowed values while the field's
      `openEnum` is false;
    - any part of an operation's effective return schema changed, whether through its method
      signature, a method-level or a class-level `returnType`;
    - the document's `apiBasePath` changed.
  - Compatible, and MUST NOT be reported as errors:
    - a field was added;
    - `sensitive` changed from true to false;
    - an output enum or allowed value was removed;
    - a value was added while `openEnum` is true;
    - description, deprecation and `checkCircularReference` changed.
- **FR-010**: Input and operation differences MUST be classified as follows.
  - Breaking:
    - an operation present in the old projection is absent from the new; the identity includes
      the parameter types, so any change to their number, order or types is a removal;
    - a parameter's name changed;
    - a channel was removed from an operation;
    - its MCP tool name changed;
    - its REST HTTP method or path changed;
    - its OpenAPI `operationId` changed. The IDs are derived from each whole projection at M
      (FR-006), so this includes a rename caused only by another operation being added, for
      example a second API-exposed `find()` in another service turning the existing `find` into a
      qualified ID;
    - a value was removed from the allowed enum constants of an input parameter.
  - Compatible:
    - an operation was added, provided that no operation present in both projections changes
      its `operationId`, REST path, HTTP method or MCP tool name as a result;
    - a channel was added;
    - a value was added to an input enum parameter;
    - description and deprecation changed.
- **FR-011**: `@AgenticField` MUST gain `boolean openEnum() default false`, recorded in the IR.
  Setting it true on a field that is neither enum-typed nor has `allowedValues` MUST be a WARNING
  naming the field.
- **FR-012**: In the default (unlocked) mode, every breaking difference MUST be a compile ERROR.
  It is reported on the declaration element when one still exists in the compilation, and
  without an element otherwise. Each message MUST name:
  - the element path, as `entity <qualified class>`, `field <qualified class>#<java name>` or
    `operation <qualified class>#<method>(<parameter types>)`;
  - the change as before → after;
  - the direction (input or output) and why it breaks clients of major M;
  - the declaration that would legitimise it:
    - a removed or changed field, including a changed effective schema →
      `@AgenticField(removedInVersion = <M+1>)` on the old field, with any replacement as a new
      field with `sinceVersion = <M+1>`;
    - a removed or changed operation, including a changed effective return schema →
      `@AgenticExposed(apiUntil = <M>)` plus a replacement with `apiSince = <M+1>`;
    - a changed `operationId` → the operation(s) whose addition caused it, and giving the new
      operation a method name that does not collide, or `apiSince = <M+1>` on it;
    - a changed DTO name, DTO package or `apiBasePath` → restoring the previous value;
    - an empty contract → restoring the annotations, or accepting the removal;
    - an added response enum value → `openEnum = true` if clients tolerate unknown values;
    - in all cases, accepting the change explicitly with `atlasAccept`.

  Compatible differences produce no diagnostic.
- **FR-013**: Whenever a comparison runs, the processor MUST also write
  `META-INF/ai-atlas/contract-diff.json`, listing every difference with its element path,
  direction, before and after values, and classification. It is deterministically ordered by
  element path, and an empty list is written when there are none.

### Accept and lock mode (US4)

- **FR-014**: The processor MUST accept the option `ai.atlas.contract.locked` (`true` or
  `false`, case-insensitive, default `false`; any other value is a compile ERROR naming option
  and value). When it is true:
  - any difference between the baseline and fresh IR documents MUST be a compile ERROR, listing
    each differing element path. This covers every declaration and attribute, including elements
    inactive at M and the document's `apiMajor`, and includes compatible ones;
  - a missing baseline MUST be a compile ERROR naming the expected path and `atlasAccept`.
- **FR-015**: The Gradle plugin MUST provide a task `atlasAccept` that writes the IR of the
  current sources to the baseline path, creating the directory. The written file MUST be
  byte-identical to the `api.ir.json` the processor emits for the same sources and options. The
  task MUST succeed when the gate or lock mode would currently fail the build, and it MUST print
  the accepted differences grouped by classification. A subsequent `build` MUST then pass the
  gate and lock mode.
- **FR-016**: The Gradle plugin's `agentic { }` extension MUST add two properties:
  - `contractBaseline`, a file property defaulting to `<projectDir>/.atlas/api.ir.json`;
  - `contractLocked`, a boolean defaulting to `false`.

  The plugin MUST pass both as `ai.atlas.contract.baseline` (absolute path) and
  `ai.atlas.contract.locked` to the main source set's `compileJava` task only. No other
  compilation receives them, so a test compilation that declares no ai-atlas annotation is never
  compared against the main baseline (FR-008). It MUST declare the baseline file as an optional
  input of that task, so that creating, editing or accepting the baseline re-runs the gate.
- **FR-017**: The `atlas` CLI (`-A`) and the STDIO MCP server (`options`) MUST pass
  `ai.atlas.contract.baseline` and `ai.atlas.contract.locked` through unchanged. A gate failure
  MUST surface as a failed generation carrying the gate's diagnostic: exit code 1 with
  `status: error` for the CLI's `--json`, and an `isError` result for MCP.

### Demo and documentation (US5)

- **FR-018**: The demo module MUST commit its baseline as `demo/.atlas/api.ir.json`, produced by
  the processor from the demo sources and options. `demo/build.gradle.kts` MUST pass
  `ai.atlas.contract.baseline` pointing at it, to the main `compileJava` task only. A demo test MUST assert that the committed
  baseline is byte-identical to the IR the demo build emits, so the demo's contract cannot change
  without its baseline being updated in the same change.
- **FR-019**: The documentation MUST cover the feature:
  - a new `docs/contract-governance.md` describing the IR document (fields and `irVersion`),
    the gate's rules as tables of breaking and compatible changes per direction, the projection
    at the baseline's published major, `openEnum`, accept, lock mode, the baseline file's place
    in version control, and the policy that each future `irVersion` ships a documented in-memory
    migration from the previous one;
  - `docs/annotation-guide.md` documenting `openEnum`;
  - `CHANGELOG.md` gaining entries under `[Unreleased]` for the IR, the gate, `openEnum`,
    `atlasAccept` and lock mode.

### Whole feature

- **FR-020**: `./gradlew build` MUST pass with Gradle on JDK 17 and, separately, on JDK 21, run
  by `scripts/build-on-jdk-matrix.sh`.

## Constraints / What NOT to Build

- No change to any generated output other than the new IR files (FR-007). The rewire changes
  where models come from, not what is generated.
- No constraint model, `@AgenticParam`, behavioural hints, per-channel projections,
  collection-safety policy, explicit REST verb and path metadata, or release snapshots: Phases 3–5.
- No cross-module contract: the IR covers one compilation; a consumer spanning modules keeps one
  baseline per module.
- No automatic baseline update during `build`: only `atlasAccept` writes the baseline.
- No network, database, model call or live service anywhere in the IR, projection or gate.
- No rename detection: a rename is a removal plus an addition, reported as such.
- No new annotation other than `@AgenticField.openEnum`; lifecycle attributes are unchanged.

## Key Entities

- **Contract IR**: the JSON document `api.ir.json` recording every declaration with its lifecycle,
  independent of any major.
- **Projection at N**: the entities, fields and operations of an IR that are active at major N,
  with deprecation resolved. It is what the generators consume, and what the gate compares.
- **Baseline**: the committed IR, `.atlas/api.ir.json` by default, written only by `atlasAccept`.
  Its `apiMajor` is the published major M the gate protects.
- **Breaking / compatible**: the classification of a difference between the projections at M, by
  direction: output (fields, returns) or input (operations, parameters).
- **Lock mode**: `ai.atlas.contract.locked=true`; every difference in the whole IR document fails
  the build until accepted.

## Success Criteria

1. **US1 / FR-001–FR-005**: compiling a fixture twice yields byte-identical `api.ir.json` that
   includes a field with `removedInVersion` below the configured major and a method with
   `apiSince` above it.
2. **US2 / FR-006–FR-007**: the golden snapshot captured before the rewire matches the output
   after it, file for file and byte for byte.
3. **US3 / FR-008–FR-013**: against a baseline at major M:
   - deleting a published field fails, naming `field …#…` and `removedInVersion = <M+1>`;
   - setting `removedInVersion = M+1` passes;
   - deleting a field already removed before M passes;
   - removing an input enum value fails;
   - changing only `@AgenticExposed(returnType)` from one declared entity to another on a
     `List<?>` method fails, and so does changing only a field's type hint;
   - adding a second API-exposed `find()` in another service fails, naming the renamed
     `operationId`;
   - removing every ai-atlas annotation from a module that has a baseline fails the Gradle build;
   - adding a value to a closed response enum fails, while with `openEnum = true` it passes;
   - `contract-diff.json` lists every difference.
4. **US4 / FR-014–FR-017**: `atlasAccept` makes a failing build pass. In lock mode, a description
   change fails until accepted. The CLI and MCP report gate failures as errors.
5. **US5 / FR-018–FR-019**: the demo's baseline test passes; the docs and changelog cover the
   feature.
6. **FR-020 / no regressions**: the build is green with Gradle on JDK 17 and on JDK 21.
