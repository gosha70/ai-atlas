# Contract Governance

ai-atlas records the contract it generates, compares it with a committed baseline on every build,
and fails the build on a breaking change that no lifecycle declaration explains. This guide covers
the Contract IR, the compatibility gate's rules, accepting a change, lock mode, and how the
baseline is kept in version control.

## The Contract IR

Every compilation that declares an `@AgenticEntity` or `@AgenticExposed` writes the **Contract IR**
to its class output as `META-INF/ai-atlas/api.ir.json`. It records every declaration with its
lifecycle, **including elements inactive at the configured major** (a field with
`removedInVersion` at or below it, a method with `apiSince` above it). The generated DTOs, MCP
tools, REST controllers, OpenAPI documents and deprecation manifest are all produced from this one
model, projected at the configured major.

### Document

| Key | Meaning |
|-----|---------|
| `irVersion` | Version of the document format. This ai-atlas writes `1` |
| `apiBasePath` | The configured REST base path (`ai.atlas.api.basePath`) |
| `apiMajor` | The configured major (`ai.atlas.api.major`) the document was emitted for. In a baseline, this is the **published major M** the gate protects |
| `entities` | Every `@AgenticEntity`, ordered by qualified class name |
| `operations` | Every `@AgenticExposed` method, ordered by service qualified name, then method identity |

**Entity:** `className` (its identity), `dtoName`, `dtoPackage`, `displayName`, `description`,
`includeTypeInfo`, and `fields` in the order the generated DTO declares them.

**Field:** `name`, `displayName`, `javaType`, `collectionKind` (`NONE`, `COLLECTION`, `ITERABLE`,
`ARRAY`), `elementType`, `typeHint` (`@AgenticField(type = …)`), `reference` (the referenced
`entity` and its `dto`), `enumType`, `allowedValues` (explicit values or the enum constants),
`openEnum`, `sensitive`, `checkCircularReference`, `description`, and `lifecycle`
(`sinceVersion`, `removedInVersion`, `deprecatedSinceVersion`, `deprecatedMessage`).

**Operation:** `service`, `method`, `toolName`, `channels`, `description`, `rest` (`httpMethod`
and `path`, without the base path and version prefix; `null` off the API channel), `parameters`
(`name`, `javaType`, `description`, `enumConstants`), `returns` (`javaType`, `returnKind`, the
effective `returnType` after method-then-class resolution, and its `reference`), and `lifecycle`
(`apiSince`, `apiUntil`, `apiDeprecatedSince`, `apiReplacement`). An operation's identity is its
service, method name and parameter Java types: `service#method(parameter types)`.

### Format

The document is deterministic: the same sources and processor options produce a byte-identical
file.

- Entities and operations are ordered as above; fields and parameters keep declaration order.
- Every object has a fixed key order.
- UTF-8, `\n` line endings, two-space indentation, and a trailing newline.
- Only plain JSON values. Java types are canonical source-form strings
  (`java.util.List<com.x.Order>`, `long`, `java.lang.String[]`). No timestamps, absolute paths,
  host names or other environment data.

### `irVersion` and the migration policy

The processor reads a baseline as follows:

- **Same `irVersion`:** read as is.
- **Higher `irVersion`:** a compile ERROR naming both versions: the baseline was written by a newer
  ai-atlas. Upgrade ai-atlas, or re-accept the baseline with `atlasAccept`.
- **Lower `irVersion`:** migrated in memory to the current version before comparison. The file on
  disk is not rewritten; `atlasAccept` writes the current version.
- **Not valid IR JSON:** a compile ERROR naming the file and the parse failure.

**Policy:** every future `irVersion` N ships with a documented, in-memory migration from N−1, and
this guide gains a section describing it. A baseline written by any earlier ai-atlas therefore stays
readable, by chaining the migrations. `irVersion` 1 is the first version, so no migration exists
yet.

## Projection at a major

The **projection at N** of an IR is the entities, fields and operations active at major N, with
deprecation resolved and OpenAPI `operationId`s assigned over the whole projection. It is what the
generators consume at the configured major, and what the gate compares.

The gate compares the projections of the baseline and the current IR **at the baseline's
published major M** (its `apiMajor`), not at the configured major:

- A declared transition leaves the projection at M unchanged, so it passes. Adding
  `removedInVersion = M+1` to a field, or `apiUntil = M` plus a replacement with `apiSince = M+1`
  to an operation, is how an element leaves the contract.
- Deleting a declaration that is active at M changes the projection, so it fails.
- Deleting a declaration already inactive at M changes nothing, so cleanup passes.
- A configured `ai.atlas.api.major` above M is a major bump: the new major may differ, but M's
  projection must still not break. Majors earlier than M are not protected.
