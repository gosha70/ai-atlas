# Phase 5 spike: resource-oriented REST, explicit first and convention second (epic #23 §9)

Branch `claude/phase5-rest-spike`, cut from `master` at `f9d0f68`, which is after Phases 0–4 and
the #50 fix. Unless a line says otherwise, every `file:line` below refers to `master` at `f9d0f68`,
with paths under `modules/processor/src/main/java/com/egoge/ai/atlas/processor/` shortened to the
part after `processor/`. The prototype's own files are listed in §5.

## TL;DR

- **Today the REST surface is pure RPC, and the rule is computed in four places.** GET without
  parameters and POST with them; the path is `/<service-kebab>/<method-kebab>`; every argument is a
  query parameter; the success status is always 200. The same rule is recomputed by
  `IrBuilder`, `RestControllerGenerator`, `OpenApiGenerator` and `RestMappingRegistry`, plus a
  fifth copy in `DeprecationManifestGenerator`. They agree only because each copy is the same
  one-liner.
- **The Contract IR already has a REST slot and the gate already guards it.**
  `ContractIr.Rest(httpMethod, path)` is recorded for every API operation, and the gate classifies a
  changed HTTP method or path as BREAKING. It has **no status and no parameter location**, so
  those could change silently.
- **One normalised mapping works.** The prototype, behind `-Aai.atlas.rest=true`, adds
  `@AgenticExposed(rest = @Rest(method, path, status, style, resource))` and
  `@AgenticParam(in = PATH|QUERY|BODY)`. It resolves each operation's mapping **once**, into the
  IR's `rest` slot, and the controller, OpenAPI, the collision check and the gate all read that.
  27 tests pass. They dispatch real requests to the generated controllers through Spring MVC's
  `MockMvc` and compare Spring's own annotations with the OpenAPI document, operation by operation.
- **An opt-in CRUD convention works and stays deterministic.** A class-level
  `@Rest(style = CRUD)` maps `findAll`, `findById`, `create`, `update` and `delete`/`deleteById` by
  name *and* parameter shape. Every other method keeps its RPC mapping. Explicit metadata wins
  **attribute by attribute**.
- **Byte-identical when off, and when on with nothing declared.** The master golden test
  (`IrRewireGoldenTest`, 24 cases including the demo) still passes. With the option on and no
  declaration, every generated source and resource, apart from the IR, is byte-identical.
- **Three things the real phase must decide, each proved by a test:**
  1. **Entity request bodies bypass the whitelist on input.** Spring binds the JSON body onto the
     entity with Jackson, so a property with a setter but no `@AgenticField` (an SSN in the test)
     is written. OpenAPI says the body is `OrderDto`, which has no such property (Q3).
  2. **A scalar `String` body is raw text.** Spring's `StringHttpMessageConverter` hands the service
     the JSON string with its quotes, while OpenAPI says `application/json` (Q3).
  3. **The spike's IR shortcut breaks lock mode.** To stay byte-identical, the spike writes
     `status` and `parameterIn` only with the option on. The comparison gate is silent when the
     option is turned on, but lock mode sees every API operation change. An `irVersion` 4 with
     always-present slots and an exact migration fixes it (Q7).
- **MCP is unaffected.** The MCP tool class and `mcp-tools.json` are byte-identical with and
  without REST metadata (test). Only one runtime piece is affected: the deprecation-header filter
  matches exact paths, so it cannot match a `{id}` template (Q10).

---

## 1. How a REST operation is mapped today

