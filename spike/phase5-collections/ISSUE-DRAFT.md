**Title:** Collection exposure safety: recognise paging contracts, declare bounds, flag unbounded collections (epic #23, Phase 5)

---

Child of #23. Delivers the epic's Phase 5, §8 "Treat unbounded collections as an exposure-safety
problem; do not invent pagination". Every exposed method that returns a collection is classified at
compile time:

```
collection return
  ├─ recognised paging contract (Spring Data Pageable, or a declared limit)  → expose paging metadata
  ├─ explicitly declared bound (maxResults = N)                               → allow
  └─ otherwise                                                                → WARNING / strict ERROR
```

ai-atlas never adds a `limit` or cursor the service does not accept, and never truncates a result
in a generated wrapper.

## Why

- **Unbounded collections are exposed silently.** `List`, `Set`, `Iterable` and array returns are
  mapped element by element to `List<XDto>` on REST and MCP (`McpToolGenerator`,
  `RestControllerGenerator`). One `findByStatus` call can pull a whole table into an agent's
  context. No diagnostic exists, even under `ai.atlas.strict`. `Stream` and `Map` returns are not
  recognised as collections at all.
- **Spring Data paging is broken on every surface today.** The spike proved each one:
  - **REST:** the `Pageable` parameter gets `@RequestParam`, which Spring MVC resolves before Spring
    Data's resolver. A correct `?page=1&size=2` request gets `400`.
  - **MCP:** Spring AI cannot deserialize the `Pageable` interface, so the tool cannot be called.
  - **OpenAPI:** the document publishes a required `pageable` string.
  - **Returns:** a `Page`/`Slice` is an `Iterable`, so it is flattened to a list, and its totals,
    page number and `hasNext` are lost.
- **The gate cannot see bounds.** The spike added and removed a declared bound. `api.ir.json`
  stayed byte-identical and `ContractGate.compare` returned nothing. Only changes to the Java
  signature, such as adding a `Pageable`, are visible today.
- **`sort` leaks hidden columns.** Spring Data's `sort` accepts any mapped property, including
  fields that are not `@AgenticField`. Once paging is bound correctly, ordering by a hidden column
  leaks information about it unless sorting is restricted.

## Scope (owner decisions needed)

The feasibility spike is on branch `claude/phase5-collections-spike`, in
`spike/phase5-collections/REPORT.md`.
- **Prototype:** `-Aai.atlas.collections=true` recognises:
  - Spring Data `Pageable` plus a `Page`, `Slice` or collection return;
  - `@AgenticParam(paging = LIMIT | CURSOR)`;
  - `@AgenticExposed(maxResults = N)`.

  Everything else warns, and fails under strict.
- **How it was proved:** 20 tests call the generated classes, MCP through Spring AI 1.1.7's
  `MethodToolCallbackProvider`, and REST through MockMvc with Spring Data's
  `PageableHandlerMethodArgumentResolver`.
- **Byte-identical when off:** with the flag off, output matches the master golden snapshot. With
  it on and nothing declared, output also matches: the flag only adds WARNINGs.

Each item below is marked **Owner decision needed**, with the spike's recommendation.

### 1. Paging contracts recognised — **Owner decision needed**
- **Recommended: Spring Data, plus explicitly declared limit/cursor parameters.**
  - A `Pageable` parameter (matched by type, by name only as a string) is a paging contract:
    - a `Page` or `Slice` return keeps its metadata;
    - a `List`/`Set` return is bounded by `size`, with no envelope.
  - `@AgenticParam(paging = LIMIT)` on an integral parameter the service honours is a paging
    contract. `CURSOR` is allowed only with a `LIMIT`; a cursor alone bounds nothing.
  - A `Page`/`Slice` returned **without** a `Pageable` is not a paging contract, because clients
    cannot request another page. It needs `maxResults` like any collection.
  - `Stream` and `Map` returns are collections. A `Stream` gets a WARNING like the others.
  - A `CURSOR` parameter is optional by default, since the first call has none. The spike found
    that Phase 3 otherwise derives a plain `String` cursor as required.
- **Alternatives:** Spring Data only, which leaves hand-rolled services only `maxResults`; or
  name-based inference (`limit`, `offset`, `pageToken`). The epic rules inference out: naming must
  not become authoritative, and a `limit` the service ignores would silence the warning.

### 2. Bounded-result declaration — **Owner decision needed**
- **Recommended:** `@AgenticExposed(maxResults = N)`.
  - It is method-level only; a class-level value is an ERROR.
  - N must be ≥ 1, on a collection return. Anything else is an ERROR.
  - It is published as OpenAPI `maxItems: N` on the response array and as "Returns at most N
    results." in the MCP description.
  - Optionally, when `ai.atlas.constraints` is on, a Bean Validation `@Size(max = N)` on the method
    (a return-value constraint) is accepted as the same declaration. If both are declared they must
    agree.
- **Alternatives:** a separate `@BoundedResult(max = N)` annotation, which is self-describing but
  adds another type.

### 3. Runtime enforcement — **Owner decision needed**
- **Recommended: declared, not enforced, and never truncated.**
  - The runtime logs a WARN when a result exceeds its declared bound, in the style of
    `DtoResponseBodyAdvice`, and returns the result unchanged.
  - A paging input that is out of range (`size < 1`, or above the page-size ceiling in item 4) is
    **rejected**, never clamped.
- **Alternatives:** the wrapper fails the call when the result exceeds N, which still pays the full
  query cost and turns data growth into an outage; or declaration only, with no signal. Real
  enforcement at the service boundary is available through the `@Size` return-value constraint on
  a `@Validated` service (item 2).

### 4. Paging metadata in schemas — **Owner decision needed**
- **Recommended — inputs:**
  - **MCP:** the `Pageable` becomes `page` (optional, 0 when omitted) and `size` (required, ≥ 1).
    The wrapper passes `PageRequest.of(page, size)`. There is no generated default page size.
  - **REST:** the `Pageable` stays unannotated, so Spring Data binds it.
  - **OpenAPI:** publishes `page`, `size` and `sort`, none required, with no defaults (those are
    application configuration).
  - **`mcp-tools.json`:** carries the same `page`/`size` as the tool class.
- **Recommended — page-size ceiling:** `maxResults` on a paged method means the largest `size`. It
  is published as `maximum` on `size` everywhere, and a larger `size` is rejected in both wrappers.
  - The spike rejects "paged + `maxResults`" as an ERROR until this is decided.
- **Recommended — `sort`:**
  - never offered on MCP;
  - on REST, stripped unless an allow-list is declared, for example
    `@AgenticParam(sortable = {"id", "createdAt"})`, validated against the entity's
    `@AgenticField`s.
- **Recommended — outputs:**
  - `Page` → `PageResult<T>(content, number, size, hasNext, totalElements, totalPages)`;
  - `Slice` → `SliceResult<T>(content, number, size, hasNext)`.

  Both are `@Generated` records nested in each wrapper. OpenAPI inlines the envelope schema. MCP
  describes paging in the tool description until an MCP `outputSchema` exists.
- **Alternatives:** Spring Data's `PagedModel` (no `Slice` form, and tied to a Spring Data
  version); returning the `Page` itself (unstable `PageImpl` JSON); an optional MCP `size` with a
  generated or runtime-read default.

