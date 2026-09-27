# Constraints and Behavioural Hints

ai-atlas reads the limits your service already declares on its inputs and outputs, records them in
the Contract IR, and, behind one opt-in flag, publishes them in the generated OpenAPI document and
MCP tools. MCP tools can also declare behavioural hints: read-only, destructive, idempotent and
open-world. This guide covers where constraints come from, how they combine, what is published and
enforced where, and the limits of that publication.

For how the compatibility gate treats constraint changes, see
[Contract governance](contract-governance.md#constraints-requiredness-and-hints).

## Constraint sources

ai-atlas reads constraints from two places:

- **Jakarta Bean Validation** annotations on `@AgenticField` fields and on the parameters of
  `@AgenticExposed` methods. They are recognised by qualified name, so the processor needs no
  compile-time dependency on the validation API.
- **ai-atlas overrides:** `@AgenticConstraints` on fields and parameters, and `@AgenticParam` on
  parameters.

Only constraints in the default validation group count: an empty `groups`, or one containing
`jakarta.validation.groups.Default`. Repeated constraints count one by one, whether written directly
or through a `.List` container such as `@Pattern.List`.

### Bean Validation mapping

| Bean Validation | Effective constraint |
|-----------------|----------------------|
| `@Min`, `@DecimalMin` | Lower bound; exclusive when `@DecimalMin(inclusive = false)` |
| `@Max`, `@DecimalMax` | Upper bound; exclusive when `@DecimalMax(inclusive = false)` |
| `@Positive` | Exclusive lower bound 0 |
| `@PositiveOrZero` | Inclusive lower bound 0 |
| `@Negative` | Exclusive upper bound 0 |
| `@NegativeOrZero` | Inclusive upper bound 0 |
| `@Size` on a `String` | `minLength` / `maxLength` |
| `@Size` on a collection or array | `minItems` / `maxItems` |
| `@Pattern` | Adds its `regexp` and `flags` to the input's set of patterns |
| `@NotNull` | Required |
| `@NotBlank` | Required, and `notBlank` |
| `@NotEmpty` | Required, and `minLength 1` (string) or `minItems 1` (collection or array) |

- `@Size`'s default bounds (`0` and `Integer.MAX_VALUE`) are not recorded. So `@Size(min = 3)`
  records only `minLength 3` and no `maxLength`, and `@Size(max = 10)` records only `maxLength 10`
  and no `minLength`.
- The pattern set is unordered; a value must match every pattern, each as a whole-string Java match.
- `notBlank` means at least one non-whitespace character anywhere, line breaks included. It is not
  translated to a regex (see [`notBlank`](#notblank)).
- Every other constraint annotation, including composed constraints, is ignored.

## `@AgenticConstraints` and `@AgenticParam`

`@AgenticConstraints` targets fields and method parameters. Every attribute has a default that
means "not set":

| Attribute | Type | Not set |
|-----------|------|---------|
| `minimum`, `maximum` | decimal string | `""` |
| `exclusiveMinimum`, `exclusiveMaximum` | boolean | `false` |
| `minLength`, `maxLength`, `minItems`, `maxItems` | int | `-1` |
| `pattern` | Java regex, matched against the whole value | `""` |

`@AgenticParam` targets method parameters:

- `description` (default `""`): when non-empty, becomes the parameter's description.
- `required`, of `Requiredness { DEFAULT, REQUIRED, OPTIONAL }` (default `DEFAULT`).

```java
public List<Order> search(
        @AgenticParam(description = "Maximum results", required = Requiredness.OPTIONAL)
        @Max(500) @AgenticConstraints(maximum = "100") Integer limit,
        @NotBlank @Size(max = 64) String customer) { ... }
```

Both annotations live in the dependency-free `annotations` module.

## Precedence

The effective contract of an input or field is resolved in two steps, and does not depend on the
order the annotations are written in.

1. **Intersect the Bean Validation constraints.** A value must satisfy all of them:
   - the lower bound is the tightest: the highest value, and at an equal value an exclusive bound
     beats an inclusive one. `@Min(10) @Positive` gives the inclusive lower bound 10;
   - the upper bound symmetrically: the lowest value, exclusive winning a tie;
   - the largest `minLength`/`minItems` and the smallest `maxLength`/`maxItems`;
   - the union of all patterns;
   - `notBlank` and requiredness when any constraint sets them. `@NotBlank @Pattern(...)` keeps both.
2. **Apply `@AgenticConstraints` per key.** Each bound, length or item key it sets replaces the
   intersected value for that key. A non-empty `pattern` replaces the whole pattern set with that
   one pattern. Every key it does not set keeps the intersected value.

### Requiredness

A parameter is required, in this order of precedence:

1. as `@AgenticParam(required)` says, when it is `REQUIRED` or `OPTIONAL`;
2. otherwise, when it is a primitive or carries `@NotNull`, `@NotBlank` or `@NotEmpty`;
3. otherwise, it is still required, as REST query parameters always have been.

So a parameter is optional only when it is declared `Requiredness.OPTIONAL`. `Requiredness.REQUIRED`
is always allowed, including together with `@NotNull`, `@NotBlank` or `@NotEmpty` or on a
primitive; since a parameter is required by default, it changes nothing.

## Errors and warnings

The processor checks both the intersected Bean Validation constraints and the final effective
contract. A **compile ERROR** is reported on the element for:

- a lower bound above the upper bound, or equal bounds with either exclusive. On an integral type
  (`byte`, `short`, `int`, `long`, their boxes, `BigInteger`) exclusive bounds are first made
  inclusive, so `> 4` and `< 5` on an `int` is an error;
- a negative length or item bound, or a `min` above its `max`;
- a pattern, or `@AgenticConstraints.pattern`, that does not compile as a Java regex with its flags;
- `exclusiveMinimum`/`exclusiveMaximum` without its bound;
- a length attribute on a non-string, or an item attribute on a non-collection, non-array;
- `minimum`/`maximum` on a non-numeric type;
- `Requiredness.OPTIONAL` on a primitive, or together with `@NotNull`, `@NotBlank` or `@NotEmpty`.

A **compile WARNING** is reported:

- on a parameter, when an `@AgenticConstraints` value is **looser** than the Bean Validation
  constraint it replaces: a lower minimum, a higher maximum, a wider length or item range, or a
  replaced pattern set. Whether one regex admits fewer values than another cannot be decided in
  general, so an `@AgenticConstraints.pattern` warns whenever the Bean Validation pattern set is
  non-empty and differs from that one pattern, even if the override is in fact tighter. It names
  the key and both values. The override wins in the published
  schemas, but Bean Validation on the service itself still enforces its own constraint;
- on an element, when a pattern cannot be published faithfully (see below). It names the pattern
  and the first unsupported construct.

## Patterns in schemas

Java and ECMAScript, which JSON Schema and OpenAPI validators follow, do not agree on regex
semantics: `\s`, `\d`, `\w` and `.` differ on non-ASCII characters, and ECMAScript without the `u`
flag matches UTF-16 code units, not code points. ai-atlas therefore publishes a pattern only when
it is written entirely in a conservative syntactic subset, below, whose translation is built so
that every value gets the same accept/reject result under Java whole-string matching and under
ECMAScript matching of the published form, with and without `u`. This is not a general
equivalence check between the two engines: a pattern outside the subset is not published, even if
it would happen to behave the same.

### The portable subset

A pattern is publishable when it has no `@Pattern.flags` and is written entirely with these
constructs, each emitted as shown:

| Java construct | Emitted as |
|----------------|------------|
| A literal BMP character, not a metacharacter or surrogate | itself (`\uXXXX` if not printable ASCII) |
| An escaped metacharacter: `\\` `\.` `\*` `\+` `\?` `\(` `\)` `\[` `\]` `\{` `\}` `\|` `\^` `\$` `\/` | itself |
| `\-` | itself inside a class; `-` outside one |
| `\t`, `\n`, `\r`, `\f`, `\xhh`, `\uhhhh` (BMP, non-surrogate) | itself |
| `\d` / `\D` | `[0-9]` / its negated form |
| `\w` / `\W` | `[a-zA-Z_0-9]` / its negated form |
| `\s` / `\S` | `[ \t\n\x0B\f\r]` / its negated form |
| `.` | the negated form of `[\n\r\u0085  ]` |
| `[...]`, `[^...]` of class members only | a class of the translated members; negated classes use the negated form |
| `(...)`, `(?:...)`, `\|` | themselves |
| `*`, `+`, `?`, `{n}`, `{n,}`, `{n,m}`, and their lazy `?` variants | themselves |

**Class members** are literal BMP non-surrogate characters, the escapes above that name a
non-surrogate, escaped metacharacters, ranges whose span does not touch U+D800–U+DFFF, and the
positive escapes `\d`, `\w` and `\s`, expanded in place (`[\d_]` becomes `[0-9_]`). The complemented
escapes `\S`, `\D` and `\W` are not allowed inside a class.

**The negated form** of a class `X` matches exactly one Java code point in both ECMAScript modes:

```
(?:[\uD800-\uDBFF][\uDC00-\uDFFF]|[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]|[^X\uD800-\uDFFF])
```

The alternatives match a surrogate pair, a lone high surrogate, a lone low surrogate, and any other
code point outside `X`, so backtracking can never split one character into two matches. The
lookbehind requires an **ECMAScript 2018 or later** regex engine on the client.

The same form is valid with and without `u`. With `u`, the escapes `\uD800`–`\uDFFF` in a class
denote lone surrogate code points, which a well-formed string never contains as units of a pair:
the pair alternative then never matches, a supplementary character matches the last alternative as
one code point, and a lone surrogate in the input still matches its own alternative. No `u`-specific
variant is needed.

The published form of a translated pattern `t` is anchored as `^(?:t)$`. Several published
patterns on one property, including `notBlank`'s, are written as an `allOf` of single-`pattern`
schemas inside that property.

### Unpublishable patterns

Anything outside the subset is not publishable: flags, inline flags, possessive quantifiers, atomic
groups, lookarounds, backreferences, named groups, `^` or `$` inside the pattern, `\b`, `\B`, `\A`,
`\Z`, `\z`, `\G`, `\R`, `\h`, `\H`, `\v`, `\X`, `\p{…}`/`\P{…}`, `\Q…\E`, octal and `\x{…}`
escapes, class union or intersection, `\S`/`\D`/`\W` inside a class, a class range spanning the
surrogates, and literal non-BMP or surrogate characters.

Such a pattern **stays in the IR and stays enforced by Bean Validation**, but it is left out of the
OpenAPI and MCP schemas, with the WARNING above. Publishing an approximation would make clients
reject values the service accepts, or accept values it rejects; omitting it only makes the schema
more permissive, and the service still has the last word.

### `notBlank`

`notBlank` follows Hibernate Validator 8.0.3's `@NotBlank`, which ai-atlas is tested with: the value
must contain at least one UTF-16 code unit above U+0020. So U+00A0 and U+2003 count as non-blank,
and U+0000–U+0020 do not. It is published as the pattern `[^\u0000- ]` together with
`minLength 1`. Other Bean Validation providers may define blankness differently.

This pattern is deliberately **not** anchored, the one exception to the `^(?:t)$` rule above. JSON
Schema and OpenAPI patterns are unanchored searches, so `[^\u0000- ]` accepts a value exactly when
some code unit anywhere in it is above U+0020, which is `notBlank`. It is not a translated Java
pattern, so the whole-string anchoring that makes a translated pattern match like Java's
`matches()` does not apply to it.

## Length units

Bean Validation's `@Size` counts **UTF-16** code units, while `minLength`/`maxLength` count code
points in both published schemas: in the MCP JSON Schema 2020-12 dialect, and in OpenAPI 3.0.3,
whose Schema Object takes them from JSON Schema. ai-atlas publishes the lengths as declared:

- for text in the Basic Multilingual Plane the two agree exactly;
- for characters outside it, such as U+1F600 (one code point, two UTF-16 units), the schemas and
  Bean Validation can disagree. Bean Validation stays the enforced limit at the MCP boundary.

## Behavioural hints

`@AgenticExposed` has four hint attributes, of `Hint { UNSET, TRUE, FALSE }`, each default `UNSET`:

| Attribute | MCP tool annotation |
|-----------|---------------------|
| `readOnly` | `readOnlyHint` |
| `destructive` | `destructiveHint` |
| `idempotent` | `idempotentHint` |
| `openWorld` | `openWorldHint` |

- A method-level value other than `UNSET` overrides the class-level one.
- Nothing is inferred from method names, parameters or the HTTP method. Only hints declared `TRUE`
  or `FALSE` are published.
- With the flag on, an AI-channel method whose four hints all resolve to `UNSET` gets a WARNING,
  which is an ERROR under `ai.atlas.strict`.

```java
@AgenticExposed(description = "Finds orders", readOnly = Hint.TRUE, openWorld = Hint.FALSE)
public List<Order> find(String status) { ... }
```

**Hints are client guidance, not authorization.** An MCP client may use them to decide whether to
ask the user before calling a tool, but nothing in ai-atlas or MCP enforces them: a tool declared
`readOnly` can still be called, and the declaration does not stop the service from writing. Keep
access control in your security layer.

## The `ai.atlas.constraints` flag

The IR records constraints and hints on every build. Publishing them in generated code is opt-in
through the processor option `ai.atlas.constraints` (`true`/`false`, case-insensitive, default
`false`; any other value is a compile ERROR).

```kotlin
agentic {
    constraints.set(true)
}
```

The Gradle plugin passes it to the main `compileJava` only, and only when `constraints` is set, so
a value added to `options.compilerArgs` stands otherwise. Other build tools pass
`-Aai.atlas.constraints=true` to javac.

**With the flag off**, every generated source and resource is byte-identical to earlier releases,
apart from the IR files.

**With the flag on:**

- **OpenAPI** (3.0.3): each query parameter carries its `required` and its constraints, and each
  DTO property its field constraints, in the OpenAPI 3.0 dialect: `minimum`/`maximum` with boolean
  `exclusiveMinimum`/`exclusiveMaximum: true`, `minLength`, `maxLength`, `minItems`, `maxItems`,
  and publishable patterns. `@Positive` is `minimum: 0, exclusiveMinimum: true`.
- **REST controllers** bind an `OPTIONAL` parameter with `@RequestParam(required = false)`.
- **MCP tool classes** set `@ToolParam(required = …)`, carry each parameter's effective contract as
  Bean Validation annotations (`@DecimalMin`/`@DecimalMax` with `inclusive`, `@Size`, `@Pattern`,
  `@NotBlank`, and `@NotNull` on a required reference type), and are annotated `@Validated`.
- **`META-INF/ai-atlas/mcp-tools.json`** lists one entry per AI-channel tool active at the configured
  major, ordered by tool name: its `name`, an `inputSchema` in the JSON Schema 2020-12 dialect that
  MCP uses, and `annotations` holding the declared hints. In this dialect an exclusive bound is the
  numeric `exclusiveMinimum`/`exclusiveMaximum` in place of `minimum`/`maximum`, so `@Positive` is
  `exclusiveMinimum: 0`.

## Enforcement

With the flag on, the generated MCP tool class is `@Validated` and carries the effective
constraints, so Spring's method validation rejects a violating call before it reaches the service,
and the MCP client receives a tool error.

That needs Bean Validation at compile time and at runtime, **which ai-atlas never adds for you**:

- **Compile time:** when `jakarta.validation.constraints.NotNull` does not resolve in the
  compilation, the processor emits neither the Bean Validation annotations nor `@Validated`, and
  reports one NOTE saying the MCP constraints are advisory.
- **Runtime:** when an `mcp-tools.json` is present and the application has no
  `MethodValidationPostProcessor` bean, the runtime logs one WARNING at startup saying the MCP
  constraints are advisory.

To enforce them, add `spring-boot-starter-validation` to the application. Without it the schemas
still describe the constraints to clients, but a call that violates them reaches the service.

## The runtime

The runtime registers the generated tools as MCP tool specifications. Spring AI derives each tool's
input schema from the Java signature; for a tool listed in any `META-INF/ai-atlas/mcp-tools.json`
on the classpath, the runtime **merges** the entry into that derived schema rather than replacing it:

- per property, it adds the constraint keywords (bounds, exclusives, lengths, items, `pattern` or
  `allOf`) and the property's membership in `required`;
- it keeps every other derived keyword: `type`, `items`, nested `properties`, `format`,
  `description`, `enum` and the rest. **The derived `type` is never changed**, because it comes from
  the real Java signature;
- a keyword that does not fit the derived type (bounds on anything but `integer`/`number`, lengths
  and patterns on anything but `string`, item bounds on anything but `array`) is left out with one
  WARNING naming the tool, property, type and keyword. Bean Validation still enforces it when method
  validation is present (see the advisory warning);
- a generated property that is not in the derived schema is not added, with one WARNING;
- the merged schema declares `"$schema": "https://json-schema.org/draft/2020-12/schema"`, and the
  tool's annotations are the entry's hints.

Tools without an entry keep their derived schema, without hints. Each tool name is registered once;
two resources listing the same tool name fail startup, naming both. While Spring AI's tool-callback
conversion is on (`spring.ai.mcp.server.tool-callback-converter`, `true` by default), an
application's own `ToolCallbackProvider` bean takes precedence: a tool it also provides is
registered through it alone, without the generated constraints and hints, and the runtime logs one
WARNING naming the tool and the provider bean. With the conversion off, Spring AI registers no
provider's tools, so AI-ATLAS registers the tool itself, with its generated schema and hints.

### SYNC servers only

Schemas and hints are applied when Spring AI's MCP server type is `SYNC`, the default. With `ASYNC`
or `STATELESS`, the runtime keeps the derived registration and logs one INFO line saying the
generated schemas and hints are not applied. Bean Validation on the tool class still applies
wherever method validation is configured.
