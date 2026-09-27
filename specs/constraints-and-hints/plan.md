---
spec_mode: full
feature_id: constraints-and-hints
risk_category: schema
justification: "Moves the persisted Contract IR to irVersion 2 with a migration, adds gate rules that can fail consumers' builds, adds two annotations and hint attributes, changes generated OpenAPI/REST/MCP shapes behind a flag, and replaces the runtime MCP registration path — schema-level, multi-module, consumer-visible."
status: approved
date: 2026-09-26
collaboration_mode: single
origin:
  issue: gosha70/ai-atlas#43
  transcripts:
    - specs/constraints-and-hints/origin/2026-09-26-owner-decisions.md
  origin_claim: |
    Epic gosha70/ai-atlas#23 §5 asks to "consume Jakarta Bean Validation constraints where present
    on entity fields and method parameters", to "support an AI-ATLAS parameter-level override (for
    example @AgenticParam)", and to "normalise all of the above into the Contract IR", with
    criteria that "constraints on service parameters reach the MCP inputSchema", "contradictory
    constraints fail at compile time" and "requiredness is represented explicitly and
    consistently". §6 asks to "emit MCP behavioural hints such as read-only, destructive,
    idempotent, and open-world", with "explicit annotation values override inferred defaults" and
    "documentation states that behavioural hints are client guidance, not authorization"; its
    design constraints require that "changes that alter generated public shapes should ship behind
    an opt-in compatibility flag". Issue #43, created from the owner-approved draft on 2026-09-26,
    fixes the decisions: per-key override precedence, explicit-only hints, @Tool kept with
    generated tool specifications registered as SyncToolSpecification, enforcement by a @Validated
    generated tool class, irVersion 2 with unknown slots for migrated baselines, one flag
    ai.atlas.constraints, SYNC servers only.
---

# Implementation Plan: Constraints reach REST and MCP; MCP behavioural hints (epic #23, Phase 3)

**Branch**: `feature/constraints-and-hints`
**Input**: specs/constraints-and-hints/spec.md

## Summary

1. Read Jakarta Bean Validation and two new ai-atlas annotations into one per-key constraint model.
2. Record the model in Contract IR version 2, which migrates version-1 baselines with unknown slots.
3. Add direction-aware gate rules for constraint changes.
4. Behind `ai.atlas.constraints`, carry the constraints and explicit hints into OpenAPI, the REST
   controllers, the MCP tool classes (`@Validated`) and a generated `mcp-tools.json`.
5. Have the runtime register every MCP tool through one lazy `List<SyncToolSpecification>`, which
   applies the generated schemas and hints.

## Technical Context

**Language/Version**: Java 17 toolchain (CI also builds on JDK 21), Gradle Kotlin DSL.
**Primary Dependencies**:
- `modules/annotations`: new `AgenticParam`, `AgenticConstraints`, `Requiredness`, `Hint`, and hint attributes on `AgenticExposed`;
- `modules/processor`: `contract/*` (IR, JSON, comparison, gate), `generator/*`, `AgenticProcessor`, and a new `constraints` package;
- `modules/runtime`: `mcp/AgenticMcpConfiguration`;
- `modules/gradle-plugin`: `AgenticExtension`, `AgenticPlugin`, `ContractArguments` (or a sibling argument provider);
- `demo`: the baseline only.

Spring AI 1.1.7 and MCP SDK 0.18.2 are already runtime dependencies, and Jackson already backs the
processor. There is no new production dependency.
**Testing**: JUnit 5 + AssertJ; processor compile-testing; the Phase 2 golden snapshot; runtime
Spring Boot tests over SSE and Streamable HTTP (as in `SseUnchangedTest`, `StreamableHttpTransportTest`
and `ProxiedToolBeanTest`); Gradle TestKit. Gate: `./gradlew build` plus
`scripts/build-on-jdk-matrix.sh` (FR-022).
**Constraints**:
- byte-identical generated output with the flag off (FR-012);
- `annotations` keeps zero dependencies;
- the processor stays `aggregating`, with its supported annotation types unchanged;
- everything offline.

