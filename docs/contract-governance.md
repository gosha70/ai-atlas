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
| `irVersion` | Version of the document format. This ai-atlas writes `4` |
| `apiBasePath` | The configured REST base path (`ai.atlas.api.basePath`) |
| `apiMajor` | The configured major (`ai.atlas.api.major`) the document was emitted for. In a baseline, this is the **published major M** the gate protects |
| `entities` | Every `@AgenticEntity`, ordered by qualified class name |
| `operations` | Every `@AgenticExposed` method, ordered by service qualified name, then method identity |

**Entity:** `className` (its identity), `dtoName`, `dtoPackage`, `displayName`, `description`,
`includeTypeInfo`, and `fields` in the order the generated DTO declares them.

**Field:** `name`, `displayName`, `javaType`, `collectionKind` (`NONE`, `COLLECTION`, `ITERABLE`,
`ARRAY`), `elementType`, `typeHint` (`@AgenticField(type = …)`), `reference` (the referenced
`entity` and its `dto`), `enumType`, `allowedValues` (explicit values or the enum constants),
`openEnum`, `sensitive`, `checkCircularReference`, `description`, `lifecycle`
(`sinceVersion`, `removedInVersion`, `deprecatedSinceVersion`, `deprecatedMessage`),
`constraints`, and `channels`.

**Operation:** `service`, `method`, `toolName`, `channels`, `description`, `rest` (`httpMethod`,
`path` without the base path and version prefix, `status` and `parameterIn`; `null` off the API
channel), `parameters` (`name`, `javaType`, `description`, `enumConstants`, `constraints`,
`required`), `returns` (`javaType`, `returnKind`, the effective `returnType` after
method-then-class resolution, its `reference`, and its `bound`), and `lifecycle`
(`apiSince`, `apiUntil`, `apiDeprecatedSince`, `apiReplacement`), and `hints`. An operation's
identity is its service, method name and parameter Java types: `service#method(parameter types)`.

**Constraints** are ai-atlas's own normalised form of an input's or field's effective constraints
(see [Constraints and behavioural hints](constraints-and-hints.md)), not a schema dialect. The
object holds only the keys that are set, in this order: `minimum`, `exclusiveMinimum`, `maximum`,
`exclusiveMaximum`, `minLength`, `maxLength`, `minItems`, `maxItems`, `patterns`, `notBlank`.
`minimum`/`maximum` are decimal strings; `exclusiveMinimum`/`exclusiveMaximum` and `notBlank` appear
only when `true`; `patterns` is a list of `{regex, flags}` objects sorted by regex, then flags.
A parameter's `required` is its resolved requiredness. **Hints** hold `readOnly`, `destructive`,
`idempotent` and `openWorld`, each `true`, `false` or absent when undeclared. The IR records
constraints and hints whether or not `ai.atlas.constraints` is on.

A field's **`channels`** are the channels whose responses carry it, sorted and always present:
`["AI", "API"]`, `["AI"]` or `["API"]`. They record its **effective** eligibility, which is
`["AI", "API"]` for every field unless `ai.atlas.projections` is on and the field declares fewer
with `@AgenticField(channels)` (see [Per-channel field projections](channel-projections.md)). The
IR records the declaration only: it never records the name of an AI record, which is derived and
never reaches the wire.

An API operation's **REST mapping** records the **effective** mapping the controller and the
OpenAPI document serve, always present on the API channel:

```json
"rest": {
  "httpMethod": "POST",
  "path": "/order-service/find-by-status",
  "status": 200,
  "parameterIn": ["QUERY"]
}
```

