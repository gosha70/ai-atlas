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
    - `pattern`: a Java regex with Bean Validation semantics (it must match the *whole* value), default `""`.
- **FR-002**: The processor MUST read Jakarta Bean Validation annotations on `@AgenticField`
  fields and on the parameters of exposed methods. It identifies them by qualified name, with no
  compile-time dependency on the validation API, and normalises them as follows:
  - **Which constraints count.** Only constraints that apply to the default validation group count:
    an empty `groups`, or one that contains `jakarta.validation.groups.Default`. Repeated
    constraints count one by one, whether written directly or through a `.List` container (for
    example `@Pattern.List`).
  - `@Min`/`@DecimalMin` become a lower bound, and `@Max`/`@DecimalMax` become an upper bound. A `@DecimalMin`/`@DecimalMax` with `inclusive = false` makes the bound exclusive.
  - `@Positive` is the exclusive lower bound 0, and `@PositiveOrZero` the inclusive lower bound 0.
  - `@Negative` is the exclusive upper bound 0, and `@NegativeOrZero` the inclusive upper bound 0.
  - `@Size` becomes `minLength`/`maxLength` on a `String`, and `minItems`/`maxItems` on a collection or array. Its default bounds (`0` and `Integer.MAX_VALUE`) are not recorded.
  - `@Pattern` adds its `regexp` and `flags` to the input's set of patterns. The set is unordered,
    and a value must match every pattern, each as a whole-string Java match.
  - `@NotNull` makes the input required.
  - `@NotBlank` makes it required and sets `notBlank`: at least one non-whitespace character
    anywhere, including across line breaks. It is **not** translated to a regex.
  - `@NotEmpty` makes it required with `minLength 1` (a string) or `minItems 1` (a collection or array).
  - Other constraint annotations, including composed ones, are ignored.
- **FR-003**: The effective contract of an input or field MUST be resolved in two steps. The result
  MUST NOT depend on the order in which annotations are written.
  - **First, intersect the Bean Validation constraints.** A value must satisfy every one of them.
    - Lower bound: the *tightest* of all lower bounds. That is the highest value; at an equal value, an exclusive bound beats an inclusive one. For example, `@Min(10) @Positive` gives the inclusive lower bound 10.
    - Upper bound: symmetrically, the lowest value, with exclusive winning a tie.
    - `minLength`/`minItems`: the largest. `maxLength`/`maxItems`: the smallest.
    - Patterns: the union of all pattern sets.
    - `notBlank` and requiredness: set if any constraint sets them.
    - `@NotBlank @Pattern(...)` keeps both.
  - **Then, apply `@AgenticConstraints` per key.** A bound, length or item key it sets replaces the
    intersected value for that key. A non-empty `pattern` replaces the whole pattern set with that
    one pattern. Every key it does not set keeps the intersected value.
  - Requiredness comes from `@AgenticParam(required)` when it is not `DEFAULT`; `@AgenticParam(description)`, when non-empty, becomes the parameter's description.
  - Otherwise a parameter is required when it is a primitive, or carries `@NotNull`, `@NotBlank` or `@NotEmpty`.
  - Otherwise it is required, as REST query parameters are today.
- **FR-004**: The processor MUST report:
  - A compile **ERROR** on the element for a self-contradiction. The check runs both on the
    intersected Bean Validation constraints and on the final effective contract:
    - a lower bound above the upper bound, or equal bounds with either exclusive (for an integral
      type, after the FR-008 normalisation, for example `> 4` and `< 5`);
    - a negative length or item bound, or a `min` above its `max`;
    - a pattern, or `@AgenticConstraints.pattern`, that does not compile as a Java regex with its flags;
    - an exclusive flag without its bound;
    - a length attribute on a non-string, or an item attribute on a non-collection, non-array;
    - `minimum`/`maximum` on a non-numeric type;
    - `Requiredness.OPTIONAL` on a primitive, or together with `@NotNull`, `@NotBlank` or `@NotEmpty`.
  - A **WARNING** on the parameter when an `@AgenticConstraints` value on an *input* is looser than
    the Bean Validation constraint it replaces, naming the key and both values. Looser means a lower
    minimum, a higher maximum, a wider length or item range, or a replaced pattern set.
  - A **WARNING** on the element when a pattern cannot be published faithfully in a schema (FR-004a).
    It names the pattern and the reason. The pattern stays in the IR and stays enforced by Bean
    Validation, but it is left out of the OpenAPI and MCP schemas.
