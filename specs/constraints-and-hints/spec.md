---
spec_mode: full
feature_id: constraints-and-hints
risk_category: schema
status: draft
date: 2026-09-26
---

# Spec: Constraints reach REST and MCP; MCP behavioural hints (epic #23, Phase 3)

<!-- Project constitution: shared/skills/ — copilot-conventions, coding-standards, safety -->
<!-- Origin: GitHub issue gosha70/ai-atlas#43 (child of #23) + specs/constraints-and-hints/origin/2026-09-26-owner-decisions.md. See plan.md `origin:` frontmatter. -->

The generated contract describes the *shape* of inputs but not their *limits*:
- An agent calling an MCP tool sees no minimum, maximum, length or pattern, even when the service
  rejects values outside them. Spring AI 1.1.7 drops every Bean Validation constraint from the
  `@Tool`-derived input schema.
- OpenAPI parameter schemas carry no constraints either.
- MCP tools declare nothing about being read-only, destructive, idempotent or open-world, and
  `@Tool` cannot carry those hints at all.
- The Contract IR has no constraint slots, so the Phase 2 gate cannot see an input constraint being
  narrowed.

This feature makes constraints and behavioural hints part of the contract:
- One **constraint model** reads Jakarta Bean Validation and ai-atlas overrides, and records the
  result in the Contract IR (`irVersion` 2).
- The **gate** classifies constraint changes by direction.
- Behind one opt-in flag, the **generated surfaces** carry the constraints and the explicitly
  declared hints: OpenAPI, the REST controllers, the MCP tool classes and a per-tool MCP
  specification.
- The **runtime** serves those specifications and enforces the constraints on MCP calls.

A feasibility spike on branch `claude/phase3-mcp-spike` (`spike/phase3-mcp/REPORT.md`) proved the
MCP registration design on Spring AI 1.1.7 with MCP SDK 0.18.2.

Owner decisions of 2026-09-26, recorded in the origin transcript:
- an override replaces only the constraints it sets;
- hints are explicit-only;
- keep `@Tool` and register generated tool specifications;
- enforcement option A (the generated MCP tool class is `@Validated`);
- `irVersion` 2 with *unknown* constraints for migrated baselines;
- one opt-in flag, `ai.atlas.constraints`;
- SYNC MCP servers only.

## User Scenarios

### US1: Constraints are one contract model (Priority: HIGH)

**Given** service parameters and entity fields carrying Bean Validation annotations and ai-atlas overrides
**When** the sources are compiled, with or without the flag
**Then** the Contract IR (`irVersion` 2) records each input's and output's effective constraints and requiredness. Contradictions fail compilation, and an input override looser than Bean Validation warns.

### US2: The gate sees constraint changes (Priority: HIGH)

**Given** a baseline written by this feature, or a migrated `irVersion` 1 baseline
**When** a developer narrows an input constraint or makes an input required
**Then** the build fails naming the parameter, the constraint and before → after. Widening passes. A migrated baseline's unknown constraints never cause a failure, in either gate mode or lock mode.

### US3: With the flag, generated surfaces carry constraints and hints (Priority: HIGH)

**Given** `ai.atlas.constraints=true`
**When** the sources are compiled
**Then** OpenAPI parameter and DTO schemas carry the constraints and requiredness. The REST controllers honour optional parameters. The MCP tool classes carry the contract constraints and are `@Validated`. A per-tool MCP specification is generated with the input schema and the declared hints. Without the flag, all generated output is byte-identical to before.

### US4: The runtime serves and enforces them (Priority: HIGH)

**Given** an application built with the flag
**When** an MCP client lists and calls tools, over SSE or Streamable HTTP
**Then** `tools/list` shows each tool's constraints and declared hints, and a call that violates a constraint returns a tool error without reaching the service. Every tool name is registered once, and #42's proxy behaviour holds.

### US5: The rules are documented (Priority: MEDIUM)

**Given** the documentation
**When** a user reads it
**Then** it covers:
- the constraint sources and precedence;
- requiredness;
- behavioural hints, as guidance and not authorization;
- the flag;
- enforcement and its optional dependency;
- the `irVersion` 2 migration and the new gate rules.

## Requirements

### The constraint model (US1)

