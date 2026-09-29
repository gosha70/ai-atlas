**Title:** Explicit REST operation metadata and an opt-in CRUD convention (epic #23, Phase 5, §9)

---

Child of #23. Delivers the epic's §9, "Resource-oriented REST should be explicit first,
convention-driven second". Today every generated REST operation is RPC-shaped: GET without
parameters, POST with them, at `/<service-kebab>/<method-kebab>`, every argument a query parameter
and every success a 200. This phase lets a service declare its HTTP method, path, parameter
locations and success status. Services whose semantics are controlled, such as ai-anvil's, can opt
into a CRUD convention. Any other method keeps the RPC mapping.

```
explicit @Rest / @AgenticParam(in)   >   CRUD rule (opt-in per service)   >   RPC fallback (today)
                    resolved once, per attribute, into the Contract IR's rest mapping
                              │
          controller ─────────┼───────── OpenAPI ───────── collision check ───────── gate
```

## Why

- **The REST shape is fixed.** Conventional clients and tooling expect `GET /orders/{id}`,
  `POST /orders` → 201 and `DELETE /orders/{id}` → 204. ai-atlas can only generate
  `POST /order-service/find-by-id?id=…`.
- **The mapping rule is recomputed in five places.** It lives in `IrBuilder`,
  `RestControllerGenerator`, `OpenApiGenerator`, `RestMappingRegistry` and
  `DeprecationManifestGenerator`. They agree only because each copy is the same one-liner. A
  configurable mapping must be resolved once, or the controller and OpenAPI will drift, which is
  exactly what Phase 0 fixed.
- **The gate cannot see a status or a location.** `ContractIr.Rest` records only `httpMethod` and
  `path`. A 200 becoming 201, or a query parameter becoming a body, would pass unnoticed.
- **Method names must not become authoritative** (epic §9). AI-ATLAS retrofits arbitrary enterprise
  services, so inference has to be opt-in, narrow and documented, and explicit metadata always wins.

## Scope (owner decisions needed)

The feasibility spike is on branch `claude/phase5-rest-spike`, in `spike/phase5-rest/REPORT.md`.
- **Prototype:** `-Aai.atlas.rest=true` adds `@AgenticExposed(rest = @Rest(method, path, status,
  style, resource))` and `@AgenticParam(in)`. `RestOption.map` resolves each operation once, into the
  IR's `rest` slot, and the controller, OpenAPI, the collision check and the gate all read that.
- **How it was proved:** `RestSpikeTest` and `RestSpikeContractTest` (27 tests):
  - dispatches real requests to the generated controllers through Spring MVC's `MockMvc` (201, 204,
    path variables, bodies, query parameters);
  - compares the controller's Spring annotations with the OpenAPI operations;
  - validates the document with `OpenAPIV3Parser`.
- **Byte-identical when off:** the master golden snapshot still passes. With the flag on and nothing
  declared, every generated file except the IR is byte-identical.
- **MCP is unaffected:** the MCP tool class and `mcp-tools.json` are byte-identical with and without
  REST metadata.

Each item below is marked **Owner decision needed**, with the spike's recommendation.

### 1. Attribute shape — **Owner decision needed**
- **Recommended:** a nested `@AgenticExposed(rest = @Rest(...))`.
  - `HttpMethod` and `RestStyle` are nested in `@AgenticExposed`.
  - On a method: `method`, `path` and `status`. On a class: `style` and `resource`. Anything else is
    an ERROR.
  - Parameter location is `@AgenticParam(in = DEFAULT|PATH|QUERY|BODY)`, next to Phase 3's `required`.
  - The processor reads `rest` from annotation mirrors, because javac's reflective proxy of a nested
    annotation fails for later-round sources.
- **Alternatives:**
  - Flat attributes on `@AgenticExposed`: five more on an annotation that already has 13.
  - A separate `@AgenticRest`: it needs an orphan check and departs from the epic's wording.

### 2. Path templates and parameter locations — **Owner decision needed**
- **Recommended:**
  - A path is relative to the resource: `<basePath>/v<major>/<resource><path>`. `""` is the resource
    itself.
  - Segments are literals (`[A-Za-z0-9._~-]+`) or `{javaIdentifier}`. No regex variables, wildcards
    or query strings.
- **Default location**, in order:
  1. explicit `in`;
  2. PATH when a `{var}` names the parameter;
  3. BODY for an `@AgenticEntity` parameter of an explicit or CRUD mapping;
  4. otherwise QUERY. Phase 0's canonical form stays the default.
- **RPC-fallback methods never get a body by default.**
- `@PathVariable` is bound by explicit name. `@RequestParam` stays bare, as today, so it still needs
  `-parameters`.