- **FR-004a**: A pattern is published into a schema only when every value's accept/reject result
  is the same under Java whole-string matching and under ECMAScript (ECMA-262) matching of the
  published form. That must hold with and without the `u` flag. A pattern qualifies only when it is
  written entirely in the **portable subset** below, and each construct is emitted through the
  **translation** below. A pattern outside the subset is *not publishable*: it stays in the IR, is
  still enforced by Bean Validation, is left out of the OpenAPI and MCP schemas, and produces the
  FR-004 WARNING naming the first unsupported construct.
  - **Scope:** only patterns with no `@Pattern.flags`.
  - **Portable subset, and how each construct is emitted:**

    | Construct in the Java pattern | Emitted as |
    |---|---|
    | A literal BMP character other than a metacharacter or a surrogate | itself (`\uXXXX` if it is not printable ASCII) |
    | An escaped metacharacter: `\\` `\.` `\*` `\+` `\?` `\(` `\)` `\[` `\]` `\{` `\}` `\|` `\^` `\$` `\/` | itself |
    | `\-` | itself inside a character class; a plain `-` outside one (ECMAScript `u` mode rejects `\-` outside a class) |
    | `\t`, `\n`, `\r`, `\f`, `\xhh`, `\uhhhh` (BMP, non-surrogate) | itself |
    | `\d` / `\D` (Java ASCII digits) | `[0-9]` / the negated form below |
    | `\w` / `\W` (Java `[a-zA-Z_0-9]`) | `[a-zA-Z_0-9]` / the negated form below |
    | `\s` / `\S` (Java `[ \t\n\x0B\f\r]`) | `[ \t\n\x0B\f\r]` / the negated form below |
    | `.` (Java: any character except `\n`, `\r`, U+0085, U+2028, U+2029) | the negated form of `[\n\r\u0085  ]` |
    | A character class `[...]` or `[^...]` whose members are only *class members* (below) | a class of the translated members; a negated class uses the negated form |
    | Groups `(...)`, non-capturing `(?:...)`, alternation `\|` | themselves |
    | Quantifiers `*`, `+`, `?`, `{n}`, `{n,}`, `{n,m}`, and their lazy `?` variants | themselves |

  - **Negated form:** Java matches by code point, and ECMAScript without `u` matches by UTF-16 code
    unit. So every negated atom (`[^X]`, `.`, `\S`, `\D`, `\W`) MUST match **exactly one Java code
    point** in both ECMAScript modes. It is emitted as a group with four alternatives:

    `(?:[\uD800-\uDBFF][\uDC00-\uDFFF]|[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]|[^X\uD800-\uDFFF])`

    | Alternative | Matches |
    |---|---|
    | `[\uD800-\uDBFF][\uDC00-\uDFFF]` | a valid surrogate pair, one astral code point, as one unit (without `u`) |
    | `[\uD800-\uDBFF](?![\uDC00-\uDFFF])` | a lone high surrogate (Java treats it as one code point) |
    | `(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]` | a lone low surrogate |
    | `[^X\uD800-\uDFFF]` | any other code point not in `X` (with `u`, this includes astral code points) |

    The last alternative excludes all surrogates, and the lone-surrogate alternatives refuse a
    surrogate that belongs to a pair. So no alternative can match half of a valid pair, and
    backtracking cannot split one character into two matches. For example, `[^a]{2}` rejects a single
    U+1F600 in both modes, as Java does.
    - `X` contains only BMP non-surrogate members, so every astral code point and every lone
      surrogate is outside `X`, as in Java.
    - Positive atoms (literals and non-negated classes) are BMP non-surrogate only, so they can never
      match a surrogate code unit.
    - The emitted lookbehind is part of the translation, not of the accepted input subset. It
      requires an ECMAScript 2018+ engine, and the docs state this.
  - **Class members.** Inside `[...]` and `[^...]`, only these are allowed:
    - a literal BMP non-surrogate character;
    - `\t`, `\n`, `\r`, `\f`, `\xhh`, and `\uhhhh` naming a non-surrogate;
    - an escaped metacharacter;
    - a range `a-b` whose endpoints are BMP non-surrogates **and whose span does not include
      U+D800–U+DFFF**, so `[퟿-]` is not allowed even though both endpoints are
      non-surrogates;
    - the **positive** escapes `\d`, `\w` and `\s`, which are expanded in place to their ASCII
      members. For example, `[\d_]` is emitted as `[0-9_]`, and `[^\s,]` as the negated form of
      `[ \t\n\x0B\f\r,]`.

    The **complemented** escapes `\S`, `\D` and `\W` are *not* allowed inside a class. The class
    they would form contains every astral code point and surrogate, which a bracket expression
    without `u` cannot express, and the four-branch form cannot be placed inside brackets. A class
    containing one, such as `[\S]` or `[\D_]`, makes the pattern not publishable, with the FR-004
    WARNING. Outside a class, `\S`, `\D` and `\W` stay publishable through the negated form.
    Consequently every non-negated class matches only BMP non-surrogate characters, and every negated
    class's `X` does too.
  - **Not in the subset, so not publishable:**
    - anything with flags;
    - inline flags, possessive quantifiers, atomic groups, lookarounds, backreferences and named groups;
    - `^` or `$` inside the pattern;
    - `\b`, `\B`, `\A`, `\Z`, `\z`, `\G`, `\R`, `\h`, `\H`, `\v`, `\X`;
    - any `\p{…}`/`\P{…}`, `\Q…\E`, octal and `\x{…}` escapes;
    - class union or intersection (nested `[` or `&&`);
    - `\S`, `\D` or `\W` inside a class, and a class range spanning U+D800–U+DFFF;
    - literal characters outside the BMP, and surrogates.
  - **Anchoring:** the published form of a translated pattern `t` is `^(?:t)$`.
  - **`notBlank` is not a regex translation.** It is defined as Hibernate Validator 8.0.3's
    `@NotBlank`, which the project tests with (`NotBlankValidator`: `toString().trim().length() > 0`).
    The value must contain at least one UTF-16 code unit above U+0020. So U+00A0 and U+2003 count as
    non-blank, and U+0000–U+0020 do not. It is published as the unanchored pattern `[^\u0000- ]`
    together with `minLength 1`. The docs state that other Bean Validation providers may define
    blankness differently.
  - **Several published patterns** on one property, including `notBlank`'s, are written as an
    `allOf` of single-`pattern` schemas inside that property, never at the schema root.