- A configured `ai.atlas.api.major` below M is a compile ERROR naming both majors.

## The compatibility gate

The gate runs inside `compileJava` when the processor option `ai.atlas.contract.baseline` names an
existing file. It reads only that file and the compilation: no network, database or model call.
Without a baseline it does not compare, and emits one NOTE naming the expected path and
`atlasAccept` (an ERROR in lock mode).

Each difference between the two projections at M is classified by **direction**: output (what
clients receive: entity fields and operation returns) or input (what clients send: operations,
their parameters and addresses).

### Output rules (entities, fields, returns)

A field's **effective schema** is its Java type, collection kind, element type, type hint, and
referenced entity and DTO. An operation's **effective return schema** is its return Java type,
return kind, effective `returnType`, and referenced entity and DTO. The DTO a response refers to
can change while the Java signature stays the same, for example a `List<?>` method moving from
`returnType = Order.class` to `returnType = Customer.class`, so both are compared whole.

| Change | Classification |
|--------|----------------|
| A field active at M is removed (including its whole entity) | **Breaking** |
| A field's Java name or display name changes | **Breaking** |
| Any part of a field's effective schema changes, including only its type hint or referenced entity/DTO | **Breaking** |
| `sensitive` changes from false to true | **Breaking** |
| An entity's DTO name, DTO package or `includeTypeInfo` changes | **Breaking** |
| A value is added to a field's enum constants or `allowedValues` while `openEnum` is false | **Breaking** |
| Any part of an operation's effective return schema changes (method signature, method-level or class-level `returnType`) | **Breaking** |
| The document's `apiBasePath` changes | **Breaking** |
| A field is added | Compatible |
| `sensitive` changes from true to false | Compatible |
| An enum constant or allowed value is removed from a field | Compatible |
| A value is added while `openEnum` is true | Compatible |
| A description, deprecation or `checkCircularReference` changes | Compatible |

### Input rules (operations, parameters)

| Change | Classification |
|--------|----------------|
| An operation active at M is removed. The identity includes the parameter types, so any change to their number, order or types is a removal | **Breaking** |
| A parameter's name changes | **Breaking** |
| A channel is removed from an operation | **Breaking** |
| The MCP tool name changes | **Breaking** |
| The REST HTTP method or path changes | **Breaking** |
| The OpenAPI `operationId` changes, including a rename caused only by another operation being added (a second API-exposed `find()` in another service turns the existing `find` into a qualified ID) | **Breaking** |
| A value is removed from an input parameter's enum constants | **Breaking** |
| An operation is added, provided no existing operation's `operationId`, REST path, HTTP method or MCP tool name changes as a result | Compatible |
| A channel is added | Compatible |
| A value is added to an input parameter's enum constants | Compatible |
| A description or deprecation changes | Compatible |

There is no rename detection: a rename is a removal plus an addition, and is reported as such.

### Diagnostics

Each breaking difference is a compile ERROR, reported on the declaration when it still exists in
the compilation and without an element otherwise. The message names:

- the element path: `entity <qualified class>`, `field <qualified class>#<java name>` or
  `operation <qualified class>#<method>(<parameter types>)`;
- the change, as before → after;
- the direction, and why it breaks clients of major M;
- the declaration that would make it legitimate:

| Breaking change | Remedy named in the error |
|-----------------|---------------------------|
| Removed or changed field, including its effective schema | `@AgenticField(removedInVersion = M+1)` on the old field, with any replacement as a new field with `sinceVersion = M+1` |
| Removed or changed operation, including its effective return schema | `@AgenticExposed(apiUntil = M)` on the old operation, plus a replacement with `apiSince = M+1` |
| Changed `operationId` | Give the operation(s) whose addition caused it a method name that does not collide, or `apiSince = M+1` on them |
| Changed DTO name, DTO package, `includeTypeInfo` or `apiBasePath` | Restore the previous value |
| Added value on a closed response enum | `openEnum = true`, if clients tolerate unknown values |
| Empty contract | Restore the annotations, or accept the removal |

In every case, the change can also be accepted explicitly with `atlasAccept`. Compatible
differences produce no diagnostic.

Whenever a comparison runs, the processor also writes `META-INF/ai-atlas/contract-diff.json` to
the class output: `publishedMajor` and every difference with its `path`, `change`, `direction`,
`before`, `after` and `classification`, ordered by element path. The list is empty when nothing
differs.

## `openEnum`

