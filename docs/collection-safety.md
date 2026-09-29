# Collection Exposure Safety

An exposed method that returns a collection can put a whole table into one REST response or one
agent's context. Behind one opt-in flag, ai-atlas classifies every exposed method that returns a
collection, iterable, array, `Stream` or `Map`:

```
collection return
  ├─ recognised paging contract (Spring Data Pageable, or a declared limit)  → expose paging metadata
  ├─ explicitly declared bound (maxResults = N)                               → allow
  └─ otherwise                                                                → WARNING / strict ERROR
```

**ai-atlas never paginates or truncates on a service's behalf.** It never adds a `limit`, cursor or
default the service does not accept, and a generated wrapper never shortens a result. It recognises
the paging contracts a service already has and publishes the bounds a service declares.

This guide covers the flag, the recognised contracts, the declarations, the envelopes, the `sort`
rule, the diagnostics, the runtime check, and how the Contract IR records the result.

## The `ai.atlas.collections` flag

The flag is off by default. Turn it on with the processor option `ai.atlas.collections`. It takes
`true` or `false` in any case, and defaults to `false`. Any other value is a compile ERROR.

```kotlin
agentic {
    collections.set(true)
}
```

- The Gradle plugin passes the option only when `collections` is set, so a value in
  `options.compilerArgs` stands otherwise. It reaches the main `compileJava` and
  `atlasAcceptCompile`: the flag decides the paging contract the accepted Contract IR records.
  It never reaches test compilations.
- Other build tools pass `-Aai.atlas.collections=true` to javac. The `atlas` CLI passes it through
  as an `-A` option unchanged.
- The flag is independent of `ai.atlas.constraints` and `ai.atlas.projections`, and works with
  either.

**With the flag off**, generated output is byte-identical to before. Then:
- a declared `@AgenticExposed(maxResults)`, `@AgenticParam(paging)` or `@AgenticParam(sortable)`
  is a compile ERROR, as it would silently do nothing;
- an exposed Spring Data `Pageable` parameter is a WARNING. Without the flag its wrappers cannot
  bind it: the REST endpoint answers `400`, and the MCP tool cannot be called.

**With the flag on and nothing declared**, generated output is byte-identical too, unless a method
takes a `Pageable` or returns a `Page` or `Slice`. The flag adds only WARNINGs.

## Recognised paging contracts

Paging contracts are recognised by type or by explicit declaration. They are never inferred from
parameter names: a parameter named `limit` that the service ignores would silence the warning.

### Spring Data `Pageable`

A parameter of type `org.springframework.data.domain.Pageable`, with a `Page`, `Slice` or
collection return, is a paging contract. Spring Data types are matched by name, so the processor
has no dependency on Spring Data.

| Surface | What is generated |
|---|---|
| REST controller | The `Pageable` parameter is left unannotated, so Spring Data's `PageableHandlerMethodArgumentResolver` binds `page`, `size` and `sort`. |
| MCP tool class | The `Pageable` becomes `page` (optional, `0` when omitted) and `size` (required). The tool passes `PageRequest.of(page, size)`. There is no generated default page size, and no `sort`. |
| `mcp-tools.json` | `page` `{integer, minimum 0}` and `size` `{integer, minimum 1}`, with `size` required. |
| OpenAPI | `page`, `size`, and, when allow-listed, `sort` query parameters. None is required and no defaults are published, as those are application configuration (`spring.data.web.pageable.*`). |

No `pageable` parameter appears on any surface. A `List` or `Set` return is bounded by `size` and
keeps its shape.

### A declared limit, and a cursor

```java
@AgenticExposed(description = "Search orders")
public List<Order> search(String text,
                          @AgenticParam(paging = Paging.LIMIT) @Max(100) int limit,
                          @AgenticParam(paging = Paging.CURSOR) String after) { ... }
```