- **FR-005**: The Contract IR MUST move to `irVersion` 2:
  - `Field` gains `constraints`.
  - `Parameter` gains `constraints` and `required` (boolean).
  - `Operation` gains `hints`, with `readOnly`, `destructive`, `idempotent` and `openWorld`, each `true`, `false` or absent.
  - `constraints` is an object with only the keys that are set, in the fixed order `minimum`, `exclusiveMinimum`, `maximum`, `exclusiveMaximum`, `minLength`, `maxLength`, `minItems`, `maxItems`, `patterns`, `notBlank`:
    - `minimum`/`maximum` are decimal strings, and `exclusiveMinimum`/`exclusiveMaximum` are booleans present only when `true`;
    - `patterns` is a list of `{regex, flags}` objects, sorted by regex then flags;
    - this is ai-atlas's own normalised form, and neither schema dialect.
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
  - **Bounds are compared as endpoints**, meaning value and exclusivity together, never separately:
    - An absent lower bound is −∞, and an absent upper bound is +∞.
    - For an integral Java type (`byte`, `short`, `int`, `long`, their boxes, `BigInteger`), an exclusive bound is first normalised to the inclusive one: `> 9` becomes `>= 10`, and `< 10` becomes `<= 9`. So `> 9` and `>= 10` are the same endpoint and no difference.
    - A lower endpoint is *tighter* when its value is higher, or equal and exclusive.
    - An upper endpoint is *tighter* when its value is lower, or equal and exclusive.
    - Examples: `>= 10` → `> 0` is widening (compatible); `> 0` → `>= 10` is narrowing (breaking); `>= 0` → `> 0` on a decimal is narrowing.
  - **Breaking:**
    - a parameter's `required` goes from `false` to `true`;
    - a lower or upper endpoint becomes tighter;
    - `minLength` or `minItems` rises or appears, and `maxLength` or `maxItems` falls or appears;
    - a pattern is added to the set, which includes a changed pattern, because that is a removal plus an addition;
    - `notBlank` goes from absent to set.
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
- **FR-014**: With the flag on, the OpenAPI document (OpenAPI 3.0.3, which ai-atlas generates today)
  MUST describe:
  - each query parameter's `required` from FR-003, and its constraints on the parameter schema;
  - each DTO property's field constraints on the component schema.

  Constraints are written in the **OpenAPI 3.0 Schema Object** dialect:
  - bounds as `minimum`/`maximum`, with boolean `exclusiveMinimum`/`exclusiveMaximum: true` for exclusive ones;
  - lengths and items as `minLength`, `maxLength`, `minItems` and `maxItems`;
  - patterns per FR-004a.

  A test MUST parse the generated document with the OpenAPI parser already used by the processor's
  tests and assert that it reports no validation messages.
