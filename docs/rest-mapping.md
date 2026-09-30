# REST Mapping

By default every generated REST operation is RPC-shaped: `GET` without parameters and `POST` with
them, at `/<service-kebab>/<method-kebab>`, every argument a query parameter and every success a
`200`. With the processor option `ai.atlas.rest=true`, a service can declare each operation's HTTP
method, path, parameter locations and success status, or opt into a CRUD convention. Any method
that declares nothing and matches no rule keeps the RPC mapping.

```
explicit @Rest / @AgenticParam(in)   >   CRUD rule (opt-in per service)   >   RPC mapping
                  resolved once, attribute by attribute, per operation
                                   │
      controller ── OpenAPI ── route collision check ── deprecation manifest ── Contract IR
```

Each operation's mapping is resolved once, and every generated surface reads that one result, so
the controller and the OpenAPI document cannot disagree. MCP tools are unaffected: tool names,
argument names and input schemas do not depend on a verb, path, status or location, and the MCP
tool classes and `mcp-tools.json` are byte-identical with and without REST metadata.

## The `ai.atlas.rest` flag

| Where | How |
|---|---|
| Processor option | `-Aai.atlas.rest=true` (`true` or `false`, any case; default `false`; any other value is a compile error) |
| Gradle plugin | `agentic { rest = true }`. Unset, it is not passed, so the processor default or a value in `options.compilerArgs` applies. It reaches the main `compileJava` and `atlasAcceptCompile`, because the Contract IR records each operation's mapping |
| CLI and MCP server | passed through unchanged with `-Aai.atlas.rest=true` |

With the flag **off**, every operation keeps the RPC mapping, and any REST declaration, an
`@AgenticExposed(rest = @Rest(...))` other than the default or an `@AgenticParam(in)` other than
`DEFAULT`, is a compile error naming the route that is still served:

```
REST metadata on 'cancel' requires ai.atlas.rest=true. Without it the operation is still served at
POST /api/v1/order-service/cancel. Turn the option on (agentic { rest = true } in Gradle) or
remove the declaration
```

With the flag off, or on with nothing declared, every generated source and resource is
byte-identical to before (golden snapshot).

## `@AgenticExposed(rest = @Rest(...))`

`@Rest` is nested in `@AgenticExposed`, with `HttpMethod` and `RestStyle`. A static import keeps it
short:

```java
import static com.egoge.ai.atlas.annotations.AgenticExposed.HttpMethod.*;

@AgenticExposed(rest = @Rest(resource = "orders"))
public class OrderService {

    @AgenticExposed(returnType = Order.class, rest = @Rest(method = GET, path = "/{id}"))
    public Order get(Long id) { ... }                                   // GET    /api/v1/orders/{id}

    @AgenticExposed(returnType = Order.class, rest = @Rest(method = POST, path = "", status = 201))
    public Order place(Order order) { ... }                             // POST   /api/v1/orders, body → 201

    @AgenticExposed(returnType = Order.class, rest = @Rest(method = PATCH, path = "/{id}/status"))
    public Order changeStatus(Long id, String status) { ... }           // id in the path, status in the query

    @AgenticExposed(rest = @Rest(method = DELETE, path = "/{id}"))
    public void cancel(Long id) { ... }                                 // DELETE /api/v1/orders/{id} → 204

    public long count() { ... }                                         // RPC: GET /api/v1/orders/count
}
```