`@AgenticField(openEnum = …)`, default `false`, says whether clients of a field tolerate values
they do not know. Response enums are **closed by default**: adding an enum constant or allowed
value to a field is breaking, because a client that switches over the known values cannot handle
a new one. With `openEnum = true`, adding a value is compatible. Removing a value from a response
field is compatible either way. Setting `openEnum = true` on a field that is neither enum-typed
nor has `allowedValues` is a compile WARNING. See the [annotation guide](annotation-guide.md#open-and-closed-enums).

## Accepting a change: `atlasAccept`

The baseline is written only by `atlasAccept`, never by `build`:

```bash
./gradlew atlasAccept
```

The task compiles the main sources without the gate and writes their IR to the baseline path,
creating the directory. The file is byte-identical to the `api.ir.json` the processor emits for
the same sources and options. When the sources declare nothing, it writes the empty IR document.
It succeeds even when the gate or lock mode currently fails the build, and prints the accepted
differences grouped by classification. The next `build` then passes.

Accepting a breaking change is a deliberate decision to break clients of the published major.
Prefer the lifecycle declaration the error names; use `atlasAccept` when the change is intended,
for example before the major is first published.

## Lock mode

With `ai.atlas.contract.locked=true` (case-insensitive; default `false`; any other value is a
compile ERROR), **any** difference between the baseline and the current IR document fails the
build, compatible ones included. It covers every declaration and attribute, including elements
inactive at M and the document's own `apiMajor`. The error lists each differing element path.
A missing baseline is also an ERROR naming the expected path and `atlasAccept`.

Lock mode makes every contract change, even a description edit, go through `atlasAccept`, so each
one reaches review as a diff of the baseline.

## Configuration

### Gradle plugin

```kotlin
agentic {
    contractBaseline.set(file(".atlas/api.ir.json")) // default: <projectDir>/.atlas/api.ir.json
    contractLocked.set(true)                          // default: false
}
```

The plugin passes them as `ai.atlas.contract.baseline` (absolute path) and
`ai.atlas.contract.locked` to the main source set's `compileJava` only; no other compilation
receives them, so test sources are never compared against the main baseline. The baseline file is
an optional input of `compileJava`, so creating, editing or accepting it re-runs the gate.

The plugin and the processor must be the same ai-atlas version. `atlasContractCheck` and
`atlasAccept` call the processor found on the `annotationProcessor` classpath, so pinning
`agentic { version }` to a different release than the plugin's makes them fail with a message naming
both versions. Align `agentic { version }` with the plugin's version, or remove the pin.

### Processor options

Other build tools pass the options directly to javac:

```
-Aai.atlas.contract.baseline=/abs/path/to/.atlas/api.ir.json
-Aai.atlas.contract.locked=true
```

The `atlas` CLI takes them as `-A` options and the STDIO MCP server as `options`, and passes them
through unchanged. A gate failure is a failed generation: exit code 1 with `status: error` for
the CLI's `--json`, and an `isError` result for MCP.

## The empty-contract check: `atlasContractCheck`

Removing a module's last `@AgenticEntity` and `@AgenticExposed` removes its whole published
contract. javac does not invoke the processor at all for such a compilation, so the gate inside
`compileJava` cannot see it. The processor module therefore provides an empty-contract check that
compares an empty IR with the baseline using the gate's own rules and messages. The tools that run
the processor call it when compilation succeeded without emitting `api.ir.json` and a baseline or
lock option is set:

- **Gradle plugin:** the `atlasContractCheck` task runs after `compileJava`, and `classes` depends
  on it. It loads the check from the project's `annotationProcessor` classpath, so it runs the same
  ai-atlas version as the compilation. It never trusts a stale `api.ir.json` left in the output by
  an earlier build, and a build that fails the check fails again when re-run unchanged.
- **CLI and MCP server:** after a successful generation that emitted no IR.

**Enforcement point:** the check is enforced through `classes` and every task that depends on it:
`jar`, `test`, `assemble` and `build`. Running `compileJava` on its own does **not** run
`atlasContractCheck`. The gate for a non-empty contract still runs inside `compileJava`.

With no baseline configured and lock mode off, a compilation that declares nothing produces no
file and no diagnostic.

## The baseline in version control

Commit the baseline, `.atlas/api.ir.json` by default, next to the sources it describes:

- Every contract change reaches review as a diff of this one file, written by `atlasAccept` in the
  same change as the sources.
- Its `apiMajor` is the published major the gate protects. Accept after bumping
  `ai.atlas.api.major` to start protecting the new major.
- The IR covers one compilation: a project spanning several modules keeps one baseline per module.
- Do not edit it by hand; regenerate it with `atlasAccept`.

The repository's demo module commits its baseline as `demo/.atlas/api.ir.json`, and
`ContractBaselineTest` asserts it is byte-identical to the IR the demo build emits, so the demo's
contract cannot change without its baseline changing in the same commit.