- **FR-015**: With the flag on, a generated REST controller MUST bind an `OPTIONAL` parameter with
  `@RequestParam(required = false)`. Required parameters are unchanged.
- **FR-016**: With the flag on, each generated MCP tool class MUST:
  - set `@ToolParam(required = …)` from FR-003;
  - carry each parameter's effective contract (FR-003) as Jakarta Bean Validation annotations,
    after intersection and overrides, never the raw source annotations:
    - bounds become `@DecimalMin`/`@DecimalMax` with `inclusive`;
    - lengths and items become `@Size`;
    - each pattern becomes a `@Pattern` with its `regexp` and `flags`;
    - `notBlank` becomes `@NotBlank`;
    - a required reference type gets `@NotNull`;
  - be annotated `@Validated`.

  When `jakarta.validation.constraints.NotNull` does not resolve in the compilation, the processor
  MUST NOT emit the Bean Validation annotations or `@Validated`. It MUST instead report one NOTE
  for the compilation saying the MCP constraints will be advisory.
- **FR-017**: With the flag on, the processor MUST write `META-INF/ai-atlas/mcp-tools.json`. It has one
  entry per AI-channel tool active at the configured major, ordered by tool name, and each entry
  holds:
  - the tool name;
  - `inputSchema`, in the **JSON Schema 2020-12** dialect, which MCP uses for tool input schemas:
    - an object schema with `"$schema": "https://json-schema.org/draft/2020-12/schema"`, `properties` (one per parameter, with its JSON type and constraints, all inside `properties`, never at the root), `required`, and `additionalProperties: false`;
    - an inclusive bound is written as `minimum`/`maximum`, and an exclusive one as the **numeric** `exclusiveMinimum`/`exclusiveMaximum` *instead of* `minimum`/`maximum`. For example, `@Positive` becomes `exclusiveMinimum: 0`;
    - lengths, items and patterns are written as in FR-014 (patterns per FR-004a).
  - `annotations`, holding only the hints declared `TRUE` or `FALSE`.

  The file's top level is `{"tools": [...]}` and MAY gain a `"version"` field later. Its
  `inputSchema` is the source of the constraint keywords, requiredness and types that the runtime
  merges into the derived schema (FR-018). It is not a replacement for the derived schema.

  **Length units (owner decision, 2026-09-27).** Bean Validation's `@Size` counts UTF-16 code
  units, while JSON Schema `minLength`/`maxLength` count code points. The lengths are published
  as they are:
  - they are exact for text in the Basic Multilingual Plane;
  - for other characters, such as U+1F600, the schemas and Bean Validation can disagree, and
    Bean Validation stays the enforced limit at the MCP boundary;
  - FR-017a's length cases use BMP inputs, plus one test documenting the known difference;
  - the docs state it.

  The file is deterministic under Phase 2's FR-003 rules. With the flag off, it is not written.
  A test MUST validate every generated `inputSchema` against the JSON Schema 2020-12 metaschema,
  offline. The metaschema is bundled by a test-only validator library, never fetched over the
  network.