| Aspect | Rule | Where |
|---|---|---|
| Which methods | API-channel methods active at `ai.atlas.api.major` | `generator/RestControllerGenerator.java:84`, `generator/OpenApiGenerator.java:247`, `util/RestMappingRegistry.java:36-38` |
| Controller class path | `@RequestMapping("<basePath>/v<major>/<service-kebab>")` | `RestControllerGenerator.java:92` |
| Method path | `/<method-kebab>`; kebab-casing is `([a-z])([A-Z]) → $1-$2`, lower-cased | `RestControllerGenerator.java:131`, `:227-231` |
| HTTP method | **GET with no parameters, POST with any** | `RestControllerGenerator.java:134-144`; again in `OpenApiGenerator.java:250-252`, `RestMappingRegistry.java:40-43`, `contract/IrBuilder.java:269-274`, `generator/DeprecationManifestGenerator.java:52-53` |
| Parameter binding | Every argument `@RequestParam`; `@RequestParam(required = false)` when `ai.atlas.constraints` is on and the IR says optional | `RestControllerGenerator.java:167-180` (optional `:171-175`) |
| Request body | **None.** `REQUEST_BODY` is declared (`:43`) but never used | — |
| Response | `XDto`/`List<XDto>` when `returnType` resolves, else the raw return; `void` for `void` | `RestControllerGenerator.java:154-165`, `:182-191` |
| Success status | **Always 200.** No `@ResponseStatus`; a `void` handler answers 200 with an empty body | — |
| OpenAPI path and method | The same kebab path and GET/POST rule, recomputed | `OpenApiGenerator.java:241-256` |
| OpenAPI parameters | `in: query`, `required: true`, or the IR requiredness when constraints are on; constrained schema when constraints are on | `OpenApiGenerator.java:272-293` |
| OpenAPI response | Only `"200"`; content by return shape: DTO `$ref`, `text/plain` for `String`, scalar schema, array of scalars, else `object`; none for `void` | `OpenApiGenerator.java:295-303`, `:331-355` |
| operationId | Method name when unique, else `{Service}_{method}_{httpMethod}` (+`_N`), assigned from the IR's `rest` | `contract/ContractProjection.java:296-306`, `:315-…`; consumed at `OpenApiGenerator.java:159-162` |
| Collision check | Same `"METHOD path"` string → ERROR on each method | `util/RestMappingRegistry.java:35-75`, recorded at `AgenticProcessor.java:387`, reported at `:352` |

**The generators do not read the IR's REST mapping.** They receive a `MethodModel` projected from the
IR (`ContractProjection.java:263-…`), which carries no REST data, and each recomputes the rule. The
IR's `rest` is read only by the operationId assignment and the gate.

## 2. Phase 0's canonical parameter representation

- **Owner decision, 2026-09-24:** "Query params — keep the controllers as they are and correct the
  OpenAPI document" (`specs/contract-quality-foundations/origin/2026-09-24-owner-decisions.md`).
- **Spec:** FR-003, `specs/contract-quality-foundations/spec.md:78-83`. The controller keeps
  `@RequestParam`, and OpenAPI describes each argument as `in: query`, `required: true`, with **no
  `requestBody`**.
- **Documented** in `CHANGELOG.md:49-56` ("Query parameters are canonical"),
  `docs/annotation-guide.md:233-240` and `docs/processor-internals.md:146,157`.
- **Phase 3 refined requiredness only.** A parameter is optional only when it is declared
  `Requiredness.OPTIONAL` (`docs/constraints-and-hints.md:94-99`), and the controller then binds
  `@RequestParam(required = false)`.
- **Phase 3's gate reasoning depends on it.** Entity-field constraints are INFORMATIONAL "because
  ai-atlas generates no request body" (`docs/contract-governance.md:209-212`). A request body
  invalidates that assumption, see Q3 and Q7.
- **Phase 4 deferred its input rule to this phase.** A *required* input field not eligible for a
  channel is an ERROR, and an *optional* one is left out of that channel's input schema (#51, item 3).

**Phase 5 does not change the canonical representation.** Query stays the default location of every
parameter the RPC fallback maps. Path and body locations exist only where declared, or where the CRUD
convention applies.

## 3. The Contract IR's REST mapping, and what the gate does with it

- **The slot.** `ContractIr.Rest(httpMethod, path)` (`contract/ContractIr.java:173-181`) holds
  `GET`/`POST` and `/<service-kebab>/<method-kebab>`, independent of the base path and major. It is
  `null` for an operation not on the API channel (`IrBuilder.java:269-274`) and is written as
  `"rest": {"httpMethod", "path"}` (`contract/IrJson.java:198-204`). Nothing records a status or a
  parameter's location.
- **The comparison gate** (`contract/ContractComparison.java`), for operations active at the
  baseline major. All of these are BREAKING, with `operationRemedy()` (`:405-408`), "apiUntil on the
  old operation, plus a replacement with apiSince = M+1":

  | Change | Direction | Where |
  |---|---|---|
  | `rest.httpMethod` changes | INPUT | `:353-355` |
  | `rest.path` changes | INPUT | `:356-357` |
  | The operation loses the API channel | INPUT | `:344-352` |
  | The operationId changes, naming the operations whose addition caused it | INPUT | `:312-325` |
  | A parameter is renamed | INPUT | `:390-393` |
  | The base path changes | document | `:94-96` |
  | The operation is removed | INPUT | `:305-309` |

  A **status change** or a **parameter moving between query, path and body** has no slot, so it
  would pass unnoticed.