- `@AgenticParam(paging = LIMIT)` marks a parameter the service honours as the most results it
  returns. It must be an `int`, `long` or `short`, boxed or not. Nothing is added to the operation:
  the parameters are already real. The MCP description gains "Returns at most 'limit' results".
- `CURSOR` marks the position the service resumes after. It counts as a paging contract only
  together with a `LIMIT`: a cursor alone bounds nothing. It is optional by default, since the
  first call has no cursor, unless it is a primitive, carries `@NotNull`, or declares
  `required = REQUIRED`.
- A `LIMIT` takes its ceiling from its constraints (`@Max`, Phase 3). With `ai.atlas.constraints`
  on, a `LIMIT` with no maximum is a WARNING, as a client can ask for the whole result set, and an
  ERROR under `ai.atlas.strict` for an AI-channel operation.
- ai-atlas cannot produce a next cursor. A cursor contract's output metadata comes from the
  service's own return type.

## Declared bounds: `@AgenticExposed(maxResults = N)`

```java
@AgenticExposed(description = "The five most recent orders", maxResults = 5)
public List<Order> recent() { ... }
```

- It declares that a known-small result holds at most `N` elements. It is method-level only: a
  class-level value is a compile ERROR. `N` must be at least 1, on a method returning a collection,
  iterable, array, `Stream` or `Map`.