- **FR-001**: The `annotations` module MUST gain two annotations, and keep zero dependencies:
  - `@AgenticParam`, targeting method parameters. It has `description` (default `""`) and
    `required`, of a new enum `Requiredness { DEFAULT, REQUIRED, OPTIONAL }`, default `DEFAULT`.
  - `@AgenticConstraints`, targeting fields and method parameters, with these attributes. Each has
    a default that means "not set".
    - `minimum` and `maximum`: decimal strings, default `""`;
    - `exclusiveMinimum` and `exclusiveMaximum`: booleans, default `false`;
    - `minLength`, `maxLength`, `minItems` and `maxItems`: ints, default `-1`;
    - `pattern`: a string, default `""`.
- **FR-002**: The processor MUST read Jakarta Bean Validation annotations on `@AgenticField`
  fields and on the parameters of exposed methods. It identifies them by qualified name, with no
  compile-time dependency on the validation API, and normalises them as follows:
  - `@Min`/`@DecimalMin` become `minimum`, and `@Max`/`@DecimalMax` become `maximum`. A `@DecimalMin`/`@DecimalMax` with `inclusive = false` sets `exclusiveMinimum`/`exclusiveMaximum`.
  - `@Positive` is `minimum 0` with `exclusiveMinimum`; `@PositiveOrZero` is `minimum 0`.
  - `@Negative` is `maximum 0` with `exclusiveMaximum`; `@NegativeOrZero` is `maximum 0`.
  - `@Size` becomes `minLength`/`maxLength` on a `String`, and `minItems`/`maxItems` on a collection or array. Its default bounds (`0` and `Integer.MAX_VALUE`) are not recorded.
  - `@Pattern` becomes `pattern`.
  - `@NotNull` makes the input required. `@NotBlank` makes it required with `minLength 1` and `pattern ".*\S.*"`. `@NotEmpty` makes it required with `minLength 1` (a string) or `minItems 1` (a collection or array).
  - Other constraint annotations, including composed ones, are ignored.
- **FR-003**: The effective contract of an input or field MUST be resolved per constraint key:
  - `@AgenticConstraints` replaces only the keys it sets, and every other Bean Validation key still applies.
  - Requiredness comes from `@AgenticParam(required)` when it is not `DEFAULT`; `@AgenticParam(description)`, when non-empty, becomes the parameter's description.
  - Otherwise a parameter is required when it is a primitive, or carries `@NotNull`, `@NotBlank` or `@NotEmpty`.
  - Otherwise it is required, as REST query parameters are today.
- **FR-004**: The processor MUST report:
  - A compile **ERROR** on the element for a self-contradiction:
    - `minimum > maximum`, or equal bounds with either exclusive;
    - a negative length or item bound, or a `min` above its `max`;
    - a `pattern` that does not compile as a Java regex;
    - an exclusive flag without its bound;
    - a length attribute on a non-string, or an item attribute on a non-collection, non-array;
    - `minimum`/`maximum` on a non-numeric type;
    - `Requiredness.OPTIONAL` on a primitive, or together with `@NotNull`, `@NotBlank` or `@NotEmpty`.
  - A **WARNING** on the parameter when an `@AgenticConstraints` value on an *input* is looser than
    the Bean Validation constraint it replaces, naming the key and both values. Looser means a lower
    minimum, a higher maximum, a wider length or item range, or a removed pattern.
- **FR-005**: The Contract IR MUST move to `irVersion` 2:
  - `Field` gains `constraints`.
  - `Parameter` gains `constraints` and `required` (boolean).
  - `Operation` gains `hints`, with `readOnly`, `destructive`, `idempotent` and `openWorld`, each `true`, `false` or absent.
  - `constraints` is an object with only the keys that are set, in the fixed order `minimum`, `exclusiveMinimum`, `maximum`, `exclusiveMaximum`, `minLength`, `maxLength`, `minItems`, `maxItems`, `pattern`.
  - Output stays deterministic under Phase 2's FR-003 rules.
  - The IR records constraints and hints whether or not the flag is on.
- **FR-006**: Reading a baseline MUST accept `irVersion` 1 and 2:
  - A version-1 document is migrated in memory, with every `constraints`, `required` and `hints` value *unknown*: JSON `null` in the model, distinct from an empty object.
  - `irVersion` above 2 stays an error, as in Phase 2's FR-004.
  - A version-2 document with a missing or `null` constraint slot is malformed.
