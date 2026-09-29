# Phase 5 spike: collection exposure safety (epic #23 §8)

Branch `claude/phase5-collections-spike`, cut from `master` at `f9d0f68`, after Phases 0–4 and the
#50 fix. Unless a line says otherwise, every `file:line` below refers to `master` at `f9d0f68`, and
`processor/` is short for `modules/processor/src/main/java/com/egoge/ai/atlas/processor/`. The
prototype's own files are named in §5.

## TL;DR

- **Today every collection return is exposed as-is, with no diagnostic.** `List`, `Set`, `Iterable`
  and arrays are mapped element by element to `List<XDto>` on both channels. `Stream` and `Map` are
  not recognised as collections at all. Nothing records or checks how large a result can be, and
  strict mode says nothing (proved by `todayAnUnboundedCollectionIsSilentlyAccepted`).
- **Spring Data paging is broken today on every surface.** Tests prove all three:
  1. **REST:** the `Pageable` parameter gets `@RequestParam`, which Spring MVC resolves before Spring
     Data's `PageableHandlerMethodArgumentResolver`. The endpoint answers `400` for a correct
     `?page=1&size=2` request, because it wants a `pageable` string.
  2. **MCP:** Spring AI describes `Pageable` as an object but cannot build the interface from the
     arguments. The tool cannot be called.
  3. **OpenAPI:** the document publishes a required `pageable` query *string*.

  A `Page` or `Slice` return is an `Iterable`, so it is flattened to `List<XDto>`. Its totals,
  page number and `hasNext` are lost.
- **The prototype works.** It sits behind `-Aai.atlas.collections=true`. 20 tests call the generated
  classes: MCP through Spring AI 1.1.7's `MethodToolCallbackProvider`, and REST through MockMvc with
  Spring Data's resolver registered, as Spring Boot registers it. They prove:
  - `Pageable` plus `Page`/`Slice` become real `page`/`size` inputs and a metadata envelope on REST,
    OpenAPI, MCP and `mcp-tools.json`;
  - a declared limit parameter, and a declared `maxResults`, are accepted;
  - every other collection is a WARNING, and an ERROR under `ai.atlas.strict`;
  - the wrappers never truncate. A service that breaks its declared bound gets all five elements
    passed through.
- **The option off is byte-identical.** The existing master golden tests pass unchanged. New
  variants prove that with the option **on** and nothing declared, every fixture and the demo are
  byte-identical too; the flag only adds WARNINGs.
- **The gate is blind to bounds.** Adding or removing `maxResults` leaves `api.ir.json`
  byte-identical, and `ContractGate.compare` returns nothing. Turning `List<T> list()` into
  `Page<T> list(Pageable)` *is* already BREAKING, because the parameter types are part of the
  operation's identity. Phase 5 needs an IR slot for the paging contract and the bound (Q6).
- **One new safety finding:** Spring Data's `sort` parameter lets a REST client sort by **any
  mapped property**, including fields that are not `@AgenticField`. Ordering by a hidden column
  leaks information about it. The prototype keeps `sort` off MCP, and Q4 recommends an allow-list
  before REST accepts it.

---

## 1. How collection returns are classified and exposed today

### 1.1 `ReturnTypeValidator` return kinds

`ReturnTypeValidator.resolveReturnKind` (`processor/util/ReturnTypeValidator.java:31-49`) classifies
the **declared Java return type**:

| Kind | Test | Line |
|---|---|---|
| `ARRAY` | `TypeKind.ARRAY` | `:34-35` |
| `COLLECTION` | erasure assignable to `java.util.Collection` (`List`, `Set`, `Queue`, …) | `:38-41` |
| `ITERABLE` | assignable to `java.lang.Iterable` but not `Collection`. **Spring Data `Page` and `Slice` land here**, as `Slice extends Streamable extends Iterable`. | `:43-46` |
| `NONE` | everything else. **`Stream`, `Map`, `Optional` and custom envelopes land here.** | `:48` |

The kind is stored in `MethodModel.returnKind` (`processor/model/ServiceModel.java`), computed at
`AgenticProcessor.java:425`, and in the IR as `Return.returnKind` (`IrBuilder.java:277-279`).

The element type is read only to validate `@AgenticExposed(returnType)` against it:
- `validateReturnTypeCompat` (`ReturnTypeValidator.java:92-112`) takes the **first type argument**
  of the declared type (`extractCollectionElementType`, `:118-133`). That is why `Page<Order>` with
  `returnType = Order.class` passes: the first argument of `Page<Order>` is `Order`.
