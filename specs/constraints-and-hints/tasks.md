# Tasks: Constraints reach REST and MCP; MCP behavioural hints (epic #23, Phase 3)

<!-- One owner per file. [P] = parallelizable within the story group. [US#] traces to spec.md. -->
<!-- Each `## US<n>:` group is one auto-build phase (fresh session). -->
<!-- Build sessions: tick a task's Done box in the same commit that completes it. -->

## US1: Constraints are one contract model

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 1 | | Annotations: `AgenticParam` (`description`, `required`), `AgenticConstraints` (the FR-001 keys with unset sentinels), enum `Requiredness { DEFAULT, REQUIRED, OPTIONAL }`, with Javadoc; the module stays dependency-free (FR-001) | `modules/annotations/src/main/java/com/egoge/ai/atlas/annotations/{AgenticParam,AgenticConstraints,Requiredness}.java` | Annotation Processor Engineer | [ ] |
| 2 | | `constraints` package: `EffectiveConstraints` (the keys in FR-005 order, plus `required`); `ConstraintReader`, which reads Bean Validation by qualified name per the FR-002 table (default group only, `.List` containers), **intersects** them order-independently (the tightest endpoint, the union of patterns, `notBlank`), then applies `@AgenticConstraints`/`@AgenticParam` per key (FR-003); the FR-004 checks on both the intersection and the final contract (errors on the element, the looser-override WARNING, the unpublishable-pattern WARNING); the FR-004a portable-subset parser and translator (allow-list, `\s`/`\d`/`\w`/`.` translations, the four-alternative negated form (surrogate pair, lone high, lone low, non-surrogate outside the class) for `[^X]`, `.`, `\S`, `\D`, `\W`, `\-` handling, anchoring) and the `notBlank` publication `[^\u0000-\u0020]` (FR-002..004a) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/constraints/*.java` | Annotation Processor Engineer | [ ] |
| 3 | | IR v2: `Field.constraints`, `Parameter.constraints` and `required`, `Operation.hints` (filled from `@AgenticExposed` once task 10 lands; `null`-safe until then); `IR_VERSION = 2`; `IrJson` writes the FR-005 key order and reads v1 through a migration that sets the new slots to unknown (`null`); a v2 document with a missing slot is malformed; `IrBuilder` fills the slots from `ConstraintReader` (FR-005, FR-006) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/{ContractIr,IrJson,IrBuilder}.java` | Annotation Processor Engineer | [ ] |
| 4 | | Regenerate the demo baseline as `irVersion` 2 from the demo sources and options; `ContractBaselineTest` stays green (FR-007) | `demo/.atlas/api.ir.json` | Annotation Processor Engineer | [ ] |
| 5 | | Tests: every FR-002 mapping row, including `@Size` on String vs collection vs array and the ignored default bounds; non-default groups ignored; `@Pattern.List` and repeated `@Pattern`; `@Min(10) @Positive` in both orders gives the inclusive lower bound 10; `@Max(5) @Negative` gives the exclusive upper bound 0; `@NotBlank @Pattern` keeps both; `@Min(10) @Max(5)` errors; on an `int`, `@DecimalMin(value="4", inclusive=false) @DecimalMax(value="5", inclusive=false)` errors; every row of FR-004a's subset table translates as specified; each excluded construct (flags, inline flags, possessive, atomic, lookarounds, backreferences, named groups, inner `^`/`$`, `\b`, `\p{…}`, `\Q…\E`, nested classes, `&&`, octal, `\x{…}`, non-BMP literals) makes a pattern unpublishable, with the WARNING naming it; per-key precedence; requiredness resolution; every FR-004 error on its element; the looser-override WARNING; IR v2 written deterministically, twice byte-identical; a v1 document migrates with unknown slots; `irVersion` 3 errors; a v2 document with a missing slot is malformed (FR-001..006) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/constraints/ConstraintModelTest.java`, `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/IrVersion2Test.java` | Annotation Processor Engineer | [ ] |

**Checkpoint US1** — verify before continuing:
- [ ] `./gradlew :modules:processor:test --tests '*ConstraintModelTest' --tests '*IrVersion2Test'` green
- [ ] `./gradlew :modules:processor:test --tests '*IrRewireGoldenTest'` green (generated output unchanged)
- [ ] `./gradlew build` green

---

## US2: The gate sees constraint changes

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 6 | | `ContractComparison`: input rules per FR-008 — bounds compared as endpoints (value plus exclusivity, integral exclusive bounds normalised to inclusive, absent = ±∞); breaking: required false→true, a tighter endpoint, a length or item bound tightened or added, a pattern added to the set, `notBlank` set; compatible: the reverse; output constraints and hints `informational` (FR-009); any pair whose baseline side is unknown is skipped, in gate and lock mode (FR-008, FR-010) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractComparison.java` | Annotation Processor Engineer | [ ] |
| 7 | | Diagnostics per FR-011 (the path `parameter <class>#<method>(<types>).<name>`, key before → after, direction, remedy `apiSince = <M+1>` or `atlasAccept`); `contract-diff.json` carries the new classification `informational` (FR-009, FR-011) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/ContractGate.java` | Annotation Processor Engineer | [ ] |
| 8 | | Tests, each against a baseline produced by compiling a fixture:<br>- `@Max(100)`→`@Max(50)` fails;<br>- `@Min(10)`→`@Positive` passes (`>= 10` → `> 0` widens);<br>- `@Positive`→`@Min(10)` fails;<br>- on an `int`, `@DecimalMin(value="9", inclusive=false)`↔`@Min(10)` is no difference in either direction;<br>- on a `BigDecimal`, `@PositiveOrZero`→`@Positive` fails;<br>- a `@NotBlank` added fails;<br>- `@Max(50)`→`@Max(100)` passes;<br>- adding `@Pattern` fails;<br>- removing it passes;<br>- `@Size(max=10)`→`@Size(max=5)` on a String fails;<br>- a `List` param gains `@Size(min=1)`, fails;<br>- `@Positive`→`@PositiveOrZero` passes;<br>- `@PositiveOrZero`→`@Positive` fails;<br>- a parameter becomes required, fails;<br>- becomes optional, passes;<br>- an override widening a Bean Validation bound passes the gate (and warns per FR-004);<br>- an output field constraint change is informational, with no diagnostic;<br>- a hint change is informational;<br>- a migrated v1 baseline against a fresh v2 IR passes in gate mode **and** in lock mode;<br>- in lock mode, a v2→v2 constraint change fails;<br>- message content and `contract-diff.json` ordering (FR-008..011) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/contract/ConstraintGateTest.java` | Annotation Processor Engineer | [ ] |

**Checkpoint US2** — verify before continuing:
- [ ] `./gradlew :modules:processor:test --tests '*ConstraintGateTest' --tests '*ContractGateTest' --tests '*ContractLockTest'` green
- [ ] `./gradlew build` green

---

## US3: With the flag, generated surfaces carry constraints and hints

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 9 | | Option `ai.atlas.constraints` (validated like `ai.atlas.contract.locked`, added to `@SupportedOptions`) (FR-012) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [ ] |
| 10 | | Hints: enum `Hint { UNSET, TRUE, FALSE }`; `@AgenticExposed` gains `readOnly`, `destructive`, `idempotent`, `openWorld` (default `UNSET`); method-level overrides class-level; recorded in the IR's `Operation.hints`; with the flag on, the all-`UNSET` AI-method WARNING, promoted to ERROR under `ai.atlas.strict` through `QualityDiagnostics` (FR-013) | `modules/annotations/src/main/java/com/egoge/ai/atlas/annotations/{Hint,AgenticExposed}.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/contract/IrBuilder.java` | Annotation Processor Engineer | [ ] |
| 11 | | With the flag on:<br>- OpenAPI parameter `required` plus constraints in the **OpenAPI 3.0** dialect (boolean exclusives, anchored publishable patterns, `allOf` for several patterns, `notBlank` as `\S` + `minLength 1`);<br>- DTO component properties carry field constraints (FR-014);<br>- REST binds `OPTIONAL` parameters with `@RequestParam(required = false)` (FR-015);<br>- MCP tool classes set `@ToolParam(required)`, emit the **effective** contract (not the source annotations) as Jakarta Bean Validation annotations — `@DecimalMin`/`@DecimalMax` with `inclusive`, `@Size`, each `@Pattern` with `flags`, `@NotBlank`, `@NotNull` — and `@Validated`, only when `jakarta.validation.constraints.NotNull` resolves, otherwise one NOTE (FR-016) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/{OpenApiGenerator,RestControllerGenerator,McpToolGenerator}.java` | Annotation Processor Engineer | [ ] |
| 12 | | `McpToolsResourceGenerator`: writes `META-INF/ai-atlas/mcp-tools.json` with the flag on (entries by tool name; `inputSchema` in **JSON Schema 2020-12**: `$schema`, `properties`, `required`, `additionalProperties: false`, every constraint inside `properties`, exclusive bounds as numeric `exclusiveMinimum`/`exclusiveMaximum` replacing `minimum`/`maximum`; `annotations` with declared hints only); not written with the flag off (FR-017) | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/generator/McpToolsResourceGenerator.java`, `modules/processor/src/main/java/com/egoge/ai/atlas/processor/AgenticProcessor.java` | Annotation Processor Engineer | [ ] |
| 13 | | Tests:<br>- `@Positive` → MCP `exclusiveMinimum: 0` (no `minimum`) and OpenAPI `minimum: 0, exclusiveMinimum: true`;<br>- every generated `inputSchema` validates against the 2020-12 metaschema with a test-only validator that bundles it (no network);<br>- the generated OpenAPI document parses with no validation messages;<br>- flag off → `IrRewireGoldenTest` unchanged and no `mcp-tools.json`;<br>- bad option value errors;<br>- flag on → OpenAPI `required`/keywords, DTO field constraints, REST `required = false`, MCP tool class annotations and `@Validated`, and the NOTE path with the validation API absent from the compile classpath;<br>- `mcp-tools.json` content, ordering and determinism;<br>- hints only when declared, method overriding class;<br>- the all-`UNSET` WARNING and its strict ERROR (FR-012..017) | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/ConstraintGenerationTest.java` | Annotation Processor Engineer | [ ] |

| 14 | | `RegexConsistencyTest` (FR-017a): compile a fixture with the flag on, then run the same inputs through Hibernate Validator on the generated MCP tool class (`Validator.forExecutables`), through a JSON Schema 2020-12 validator on its `mcp-tools.json` entry, and through the OpenAPI parameter schema. **Schema-side regexes run on an ECMAScript engine** (GraalJS, for example networknt's `GraalJSRegularExpressionFactory`), both without and with the `u` flag, never a Java-backed engine. Assert identical results for every published case listed in FR-017a, including the Unicode inputs U+00A0, U+2003, U+0085, U+2028, U+FEFF, U+0663, `é` and U+1F600, the quantified and adjacent negated atoms (`[^a]{2}`, `.{2}`, `\S{2}`, `[^a][^b]`, `.\S`, `.+x`, `[^a]*b`), and lone and reversed surrogates. For unpublished patterns (`CASE_INSENSITIVE`, possessive, `\b`, `\p{L}`, literal U+1F600), assert the one-sided result: Bean Validation rejects and both schemas accept, with the WARNING. Hibernate Validator, the JSON-Schema validator and GraalJS are **testImplementation** only | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/RegexConsistencyTest.java`, `modules/processor/build.gradle.kts` | Annotation Processor Engineer | [ ] |

**Checkpoint US3** — verify before continuing:
- [ ] `./gradlew :modules:processor:test --tests '*ConstraintGenerationTest' --tests '*RegexConsistencyTest' --tests '*IrRewireGoldenTest'` green
- [ ] `./gradlew build` green

---

## US4: The runtime serves and enforces them

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 15 | | Replace `LazyToolCallbackProvider` with a lazy `List<SyncToolSpecification>` bean (ADR-5):<br>- keep #42's scan, target-class check and JDK-proxy skip;<br>- callbacks through `MethodToolCallbackProvider`, wrapped with `McpToolUtils.toSyncToolSpecification`;<br>- tools listed in any `META-INF/ai-atlas/mcp-tools.json` (all classpath copies) rebuilt with that `inputSchema` and `ToolAnnotations`;<br>- no other registration path, so every name is registered once;<br>- two resources listing the same name fail startup, naming both;<br>- ASYNC and STATELESS keep the old provider and log one INFO line (FR-018) | `modules/runtime/src/main/java/com/egoge/ai/atlas/runtime/mcp/AgenticMcpConfiguration.java` | Team Lead / Framework Architect | [ ] |
| 16 | | Advisory WARNING when any `mcp-tools.json` is present and no `MethodValidationPostProcessor` bean exists; no production dependency on `spring-boot-starter-validation` (FR-019) | `modules/runtime/src/main/java/com/egoge/ai/atlas/runtime/mcp/AgenticMcpConfiguration.java` | Team Lead / Framework Architect | [ ] |
| 17 | | Tests, over SSE and Streamable HTTP:<br>- `tools/list` shows a spec-listed tool's constraint keywords and declared annotations verbatim, and a tool with no spec keeps its derived schema without annotations;<br>- a call violating `maximum` on a `@Validated` tool returns `isError: true` and the service is not invoked;<br>- a valid call succeeds;<br>- duplicate names across two resources fail startup naming both;<br>- the advisory WARNING without method validation;<br>- ASYNC keeps the old registration;<br>- `ProxiedToolBeanTest`, `SseUnchangedTest` and `StreamableHttpTransportTest` stay green (FR-018, FR-019) | `modules/runtime/src/test/java/com/egoge/ai/atlas/runtime/mcp/McpToolSpecificationTest.java` | Team Lead / Framework Architect | [ ] |
| 18 | | Plugin: `agentic { constraints }` (default `false`), passed to the main `compileJava` as `ai.atlas.constraints`; the functional test asserts the option reaches `compileJava` only, and that turning it on writes `mcp-tools.json` (FR-020) | `modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/{AgenticExtension,AgenticPlugin,ContractArguments}.java`, `modules/gradle-plugin/src/functionalTest/java/com/egoge/ai/atlas/plugin/ConstraintsOptionFunctionalTest.java` | Team Lead / Framework Architect | [ ] |

**Checkpoint US4** — verify before continuing:
- [ ] `./gradlew :modules:runtime:test` green
- [ ] `./gradlew :modules:gradle-plugin:functionalTest` green
- [ ] `./gradlew build` green

---

## US5: The rules are documented

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 19 | [P] | `docs/constraints-and-hints.md`: sources and the FR-002 mapping table, precedence and requiredness, errors and the looser-override warning, `@AgenticParam` and `@AgenticConstraints`, hints as client guidance and not authorization, the `ai.atlas.constraints` flag, enforcement and its optional dependency, SYNC-only scope. It must name `@AgenticParam`, `@AgenticConstraints`, `ai.atlas.constraints`, `readOnlyHint`, `@Validated`, `spring-boot-starter-validation` and `SYNC` (checked by `scripts/check-constraints-docs.sh`) (FR-021) | `docs/constraints-and-hints.md` | Team Lead / Framework Architect | [ ] |
| 20 | [P] | `docs/contract-governance.md`: `irVersion` 2, the migration with unknown slots, the new gate rules and `informational`; `CHANGELOG.md` entries under `[Unreleased]` naming `ai.atlas.constraints`, `@AgenticParam`, `irVersion 2` and `mcp-tools.json` (FR-021) | `docs/contract-governance.md`, `CHANGELOG.md` | Team Lead / Framework Architect | [ ] |

**Checkpoint US5** — verify before continuing:
- [ ] `scripts/check-constraints-docs.sh` passes (FR-021)
- [ ] `scripts/build-on-jdk-matrix.sh` passes (FR-022)

---

## Final Verification

- [ ] `scripts/build-on-jdk-matrix.sh` passes: the build is green with Gradle on JDK 17 and on JDK 21 (FR-022)
- [ ] No `[NEEDS CLARIFICATION]` markers remain in spec.md
- [ ] Every FR in `verification.yaml` has a passing verifier
- [ ] Review verdict `PASS` recorded in `collaboration/build-review.md` for every phase