- **FR-007**: The demo's committed baseline `demo/.atlas/api.ir.json` MUST be regenerated as
  `irVersion` 2 in the same change that moves the IR to version 2. Phase 2's `ContractBaselineTest`
  MUST stay green.

### The gate (US2)

- **FR-008**: For inputs (operation parameters), compared at the baseline's published major:
  - **Breaking:**
    - a parameter's `required` goes from `false` to `true`;
    - a new key is set;
    - `minimum` rises, `maximum` falls, or either becomes exclusive;
    - `minLength` or `minItems` rises;
    - `maxLength` or `maxItems` falls;
    - `pattern` is added or changed.
  - **Compatible:** the reverse of each of these.
  - **Never a difference:** any change from an *unknown* baseline value.
- **FR-009**: Differences in output constraints (entity fields) and in hints MUST be classified as
  `informational`. They appear in `contract-diff.json` and produce no diagnostic, except in lock mode.
- **FR-010**: In lock mode, every constraint, requiredness and hint difference is a difference,
  except a change from an *unknown* baseline value. So upgrading ai-atlas does not fail a locked
  project that has not yet accepted a version-2 baseline.
- **FR-011**: Each breaking constraint diagnostic MUST name:
  - the path `parameter <qualified class>#<method>(<parameter types>).<name>`;
  - the constraint key, as before → after;
  - that it narrows an input for clients of major M;
  - the legitimising declaration: a replacement operation with `apiSince = <M+1>`, or accepting the change with `atlasAccept`.

### Generated surfaces behind the flag (US3)

- **FR-012**: The processor MUST accept the option `ai.atlas.constraints`: `true` or `false`,
  case-insensitive, default `false`. Any other value is a compile ERROR naming the option and the
  value. With the option `false`, every generated source and resource MUST be byte-identical to
  before this feature, except the IR files, which the golden snapshot excludes. The golden snapshot
  test proves this.
- **FR-013**: `@AgenticExposed` MUST gain `readOnly`, `destructive`, `idempotent` and `openWorld`,
  of a new enum `Hint { UNSET, TRUE, FALSE }`, each default `UNSET`.
  - A method-level value other than `UNSET` overrides the class-level one.
  - Nothing is inferred from method names, parameters or HTTP method.
  - With the flag on, an AI-channel method whose four resolved hints are all `UNSET` gets a
    WARNING naming the method, which becomes an ERROR under `ai.atlas.strict`.
- **FR-014**: With the flag on, the OpenAPI document MUST describe:
  - each query parameter's `required` from FR-003, and its constraints as JSON-Schema keywords on the parameter schema;
  - each DTO property's field constraints on the component schema.
- **FR-015**: With the flag on, a generated REST controller MUST bind an `OPTIONAL` parameter with
  `@RequestParam(required = false)`. Required parameters are unchanged.
- **FR-016**: With the flag on, each generated MCP tool class MUST:
  - set `@ToolParam(required = …)` from FR-003;
  - carry each parameter's effective contract constraints as Jakarta Bean Validation annotations;
  - be annotated `@Validated`.

  When `jakarta.validation.constraints.NotNull` does not resolve in the compilation, the processor
  MUST NOT emit the Bean Validation annotations or `@Validated`. It MUST instead report one NOTE
  for the compilation saying the MCP constraints will be advisory.
- **FR-017**: With the flag on, the processor MUST write `META-INF/ai-atlas/mcp-tools.json`. It has one
  entry per AI-channel tool active at the configured major, ordered by tool name, and each entry
  holds:
  - the tool name;
  - `inputSchema`: an object schema with `properties` (one per parameter, with its JSON type and
    constraints, all inside `properties`, never at the root), `required`, and
    `additionalProperties: false`;
  - `annotations`, holding only the hints declared `TRUE` or `FALSE`.

  The file is deterministic under Phase 2's FR-003 rules. With the flag off, it is not written.

### The runtime (US4)