- A `Stream<Order>` is `NONE`, so `Stream` itself is checked against `Order`. The check finds them
  `INCOMPATIBLE`, and the method is a compile ERROR (`AgenticProcessor.java:431-440`). Without a
  `returnType`, the `Stream` is returned raw.

### 1.2 Generated MCP tool

`McpToolGenerator.buildToolMethod` (`processor/generator/McpToolGenerator.java`):
- **Return type** (`:159-169`): with a resolved DTO, any kind other than `NONE` becomes
  `List<XDto>`. Otherwise the tool returns the declared type unchanged.
- **Mapping** (`addMappingStatement`, `:264-282`):
  - `COLLECTION`: `.stream().map(XDto::fromEntity).toList()`;
  - `ITERABLE`: `StreamSupport.stream(….spliterator(), false)…`;
  - `ARRAY`: `Arrays.stream(…)…`.

  For a `Page` this materialises the page's content and drops every other property.
- **Parameters** (`:172-189`): every parameter is copied with `@ToolParam`. A `Pageable` stays a
  `Pageable`. Spring AI derives an `object` schema for it, then fails to deserialize the interface.
  `todayAPageableToolCannotBeCalledOverMcp` shows the failure.
- **No bound:** there is no limit, no truncation and no size check. Spring AI serializes the whole
  list, through `AgentSafeToolCallResultConverter` since #50
  (`runtime/mcp/AgentSafeToolCallResultConverter.java:59`).

### 1.3 Generated REST controller

`RestControllerGenerator.buildEndpointMethod` (`processor/generator/RestControllerGenerator.java`):
- **HTTP method:** `GET` without parameters, `POST` with them (`:134-144`).
- **Return and mapping:** identical to MCP (`:155-165`, `:196-214`).
- **Parameters:** every parameter is `@RequestParam` (`:177`, or `required = false` under Phase 3,
  `:171-175`).
  - Spring MVC runs its annotation-based resolvers before the custom ones, so
    `RequestParamMethodArgumentResolver` wins over Spring Data's `PageableHandlerMethodArgumentResolver`.
  - It looks for a request parameter literally named `pageable`, finds none, and answers `400`.
  - `todayAPageableEndpointCannotBeBoundOverRest` shows it through MockMvc, with the Spring Data
    resolver registered.
- **Runtime:** `DtoResponseBodyAdvice` (`runtime/security/DtoResponseBodyAdvice.java:50-54`)
  inspects only the **first element** of a `Collection` body. It never looks at size.

### 1.4 OpenAPI

`OpenApiGenerator` (`processor/generator/OpenApiGenerator.java`):
- **Parameters** (`:273-289`): each parameter is a required `query` parameter. Its schema comes
  from `mapJavaTypeToSchema`, whose fallback is `string` (`:232`). So `Pageable` is published as a
  **required string `pageable`**, which no client can send
  (`todayAPageIsFlattenedToAListAndItsMetadataIsLost`).
- **Response** (`buildResponseContent`, `:331-355`):
  - with a DTO, any collection kind becomes `array` of `$ref XDto` (`:336`), so a `Page` is an array;
  - a scalar-element collection becomes an array of scalars;
  - anything else is `object` (`:354`).

  No `maxItems` is ever emitted on a response.

### 1.5 `mcp-tools.json` (Phase 3, `ai.atlas.constraints` on)

`McpToolsResourceGenerator.inputSchema` (`:142-165`) types a `Pageable` parameter as `object`
(`:201`), like any type with no JSON counterpart. There is no output schema, so the shape of a
collection response is not described anywhere on the MCP side.

### 1.6 Contract IR and gate

- **What the IR records.** An operation's identity is `service#method(parameter types)`
  (`contract/ContractIr.java:151-158`), so a `Pageable` parameter is part of it.
  `Return(javaType, returnKind, returnType, reference)` (`ContractIr.java:211`) records, for example,
  `org.springframework.data.domain.Page<shop.Order>` with kind `ITERABLE`.
- **What the IR does not record.** There is no bound, no paging role and no envelope shape.
- **What the gate compares** (`contract/ContractComparison.java`):
  - a removed operation is BREAKING INPUT (`:307`);
  - `returns.javaType` and `returns.returnKind` differences are BREAKING OUTPUT (`:373-376`).

  So `List<T> list()` → `Page<T> list(Pageable)` is **removed + added** (BREAKING, proved by
  `theGateSeesAListBecomingAPageAsABreakingChange`), and `List<T>` → `Page<T>` alone is a BREAKING
  `returns.javaType` change. **Anything that is not in the Java signature is invisible to the gate**:
  a declared bound appearing, disappearing or changing
  (`theContractIrAndGateDoNotSeeABoundAppearOrDisappear`), or a wrapper whose wire shape changes
  while the Java signature stays the same.