- **Lock mode** (`ContractGate.documentDifferences`, `contract/ContractGate.java:262-…`) compares
  operations by record equality. **Any** change to `Rest` is therefore a lock-mode difference,
  including a key the spike adds only with the option on (Q7).
- **Other consumers of the verb and path:**
  - `DeprecationManifestGenerator.java:45-57` writes `METHOD path` from the RPC rule.
  - The runtime `DeprecationHeaderFilter` looks it up by **exact** `request.getMethod() + " " + path`
    (`modules/runtime/.../security/DeprecationHeaderFilter.java:58-63`, `:97`).

## 4. MCP's relation to the REST mapping

- The MCP tool class keeps AI-channel methods (`generator/McpToolGenerator.java:93`) and binds
  arguments with `@ToolParam` by parameter name (`:171-…`). `mcp-tools.json` holds `inputSchema`
  and hints only.
- **Neither reads a verb, a path, a status or a location.** A REST mapping is invisible to MCP.
- **Parameter names are on the MCP wire.** So a change the REST side considers harmless, such as
  renaming a path variable together with its parameter, is still breaking for MCP (Q7).

## 5. The prototype

**Option:** `-Aai.atlas.rest=true|false`, default off (`generator/RestOption.java`, `OPTION`). It is
registered in `AgenticProcessor`'s `@SupportedOptions` and read by `IrBuilder.resolveRest()`. The CLI
passes it through its generic `-A`. The Gradle plugin is not wired; that would be
`agentic { rest = true }`.

**Annotations** (`modules/annotations`):

```java
@AgenticExposed(rest = @Rest(resource = "orders"))            // class: style, resource
public class OrderService {
    @AgenticExposed(description = "Order by id", returnType = Order.class,
            rest = @Rest(method = HttpMethod.GET, path = "/{id}"))            // method: method, path, status
    public Order get(Long id) { … }

    @AgenticExposed(description = "Place an order", returnType = Order.class,
            rest = @Rest(method = HttpMethod.POST, path = "", status = 201))
    public Order place(Order order) { … }                                     // entity → body

    @AgenticExposed(description = "Change status", returnType = Order.class,
            rest = @Rest(method = HttpMethod.PATCH, path = "/{id}/status"))
    public Order changeStatus(Long id, String status) { … }                  // id → path, status → query

    @AgenticExposed(description = "Cancel", rest = @Rest(method = HttpMethod.DELETE, path = "/{id}"))
    public void cancel(Long id) { … }                                        // void DELETE → 204

    @AgenticExposed(description = "Number of orders")
    public long count() { … }                                                // RPC fallback: GET /orders/count
}
```

- `@AgenticExposed.Rest` is a nested annotation. Its defaults mean "derive":
  - `method = UNSET`, `path = "\0"`, `status = 0`, `style = INHERIT`, `resource = ""`;
  - `path = ""` is the resource itself.
- `@AgenticParam(in = DEFAULT|PATH|QUERY|BODY)`.

**Resolution** (`RestOption.map`). Each attribute resolves on its own, in this order: explicit, else
the matching CRUD rule, else today's RPC rule.

| Attribute | Explicit | CRUD rule (`style = CRUD`) | RPC fallback |
|---|---|---|---|
| Resource | class `resource` | — | `<service-kebab>` |
| HTTP method | `@Rest(method)` | from the rule table below | GET with no parameters, POST with any |
| Path below the resource | `@Rest(path)` | from the rule table | `/<method-kebab>` |
| Status | `@Rest(status)` | the rule's status, **only while the rule's HTTP method is the effective one** | 204 for a `void` DELETE, else 200 |
| Parameter location | `@AgenticParam(in)` | named by a `{var}` → PATH; an `@AgenticEntity` (or subtype) → BODY; else QUERY | a `{var}` → PATH; else QUERY (entities too, as today) |

**CRUD rules**, matched on the name **and** the parameter shape. A *scalar* is a primitive, its box,
`String`, an enum, `UUID`, `BigDecimal` or `BigInteger`.

| Method | Parameters | Mapping | Status |
|---|---|---|---|
| `findAll`, `list` | none | `GET /<resource>` | 200 |
| `findById`, `getById` | one scalar `p` | `GET /<resource>/{p}` | 200 |
| `create` | one entity | `POST /<resource>`, body | 201 |
| `update` | scalar `p`, entity | `PUT /<resource>/{p}`, body | 200 |
| `delete`, `deleteById` | one scalar `p` | `DELETE /<resource>/{p}` | 204 if `void`, else 200 |