`status` is the success status, a 2xx code. `parameterIn` holds one location per parameter, in
declaration order: `PATH`, `QUERY` or `BODY`. It sits in `rest` rather than on each parameter
because MCP operations share the parameter record and have no REST mapping. A `path` segment may be
a `{name}` variable. Today every mapping is the RPC one: `200`, with every parameter a query
parameter (Phase 0's canonical form).

Every operation's return records its **`bound`**, the effective bound on its result, always
present:

```json
"bound": {
  "style": "NONE",
  "envelope": "NONE",
  "limitParameter": null,
  "cursorParameter": null,
  "maxResults": null
}
```

- `style` is how clients page the result: `PAGEABLE` (a Spring Data `Pageable` parameter), `LIMIT`
  (a declared limit parameter, with an optional cursor), `DECLARED` (`maxResults` alone) or `NONE`.
- `envelope` is the wire shape clients receive: `PAGE`, `SLICE` or `NONE` for the plain result.
- `limitParameter` and `cursorParameter` name the parameters with those paging roles; for
  `PAGEABLE`, `limitParameter` names the `Pageable`.
- `maxResults` is at least 1, or `null`. For a paged style (`PAGEABLE`, `LIMIT`) it is the
  **page-size ceiling**, the largest page clients may request. For any other style it is the most
  results the operation returns.

Today every operation records `NONE`/`NONE` with `null` parameters and `maxResults`.

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
readable, by chaining the migrations.

### Migration from `irVersion` 1 to `irVersion 2`

`irVersion 2` adds `Field.constraints`, `Parameter.constraints`, `Parameter.required` and
`Operation.hints`. A version-1 baseline is migrated in memory with every one of these slots
**unknown**: JSON `null`, which is distinct from an empty object (known to set nothing). A
document that declares `irVersion 2` in its file and has a missing or `null` slot is malformed.
That check applies only when a file is read; a version-1 baseline's unknown slots are the result of
its migration, and are never rejected.

The gate never reports a change from an unknown baseline value, in gate mode or in lock mode. So
upgrading ai-atlas does not fail a project, even a locked one, whose baseline is still version 1.
Run `atlasAccept` to write a current baseline; from then on, constraint changes are compared.

### Migration from `irVersion` 2 to `irVersion 3`

`irVersion 3` adds `Field.channels`. Nothing before it could narrow a field's channels, so the
migration is **exact**, not unknown: every field of a version-1 or version-2 baseline is migrated to
`["AI", "API"]`. A version-2 baseline therefore shows no difference against the same contract built
with `ai.atlas.projections` off, in gate mode or lock mode, and needs no special case. `atlasAccept`
writes version 3.

A document that declares `irVersion 3` and has a field whose `channels` is missing, `null`, empty,
unsorted, repeated, or names anything other than `AI` and `API` is malformed.

### Migration from `irVersion` 3 to `irVersion 4`

`irVersion 4` adds `returns.bound` to every operation, and `rest.status` and `rest.parameterIn`
to every API operation. Before it, every API operation answered `200` with every parameter in the
query, and no operation could declare a bound or produce a paging envelope. So the migration is
**exact**: every operation of a version-1, -2 or -3 baseline gets the bound `NONE`/`NONE` with
`null` parameters and `maxResults`, and every mapping gets `status` `200` and `QUERY` for each
parameter. Such a baseline shows no difference against the same contract, in gate mode or lock
mode, with no special case. `atlasAccept` writes version 4.

A document that declares `irVersion 4` is malformed when a `returns` has no `bound` object or a
`bound` lacks any of its five keys, or a `rest` lacks `status` or `parameterIn`. It is also
malformed when a `style` or `envelope` is unknown, `maxResults` is below 1, `status` is not 2xx,
a location is not `PATH`, `QUERY` or `BODY`, or `parameterIn` does not hold one location per
parameter.

A document that contradicts itself is malformed too, as a hand-edited baseline could otherwise hide
a change from the gate:

- a bound whose parameters do not fit its style: `NONE` with a limit or cursor parameter, `LIMIT`
  without a `limitParameter`, a `cursorParameter` on any style other than `LIMIT`, or `DECLARED`
  without `maxResults`;
- a `limitParameter` or `cursorParameter` that names none of the operation's parameters;
- a `PATH` parameter whose name no `{name}` variable of the path carries, or more than one `BODY`;
- a `rest` object on an operation without the `API` channel, or `rest: null` on one with it.

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
| A parameter's name changes, unless the rule below applies | **Breaking** |
| A `PATH` or `BODY` parameter is renamed, on an operation served only on the API channel on both sides | Compatible: REST clients never send its name. On an AI operation it stays breaking, as MCP clients pass arguments by name |
| A channel is removed from an operation | **Breaking** |
| The MCP tool name changes | **Breaking** |
| The REST HTTP method or path changes | **Breaking** |
| The REST path changes only in the names of its `{name}` variables, each position still binding the same parameter | Compatible |
| A parameter's REST location (`parameterIn`) changes | **Breaking** |
| The OpenAPI `operationId` changes, including a rename caused only by another operation being added (a second API-exposed `find()` in another service turns the existing `find` into a qualified ID) | **Breaking** |
| A value is removed from an input parameter's enum constants | **Breaking** |
| An operation is added, provided no existing operation's `operationId`, REST path, HTTP method or MCP tool name changes as a result | Compatible |
| A channel is added | Compatible |
| A value is added to an input parameter's enum constants | Compatible |
| A description or deprecation changes | Compatible |

There is no rename detection: a rename is a removal plus an addition, and is reported as such.

### REST status and result bounds

The `irVersion 4` slots, for operations active at M in both documents. Each change is named after
its IR key: `rest.status`, `rest.parameterIn[<index>]` (above) and `returns.bound.<key>`.
`maxResults` means two things, so it is reported with a direction: as an **output** it is the
result bound of a non-paged style (`DECLARED`, `NONE`), and as an **input** the page-size ceiling of
a paged style (`PAGEABLE`, `LIMIT`).

| Change | Classification |
|--------|----------------|
| `rest.status` changes | **Breaking** (output) |
| The envelope changes | **Breaking** (output) |
| A result bound appears or falls | Compatible |
| A result bound disappears or rises | **Breaking** (output) |
| A page-size ceiling rejects a page size the baseline accepted (below) | **Breaking** (input) |
| Any other change to a page-size ceiling | Compatible |
| A paging role is declared or removed on an existing parameter (`style`, `limitParameter`, `cursorParameter`) | `informational` |

A `Pageable` or limit parameter added or removed is already breaking through the operation's
identity.

**Page-size ceilings and a limit parameter's maximum.** A `LIMIT` parameter can carry its own
Phase 3 maximum, such as `@Max(100)`, which the constraint rules above already gate as
`maximum`. So that each real input change is reported once:

- a `LIMIT` ceiling at or above its limit parameter's effective maximum on the same side adds
  nothing, and counts as no ceiling. Declaring `@AgenticParam(paging = LIMIT)` on
  `find(@Max(100) int limit)` with a ceiling of 100 reports only the informational paging role;
- the ceiling is compared only when what it adds differs between the two sides, or when it limits
  another parameter. `@Max(100)` with a ceiling of 100 becoming `@Max(50)` with a ceiling of 50 is
  one breaking `maximum` change;
- a ceiling that remains is **breaking** when it is below every page size the baseline accepted
  for the parameter it limits: the baseline ceiling, only if it limited the same parameter, and
  that parameter's baseline maximum. A `Pageable` ceiling of 50 moving to a previously unlimited
  `size` parameter is breaking at 80 as at 50, as `size=90` was accepted. Otherwise it is
  compatible. `@Max(50)` with no ceiling becoming `@Max(100)` with a ceiling of 80 rejects nothing
  that was accepted, so both changes are compatible.

**Crossing between paged and non-paged styles.** `maxResults` changes meaning, so each direction is
compared on its own. `DECLARED` with 50 becoming `LIMIT` with a ceiling of 50 is:

- a result bound disappearing: **breaking** (output);
- no input change when the limit parameter's maximum is 50 or less, as its constraint already
  rejected larger page sizes;
- otherwise a ceiling appearing below what the baseline accepted: **breaking** (input).

The reverse, `PAGEABLE` or `LIMIT` becoming `DECLARED`, is a result bound appearing and a ceiling
disappearing, both compatible; a changed envelope is still breaking.

### Constraints, requiredness and hints

A parameter's requiredness and constraints are input rules, compared per parameter at M. **Bounds
are compared as endpoints**, value and exclusivity together:

- an absent lower bound is −∞, and an absent upper bound is +∞;
- on an integral Java type (`byte`, `short`, `int`, `long`, their boxes, `BigInteger`), an exclusive
  bound is first made inclusive: `> 9` becomes `>= 10` and `< 10` becomes `<= 9`, so `> 9` and
  `>= 10` are no difference;
- a lower endpoint is tighter when its value is higher, or equal and exclusive; an upper endpoint
  when its value is lower, or equal and exclusive. `>= 10` → `> 0` widens; `> 0` → `>= 10` narrows;
  on a decimal, `>= 0` → `> 0` narrows.

| Change | Classification |
|--------|----------------|
| A parameter's `required` goes from `false` to `true` | **Breaking** |
| A lower or upper endpoint becomes tighter | **Breaking** |
| `minLength` or `minItems` rises or appears; `maxLength` or `maxItems` falls or appears | **Breaking** |
| A pattern is added to the set, including a changed pattern (a removal plus an addition) | **Breaking** |
| `notBlank` goes from absent to set | **Breaking** |
| The reverse of each of these, including a pattern removed from the set | Compatible |
| An entity field's constraints change | `informational` |
| An operation's hints change | `informational` |
| Any change from an unknown baseline value (a migrated version-1 baseline) | No difference |

An **`informational`** difference appears in `contract-diff.json` but produces no diagnostic and
never fails the build, except in lock mode. Entity fields are always output: an operation's inputs
are its parameters, bound as query parameters, and ai-atlas generates no request body, so an entity
field's constraints only describe what responses already satisfy. Hints are client guidance.
Neither breaks a client.

### Field channels

For each field active at M in both documents, per channel C (`AI` or `API`), reported as the change
`channels.C` with the field's channels before → after:

| Change | Classification |
|--------|----------------|
| The field loses C, and its entity is **reachable** on C in the baseline | **Breaking** (output) |
| The field loses C, and its entity is not reachable on C | Compatible |
| The field gains C | Compatible |
| An entity's AI record appears or disappears (the change `aiRecord`, `shared` → `separate` or back) | `informational` |

An entity is **reachable on C** when an operation active at M on channel C returns it, or when a
field on C of a reachable entity refers to it, directly or through a collection, iterable or array. A field of
an entity no client of C can receive is invisible to that channel, so narrowing it breaks no one.
Turning `ai.atlas.projections` on with a declaration is gated like any other change: a baseline
written with the flag off records every field on both channels.

An entity's AI record is the separate record MCP tools return when its AI and API projections
differ. Its name never reaches the wire, so it appearing or disappearing is informational. The DTO
name, which REST, OpenAPI and Java clients use, keeps the breaking `dtoName` rule.

### Diagnostics

Each breaking difference is a compile ERROR, reported on the declaration when it still exists in
the compilation and without an element otherwise. The message names:

- the element path: `entity <qualified class>`, `field <qualified class>#<java name>`,
  `operation <qualified class>#<method>(<parameter types>)` or, for a constraint or requiredness,
  `parameter <qualified class>#<method>(<parameter types>).<name>` with the constraint key;
- the change, as before → after;
- the direction, and why it breaks clients of major M;
- the declaration that would make it legitimate:

| Breaking change | Remedy named in the error |
|-----------------|---------------------------|
| Removed or changed field, including its effective schema | `@AgenticField(removedInVersion = M+1)` on the old field, with any replacement as a new field with `sinceVersion = M+1` |
| Removed or changed operation, including its effective return schema | `@AgenticExposed(apiUntil = M)` on the old operation, plus a replacement with `apiSince = M+1` |
| Narrowed input constraint or newly required parameter | A replacement operation with `apiSince = M+1` (and `apiUntil = M` on the old one) |
| Changed `operationId` | Give the operation(s) whose addition caused it a method name that does not collide, or `apiSince = M+1` on them |
| A field losing a channel it is reachable on | Publish it in a new major (`ai.atlas.api.major = M+1`, then `atlasAccept`): a field's channels have no lifecycle of their own |
| A changed REST status or parameter location, result bound, page-size ceiling or envelope | Publish it in a new major (`ai.atlas.api.major = M+1`, then `atlasAccept`): a REST mapping and a result bound have no lifecycle of their own |
| Changed DTO name, DTO package, `includeTypeInfo` or `apiBasePath` | Restore the previous value |
| Added value on a closed response enum | `openEnum = true`, if clients tolerate unknown values |
| Empty contract | Restore the annotations, or accept the removal |

In every case, the change can also be accepted explicitly with `atlasAccept`. Compatible
differences produce no diagnostic.

Whenever a comparison runs, the processor also writes `META-INF/ai-atlas/contract-diff.json` to
the class output: `publishedMajor` and every difference with its `path`, `change`, `direction`,
`before`, `after` and `classification` (`breaking`, `compatible` or `informational`), ordered by
element path. The list is empty when nothing
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

Every constraint, requiredness and hint difference counts in lock mode, `informational` ones
included, except a change from an unknown value in a migrated version-1 baseline. So does every
change to a field's `channels`, compatible or not.

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
receives them, so test sources are never compared against the main baseline. `agentic { projections }`
also reaches `atlasAcceptCompile`, because it decides the channels the accepted baseline records
(see [Per-channel field projections](channel-projections.md#the-aiatlasprojections-flag)). The baseline file is
an optional input of `compileJava`, so creating, editing or accepting it re-runs the gate.

The plugin and the processor must be the same ai-atlas version. `atlasContractCheck` and
`atlasAccept` call the processor found on the `annotationProcessor` classpath, so pinning
`agentic { version }` to a different release than the plugin's makes them fail with a message naming
both versions. Align `agentic { version }` with the plugin's version, or remove the pin: the plugin
then uses its own version.

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