### 1.7 Phase 3 constraints and Phase 4 projections today

- **Phase 3.** `@Size`/`@AgenticConstraints(maxItems)` reach **parameters** (MCP `inputSchema`,
  OpenAPI parameter schema, Bean Validation on the tool) and **entity fields** (DTO and OpenAPI
  schema) (`constraints/ConstraintReader.java:108`, `:118`). Nothing reads a constraint on a
  method's **return**. A limit parameter can already carry a real, enforced ceiling today
  (`@Max(100) int limit`). The `AgenticParam` Javadoc example is exactly such a `limit`
  (`annotations/.../AgenticParam.java:19`).
- **Phase 4.**
  - The MCP tool returns the AI projection: `projections.toolModel` rewrites `returnDtoType`.
  - With projections on, a collection of entities without a resolvable `returnType` is an ERROR
    (`ProjectionsOption.checkResponse`, `processor/generator/ProjectionsOption.java:334`).
    `ReturnedTypes.elementType` walks to `Iterable`, so a raw `Page<Order>` is covered.
  - **Nested collection fields** such as `Order.actions` are unbounded too. They are outside §8's
    operation-level policy (Q8).

## 2. Spring Data `Page`/`Slice`/`Pageable`/`Sort` and `Stream`

| Shape | Kind today | REST today | MCP today | OpenAPI today |
|---|---|---|---|---|
| `Page<T> m(…, Pageable p)` | `ITERABLE` | **400**, `pageable` wanted as a request param | **uncallable**: `Pageable` cannot be deserialized | required `pageable` string; response `array` |
| `Slice<T> m(Pageable p)` | `ITERABLE` | as above | as above | as above |
| `Page<T> m()` (service-chosen page) | `ITERABLE` | works, flattened to `List<XDto>`; totals lost | works, flattened | `array` |
| `List<T> m(Pageable p)` | `COLLECTION` | 400 | uncallable | required `pageable` string |
| `Sort` parameter | — | `@RequestParam Sort` fails the same way | object schema, cannot be deserialized | required string |
| `Stream<T>` + `returnType` | `NONE` | **compile ERROR** (incompatible `returnType`) | ERROR | — |
| `Stream<T>`, no `returnType` | `NONE` | raw `Stream` handed to Jackson | raw `Stream` handed to Spring AI's converter | `object` |
| `Map<K, V>` | `NONE` | raw | raw | `object` |

**Why `Stream` is special.** A Spring Data JPA `Stream<T>` must be consumed and closed inside the
transaction that opened it. A generated wrapper that returns it, or maps it after the service
returned, has already left the service's transaction, and in most setups nothing closes it. The
prototype classifies `Stream` (via `BaseStream`) and `Map` as collections for the diagnostic only;
the generated code is unchanged. Neither raw serialization path was tested in this spike.

**`Sort`** is out of the prototype as a standalone parameter. Inside a `Pageable` it is bound by
Spring Data on REST (see Q4, and the finding in the TL;DR). It is not offered on MCP.

## 3. What the IR and the gate would need

To classify bounded, paged and unbounded status, `ContractIr.Operation` (or `Return`) needs one
more slot. Proposed for `irVersion` 4, details in Q6:

```json
"returns": {
  "javaType": "org.springframework.data.domain.Page<shop.Order>",
  "returnKind": "ITERABLE",
  "returnType": "shop.Order",
  "reference": { … },
  "bound": {
    "style": "PAGEABLE",           // PAGEABLE | LIMIT | DECLARED | NONE
    "envelope": "PAGE",            // PAGE | SLICE | NONE — the wire shape clients receive
    "limitParameter": null,        // name, for LIMIT
    "cursorParameter": null,       // name, for LIMIT with a cursor
    "maxResults": null             // for DECLARED, or a page-size ceiling (Q4)
  }
}
```

- **Effective, not declared.** The slot records what clients actually receive. With the option off,
  every operation records `NONE`/`NONE`, because that is today's wire shape (a list). Turning the
  option on for a `Page`-returning method then shows up as a real output change
  (list → envelope). This mirrors Phase 4's `Field.channels`.