Anything else, including `findByStatus`, `save` and `create(String name)`, keeps the RPC mapping.

**Validation** (compile ERRORs on the method, parameter or class):

| Error | Message fragment |
|---|---|
| Malformed path | "must be empty or '/'-separated segments" |
| A `{var}` naming no parameter | "names no parameter" |
| A `{var}` repeated | "appears more than once" |
| `in = PATH` without a `{var}` | "has no {id}" |
| A `{var}`'s parameter declared QUERY or BODY | "is named by the path variable" |
| A non-scalar path parameter | "must be a scalar" |
| More than one body | "at most one parameter may be in the body" |
| A body on GET or DELETE | "must not have a request body" |
| A status outside the 2xx constants Spring's `HttpStatus` names (200–208, 226) | "must be a 2xx success status" |
| 204 on a method that returns a value | "carries no body" |
| `method`, `path` or `status` on a class | "declare them on each method" |
| `style` or `resource` on a method | "declare them on the service class" |
| A resource that is not a single segment | "must be one path segment" |
| Any REST declaration with the option off | "has no effect unless ai.atlas.rest=true" |

**One normalised model** (`contract/ContractIr.java`, `Rest`):
- `Rest` becomes `(httpMethod, path, status, parameterIn)`.
- `IrBuilder.addOperation` fills it once, through `RestOption.map`. An invalid mapping drops the
  operation, and the ERROR fails the compilation.
- The generators receive it by operation key (`key -> projection.operation(key).rest()`):
  - `RestControllerGenerator` emits `@{Get,Post,Put,Patch,Delete}Mapping`, `@ResponseStatus(HttpStatus.X)`
    when the status is not 200, and `@PathVariable("name")`/`@RequestBody`/`@RequestParam`.
    The class-level path is the resource.
  - `OpenApiGenerator` emits the same path, method and status; `in: path`/`in: query`; a
    `requestBody` whose schema is a `$ref` to the DTO for an entity; and no content for 204.
  - `RestMappingRegistry` keys collisions by `Rest.routeKey()`, which turns every `{var}` into `{}`,
    so `/{id}` and `/{orderId}` collide as they do in Spring.
- **Byte-identical off.** With the option off, `status` and `parameterIn` are `null`, which means
  200 and every parameter in the query, and `IrRestJson` omits them. With the option on, they are
  always recorded.

**Gate** (`contract/ContractComparison.java`, in `compareOperation`):
- `rest.status` changes: BREAKING OUTPUT.
- `parameter N.in` changes: BREAKING INPUT.
- `rest.path` changes only in variable names: COMPATIBLE. Any other path change stays BREAKING.
- A document without `status` or `parameterIn` is compared as 200 and QUERY.

**Tests:** 27 in all, 15 of them one parameterized test, and all pass. They are in
`modules/processor/src/test/java/com/egoge/ai/atlas/processor/`: `RestSpikeTest` (7, generation and
runtime), `RestSpikeContractTest` (20, compile errors, IR and gate), and `RestSpikeSupport` (fixtures
and helpers).

| Test | Proves |
|---|---|
| `explicitMappingsAreServedAsDeclaredAndOpenApiDescribesTheSameOperations` | The controller's Spring annotations and the OpenAPI operations give the same `VERB path status [locations]` set, the document passes `OpenAPIV3Parser`, and `MockMvc` serves each one: GET `/{id}`, GET with a query, POST body → **201**, PATCH `/{id}/status`, DELETE → **204** with no body, and the RPC fallback `GET /orders/count`. |
| `crudConventionDerivesVerbPathAndStatusAndLeavesOtherMethodsRpc` | The five rules, an explicit `GET /by-name/{name}` in a CRUD service, and `activate(id)` falling back to `POST /customers/activate`. Controller = OpenAPI, and every route is served by `MockMvc`. |
| `explicitMetadataWinsOverTheConventionAttributeByAttribute` | An explicit path keeps the rule's verb; an explicit status wins; an explicit POST on `deleteById` drops the rule's 204. |
| `mcpToolAndToolSpecificationsAreUnchangedByRestMetadata` | The MCP tool class and `mcp-tools.json` (constraints on) are byte-identical with and without `@Rest`. |
| `routesThatDifferOnlyByVariableNamesCollide` | `GET /{id}` versus `GET /{number}`, and an explicit `POST /activate` versus an RPC `activate(id)`: an ERROR on each method, naming the other. |
| `invalidDeclarationsAreCompileErrors` (15 cases) | Each validation row above except the option-off one. |
| `declarationsWithTheOptionOffAreAnError` | The option-off ERROR, naming the route that would still be served. |
| `contractIrRecordsTheResolvedMapping` | `api.ir.json` carries `httpMethod`, `path`, `status` and `parameterIn` per operation. |
| `gateClassifiesChangedVerbPathStatusAndLocationAsBreaking` | Method, status, path and location changes are each BREAKING. A variable-only rename is a COMPATIBLE path change, but the parameter rename is still BREAKING (Q7). |
| `turningTheOptionOnWithoutDeclarationsIsNoContractChange` | `ContractGate.compare` is empty both ways. **Lock mode lists every API operation** (Q7). |
| `optionOnWithoutDeclarationsGeneratesByteIdenticalSourcesAndResources` | Every generated file except the IR is byte-identical. The IR differs only by the two new keys. |
| `entityRequestBodyBindsPropertiesOutsideTheWhitelist` | The whitelist gap on input (Q3). |
| `scalarStringBodyIsBoundAsRawTextWhileOpenApiSaysJson` | The raw-text `String` body (Q3). |