- It is published as OpenAPI `maxItems: N` on the response array (`maxProperties` on a map, and
  `maxItems` on an envelope's `content`), and as "Returns at most N results." in the MCP tool
  description.
- **It is declared, not enforced.** The wrappers never truncate. Each generated wrapper method
  carries `@AgenticBound(maxResults = N)`, and the runtime logs a WARN when a result holds more,
  returning it unchanged (see [Runtime](#runtime)).
- **On a paged method it is the page-size ceiling.** With a `Pageable`, `maxResults` is published
  as `maximum` on `size` in the MCP tool, `mcp-tools.json` and OpenAPI. A larger `size` is
  rejected in both wrappers: MCP fails the call, and REST answers `400`. It is never clamped.
- A `LIMIT` and `maxResults` on one method is an ERROR. Declare the limit's ceiling with `@Max`.
- With `ai.atlas.constraints` on, a Bean Validation `@Size(max = N)` on the method, a return-value
  constraint, is the same declaration. When both are declared they must agree, or it is an ERROR.
  On a `@Validated` service, Spring's method validation enforces it at the service boundary.

## Envelopes for `Page` and `Slice`

A `Page` or `Slice` return keeps its metadata on both channels, in a `@Generated` record nested in
each wrapper class:

| Return | Envelope | Properties |
|---|---|---|
| `Page<T>` | `PageResult<T>` | `content`, `number`, `size`, `hasNext`, `totalElements`, `totalPages` |
| `Slice<T>` | `SliceResult<T>` | `content`, `number`, `size`, `hasNext` |

- The envelope wraps each channel's projection: the MCP `content` holds the AI record with
  `ai.atlas.projections` on, and REST's holds the DTO.
- OpenAPI inlines the envelope schema, with every property required. The MCP tool description says
  which properties the result carries, until an MCP `outputSchema` is emitted.
- A `Page` or `Slice` returned **without** a `Pageable` also gets its envelope, but it is not a
  paging contract: clients cannot ask for another page. It needs `maxResults` like any collection.
- Spring Data's `PagedModel` and the raw `Page` are not used: the first has no `Slice` form and
  ties the wire shape to a Spring Data version, and `PageImpl`'s JSON is not a stable format.

## The `sort` rule

Spring Data's `sort` accepts any mapped property, including fields that are not `@AgenticField`.
Ordering by a hidden column leaks information about it, for example by sorting on `creditScore` and
reading the order. So:

- `sort` is **never offered on MCP**.
- On REST, the controller **drops** any requested sort, unless the `Pageable` allow-lists
  properties:

  ```java
  public Page<Order> byStatus(String status,
                              @AgenticParam(sortable = {"id", "createdAt"}) Pageable pageable)
  ```

  Each property must be a field of the returned entity's REST DTO, or it is a compile ERROR. A
  request sorting by any other property is rejected with `400`. Only then does OpenAPI publish the
  `sort` parameter, listing the sortable properties.
- `sortable` on anything but a `Pageable` is an ERROR.

The service must still impose a deterministic order for paging to be stable.

## Diagnostics

An exposed method returning a collection with no paging contract and no declared bound is reported
at the method, naming the method, the return type and the channels:

```
[ai-atlas] shop.OrderService#list returns java.util.List<shop.Order> on channels [AI, API] with no
paging contract and no declared bound, so one call can return the whole result set. ...
```

It is a WARNING on both channels. Under `ai.atlas.strict` it is an ERROR for operations exposed on
the AI channel, and stays a WARNING for API-only operations. Only operations active at the
configured major are reported.

Misuse is a compile ERROR:
- `maxResults` below 1, on a method that does not return a collection, or on the class;
- a paging role on a method that does not return a collection;
- a `LIMIT` that is not an `int`, `long` or `short`;
- two `Pageable` parameters, or two parameters with the same role;
- a `Pageable` mixed with a `LIMIT` or `CURSOR`;
- a `LIMIT` together with `maxResults`;
- a parameter named `page`, `size` or `sort` next to a `Pageable`;
- a `maxResults` and a `@Size(max)` on the result that disagree;
- a `sortable` property that is not a field of the REST DTO, or `sortable` on anything but a
  `Pageable`.

Nested collection fields of entities, such as `Order.actions`, are out of scope. A field's
`@Size(max)` already reaches the OpenAPI schema as `maxItems` through Phase 3.

## Runtime

The runtime module logs a WARN when a generated wrapper's result holds more elements than its
`@AgenticBound` declares, and returns the result unchanged:

```
[ai-atlas] MCP tool 'top' returned 7 results, more than the maxResults = 5 its service method
OrderServiceMcpTool#top declares. The result is passed through unchanged; page the method, or correct the bound
```

- On REST, `DtoResponseBodyAdvice` checks the bodies of generated controllers.
- On MCP, the check runs where the runtime already rebuilds each tool callback around its
  agent-safe result converter: both the tools AI-ATLAS registers and those the application's own
  providers serve.
- A collection, map, array or envelope `content` is counted. A `Stream` is not, as counting it
  would consume it.

The operator learns that the bound is wrong without an outage. For real enforcement, declare the
bound as `@Size(max = N)` on a `@Validated` service.

## The Contract IR

Contract IR `irVersion 4` records each operation's effective paging contract in `returns.bound`:

| Declared | `style` | `limitParameter` | `cursorParameter` | `maxResults` |
|---|---|---|---|---|
| A `Pageable` | `PAGEABLE` | the `Pageable` | `null` | the page-size ceiling, or `null` |
| `@AgenticParam(paging = LIMIT)` | `LIMIT` | the limit | the cursor, or `null` | `null`: the ceiling is the limit's own `maximum`, in its constraints |
| `maxResults` alone | `DECLARED` | `null` | `null` | the bound |
| nothing, or a cursor alone | `NONE` | `null` | `null` | `null` |

`envelope` is `PAGE` or `SLICE` for a `Page` or `Slice` return, the wire shape clients receive,
including one returned without a `Pageable`, and `NONE` otherwise.

The slot is the **effective** contract. With the flag off every operation records `NONE`/`NONE`,
since that is what its clients receive. So a baseline accepted with the flag off fails the build
that turns it on for a `Page`-returning method: the envelope changing is a breaking output change.
Accept it with `atlasAccept`, or publish it in a new major. Version 1 to 3 baselines migrate to
`NONE`/`NONE` exactly, and `atlasAccept` writes version 4. The gate's rules for a bound appearing,
rising, falling or disappearing, and for a page-size ceiling, are in
[contract governance](contract-governance.md#rest-status-and-result-bounds).