- **Migration is exact.** Nothing before v4 declared a bound or produced an envelope, so every v1–v3
  operation migrates to `style NONE`, `envelope NONE`. Lock mode needs no special case.
- **Gate rules**: proposed in Q6.

## 4. Channels and severity today

- `QualityDiagnostics.resolveKind` (`processor/util/QualityDiagnostics.java:29-40`) maps
  `ai.atlas.strict` to WARNING or ERROR.
- The two existing quality diagnostics, missing description and missing hints (`:42`, `:69`), apply
  to **AI-channel methods only**.
- The epic's own wording is "Strict mode can reject unbounded **AI** collection tools", which frames
  Q5.

## 5. The prototype

**Option:** `-Aai.atlas.collections=true|false`, default off. It is registered in `AgenticProcessor`'s
`@SupportedOptions`, and the name is `CollectionsOption.OPTION`. The Gradle plugin and CLI are not
wired. The plugin wiring would be `agentic { collections = true }`, like `constraints` in
`plugin/ContractArguments.java`.

**Annotations:**
- `@AgenticExposed(maxResults = N)`, default `-1` (none). Method-level only; a class-level value is
  an ERROR.
- `@AgenticParam(paging = LIMIT | CURSOR)`, default `NONE`.

**Classification** (`processor/generator/CollectionsOption.java`, `check`, called from
`AgenticProcessor.recordOperations` for every recorded operation). A return is a collection when its
kind is not `NONE`, or it is a `BaseStream` or a `Map`. Spring Data types are matched **by name**
through `Elements.getTypeElement`, so the processor has no Spring Data dependency.

| Declared | Result |
|---|---|
| one `Pageable` parameter | **paged (PAGEABLE)**. A `Page`/`Slice` return gets an envelope; a `List` return stays a list, bounded by `size`. |
| an integral `@AgenticParam(paging = LIMIT)`, optionally a `CURSOR` | **paged (LIMIT)**. The parameters are already real, so nothing is generated beyond description guidance. |
| `maxResults = N` | **declared bound**: OpenAPI `maxItems: N` on the response array and "Returns at most N results." in the tool description. **Not enforced** (Q3). |
| nothing, or only a `CURSOR` | **WARNING**, and an ERROR under `ai.atlas.strict`, for active operations on **any** channel (Q5), naming the method, return type, channels and the three remedies. |
| misuse | ERROR. The cases: `maxResults` < 1 or on a non-collection; class-level `maxResults`; a non-integral `LIMIT`; two `Pageable` parameters, or two parameters with the same role; a `Pageable` mixed with `LIMIT`/`CURSOR`; paged plus `maxResults` (Q4); a `Pageable` next to a parameter named `page` or `size`. |

With the option off, the only effect is a WARNING that a declared `maxResults` or paging role is
ignored. The IR is never changed.

**Generation, only for operations with a paging contract** (`PagingContract.java`, plus overloads of
the four generators; flag off passes `null` and takes the unchanged code path):

| Surface | `Pageable` parameter | `Page` / `Slice` return |
|---|---|---|
| REST controller | Left **unannotated**, so Spring Data's resolver binds `page`, `size` and `sort`. | Nested `@Generated` record `PageResult<T>(content, number, size, hasNext, totalElements, totalPages)` or `SliceResult<T>(content, number, size, hasNext)`. The content is mapped through `XDto.fromEntity`. |
| MCP tool | Replaced by `Integer page` (optional, `0` when omitted) and `int size` (required). The wrapper passes `PageRequest.of(page, size)`, which **rejects** `size < 1` (`anInvalidPageSizeIsRejectedNotClamped`). **No `sort`.** | The same records, nested in the tool class. With Phase 4 on, the content is the AI projection (`returnDtoType` is already the AI record). |
| MCP description | Appends "Results are paged: pass size … the result carries hasNext, totalElements and totalPages." | |
| OpenAPI | `page` (int ≥ 0), `size` (int ≥ 1), `sort` (string array), none required, and no defaults published (they are application configuration). | An inline object schema with every property required; `content.items` is `$ref XDto`. |
| `mcp-tools.json` | `page` {integer, minimum 0} and `size` {integer, minimum 1, required} in place of `pageable`. | (no output schema, as today) |

**Nothing is synthesised.** Page and size inputs appear only where the service takes a `Pageable`.
A limit is exposed only where the service has the parameter. No wrapper slices, truncates or clamps
a result.