## Constitution Check

| Rule file | Concern | Status |
|-----------|---------|--------|
| `coding-standards.md` | Option keys as constants beside `OPT_CONTRACT_LOCKED`; diagnostics prefixed `[ai-atlas]`; constraint keys as constants | OK |
| `safety.md` | Regex patterns compiled at build time to validate, never executed against data by the processor; option values validated | OK |
| `copilot-conventions.md` | One logical change per commit; no new production dependency | OK |

## Architecture Decisions

### ADR-1: Two small annotations, not attributes on `@AgenticField`

**Context**: Constraints need an override on both fields (outputs) and parameters (inputs). A
parameter also needs requiredness and a description.
**Decision**:
- `@AgenticConstraints` targets `FIELD` and `PARAMETER` and holds only the constraint keys.
- `@AgenticParam` targets `PARAMETER` and holds `description` and `required`.
- Unset sentinels are `""` for strings and `-1` for ints; bounds are decimal strings, so
  `@DecimalMin` fits without precision loss.

**Consequences**:
- One vocabulary for fields and parameters.
- `@AgenticField` gains nothing, so its surface stays small.
- A sentinel-based API needs FR-004's type checks, because the compiler cannot enforce "set".

### ADR-2: Normalise to JSON-Schema keywords in one place

**Context**: The same constraints feed the IR, OpenAPI, the MCP schema and the generated Bean
Validation annotations.
**Decision**: A new `constraints` package has:
- `ConstraintReader`, which reads Bean Validation by qualified name and applies overrides;
- `EffectiveConstraints`, which holds the keys in FR-005 order;
- the checks for FR-004.

`IrBuilder` stores the result, and every generator reads it from the projection. No generator reads
annotations itself.

**Consequences**: Surfaces cannot disagree, extending Phase 2's "one model, many projections".

### ADR-7: One normalised model, two schema dialects, whole-string regex (review of PR #44)

**Context**: Review of PR #44 at `d701c4d` found four gaps.
- OpenAPI 3.0.3 writes exclusive bounds as booleans. JSON Schema 2020-12, which MCP uses for tool input schemas, writes them as numbers.
- Bean Validation's `@Pattern` must match the *whole* value. A JSON Schema `pattern` matches a *substring*, so `[A-Z]+` rejects `xABCy` in validation but accepts it in the schema.
- Several Bean Validation constraints on one input had no combination rule.
- Treating exclusivity separately from the bound's value made `>= 10` → `> 0` look breaking, when it widens.