**Golden comparison with `master`.** `IrRewireGoldenTest`, `master`'s snapshot of 10 fixtures plus the
demo with the option off, still passes: 24 tests, 0 skipped, including
`demoOutputMatchesTheGoldenSnapshot`. The full processor suite passes (§8).

**Sample output** is in `sample-output/`: the `OrderService` and `CustomerService` fixtures, the
generated controllers, OpenAPI and IR.

**Known prototype limits, deliberately left for the real phase:**
- no `irVersion` bump; the new keys are optional (Q7);
- no entity-body whitelist and no Phase 4 input rule (Q3);
- the deprecation manifest and runtime filter still use the RPC path (Q10);
- REST declarations on an AI-only method are silently ignored; the real phase should WARN;
- no Gradle plugin wiring and no docs;
- no per-major REST mapping (Q10).

## 6. Open design questions for the owner

Each gives options, trade-offs and a **recommendation**. `ISSUE-DRAFT.md` repeats them as "Owner
decision needed".

### Q1. Attribute shape

| Option | Trade-offs |
|---|---|
| **A. Nested `@AgenticExposed(rest = @Rest(method, path, status, style, resource))`** (prototype) | One annotation, as the epic says ("explicit REST metadata to `@AgenticExposed`"). REST settings are grouped and visibly REST-only. `@Rest` has no target, so it cannot be put anywhere on its own. Verbose: `rest = @Rest(method = HttpMethod.GET, path = "/{id}")`. Attributes are read from annotation mirrors, because javac's reflective proxy of a nested annotation throws `AnnotationTypeMismatchException` for later-round sources (found by `ContractIrTest.declarationsGeneratedInALaterRoundAreRecorded`). |
| B. Flat attributes on `@AgenticExposed` (`httpMethod`, `path`, `status`, `restStyle`, `resource`) | Shortest. It adds five attributes to an annotation that already has 13, including MCP hints. `path` and `status` are ambiguous next to MCP attributes, and "class may set only style/resource" is harder to see. |
| C. A separate `@AgenticRest` on type and method | Short (`@AgenticRest(method = GET, path = "/{id}")`) and clearly separate. It is a second annotation that means nothing without `@AgenticExposed`, needs an "orphan" ERROR, and adds an annotation type to `@SupportedAnnotationTypes`. It departs from the epic's wording. |

**Recommendation: A.** Keep `HttpMethod` and `RestStyle` nested in `@AgenticExposed`, and document a
static-import idiom. Parameter location stays on `@AgenticParam(in)`, next to Phase 3's `required`.

### Q2. Path templates and parameter locations

- **Where a path is relative to.** Options:
  - **A. Below the resource** (prototype): `<basePath>/v<major>/<resource><path>`. The version prefix
    and resource cannot be escaped, and one controller class has one `@RequestMapping`.
  - B. Below `<basePath>/v<major>`: full freedom, but two services can share a resource and the
    controller class path disappears.

  **Recommendation: A.**
- **Syntax:** `""` or `/`-separated segments, each a literal (`[A-Za-z0-9._~-]+`) or `{javaIdentifier}`.
  No regex variables (`{id:\d+}`), wildcards, `**`, query strings or matrix parameters. They would
  make routes, OpenAPI and collision detection disagree. **Recommendation:** as prototyped.