**Sample output** is in `sample-output/`: the fixture sources in `input/`, then the generated
controller, tool, OpenAPI document and `mcp-tools.json`, with the option and `ai.atlas.constraints`
on.

**Tests** are in `modules/processor/src/test/java/.../CollectionsSpikeTest.java`. All 20 pass, and so
does `IrRewireGoldenTest` with its new cases (35 tests, 0 skipped).

| Test | Proves |
|---|---|
| `todayAPageableToolCannotBeCalledOverMcp` | Flag off: the tool keeps `pageable`, and Spring AI cannot build it. |
| `todayAPageableEndpointCannotBeBoundOverRest` | Flag off: `@RequestParam Pageable` answers `400` for `?page=1&size=2`, even with Spring Data's resolver registered. |
| `todayAPageIsFlattenedToAListAndItsMetadataIsLost` | Flag off: `Slice` → `List<OrderDto>`; OpenAPI `array` response and required `pageable` string. |
| `todayAnUnboundedCollectionIsSilentlyAccepted` | Flag off, strict on: no diagnostic. |
| `withTheOptionOffADeclaredBoundOrPagingRoleIsAWarningThatItIsIgnored` | Three WARNINGs: `top`, `search`, `after`. |
| `aPageableAndPageAreRepresentedOnMcp` | Tool schema `status, page, size`; the result for `page=1,size=2` is ids 3–4, `totalElements` 5, `totalPages` 3, `hasNext`; the service received `PageRequest.of(1, 2)`; `page` defaults to 0. |
| `anInvalidPageSizeIsRejectedNotClamped` | `size = 0` fails the call. |
| `aSliceCarriesHasNextButNoTotals` | The `Slice` envelope has exactly `content, number, size, hasNext`. |
| `aPageableAndPageAreRepresentedOnRest` | MockMvc `?page=1&size=2&sort=id,desc` → 200 and the envelope. The service received page 1, size 2, `id: DESC`. |
| `aPageableAndPageAreRepresentedInOpenApi` | Parameters `status, page, size, sort` (paging ones optional); the envelope schema. |
| `mcpToolsJsonAgreesWithTheToolClass` | With Phase 3 on, `mcp-tools.json` has `page`, `size` (required, minimum 1). |
| `theMcpEnvelopeCarriesTheAiProjection` | With Phase 4 on, the MCP envelope holds the AI record and REST's the API record. |
| `aLimitTheServiceHonoursIsAPagingContractAndNothingIsAdded` | No WARNING. The tool keeps `text, limit, after`, with guidance in the description. |
| `aDeclaredBoundIsPublishedButNeverEnforcedByTruncation` | No WARNING; OpenAPI `maxItems: 2`. The service returns 5, and both wrappers return all 5. |
| `anUnboundedCollectionIsAWarning` | `list`, `top` and `after` warn; `after` notes that its CURSOR bounds nothing. `byStatus`, `recent`, `search` and `find` do not. |
| `strictModeMakesAnUnboundedCollectionAnError` | Three ERRORs under strict. |
| `streamAndMapReturnsAreCollectionsToo` | `Stream<String>` and `Map<String, Long>` warn. |
| `misusedDeclarationsAreErrors` | The seven misuse ERRORs. |
| `theContractIrAndGateDoNotSeeABoundAppearOrDisappear` | `api.ir.json` is byte-identical with and without `maxResults`. `compare` and `documentDifferences` are empty. |
| `theGateSeesAListBecomingAPageAsABreakingChange` | Adding a `Pageable` is removed + added. |

**Golden comparison with `master`.**
- `IrRewireGoldenTest` is the snapshot of `master`'s generated output for 10 fixtures plus the demo,
  and it still passes with the option off.
- New variants run the same fixtures and the demo with `ai.atlas.collections=true` and compare them
  to the **same** snapshot. They pass: the demo's unbounded `List` returns only draw WARNINGs.

**Known prototype limits, deliberately left for the real phase:**
- no IR slot and no gate rules (Q6);
- no Gradle or CLI wiring (Q7);
- `sort` is passed through on REST without an allow-list (Q4);
- no runtime check or log when a declared bound is exceeded (Q3);
- the nested envelope records are declared once per wrapper class, so REST and MCP each have their
  own `PageResult`;
- a `Page`/`Slice` returned **without** a `Pageable` still gets the envelope when the flag is on.
  That is a real wire change for a working (lossy) endpoint, and the IR does not see it;