| Attribute | Where | Default | Meaning |
|---|---|---|---|
| `method` | method | `UNSET` | `GET`, `POST`, `PUT`, `PATCH` or `DELETE`. `UNSET` derives it |
| `path` | method | `"\0"` | The path below the resource. `""` is the resource itself. The default, a single NUL, derives it |
| `status` | method | `0` | The success status, a 2xx code Spring's `HttpStatus` names: 200–208 or 226. `0` derives it |
| `style` | class | `INHERIT` | `CRUD` opts the service into the [CRUD convention](#the-crud-convention). `INHERIT` and `RPC` keep the RPC mapping for undeclared methods |
| `resource` | class | `""` | One path segment every operation of the service is mapped below. Empty keeps the service's kebab-case name. Nothing is pluralized |

A method-level attribute on a class, or a class-level attribute on a method, is a compile error.

### Paths

A route is `<basePath>/v<major>/<resource><path>`: the base path and version prefix cannot be
escaped, and each controller class has one `@RequestMapping`, its resource. A path is `""` or
`/`-separated segments, each either:

- a literal of letters, digits, `.`, `_`, `~` or `-`, other than `.` and `..`; or
- a `{name}` variable, where `name` is a Java identifier that names a parameter of the method.

A `.` or `..` segment, in a path or as the resource, is a compile error, so no route leaves its
resource or the version prefix. Regex variables (`{id:\d+}`), wildcards, `**`, query strings and matrix parameters are not allowed:
the controller, the OpenAPI document and the collision check would read them differently. Mappings
are unversioned: each operation has one mapping per build, whatever the major. Moving a route in v2
while keeping v1's takes two Java methods.

## Parameter locations

`@AgenticParam(in = DEFAULT | PATH | QUERY | BODY)` places a parameter. A parameter left at
`DEFAULT` is located in this order:

1. an explicit `in`;
2. `PATH`, when a `{var}` of the path names it;
3. `BODY`, for an `@AgenticEntity` parameter of an operation with an **explicit or CRUD mapping**;
4. otherwise `QUERY`, the canonical form of Phase 0.

An operation on the RPC mapping never has a body: every parameter stays in the query, entities
included, and an explicit `in = BODY` on it is a compile error. A query parameter binds through
Spring's type conversion of one request parameter, never through Jackson, so it cannot set an
entity's properties.

`@Rest(status)` alone is an explicit mapping too: it keeps the RPC method and path, but moves an
`@AgenticEntity` parameter from the query to the body. That move is a **WARNING** on the parameter;
declare `@AgenticParam(in = BODY)` to keep it there without the warning, or `in = QUERY` to keep it
in the query.

| Location | Controller | OpenAPI |
|---|---|---|
| `PATH` | `@PathVariable("name")` | `in: path`, always `required` |
| `QUERY` | `@RequestParam`, as before; `required = false` for an `OPTIONAL` parameter with `ai.atlas.constraints=true` | `in: query` |
| `BODY` | `@RequestBody` of the entity's input record, or of the declared type | `requestBody`, `application/json`; `required` follows the parameter's requiredness |

`@PathVariable` names its variable, while `@RequestParam` stays bare, as before, so query
parameters still need javac's `-parameters`, which Spring Boot's Gradle plugin sets.

A path parameter must be a scalar: a primitive, its box, `String`, an enum, `UUID`, `BigDecimal` or
`BigInteger`.

## The CRUD convention

`@AgenticExposed(rest = @Rest(style = CRUD))` on a service opts it into five fixed rules. Each
matches the method name **and** the parameter shape; a *scalar* is as above, and an *entity* is an
`@AgenticEntity` or a subtype of one.

| Method | Parameters | Mapping | Status |
|---|---|---|---|
| `findAll`, `list` | none | `GET /<resource>` | 200 |
| `findById`, `getById` | one scalar `p` | `GET /<resource>/{p}` | 200 |
| `create` | one entity | `POST /<resource>`, the entity in the body | 201 |
| `update` | a scalar `p`, then an entity | `PUT /<resource>/{p}`, the entity in the body | 200 |
| `delete`, `deleteById` | one scalar `p` | `DELETE /<resource>/{p}` | 204 if `void`, else 200 |

- Any other method, including `findByStatus`, `save`, `create(String name)` and
  `findById(Order probe)`, keeps its RPC mapping below the resource.
- Method names never become a semantic claim beyond these five rules: there is no prefix
  inference (`find*` → `GET`) and no global CRUD mode.
- **Explicit metadata wins attribute by attribute.** An explicit `path` keeps the rule's method and
  status; an explicit `status` keeps its method and path. A rule's status holds only while the
  rule's HTTP method is the effective one, so `@Rest(method = POST)` on a `void deleteById` answers
  200, not 204.
- The resource is `@Rest(resource)`, else the service's kebab-case name: `OrderService` in CRUD
  mode serves `GET /api/v1/order-service/{id}` until it declares `resource = "orders"`.

```java
@AgenticExposed(returnType = Customer.class, rest = @Rest(style = RestStyle.CRUD, resource = "customers"))
public class CustomerService {
    public List<Customer> findAll() { ... }                 // GET    /api/v1/customers
    public Customer findById(Long id) { ... }               // GET    /api/v1/customers/{id}
    public Customer create(Customer customer) { ... }       // POST   /api/v1/customers → 201
    public Customer update(Long id, Customer customer) { ... } // PUT /api/v1/customers/{id}
    public void deleteById(Long id) { ... }                 // DELETE /api/v1/customers/{id} → 204
    public Customer activate(Long id) { ... }               // RPC:   POST /api/v1/customers/activate?id=
}
```

## Success statuses

The status is, in order: the declared `@Rest(status)`; else the CRUD rule's status; else `204` for
a `void` `DELETE`; else `200`.

- The controller declares `@ResponseStatus(HttpStatus.X)` for a status other than 200, and the
  OpenAPI document describes that one success response. Error responses (4xx and 5xx) are not
  described.
- `204 No Content` on a method that returns a value is a compile error. A `void` operation with any
  other status answers with an empty body.
- A `201 Created` carries no `Location` header: AI-ATLAS cannot know the created resource's URI.

## Request bodies and input records

An `@AgenticEntity` body is never bound as the entity: Jackson would set every settable property,
including fields without `@AgenticField`, which is a mass-assignment hole. Instead, the processor
generates a whitelisted **input record**, `<Entity>Input` in the entity's DTO package, and the
controller binds it and passes `toEntity()` to the service:

```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public OrderDto place(@RequestBody OrderInput order) {
    return OrderDto.fromEntity(service.place(order.toEntity()));
}
```

```java
public record OrderInput(Long id, String status) {
    public Order toEntity() {
        Order entity = new Order();
        entity.setId(id);
        entity.setStatus(status);
        return entity;
    }
}
```

- **Components.** The entity's `@AgenticField`s active at the configured major, except:
  - a field declared `@AgenticField(input = false)`, for values the caller must not set, such as
    an `id`;
  - under Phase 4's input rule, a field not eligible for the API channel
    (`@AgenticField(channels = AI)` with `ai.atlas.projections=true`). An *optional* one is left
    out. A *required* one, a primitive or a field with `@NotNull`, `@NotBlank` or `@NotEmpty` in the
    default group, is a compile error, because no REST request could set it.
- **A property without `@AgenticField`,** or one declared `input = false`, sent in the JSON body
  is ignored. It is never set on the entity.
- **Creating the entity.** `toEntity()` uses an accessible no-argument constructor and a setter for
  each component. Failing that, it uses an accessible constructor taking every component in
  declaration order. An entity with neither, or an abstract one, is a compile error naming the
  missing setters and the constructor it would take.
- **Requiredness.** The OpenAPI schema of `<Entity>Input` lists its required components: primitives
  and fields with `@NotNull`, `@NotBlank` or `@NotEmpty`. The record **enforces** that list,
  with `ai.atlas.constraints` on or off, as a required `@RequestParam` is enforced: a required
  primitive component is boxed, and the record's compact constructor throws
  `IllegalArgumentException` for a required component that is missing or `null`, which Spring
  answers with `400 Bad Request` before the service is called. This needs no Bean Validation. With
  `ai.atlas.constraints=true`, its properties also carry the fields' constraints (`@Size`,
  `@Pattern`, `@NotBlank`'s blankness, …), which are published, not enforced, as for query
  parameters. The body parameter itself is required unless it is
  declared `@AgenticParam(required = OPTIONAL)`, which binds `@RequestBody(required = false)` and
  passes `null` to the service when the body is absent.
- **OpenAPI** references `#/components/schemas/<Entity>Input`, exactly what is accepted. The
  entity's DTO schema still describes responses.
- A field that refers to another entity cannot be an input: declare it `input = false`.

Other bodies bind their declared type: a command record, a `Map`, a number, or a list of scalars.
These are compile errors:

- a `String` or other `CharSequence` body, which Spring reads as raw text, not JSON, while OpenAPI
  says `application/json`. Wrap it in a record;
- a body whose type is an unannotated subtype of an entity, or a collection or array of entities.
  No whitelist could bind them;
- an `Optional<Entity>` body. Jackson would bind the entity itself; declare the parameter as the
  entity, with `@AgenticParam(required = OPTIONAL)` for an optional body (see *Requiredness*);
- any other body type that **reaches** an entity, or an unannotated subtype of one, that Jackson
  would bind in full: through a type argument (`Map<String, Order>`), a map key or value, an array
  component, a record component, a field, a setter's parameter or a constructor parameter,
  transitively and through generic types (`Wrapper<Order>`). A command record `PlaceRequest(Order
  order, String note)` would let a non-`@AgenticField` property such as `ssn` reach the service.
  The error names the path, such as `shop.PlaceRequest.order`; hold the entity's fields in the
  command record instead. Members of JDK types are not followed, only their type arguments.

MCP tools keep taking the method's parameters: input records are REST-only.

## Validation

Every error names the declaration: the method, the parameter, the class or the field.

| Diagnostic | Kind |
|---|---|
| A malformed path, or a regex, wildcard or query in it | ERROR |
| A `{var}` naming no parameter, or repeated | ERROR |
| `in = PATH` without a `{var}` naming the parameter | ERROR |
| A `{var}`'s parameter declared `QUERY` or `BODY` | ERROR |
| A path parameter that is not a scalar | ERROR |
| More than one body parameter | ERROR |
| A body on `GET` or `DELETE` | ERROR |
| `in = BODY` on an operation on the RPC mapping | ERROR |
| With `ai.atlas.collections` on, a paging input bound from the path or the body: a Spring Data `Pageable`, or an `@AgenticParam(paging = LIMIT \| CURSOR)` parameter, declared `in = PATH` or `in = BODY` or named by a `{var}`. Paging inputs are query parameters only, as the generated `page`/`size` checks and OpenAPI read them (see [Collection exposure safety](collection-safety.md)) | ERROR |
| A `String` body, an entity subtype body, a collection of entities, an `Optional` of an entity, or a body type that reaches an entity through its type arguments or properties | ERROR |
| A status outside the 2xx codes Spring's `HttpStatus` names | ERROR |
| `204` on a method that returns a value | ERROR |
| `method`, `path` or `status` on a class; `style` or `resource` on a method | ERROR |
| A resource that is not one path segment, or a `.` or `..` path or resource segment | ERROR |
| An entity body whose input record cannot create the entity, or whose name another type takes | ERROR |
| A required field not eligible for the API channel, or an entity-reference field, in an input record | ERROR |
| Any REST declaration while `ai.atlas.rest` is off | ERROR |
| Two routes that match after `{var}` names are normalised: `/{id}` and `/{orderId}` collide, as in Spring. Reported on each method, naming the others with their parameter types, such as `S#findById(Long)`, so overloads are told apart | ERROR |
| Two routes that match the same requests while neither is more specific, such as `/{id}/items` and `/open/{kind}` | ERROR |
| `@Rest` or `@AgenticParam(in)` on a method that is not on the API channel, which has no REST mapping | WARNING |
| `@AgenticField(input = false)` while `ai.atlas.rest` is off | WARNING |
| `@Rest(status)` alone moving an `@AgenticEntity` parameter from the query to the body | WARNING |
| A literal route beside a variable one, such as `GET /orders/active` next to `GET /orders/{id}`. Spring routes the literal first, which clients may not expect | NOTE |

Routes in different modules are not checked against each other, as for MCP tool names.

## Deprecation headers

The deprecation manifest (`META-INF/ai-atlas/deprecation-manifest.json`) records each endpoint's
resolved HTTP method and route template, such as `DELETE /api/v1/orders/{id}`. The runtime
`DeprecationHeaderFilter` matches a request as Spring MVC routes it: an endpoint whose path equals
the request path first, else the most specific matching template, by Spring's `PathPattern`. A
request to `DELETE /api/v1/orders/42` on a deprecated operation gets `Deprecation: true`.

## The Contract IR and the gate

Each API operation's `rest` in the Contract IR (`META-INF/ai-atlas/api.ir.json`, `irVersion 4`)
records its **effective** mapping: `httpMethod`, `path` below the major (with its `{name}`
variables), `status`, and `parameterIn`, each parameter's location in declaration order. It
records the mapping with the flag off too, where every operation is on the RPC mapping: `200`, with
every parameter in the query. So turning `ai.atlas.rest` on without declaring anything writes a
byte-identical IR, and lock mode sees no difference.

```json
"rest": {
  "httpMethod": "DELETE",
  "path": "/orders/{id}",
  "status": 204,
  "parameterIn": [ "PATH" ]
}
```

Baselines of `irVersion` 1 to 3 migrate exactly, with a status of 200 and every parameter in the
query, because that was every operation's mapping before this feature. `atlasAccept` writes
version 4. See [Contract Governance](contract-governance.md#migration-from-irversion-3-to-irversion-4)
for the format and migration.

The gate classifies a declaration's effect on published clients:

| Change | Classification |
|---|---|
| The HTTP method changes | Breaking input |
| The path changes | Breaking input |
| The path changes only in its `{name}` variables | Compatible |
| The status changes | Breaking output |
| A parameter's location changes | Breaking input |
| A `PATH` or `BODY` parameter is renamed, on an operation served only on the API channel | Compatible. On an operation also on the AI channel it stays breaking, as MCP clients pass arguments by name |

So opting a published service into the CRUD convention is a breaking change for each operation
whose mapping moves. A mapping has no lifecycle of its own, so the remedy is a new major, or
`atlasAccept` when the change is deliberate. A constraint change to an entity field that feeds an
input record is still classified as a response field's is (informational); the input direction
for request-body fields is not yet gated.

## operationId

The OpenAPI `operationId` rule is unchanged: the method name when it is unique, else
`{Service}_{method}_{httpMethod}`. It is assigned from the resolved mapping, so it follows the
effective HTTP method and path.

## Out of scope

- Error responses (4xx and 5xx) in OpenAPI, a `Location` header for `201`, and content types other
  than JSON.
- Per-major REST mappings (`restSince` and `restUntil`).
- A global CRUD mode, and prefix-based inference.