- **Default location**, in this order:
  1. explicit `in`;
  2. PATH, when a `{var}` has the parameter's name;
  3. BODY, for an `@AgenticEntity` parameter of an explicit or CRUD mapping;
  4. otherwise QUERY, the Phase 0 canonical form.

  RPC-fallback methods never get a body by default, which keeps the option byte-identical with
  nothing declared. **Recommendation:** as prototyped.
- **Binding names:** `@PathVariable("name")` is explicit. `@RequestParam` stays bare, as today, so
  the byte-identical rule holds. It still needs `-parameters`, as today; document that.

### Q3. Request bodies

**What exists today:** no body at all (Phase 0). The prototype binds a BODY parameter with
`@RequestBody` as its declared Java type. Two tests show why that cannot ship as is:

1. **Entity bodies bypass the whitelist on input.** `place(Order order)` receives an `Order` that
   Jackson filled from every settable property, including `ssn`, which has no `@AgenticField`.
   OpenAPI describes the body as `OrderDto`, which has no `ssn`. The epic's safety model is "a field
   without `@AgenticField` is on no channel". On input, the prototype breaks it.
2. **A scalar `String` body is raw text.** Spring's `StringHttpMessageConverter` runs before
   Jackson, so JSON `"hello"` arrives as the 7-character `"hello"` with its quotes, while OpenAPI says
   `application/json`. Other scalars go through Jackson and are fine.

| Option for entity bodies | Trade-offs |
|---|---|
| **A. A generated input record `XInput`**, the whitelisted `@AgenticField`s of the channel's projection, with a generated `toEntity()`; the controller binds `@RequestBody XInput` and calls `service.create(input.toEntity())` | Keeps the whitelist on input. OpenAPI references `XInput`, which is exactly what is accepted. Needs an accessible no-arg constructor and setters, or a canonical constructor; otherwise a compile ERROR. Read-only fields such as `id` need a rule, below. This is where Phase 4's input rule lands: a *required* input field not eligible for the channel is an ERROR, and an *optional* one is left out of `XInput`. |
| B. Bind the entity, with a runtime whitelisting deserializer (`AgentSafeModule` for input) | No new types. The OpenAPI schema and the accepted shape still differ unless the deserializer rejects unknown properties. A runtime change, the mirror image of #50's serializer fix. Channel-unaware. |
| C. Allow BODY only for non-entity types in 1.x (command records, `Map`, scalars) | Nothing to generate. It rules out the `create(entity)` CRUD rule, which is the case ai-anvil services need most. |
| D. Bind the entity as prototyped | Simple. It is a mass-assignment hole, proved by a test. Rejected. |

**Recommendation: A**, with these rules:
- **Requiredness of `XInput` components:** from Bean Validation on the entity field (Phase 3's
  requiredness model applied to fields). `id` and other fields the caller must not set are excluded
  by a new `@AgenticField(input = false)`, or `readOnly`.
- **Phase 4 input rule** enforced as #51 wrote it.
- **The body parameter's own requiredness** follows `@AgenticParam(required)`, as the prototype
  already emits (`@RequestBody(required = false)`).
- **Scalar bodies:** ERROR for `String` (and `CharSequence`) in BODY, "wrap it in a record". The
  alternative is generating `consumes = "text/plain"` and documenting it that way.
- **Gate:** once bodies exist, an entity field that feeds an `XInput` is also INPUT. Its constraint
  changes need Phase 3's input rules, not today's "entity fields are output, so informational"
  (`docs/contract-governance.md:209-212`). That is a real gate change; see Q7.

### Q4. The CRUD convention and the resource name

| Option (rules) | Trade-offs |
|---|---|
| **A. The five rules above, matched on name *and* parameter shape** (prototype) | Deterministic and documented. It never guesses from a prefix alone: `findByStatus`, `save` (an upsert), `createOrder(String)` and `delete(Order)` are not matched. Covers what ai-anvil generates. |
| B. Prefix rules (`find*` → GET, `delete*` → DELETE, `create*`/`add*` → POST, `update*` → PUT) | Covers more methods. Method naming becomes a semantic claim: `findAndLock` becomes a GET, and the epic says "method naming alone must not become a security boundary". |
| C. No convention; explicit only | Simplest, but every ai-anvil method needs five attributes. The epic asks for the opt-in mode. |