- **`AgenticProcessor` is at its checkstyle limit** (`FileLength` 500; `master` has 499 lines). The
  prototype wiring needed about 8 lines, and fitting it meant removing four blank lines inside
  `process()`. The real phase should move the per-method checks out of `AgenticProcessor`, for
  example into a `QualityDiagnostics`-style hook that the options register with.

## 6. Open design questions for the owner

Each question gives options, trade-offs and a **recommendation**. `ISSUE-DRAFT.md` repeats them as
"Owner decision needed".

### Q1. Which paging contracts to recognise

| Option | Trade-offs |
|---|---|
| A. Spring Data only: a `Pageable` parameter, with a `Page`, `Slice` or collection return | Structural, typed and unambiguous; matches most retrofit targets (JPA repositories). It leaves out every hand-rolled `limit`/`cursor` service, which then needs `maxResults` or a strict-mode exemption. |
| **B. Spring Data, plus explicitly declared `@AgenticParam(paging = LIMIT / CURSOR)`** (prototype) | Also covers hand-rolled services, with no guessing. A LIMIT must be integral. A CURSOR alone bounds nothing, so it still needs a LIMIT or `maxResults`. ai-atlas cannot produce a "next cursor", so a cursor contract's output metadata comes from the service's own return type. |
| C. B plus name-based inference (`limit`, `pageSize`, `offset`, `cursor`, `pageToken`) | Zero annotation effort. It makes method naming authoritative, which the epic rules out ("Method-name inference must not become authoritative"), and a parameter named `limit` that the service ignores would silence the warning. |

**Recommendation: B.** Three sub-decisions go with it:
- **`Pageable` with a plain `List`/`Set` return** counts as paged, bounded by `size`. There is no
  envelope, because the service returns no totals.
- **A `Page`/`Slice` with no `Pageable` parameter** is *not* a paging contract, because clients
  cannot ask for another page. It warns like any unbounded collection unless `maxResults` is
  declared. The envelope question for it is in Q7.
- **`Stream` returns: a WARNING** like the other collections (as prototyped). The real phase could
  make them an ERROR, because a wrapper cannot close them inside the service's transaction.
- **A `CURSOR` parameter is optional by default.** The first call has no cursor. With
  `ai.atlas.constraints` on, the sample output shows that Phase 3 derives `after` (a plain `String`)
  as **required**, on MCP (`required = true`, `@NotNull`) and REST. So an agent cannot make the first
  call without inventing a cursor. The real phase should default a `CURSOR`'s requiredness to
  OPTIONAL unless it is declared otherwise.

### Q2. Declaration shape and name for a bounded result

| Option | Trade-offs |
|---|---|
| **A. `@AgenticExposed(maxResults = N)`, method-level only** (prototype) | One annotation, next to `returnType` and `channels`. It sits oddly with the class-level inheritance every other attribute has, which is why a class-level value is an ERROR. |
| B. A new `@BoundedResult(max = N)` method annotation | Self-describing, and it can grow (`reason = "…"`). It is one more annotation type, and still declaration-only. |
| C. Read Bean Validation's `@Size(max = N)` on the method (a return-value constraint), as Phase 3 reads parameters | Reuses the Phase 3 vocabulary. If the service is `@Validated`, Spring's method validation **enforces** it at the service boundary, which is real enforcement without a wrapper check. It depends on `ai.atlas.constraints` and on the service opting into validation. Many readers would not expect `@Size` on a method to mean "bound". |

**Recommendation: A, named `maxResults`.** The rules are ≥ 1, method-level only, and on a collection
return only. Also accept C as an alternative source when `ai.atlas.constraints` is on; if both are
declared, they must agree (ERROR otherwise). The Javadoc says "declared, not enforced by ai-atlas".

### Q3. Enforced at runtime, or only declared

| Option | Trade-offs |
|---|---|
| **A. Declared only** (prototype) | No cost and no behaviour change. A service that grows past its bound silently floods the agent's context, and the declaration becomes a lie. |
| B. The wrapper fails the call when the result exceeds N (an error, never a truncated result) | The agent never receives an over-large result, and the lie is loud. It still pays the full query cost, and a data-growth event becomes an outage on that tool. |
| C. Declared, plus a runtime WARN log (or metric) when a result exceeds N, and the result is returned unchanged | The same pattern as `DtoResponseBodyAdvice`'s non-DTO warning. Operators learn the bound is wrong without an outage. The agent still receives the large result. |