### 3. Request bodies — **Owner decision needed**
- **The spike proved two problems with binding a body as its declared type:**
  - An **entity body bypasses the whitelist on input**: Jackson set a non-`@AgenticField` `ssn`,
    while OpenAPI described the body as `OrderDto`.
  - A **`String` body arrives as raw text**, quotes included, while OpenAPI says JSON.
- **Recommended:**
  - An entity body binds a generated, whitelisted **`XInput`** record of the channel's
    `@AgenticField`s, with a generated `toEntity()`. The entity needs an accessible no-arg
    constructor and setters, or a matching constructor; otherwise a compile ERROR.
  - OpenAPI references `XInput`.
  - Fields the caller must not set, such as `id`, are excluded by a new `@AgenticField(input = false)`.
  - `XInput` requiredness follows Phase 3's rules applied to the field.
  - **Phase 4's input rule is enforced here:** a *required* input field not eligible for the
    operation's channel is an ERROR, and an *optional* one is left out of that channel's `XInput`.
  - A `String`/`CharSequence` BODY is an ERROR ("wrap it in a record").
- **Alternatives:**
  - A runtime whitelisting deserializer: channel-unaware, and a runtime change.
  - Non-entity bodies only in 1.x: rules out the `create(entity)` convention.
  - Binding the entity as prototyped: a mass-assignment hole.

### 4. CRUD convention and resource name — **Owner decision needed**
- **Recommended:** opt-in per service with `@Rest(style = CRUD)` on the class. Five rules match the
  method name **and** parameter shape. A *scalar* is a primitive, its box, `String`, an enum, `UUID`,
  `BigDecimal` or `BigInteger`.

  | Method | Parameters | Mapping | Status |
  |---|---|---|---|
  | `findAll`, `list` | none | `GET /<resource>` | 200 |
  | `findById`, `getById` | one scalar `p` | `GET /<resource>/{p}` | 200 |
  | `create` | one entity | `POST /<resource>`, body | 201 |
  | `update` | scalar `p`, entity | `PUT /<resource>/{p}`, body | 200 |
  | `delete`, `deleteById` | one scalar `p` | `DELETE /<resource>/{p}` | 204 if `void`, else 200 |

  - Any other method keeps its RPC mapping under the resource.
  - Explicit metadata wins **attribute by attribute**. A rule's status holds only while the rule's
    HTTP method does.
  - **Resource:** `@Rest(resource)`, else the service's kebab name. No pluralization inference.
- **Alternatives:**
  - Prefix rules (`find*` → GET): naming becomes a semantic claim.
  - Pluralizing the returned entity: not deterministic.
  - Stripping `Service`: moves existing RPC paths.

### 5. Status codes — **Owner decision needed**
- **Recommended:** declared, else the CRUD rule's status, else 204 for a `void` DELETE, else 200.
  - A status must be one of Spring's `HttpStatus` 2xx constants.
  - 204 on a method with a return value is an ERROR.
  - OpenAPI describes only the success status.
  - A 201 carries no `Location` header; AI-ATLAS cannot invent the created URI.
- **Alternatives:**
  - 204 for every `void` operation with explicit metadata: a return-type inference, and inconsistent
    with the RPC fallback in the same service.
  - Always 200 unless declared: misses the epic's create/delete criterion.

### 6. Collisions and validation — **Owner decision needed**
- **Recommended:** routes collide when they match after `{var}` names are normalised (`/{id}` and
  `/{orderId}` collide, as in Spring). It is an ERROR on each method, naming the others.
- **Also ERRORs**, all prototyped:
  - a malformed path;
  - a `{var}` naming no parameter, or repeated;
  - `in = PATH` without a `{var}`;
  - a `{var}`'s parameter declared elsewhere;
  - a non-scalar path parameter;
  - more than one body;
  - a body on GET or DELETE;
  - a non-2xx status;
  - 204 with a body;
  - misplaced class or method attributes;
  - a multi-segment resource.
- **Add:**
  - a WARNING for `@Rest` or `@AgenticParam(in)` on a method not on the API channel;
  - a NOTE when a literal route sits beside a variable one (Spring prefers the literal).
- **Cross-module collisions:** not detected, as for tool names.

### 7. Contract IR version 4 and the gate — **Owner decision needed**
- **Recommended: `irVersion` 4.** `rest` gains `status` and `parameterIn`, always present for API
  operations, and always the **effective** mapping, with the flag off too.
- **Migration is exact.** Before Phase 5 every API operation was 200 with every parameter in the
  query, so v1–v3 migrate with no unknown values. Lock mode needs no special case, and `atlasAccept`
  writes v4.