- **FR-018**: The runtime MUST register MCP tools through one lazy `List<SyncToolSpecification>`
  bean instead of `LazyToolCallbackProvider`.
  - It builds callbacks with `MethodToolCallbackProvider` over the same beans #42's scan selects,
    and keeps #42's JDK-proxy skip and warning.
  - For a tool listed in any `META-INF/ai-atlas/mcp-tools.json` on the classpath, it rebuilds the
    `McpSchema.Tool` with that `inputSchema` and `annotations`. Every other tool keeps its derived
    schema, without hints.
  - Each tool name is registered exactly once.
  - Two resources listing the same tool name fail startup with a message naming both resources.
  - This applies to the SYNC server type. With ASYNC or STATELESS, the runtime keeps today's
    derived registration and logs one INFO line saying schemas and hints are not applied.
- **FR-019**: When any `mcp-tools.json` is present and the application context has no
  `MethodValidationPostProcessor` bean, the runtime MUST log one WARNING at startup. The WARNING
  says MCP constraints are advisory and names `spring-boot-starter-validation`.
  `spring-boot-starter-validation` MUST NOT become a production dependency of any ai-atlas module.
- **FR-020**: The Gradle plugin's `agentic { }` extension MUST gain a `constraints` boolean
  (default `false`). The plugin passes it to the main `compileJava` as `ai.atlas.constraints`, next
  to the Phase 2 contract options.

### Documentation and build (US5)

- **FR-021**: The documentation MUST cover the feature, as follows.
  - A new `docs/constraints-and-hints.md` covering:
    - the constraint sources and the FR-002 mapping table;
    - precedence and requiredness;
    - the contradiction errors and the looser-override warning;
    - `@AgenticParam` and `@AgenticConstraints`;
    - hints, stating that they are client guidance, not authorization;
    - the `ai.atlas.constraints` flag;
    - enforcement and its optional dependency;
    - the SYNC-only scope.
  - `docs/contract-governance.md`: the `irVersion` 2 format, the migration and the new gate rules.
  - `CHANGELOG.md`: entries under `[Unreleased]`.
- **FR-022**: `./gradlew build` MUST pass with Gradle on JDK 17 and, separately, on JDK 21, run by
  `scripts/build-on-jdk-matrix.sh`.

## Constraints / What NOT to Build

- No change to any generated source or resource while the flag is off (FR-012), except the IR files.
- No inference of hints, and no behaviour invented for the service (epic design constraint).
- No `@McpTool`, no fork of Spring AI's auto-configuration, and no new production dependency.
- No schemas or hints for ASYNC or STATELESS MCP servers.
- No per-channel projections, collection safety, REST metadata or release snapshots (Phases 4–5).
- No network, database, model call or live service in the processor or the gate.

## Key Entities

- **Effective contract (of an input or field):** the constraints and requiredness after FR-003's precedence.
- **Constraint keys:** `minimum`, `exclusiveMinimum`, `maximum`, `exclusiveMaximum`, `minLength`, `maxLength`, `minItems`, `maxItems`, `pattern`.
- **Unknown:** the value of every constraint, requiredness and hint slot in a migrated `irVersion` 1 baseline.
- **Tool specification:** an entry of `META-INF/ai-atlas/mcp-tools.json`, registered by the runtime as a `SyncToolSpecification`.

## Success Criteria

1. **US1 / FR-001–FR-007:**
   - a fixture mixing Bean Validation and overrides yields the expected IR version 2 constraints;
   - each contradiction errors, and a looser override warns;
   - a version-1 baseline migrates with unknown slots;
   - the demo baseline is version 2 and its test passes.
2. **US2 / FR-008–FR-011:**
   - narrowing a `@Max`, or making a parameter required, fails, naming the path and before → after;
   - widening passes;
   - a migrated baseline passes in gate and lock mode;
   - output-constraint and hint changes are informational.
3. **US3 / FR-012–FR-017:**
   - the golden snapshot is unchanged with the flag off;
   - with the flag on, OpenAPI, REST, MCP classes and `mcp-tools.json` match fixtures, and hints appear only when declared.
4. **US4 / FR-018–FR-020:** over SSE and Streamable HTTP:
   - `tools/list` shows the constraints and declared hints;
   - a violating call returns a tool error without reaching the service;
   - duplicate names fail startup;
   - `ProxiedToolBeanTest` passes;
   - the advisory WARNING appears without method validation.
5. **US5 / FR-021–FR-022:** the docs check passes, and the build is green on JDK 17 and 21.