### 5. Channels and severity — **Owner decision needed**
- **Recommended:** a WARNING for an unbounded collection on either channel. Under `ai.atlas.strict`
  it is an ERROR for AI-channel operations only; API-only operations stay a WARNING. This matches
  the epic's "strict mode can reject unbounded AI collection tools" and the existing AI-only quality
  diagnostics.
- **Alternatives:** the same severity on both channels, which is the spike prototype; or AI only,
  which hides a real REST risk.

### 6. Contract IR version 4 and the gate — **Owner decision needed**
- **Recommended:** `irVersion` 4, with `returns.bound` recording the **effective** contract:
  - `style`: `PAGEABLE`, `LIMIT`, `DECLARED` or `NONE`;
  - `envelope`: `PAGE`, `SLICE` or `NONE`, the wire shape clients receive;
  - `limitParameter`, `cursorParameter` and `maxResults`.

  With the flag off every operation records `NONE`/`NONE`, so turning the flag on for a
  `Page`-returning method is gated as a real output change.
- **Migration is exact.** Nothing before v4 declared a bound or produced an envelope, so every v1–v3
  operation migrates to `NONE`/`NONE`. `atlasAccept` writes v4.
- **Gate rules**, for operations active at the baseline major:

  | Change | Classification |
  |---|---|
  | `maxResults` appears or falls | COMPATIBLE |
  | `maxResults` disappears or rises | BREAKING OUTPUT |
  | The envelope changes | BREAKING OUTPUT |
  | A `Pageable` or limit parameter is added or removed | Already BREAKING through operation identity |
  | A paging role is declared or removed on an existing parameter | INFORMATIONAL |
  | The page-size ceiling appears or falls | BREAKING INPUT |
  | The page-size ceiling rises or disappears | COMPATIBLE |

  The remedy text is `atlasAccept` or a new major, because a bound has no lifecycle of its own.
- **Alternatives:** optional keys in v3, which breaks the malformed-slot rule; or no IR change,
  which the spike proved blind.