- **The spike's shortcut shows why:** it wrote the keys only with the flag on, and lock mode then
  reported every API operation changed when the flag was merely turned on.
- **Gate rules:**

  | Change | Classification |
  |---|---|
  | HTTP method changes | BREAKING INPUT (existing) |
  | Path changes | BREAKING INPUT (existing) |
  | Path changes only in `{var}` names | COMPATIBLE |
  | `status` changes | BREAKING OUTPUT |
  | A parameter's location changes | BREAKING INPUT |
  | A PATH or BODY parameter renamed on an API-only operation | COMPATIBLE (on an AI operation it stays BREAKING: MCP argument names) |
  | An entity field that feeds an `XInput` changes | Classified in the INPUT direction under Phase 3's rules |

  - The remedy text adds `atlasAccept`, since a mapping has no per-major lifecycle.
- **Alternatives:**
  - Optional keys in v3: breaks lock mode, and older readers drop them.
  - The location on `ContractIr.Parameter`: REST-only data in a record MCP uses.

### 8. Opt-in flag — **Owner decision needed**
- **Recommended:** a new `ai.atlas.rest`, exposed by the Gradle plugin as `agentic { rest = true }`.
  - The CLI already passes `-A` through.
  - REST declarations with the flag off are an **ERROR**, naming the route that would still be
    served. This follows #51's Phase 4 decision.
- **Alternatives:**
  - No flag, the declarations being the opt-in: output with nothing declared is already
    byte-identical, but there is no build-wide switch.
  - Reusing an existing flag: a silent opt-in on upgrade.

### 9. MCP — **Owner decision needed (confirm)**
- **Recommended: no MCP change.** Tool names, argument names and `inputSchema` are independent of
  verb, path, status and location. The spike proved byte-identical MCP output.
- Entity input records are REST-only.

### 10. Runtime and smaller points — **Owner decision needed**
- **Deprecation headers.** `DeprecationManifestGenerator` must write the resolved mapping, and
  `DeprecationHeaderFilter` must match templates with Spring's `PathPattern`; it matches exact paths
  today.
- **Mappings are unversioned** (one per operation per build), like Phase 4's eligibility.
- **operationId** is unchanged, and follows the resolved mapping.
- **ai-anvil** emits `@Rest(style = CRUD, resource = "<plural>")` and rule-matching method names.

## Out of scope
- Error responses (4xx/5xx) in OpenAPI, a `Location` header for 201, and content negotiation other
  than JSON.
- Per-major REST mappings (`restSince`/`restUntil`).
- Global CRUD mode, and prefix-based inference.
- Collection safety and pagination (epic §8), and release snapshots (§10).
- Qualified tool names (2.0).

## Acceptance criteria
- [ ] `@AgenticExposed(rest = @Rest(method, path, status))` on a method, and
      `@Rest(style, resource)` on a class, are honoured identically by the generated controller and
      the OpenAPI document. Tests dispatch real requests to the controller and compare each
      operation's method, path, parameter locations, request body and success status with OpenAPI.
- [ ] `@AgenticParam(in)` places a parameter in the path, query or body. The default locations
      follow item 2, and every parameter of an RPC-fallback method stays in the query.
- [ ] The RPC fallback is unchanged. With the flag off, and with it on and nothing declared, all
      generated sources and resources other than the Contract IR are byte-identical to before
      (golden snapshot).
- [ ] The CRUD convention is opt-in per service, and applies exactly the five documented rules.
      Explicit metadata wins attribute by attribute.
- [ ] `create` returns 201. A `void` `delete`/`deleteById`, or any `void` DELETE, returns 204 with no
      content. Declared statuses are honoured and validated.
- [ ] Entity request bodies bind a generated whitelisted input record. A non-`@AgenticField` property
      is not bound (test). Phase 4's input rule is enforced. A `String` body is a compile error.
- [ ] Route collisions, including ones differing only by variable names, and every validation error
      in item 6 fail compilation with a diagnostic naming the declaration.
- [ ] The Contract IR is version 4 with `rest.status` and `rest.parameterIn`. v1–v3 baselines
      migrate exactly, with no lock-mode difference, and `atlasAccept` writes v4.
- [ ] The gate classifies a changed method, path, status or location as breaking, and a
      variable-only path rename as compatible.
- [ ] MCP tool classes and `mcp-tools.json` are unchanged by REST metadata (test).
- [ ] Deprecation headers are sent for templated routes.
- [ ] The Gradle plugin exposes `agentic { rest = true }`, and the CLI passes the option through.
- [ ] Docs cover the attributes, the location defaults, the CRUD rules, statuses, the flag, the input
      records and the IR v4 migration.
- [ ] No network, database, model call or live service anywhere in the processor or the gate.