**Recommendation: A for the declaration, plus C in the runtime module.** Never truncate. Keep B
available only through C's route (the `@Size` return-value constraint, which the service itself
enforces), not in generated code. A paging request that is out of range, such as `size < 1`, is
**rejected**, never clamped. That is already true of `PageRequest.of`.

### Q4. How paging metadata appears in MCP input/output schemas and in OpenAPI

**Inputs:**

| Option | Trade-offs |
|---|---|
| **A. MCP: `page` optional (0), `size` required.** REST/OpenAPI: Spring Data's `page`, `size` and `sort`, none required and no defaults published (prototype). | The agent must choose a page size, so there is no hidden default. REST is exactly what Spring Data's resolver accepts. The configured defaults (`spring.data.web.pageable.*`) and the size cap live in application configuration, which the processor cannot see. |
| B. MCP `size` optional, with a generated default (say 20) | Friendlier to models. ai-atlas would be inventing a default the service never declared. |
| C. MCP `size` optional, defaulted at runtime from the application's `PageableHandlerMethodArgumentResolver` settings | Consistent across channels. It needs runtime wiring in the tool path, and the schema cannot state the default. |

- **Page-size ceiling.** Spring Data caps REST `size` at `max-page-size` (2000 by default) by
  clamping silently; the MCP wrapper has no cap. **Recommendation:** allow `maxResults` on a paged
  method to mean the **page-size ceiling**. It is published as `maximum` on `size` in MCP,
  `mcp-tools.json` and OpenAPI. It is enforced by **rejecting** a larger `size` in both wrappers
  (REST checks `pageable.getPageSize()`), never by clamping. The prototype currently rejects
  "paged + `maxResults`" as an ERROR, pending this decision.
- **`sort` (safety).** A `sort` property can name any mapped entity property, including
  non-`@AgenticField` ones. Ordering by a hidden column leaks information about it (for example,
  sort by `creditScore` and read the order). **Recommendation:**
  - never offer `sort` on MCP;
  - on REST, strip it (`PageRequest.of(p.getPageNumber(), p.getPageSize())`) unless an explicit
    allow-list is declared, for example `@AgenticParam(sortable = {"id", "createdAt"})`, validated
    against the entity's `@AgenticField`s.

  Either way, the service must impose a deterministic order for paging to be stable.

**Output:**

| Option | Trade-offs |
|---|---|
| **A. Nested generated records `PageResult<T>` / `SliceResult<T>`, flat properties** (prototype) | A stable, documented wire shape; `@Generated`, so `DtoResponseBodyAdvice` accepts it; no cross-file coordination. Each wrapper declares its own copy. |
| B. Spring Data's `PagedModel` (`{content, page: {size, number, totalElements, totalPages}}`) | Spring's own recommended stable JSON. It exists only for `Page`, not `Slice`, and it ties the wire shape to a Spring Data version. |
| C. Return the `Page` itself | Spring Data warns that `PageImpl` JSON is not a stable format. It serializes `pageable`, `sort` and other internals. |

- **OpenAPI.** Use an inline envelope schema (as prototyped) rather than named components, which
  would need a naming rule (`OrderDtoPage`?) and collision handling.
- **MCP.** Describe paging in the tool description (as prototyped) until an MCP `outputSchema` is
  emitted. That is tracked with Phase 4's deferred output schema, and would then carry the envelope.

**Recommendation:** inputs A, plus the `maxResults` page-size ceiling and the `sort` rule above;
outputs A.

### Q5. AI-only vs both channels, and severity

| Option | Trade-offs |
|---|---|
| A. Both channels; WARNING, and ERROR under strict, on both (prototype) | Simple and uniform. Strict mode fails builds for REST-only list endpoints, which REST clients handle with their own tooling and which were never the epic's concern. |
| **B. WARNING on both channels; under strict, ERROR for AI-channel operations only** | Matches the epic ("Strict mode can reject unbounded **AI** collection tools") and the two existing quality diagnostics, which are AI-only. REST users still see the warning. |
| C. AI channel only | No noise for REST-only services. It hides a real REST risk (an unbounded `/list` endpoint) that is cheap to report. |

**Recommendation: B.** The message names the channels, as the prototype's already does.

### Q6. Contract IR and gate impact