### 7. Opt-in flag — **Owner decision needed**
- **Recommended:** a new flag, `ai.atlas.collections`, exposed by the Gradle plugin as
  `agentic { collections = true }` and passed through by the CLI. It is independent of
  `constraints` and `projections`.
- **Flag off:**
  - generated output is byte-identical to today;
  - every exposed `Pageable` parameter gets a WARNING pointing at the flag, because those wrappers
    cannot be called today;
  - a declared `maxResults` or paging role gets a WARNING that it is ignored.
  - **Alternative for the last item:** an ERROR, following Phase 4's precedent for ignored
    declarations.
- **Alternatives:** reusing `ai.atlas.strict` or `ai.atlas.constraints`, which is a silent opt-in
  that adds warnings and changes shapes on upgrade.

### 8. Interaction with Phase 3 and Phase 4 — **Owner decision needed**
- **Recommended — Phase 3:**
  - A `LIMIT` parameter takes its ceiling from its Phase 3 `maximum` (`@Max`). With both flags on,
    a `LIMIT` without a maximum gets a WARNING, and an ERROR under strict for the AI channel.
  - `maxResults` is published as `maxItems` on the response array, the output-direction mirror of
    Phase 3's `maxItems` on inputs.
- **Recommended — Phase 4:** the envelope wraps the channel's projection. MCP `content` is `XAiDto`
  and REST `content` is `XDto`; the spike proves it. No further change.
- **Recommended — nested collection fields** (`Order.actions`): out of scope here. They can later get
  a WARNING when AI-eligible without `maxItems`.

## Out of scope
- **Truncating, sampling or synthesising paging** for a service that does not page. That is ruled
  out by the epic.
- **Producing a next cursor.** ai-atlas cannot derive one; a cursor contract's output metadata
  comes from the service's own return type.
- **Bounds on nested collection fields** of entities (item 8).
- **Emitting an MCP `outputSchema`.** It is tracked with Phase 4's deferred output schema.
- **Explicit REST verbs and paths (§9)**, and **release snapshots (§10)**.
- **Moving per-method checks out of `AgenticProcessor`.** It is at its checkstyle `FileLength`
  limit (500), so the real phase should add a hook rather than more inline calls. This is an
  implementation note, not scope.

## Acceptance criteria
- [ ] An exposed method returning a collection, iterable, array, `Stream` or `Map` with no
      recognised paging contract and no declared bound produces a diagnostic naming the method,
      return type and channels. It is a WARNING by default, and under `ai.atlas.strict` it follows
      item 5.
- [ ] A Spring Data `Pageable` parameter is bound by Spring Data on REST, becomes `page`/`size` on
      MCP and in `mcp-tools.json`, and is described as `page`/`size`/`sort` query parameters in
      OpenAPI (subject to item 4's `sort` rule). No `pageable` parameter appears on any surface.
- [ ] A `Page` return keeps `content`, `number`, `size`, `hasNext`, `totalElements` and
      `totalPages`, and a `Slice` keeps `content`, `number`, `size` and `hasNext`, identically on
      REST and MCP and as described in OpenAPI.
- [ ] Declared `@AgenticParam(paging = LIMIT)` parameters are recognised and nothing is added to
      the operation. A `CURSOR` without a `LIMIT` does not count as a bound.
- [ ] `@AgenticExposed(maxResults = N)` permits a known-small collection. It is published as
      OpenAPI `maxItems` and in the MCP description, and it is never enforced by truncation.
- [ ] Generated wrappers never add a limit, cursor or default the service does not accept, and never
      shorten a result. Out-of-range paging inputs are rejected, not clamped.
- [ ] Misuse is a compile ERROR:
  - `maxResults` < 1, on a non-collection, or on the class;
  - a non-integral `LIMIT`;
  - two `Pageable` parameters, or two parameters with the same role;
  - a `Pageable` mixed with `LIMIT`/`CURSOR`;
  - a parameter shadowing `page` or `size`.
- [ ] With Phase 4 on, the MCP envelope carries the AI projection and REST the API projection.
- [ ] The IR is version 4 with `returns.bound`. v1–v3 baselines migrate to `NONE`/`NONE` with no
      lock-mode difference, `atlasAccept` writes v4, and the gate applies item 6's rules.
- [ ] With the flag off, generated sources and resources other than the Contract IR are
      byte-identical to before (golden snapshot). With the flag on and no paging contract or bound
      declared, they are also byte-identical.
- [ ] The Gradle plugin exposes `agentic { collections = true }`, and the CLI passes the option
      through.
- [ ] Docs cover:
  - the recognised contracts, the declarations and the diagnostic;
  - the envelope shapes and the `sort` rule;
  - the flag and the IR v4 migration;
  - a statement that ai-atlas never paginates or truncates on a service's behalf.
- [ ] No network, database, model call or live service anywhere in the processor or the gate.