- **FR-017a**: Regex semantics MUST agree across the three places a value is checked, for every
  **published** constraint:
  - Bean Validation on the generated MCP tool class, using the Hibernate Validator the project tests with;
  - the `mcp-tools.json` input schema;
  - the OpenAPI parameter schema.

  Published patterns MUST be evaluated by an **ECMAScript regex engine**, such as GraalJS through a
  test-only dependency (for example networknt's `GraalJSRegularExpressionFactory`). They MUST be
  evaluated both without and with the `u` flag. A Java-backed regex engine MUST NOT be used for the
  schema side, because it would hide exactly these differences. A shared fixture asserts identical
  accept/reject results for:
  - `@Pattern("[A-Z]+")`: accepts `"ABC"`, rejects `"xABCy"`;
  - `@Pattern("\\s+")`: accepts `" \t"`, rejects U+00A0, U+2003, U+0085 and U+FEFF;
  - `@Pattern("\\S+")`: accepts U+00A0 and U+2003;
  - `@Pattern(".")`: accepts `"a"`, U+00A0 and U+1F600 (one code point), rejects U+0085, U+2028 and `"\n"`;
  - `@Pattern("[^a]")`: accepts U+1F600;
  - **Quantified and adjacent negated atoms**, which catch a pair being split under backtracking:
    - `@Pattern("[^a]{2}")`, `@Pattern(".{2}")` and `@Pattern("\\S{2}")`: reject a single U+1F600, accept U+1F600 U+1F600 and `"xy"`;
    - `@Pattern("[^a][^b]")` and `@Pattern(".\\S")`: reject a single U+1F600, accept `"x"` + U+1F600;
    - `@Pattern(".+x")`: accepts U+1F600 + `"x"`, rejects `"x"`;
    - `@Pattern("[^a]*b")`: accepts U+1F600 U+1F600 `"b"`;
  - **Lone surrogates**, evaluated as Java strings and as the same UTF-16 sequence on the ECMAScript side: with `@Pattern(".")` and `@Pattern("[^a]")`, a lone high surrogate U+D83D and a lone low surrogate U+DE00 are each accepted; the reversed sequence U+DE00 U+D83D is rejected by `.` and accepted by `.{2}`;
  - `@Pattern("\\d+")` rejects `"٣"` (U+0663), and `@Pattern("\\w+")` rejects `"é"`;
  - `@NotBlank`: accepts `"a\nb"`, `" a "`, U+00A0 and U+2003, rejects `""`, `" "`, `"\n\t"` and U+0000;
  - `@NotBlank @Pattern("[a-z ]+")`: rejects `"   "`, accepts `" ab "`.

  - **Class-member boundary (published):** `@Pattern("[\\d_]+")` accepts `"4_2"` and rejects `"٣"` (U+0663); `@Pattern("[^\\s,]+")` accepts U+1F600 and U+00A0, rejects `"a,b"`.

  For constraints that are **not published**, the assertion is deliberately one-sided. Both schemas
  accept every input, because they omit the constraint, while Bean Validation keeps its Java
  result. The fixture chooses inputs that Bean Validation rejects, so the gap is visible. The cases
  are:
  - a `@Pattern` with `CASE_INSENSITIVE`;
  - a possessive quantifier;
  - `\b` and `\p{L}`;
  - a literal U+1F600 in the pattern;
  - `[\\S]` (input `" "`);
  - `[\\D_]` (input `"5"`);
  - `[\\uD7FF-\\uE000]` (input `"a"`).

  Each also produces the FR-004 WARNING naming the unsupported construct.

### The runtime (US4)

- **FR-018**: The runtime MUST register MCP tools through one lazy `List<SyncToolSpecification>`
  bean instead of `LazyToolCallbackProvider`.
  - It builds callbacks with `MethodToolCallbackProvider` over the same beans #42's scan selects,
    and keeps #42's JDK-proxy skip and warning.
  - For a tool listed in any `META-INF/ai-atlas/mcp-tools.json` on the classpath, it rebuilds the
    `McpSchema.Tool` by **merging** that entry into Spring AI's derived input schema, never by
    replacing the schema (owner decision, 2026-09-27). The derived schema is richer for object,
    `Map`, DTO-collection and date parameters, and nothing it describes may be lost.
    - The merge adds, per property: the generated constraint keywords (bounds, exclusives, lengths,
      items, the `pattern`/`allOf` of FR-004a), and the property's membership in `required`.
    - It keeps every other keyword of the derived property: `items`, nested `properties`,
      `format`, `description`, `enum` and the rest.
    - When a property's derived `type` differs from the generated one, the generated `type` wins for
      that property, because the constraint keywords need their type. The runtime logs one DEBUG line
      naming the tool, the property and both types.
    - The merged schema declares `"$schema": "https://json-schema.org/draft/2020-12/schema"`. A test
      MUST validate every merged `inputSchema` against the 2020-12 metaschema.
    - The tool's `annotations` are the entry's hints.
    - Every other tool keeps its derived schema, without hints.
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
    - the FR-004a portable regex subset and its translation, why unpublishable patterns are enforced but omitted, and `notBlank`'s Hibernate Validator semantics;
    - the length-unit difference (UTF-16 units in Bean Validation, code points in JSON Schema);
    - that the runtime merges constraints into Spring AI's derived MCP schema;
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
- **Constraint keys (ai-atlas's normalised form):** `minimum`, `exclusiveMinimum`, `maximum`, `exclusiveMaximum`, `minLength`, `maxLength`, `minItems`, `maxItems`, `patterns`, `notBlank`.
- **Endpoint:** a bound's value together with its exclusivity. It is compared as a whole (FR-008).
- **Dialects:** OpenAPI 3.0 Schema Object (boolean exclusives) for the OpenAPI document; JSON Schema 2020-12 (numeric exclusives) for MCP `inputSchema`.
- **Publishable pattern:** a flag-free pattern written entirely in FR-004a's portable subset, emitted through its translation and anchored as `^(?:t)$`.
- **Unknown:** the value of every constraint, requiredness and hint slot in a migrated `irVersion` 1 baseline.
- **Tool specification:** an entry of `META-INF/ai-atlas/mcp-tools.json`, registered by the runtime as a `SyncToolSpecification`.

## Success Criteria

1. **US1 / FR-001–FR-007:**
   - a fixture mixing Bean Validation and overrides yields the expected IR version 2 constraints;
   - `@Min(10) @Positive` and `@Positive @Min(10)` both give the inclusive lower bound 10, and `@NotBlank @Pattern` keeps both;
   - each contradiction errors, and a looser override warns;
   - a version-1 baseline migrates with unknown slots;
   - the demo baseline is version 2 and its test passes.
2. **US2 / FR-008–FR-011:**
   - narrowing a `@Max`, or making a parameter required, fails, naming the path and before → after;
   - `>= 10` → `> 0` passes, `> 0` → `>= 10` fails, and on an `int`, `> 9` ↔ `>= 10` is no difference;
   - widening passes;
   - a migrated baseline passes in gate and lock mode;
   - output-constraint and hint changes are informational.
3. **US3 / FR-012–FR-017:**
   - the golden snapshot is unchanged with the flag off;
   - with the flag on, OpenAPI, REST, MCP classes and `mcp-tools.json` match fixtures, and hints appear only when declared;
   - `@Positive` is `exclusiveMinimum: 0` in MCP and `minimum: 0, exclusiveMinimum: true` in OpenAPI, and every `inputSchema` validates against the 2020-12 metaschema;
   - the FR-017a fixture gives identical results for published constraints under an ECMAScript engine (with and without `u`), and the documented one-sided results for unpublished ones.
4. **US4 / FR-018–FR-020:** over SSE and Streamable HTTP:
   - `tools/list` shows the constraints and declared hints;
   - a violating call returns a tool error without reaching the service;
   - duplicate names fail startup;
   - `ProxiedToolBeanTest` passes;
   - the advisory WARNING appears without method validation.
5. **US5 / FR-021–FR-022:** the docs check passes, and the build is green on JDK 17 and 21.