| Option (resource name) | Trade-offs |
|---|---|
| **A. The service's kebab name unless `@Rest(resource)` is set** (prototype) | Unchanged base path, and no inference. `OrderService` in CRUD mode serves `GET /order-service/{id}` until a resource is declared. |
| B. Derive from the class-level `returnType` entity and pluralize (`Order` → `orders`) | Idiomatic. English pluralization is not deterministic enough (`Category`, `Person`, `Status`), and it needs a class-level `returnType`. |
| C. Strip a `Service` suffix and kebab (`OrderService` → `order`) | Deterministic, but singular, and it silently moves every existing RPC path of that service when CRUD is enabled. |

**Recommendation: A and A.** ai-anvil can emit `resource` explicitly. The opt-in is per service
(`@Rest(style = CRUD)` on the class), not global, because the epic reserves the convention for
"services whose semantics are controlled and predictable". A method in a CRUD service that no rule
matches keeps its RPC mapping under the resource.

### Q5. Status codes

| Option | Trade-offs |
|---|---|
| **A. 200 unless declared; the CRUD rule's status (201 create, 204 void delete); 204 for any `void` DELETE** (prototype) | Only structural or explicitly classified inferences. A `void` non-DELETE stays 200 with an empty body, as today. |
| B. 204 for every `void` operation with explicit or CRUD metadata | More idiomatic. It is a status inference from the return type alone, and it differs from the RPC fallback's `void` → 200 in the same service. |
| C. Always 200 unless declared | Simplest, but `create` → 201 is exactly what the epic's acceptance criterion names. |

**Recommendation: A.** Also:
- Validate against Spring's `HttpStatus` 2xx constants.
- ERROR on 204 with a response body.
- OpenAPI describes **only** the success status. Error responses are out of scope.
- 201 without a `Location` header: AI-ATLAS cannot invent the created URI. Document it; a later phase
  could add `@Rest(location = "/{id}")` from the returned DTO's id.

### Q6. Collision and validation errors

- **Collisions:** keep `RestMappingRegistry`, keyed by the resolved mapping with variable names
  normalised. Two open points:
  - **Literal versus variable** (`GET /orders/active` next to `GET /orders/{id}`): Spring routes the
    literal first, so it is **not** an error. Recommend a NOTE, since clients may be surprised.
  - **Across modules:** not detected, as today (the same limit as tool names).
- **Validation list:** as in §5. Recommend also:
  - WARN on `@Rest` or `@AgenticParam(in)` on a method not on the API channel. The prototype ignores
    them silently.
  - ERROR on an entity-typed QUERY parameter. Spring cannot bind it, and today's RPC rule already
    generates such broken bindings silently; flag them in strict mode first.

**Recommendation:** as prototyped, plus the two additions above.

### Q7. Contract IR and gate

| Option | Trade-offs |
|---|---|
| **A. `irVersion` 4: `rest` gains `status` and `parameterIn`, always present for API operations** | Lock mode and the comparison gate agree. Needs a migration, which is **exact**: before Phase 5 every API operation was 200 with every parameter in the query, so a v1–v3 document migrates with no unknown values, like Phase 4's channels. `atlasAccept` writes v4. |
| B. Keep v3 with optional keys (prototype) | No bump. **Lock mode fails when the option is turned on without a single declaration** (proved), because the IR text changes. An older ai-atlas would drop the keys. Breaks the rule that a missing slot in a current document is malformed. |
| C. Put the location on `ContractIr.Parameter` (`in`) instead of `Rest.parameterIn` | It is next to requiredness and constraints. It mixes REST-only data into a record MCP also uses, and it is meaningless for AI-only operations. |

**Recommendation: A**, with `parameterIn` inside `rest`. Always record the **effective** mapping,
with the option off too, as Phase 4 does for channels. Turning the option on changes nothing unless
something is declared.

**Gate rules** (prototyped unless marked):

| Change | Classification |
|---|---|
| `rest.httpMethod` changes | BREAKING INPUT (existing) |
| `rest.path` changes | BREAKING INPUT (existing) |
| `rest.path` changes only in `{var}` names | COMPATIBLE |
| `rest.status` changes | BREAKING OUTPUT |
| `parameter N.in` changes | BREAKING INPUT |
| **Not prototyped:** a parameter rename where the parameter is PATH or BODY on an API-only operation | Should be COMPATIBLE, because its name is not on the REST wire. On an AI operation it stays BREAKING (MCP argument names). Today it is always BREAKING. |
| **Not prototyped, from Q3:** a change to an entity field that feeds a request body | Classified with Phase 3's input direction, not as OUTPUT. |