**Decision**:
- The IR keeps ai-atlas's own form: decimal bounds with boolean exclusivity, a set of `{regex, flags}` patterns, and `notBlank`.
- Two renderers produce the dialects: one for OpenAPI 3.0 (boolean exclusives) and one for JSON Schema 2020-12 (numeric exclusives replace `minimum`/`maximum`).
- Patterns are published only when they are written in FR-004a's **portable subset**, an allow-list with an explicit translation. Shared syntax can still mean different things in the two engines: Java's `\s`, `\d`, `\w` and `.` differ from ECMAScript's on Unicode, and so does counting characters outside the BMP. So `\s`, `\d` and `\w` become explicit ASCII classes, `.` becomes Java's exact line-terminator exclusion, and every negated atom becomes a four-way group matching exactly one Java code point in both ECMAScript modes: a surrogate pair, a lone high surrogate, a lone low surrogate, or any other non-surrogate outside the class. This makes backtracking unable to split a pair (third review of PR #44 at `887c013`: `[^a]{2}` accepted one U+1F600 without `u`). The result is anchored as `^(?:t)$`. A pattern outside the subset stays enforced by Bean Validation, is left out of the schemas, and warns. (Refined after the second review of PR #44 at `b0b75f2`.)
- `@NotBlank` is its own key with Hibernate Validator 8.0.3's semantics (`trim().length() > 0`: at least one code unit above U+0020). It is published as the unanchored `[^\u0000-\u0020]` plus `minLength 1`, never as a Java regex. Several patterns are published as an `allOf` inside the property.
- Bean Validation constraints are **intersected** first, which is order-independent: the tightest endpoint and the union of patterns. `@AgenticConstraints` then replaces per key.
- Bounds are compared as **endpoints** (value plus exclusivity), with integral exclusive bounds normalised to inclusive ones.

**Consequences**:
- Every published schema is valid in its dialect: tests validate MCP schemas against the 2020-12 metaschema and parse the OpenAPI document.
- The FR-017a fixture proves that accept/reject results agree, for published constraints, across Bean Validation and an **ECMAScript** engine (GraalJS, with and without `u`), including Unicode cases. A Java-backed schema regex engine would hide the differences. For unpublished constraints the fixture asserts the documented one-sided result.
- Some Java regexes cannot be published. They are still enforced at the MCP boundary, and the user is told why.
- Two new **test-only** dependencies: a JSON-Schema validator that bundles the metaschemas, and a Bean Validation provider for the processor's consistency test. There is no production dependency.

### ADR-3: IR version 2, with *unknown* slots from a version-1 baseline

**Context**: Adding slots changes the IR format. Existing committed baselines are version 1.
**Decision**:
- `IrJson` writes version 2.
- It reads version 1 through a migration step that sets every new slot to `null` (unknown) in
  the model.
- `ContractComparison` skips any pair whose baseline side is unknown, for gate rules and lock mode
  alike.
- `atlasAccept` writes version 2, which replaces unknown with real values.

**Consequences**:
- Upgrading ai-atlas never fails a build by itself.
- The first accept after an upgrade shows constraints appearing, reviewable as one diff.
- Phase 2's documented migration policy is honoured.

### ADR-4: The flag gates generated shapes, not the IR

**Context**: The epic requires generated shape changes to be opt-in. The gate is only useful if it
sees constraints from day one.
**Decision**:
- `ai.atlas.constraints` gates FR-014 to FR-017.
- IR recording and the gate rules (FR-005 to FR-011) always apply.
- The golden snapshot, run with the flag off, proves FR-012.

**Consequences**: Teams get constraint governance on upgrade and opt into schema changes when ready.

### ADR-5: Runtime registration through `List<SyncToolSpecification>` (spike-proven)

**Context**: The spike showed that `ToolDefinition` cannot carry hints. The supported hook is
`McpServerAutoConfiguration.mcpSyncServer(..., ObjectProvider<List<SyncToolSpecification>>)`.
Registering the same name through two paths fails startup.
**Decision**:
- `LazyToolCallbackProvider` is replaced by a lazy `List<SyncToolSpecification>` bean. It is an
  `AbstractList` that resolves on first access, and it keeps #42's `@Service` scan, target-class
  check and JDK-proxy skip.
- The bean builds callbacks with `MethodToolCallbackProvider`.
- It wraps each callback with `McpToolUtils.toSyncToolSpecification`.
- For each tool found in `mcp-tools.json` (all classpath copies, through `getResources`), it rebuilds
  the `McpSchema.Tool` by **merging** the generated constraint keywords, requiredness and types
  into Spring AI's derived `inputSchema`, and sets the `ToolAnnotations`. The review of PR #46
  showed that the generated schema is poorer than the derived one for object, `Map`, DTO-collection
  and date parameters. The owner chose to merge, not replace, on 2026-09-27.
- No `ToolCallbackProvider` bean remains, so every name comes from one path.
- ASYNC and STATELESS keep the old provider behaviour: registered only under those server types.

**Consequences**: Tool definitions become data that the processor owns. The spike's evidence tests
become the regression suite.

### ADR-6: Enforcement by a `@Validated` generated tool class (owner decision A)

**Context**: Nothing in the MCP call path validates arguments against the schema (spike Q4).
**Decision**:
- With the flag on, and when the validation API resolves in the compilation, the generated MCP
  tool class carries the effective contract as Bean Validation annotations and is `@Validated`.
  Spring's method validation then rejects a violating call as a tool error, through the proxy
  that #42 registers.
- Without a `MethodValidationPostProcessor` at runtime, the runtime logs FR-019's WARNING.

**Consequences**:
- The MCP boundary enforces exactly the contract, which may be looser or stricter than the
  persistence constraints.
- The validation starter stays the consumer's choice.

## Project Structure

```
modules/annotations/src/main/java/com/egoge/ai/atlas/annotations/{AgenticParam,AgenticConstraints,Requiredness,Hint}.java — new (FR-001, FR-013)
modules/annotations/src/main/java/com/egoge/ai/atlas/annotations/AgenticExposed.java     — hint attributes (FR-013)
modules/processor/src/main/java/com/egoge/ai/atlas/processor/constraints/                 — new: ConstraintReader, EffectiveConstraints, checks (FR-002..004)
modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/                    — IR v2 records, IrJson v1→v2 migration, comparison rules, gate messages (FR-005..011)
modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/                   — OpenAPI, REST, MCP tool class, new McpToolsResourceGenerator (FR-014..017)
modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java        — option ai.atlas.constraints, hint diagnostic (FR-012, FR-013)
modules/processor/src/test/java/com/egoge/ai/atlas/processor/constraints/                 — ConstraintModelTest
modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/                    — IrVersion2Test, ConstraintGateTest
modules/processor/src/test/java/com/egoge/ai/atlas/processor/                             — ConstraintGenerationTest, RegexConsistencyTest (FR-017a)
modules/runtime/src/main/java/com/egoge/ai/atlas/runtime/mcp/AgenticMcpConfiguration.java — SyncToolSpecification registration (FR-018, FR-019)
modules/runtime/src/test/java/com/egoge/ai/atlas/runtime/mcp/                             — McpToolSpecificationTest (+ ProxiedToolBeanTest kept green)
modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/                            — constraints property (FR-020)
modules/gradle-plugin/src/functionalTest/java/com/egoge/ai/atlas/plugin/                  — ConstraintsOptionFunctionalTest
demo/.atlas/api.ir.json                                                                  — regenerated as irVersion 2 (FR-007)
docs/constraints-and-hints.md, docs/contract-governance.md, CHANGELOG.md                 — FR-021
scripts/check-constraints-docs.sh                                                        — FR-021 verifier (committed with the spec)
```

## Scope

### Task 1: Constraint model and IR v2 (US1, FR-001..007)
- [ ] Annotations; reader and precedence; errors and warnings; IR v2 with migration; demo baseline regenerated

### Task 2: Gate rules (US2, FR-008..011)
- [ ] Input narrowing and widening; informational outputs and hints; unknown skipped in gate and lock mode; messages

### Task 3: Generated surfaces behind the flag (US3, FR-012..017)
- [ ] Option; hints and diagnostic; OpenAPI; REST optional parameters; `@Validated` MCP tool classes; `mcp-tools.json`; golden unchanged with the flag off

### Task 4: Runtime and plugin (US4, FR-018..020)
- [ ] `SyncToolSpecification` registration; one path per name; advisory warning; `constraints` plugin property

### Task 5: Docs and build (US5, FR-021..022)
- [ ] Docs, changelog, two-JDK build

## Constraints / What NOT to Build

- No generated-output change with the flag off, except the IR files (FR-012).
- No hint inference, `@McpTool`, auto-configuration fork, or new production dependency.
- No ASYNC or STATELESS schemas or hints, and no Phase 4–5 features.

## File Ownership (Non-Overlapping)

| Owner | Files |
|-------|-------|
| Annotation Processor Engineer | `modules/annotations/**`, `modules/processor/**`, `demo/.atlas/**` |
| Team Lead / Framework Architect | `modules/runtime/**`, `modules/gradle-plugin/**`, `docs/**`, `CHANGELOG.md` |

## Collaboration (Dual Mode)

Single-provider build. Review gating is handled by the auto-build-loop reviewer (DeepSeek, per
`automation.json`), not the dual peer-review protocol. `collaboration_mode: single`.