| Option | Trade-offs |
|---|---|
| **A. `irVersion` 4 with `returns.bound` {style, envelope, limitParameter, cursorParameter, maxResults}**, recording the **effective** contract (§3) | The gate can classify bound changes, and an envelope appearing because the flag was switched on. Needs the exact migration below. |
| B. Optional keys in v3 | Breaks the "a missing slot of the current version is malformed" rule. Older ai-atlas versions would silently drop the keys. |
| C. No IR change | Proved blind: a bound appearing or disappearing leaves the IR byte-identical. |

**Recommendation: A.** Migration from v1–v3 is exact (`NONE`/`NONE`), and `atlasAccept` writes v4.

Proposed gate rules, for operations active at the baseline major:

| Change | Classification |
|---|---|
| `maxResults` appears (none → N), or falls | COMPATIBLE: clients receive no more than before |
| `maxResults` disappears or rises | **BREAKING OUTPUT**: clients (and agents' context budgets) relied on it. Remedy: a new major, or `atlasAccept`. |
| Envelope changes (`NONE` ↔ `PAGE`/`SLICE`, `PAGE` ↔ `SLICE`) | **BREAKING OUTPUT** (the response schema changes) |
| A `Pageable` or limit parameter is added or removed | Already BREAKING via operation identity; no new rule |
| A `LIMIT`/`CURSOR` role is declared on, or removed from, an existing parameter | INFORMATIONAL: the wire is unchanged. Removing it re-triggers the unbounded diagnostic. |
| The page-size ceiling (Q4) appears or falls | **BREAKING INPUT**: a larger `size` that used to work is now rejected. Rising or disappearing is COMPATIBLE. |

### Q7. Reuse a flag or add a new one, and what changes without it

| Option | Trade-offs |
|---|---|
| **A. A new flag `ai.atlas.collections`**, Gradle `agentic { collections = true }` (prototype) | Independent of Phases 3 and 4, like `projections`. Existing users see no new warnings or shapes on upgrade. |
| B. Reuse `ai.atlas.strict` or `ai.atlas.constraints` | A silent opt-in: an upgrade adds warnings, and new shapes for Page-returning methods. |
| C. On by default in 1.x | The diagnostic alone would be fine as a WARNING, but the envelope changes a working wire shape (`Page` without `Pageable`). |

**Recommendation: A.** Two sub-decisions:
- **`Pageable` with the flag off.** Those wrappers are uncallable today on REST and MCP. Emit a flag-off
  **WARNING on every exposed `Pageable` parameter**, pointing at the flag. It is cheap and changes
  no shapes. Do not fix the binding without the flag, because that changes the OpenAPI document and
  the effective IR.
- **A declared `maxResults` or paging role with the flag off.**
  - **WARNING** (prototype): ignoring a bound exposes nothing new, unlike Phase 4's channel
    restriction on a sensitive field.
  - **ERROR** (Phase 4's precedent): a declaration silently doing nothing, which also drops OpenAPI
    `maxItems`.

  **Recommendation: WARNING.**

### Q8. Interaction with Phase 3 (`maxItems`) and Phase 4 (projections)

- **A LIMIT parameter's ceiling comes from Phase 3.** `@Max(100) int limit` already reaches MCP
  `inputSchema`, OpenAPI and Bean Validation on the tool. **Recommendation:** with both flags on,
  a `LIMIT` parameter with no `maximum` gets a WARNING (the agent can pass `limit = 10^6`), and
  an ERROR under strict for the AI channel.
- **`maxItems` on responses.** Publish `maxResults` as OpenAPI `maxItems` on the response array
  (prototype). It is the same JSON Schema keyword Phase 3 uses for inputs, now on an output.
  - The gate rule in Q6 is the output-direction mirror of Phase 3's input rule, where
    `maxItems` falling or appearing is BREAKING.
  - Q2-C (`@Size` on the method) folds the two vocabularies together.
- **Nested collection fields** (`Order.actions`) are also unbounded. A field's `@Size(max)` already
  reaches the OpenAPI schema as `maxItems` through Phase 3.
  **Recommendation:** out of scope for Phase 5. Consider a later WARNING for AI-eligible collection
  fields without `maxItems`.
- **Phase 4.** The envelope wraps the channel's projection: the MCP `content` holds `XAiDto`, REST's
  holds `XDto` (proved by `theMcpEnvelopeCarriesTheAiProjection`). Phase 4's raw-entity ERROR already
  covers a raw `Page<Entity>`. **No further interaction.**
- **Phase 5 §9 (REST metadata).** `page`/`size`/`sort` are query parameters, so they remain
  correct under explicit `GET` mappings. Today a `Pageable`-only method is a `POST` with query
  parameters, as every method with parameters is.