- **Remedy text.** The existing `operationRemedy()` asks for a replacement operation with
  `apiSince = M+1`, which for a pure route change means a second Java method. Add a remedy naming
  `atlasAccept`, as Phase 4 did, since a mapping has no per-major lifecycle (Q10).

### Q8. The opt-in flag

| Option | Trade-offs |
|---|---|
| **A. A new flag `ai.atlas.rest`** (Gradle `agentic { rest = true }`); declarations with it off are an ERROR (prototype) | Matches #51's decided pattern for Phase 4. A platform team can keep a build RPC-only. The ERROR avoids a declared route silently not being served. One more flag. |
| B. No flag: `@Rest` and `@AgenticParam(in)` are themselves the opt-in | Less ceremony. With nothing declared, output is already byte-identical (proved), so the flag gates only declarations. It departs from the Phase 3 and 4 precedent and gives no build-wide switch. |
| C. Reuse `ai.atlas.projections` or `ai.atlas.constraints` | Couples unrelated shape changes, a silent opt-in on upgrade. |

**Recommendation: A**, for consistency with the owner's Phase 4 decision. With the option off, the IR
still records the effective (RPC) mapping (Q7).

### Q9. Is MCP affected?

**No, confirmed by a test.** The MCP tool class and `mcp-tools.json` are byte-identical with and
without REST metadata. Tool names, argument names and input schemas are independent of verb, path,
status and location.

The only coupling is indirect:
- **Parameter names** are MCP argument names, so the gate must keep a rename BREAKING on AI
  operations (Q7).
- **Entity input records** (Q3) are REST-only. MCP keeps taking method parameters.

**Recommendation:** state "no MCP change" in the child issue.

### Q10. Smaller points to confirm

- **The deprecation manifest and header filter.** The manifest must use the resolved mapping. The
  runtime filter matches exact `METHOD path`, so a templated route (`DELETE /api/v1/orders/{id}`)
  never matches.
  - **Recommendation:** match with Spring's `PathPattern` in `DeprecationHeaderFilter`, a small
    runtime change. Alternatively, have the controller set the headers itself (generated
    `@Deprecated` handlers), which needs no manifest.
- **Per-major mappings.** A mapping is unversioned: one per operation per build, like Phase 4's
  eligibility.
  - Moving a route in v2 while keeping v1's needs two Java methods today.
  - **Recommendation:** unversioned in Phase 5. Revisit per-major attributes (`restSince`) only on demand.
- **operationId.** Unchanged. Assigned from `rest` (`ContractProjection.java:296-…`), so it follows
  the resolved path and method. A CRUD service's shared names (`findById` on two services) get the
  existing `{Service}_{method}_{httpMethod}` form.
- **ai-anvil.** Its generated services should emit
  `@AgenticExposed(rest = @Rest(style = CRUD, resource = "<plural>"))` at class level and method
  names that match the rules. Coordinate with `specs/ai-anvil/plan.md`.
- **Error responses and `Location`.** Out of scope (Q5).

## 7. What I would put in the phase (summary)

- `@AgenticExposed(rest = @Rest(...))` plus `@AgenticParam(in)` (Q1-A), path rules and default
  locations as prototyped (Q2).
- Entity bodies through a generated, whitelisted `XInput` record, with Phase 4's input rule; ERROR
  for `String` bodies (Q3-A).
- The five-rule CRUD convention, opted into per service; resource explicit or service-kebab (Q4-A).
- Status: declared, else the rule's, else 204 for `void` DELETE, else 200 (Q5-A).
- Collisions normalised by variable, the validation list, plus a WARNING for REST metadata on
  non-API methods (Q6).
- IR v4 with an always-present `status` and `parameterIn`, exact migration, and the gate rules
  above (Q7-A).
- A new `ai.atlas.rest` flag, with an ERROR for declarations when it is off (Q8-A).
- No MCP change (Q9). Template-aware deprecation headers (Q10).

## 8. Build

`./gradlew build -x javadoc -Porg.gradle.java.installations.paths=/usr/lib/jvm/java-17-openjdk-amd64,/usr/lib/jvm/java-21-openjdk-amd64 --continue`

- **Passes** (`BUILD SUCCESSFUL`): every module's tests, checkstyle, the Gradle plugin's functional
  tests and the demo.
- Processor module: every test passes, including the 27 spike tests and `IrRewireGoldenTest` (24).
- Maven Central rate-limited this environment (HTTP 429) during full builds. Retry download failures
  before reading a red task as a code failure.
