# Per-Channel Field Projections

`@AgenticExposed(channels)` decides which channels see an operation: `AI` (MCP tools) or `API` (REST
controllers and the OpenAPI document). `@AgenticField(channels)` decides which channels receive a
field. Behind one opt-in flag, each response is projected to the fields eligible for the channel
serving it:

```
field eligible channels  ×  operation exposed channels  =  effective response projection
```

A field useful to a back-office REST client, such as an internal margin, can stay out of LLM
context. A field written for agents, such as a plain-language summary, can stay out of the public
REST and OpenAPI contract. This guide covers the attribute, the generated types and their names,
the flag, the errors, the runtime caveat, and how the Contract IR and the gate record the change.

## `@AgenticField(channels)`

```java
@AgenticEntity(description = "A customer order")
public class Order {
    @AgenticField(description = "Order id")
    private Long id;

    @AgenticField(description = "Internal margin", channels = Channel.API)
    private Integer marginCents;

    @AgenticField(description = "Plain-language summary for agents", channels = Channel.AI)
    private String agentSummary;

    private String customerSsn; // no @AgenticField: on no channel
}
```

- The attribute reuses `AgenticExposed.Channel`. The default, `{INHERIT}`, makes the field eligible
  for **every channel**, so a field that declares nothing behaves exactly as before.
- An explicit value narrows eligibility: `channels = Channel.API` keeps the field out of MCP tool
  results, and `channels = Channel.AI` keeps it out of REST responses and the OpenAPI document.
  Declaring both channels is allowed, and means the same as the default.
- An empty array, or `INHERIT` mixed with explicit values, is a compile ERROR on the field.
- **The whitelist is unchanged.** Eligibility is read only on `@AgenticField` fields and can only
  remove a field from a channel. A field without `@AgenticField` is on no channel, whatever else is
  declared.

## The `ai.atlas.projections` flag

Projections are off by default. Turn them on with the processor option `ai.atlas.projections`
(`true`/`false`, case-insensitive, default `false`; any other value is a compile ERROR):

```kotlin
agentic {
    projections.set(true)
}
```

- The Gradle plugin passes the option only when `projections` is set, so a value added to
  `options.compilerArgs` stands otherwise. It reaches the main `compileJava` and
  `atlasAcceptCompile`, which must see the same flag, since it decides the channels the accepted
  Contract IR records. It never reaches test compilations.
- Other build tools pass `-Aai.atlas.projections=true` to javac. The `atlas` CLI takes it as an `-A`
  option and the STDIO MCP server in `options`, and passes it through unchanged.
- The flag is independent of `ai.atlas.constraints`: turning constraints on never changes a
  response's shape.
- **Off**, generated sources and resources are byte-identical to a build without this feature,
  apart from the Contract IR's move to `irVersion` 3. Any explicit `@AgenticField(channels)` is a
  compile ERROR naming the field and the option: a `channels = API` on a sensitive field that is
  still served to agents would turn a safety declaration into a silent no-op.
- **On with no declaration anywhere**, the output is also byte-identical: the channels agree, so no
  AI record is generated. Turning the flag on can still fail a build whose entity-returning methods
  lack a `returnType` (see [The raw-entity path](#the-raw-entity-path)).

## Generated types and names

Projection happens at compile time. A record's components cannot be removed at runtime, so a
channel whose projection differs gets its own record.

- **The API keeps the existing name.** Each entity's DTO, `OrderDto` or `@AgenticEntity(dtoName)`,
  is its API projection. REST controllers return it, the OpenAPI component schema describes it, and
  Java code keeps using it. An entity whose fields are all AI-only has no API record and no
  OpenAPI schema.
- **The AI gets a separate record only where the projections differ.** MCP tool classes return the
  AI projection. For an entity whose projections agree it is the same `OrderDto`, shared by both
  channels. For one that differs it is a separate record, `OrderAiDto`, in the DTO's package.
- **Names.** The AI record's name is the DTO name with its `Dto` suffix replaced by `AiDto`, so
  `OrderDto` becomes `OrderAiDto` and a custom `OrderSummary` becomes `OrderSummaryAiDto`.
  `@AgenticEntity(aiDtoName = "OrderForAgents")` overrides it. A name that collides with another
  type in the package is a compile ERROR on the entity: another entity's DTO or AI record, or a type
  the compilation declares. `aiDtoName` must be a Java identifier; with the flag off it has no
  effect and draws a WARNING.
- **Each record's metadata is its own.** `FIELD_METADATA` holds only the record's fields.
- **AI record names are not on the wire.** MCP returns JSON and ai-atlas emits no MCP output schema,
  so no client sees the name. An AI record appears or disappears as declarations change, anywhere in
  the entity's reference graph.

### Transitive splitting

An entity splits (gets an AI record) when its own AI and API field sets differ, **or** when one of
its fields refers, directly or through a collection, iterable or array, to an entity that splits: its
AI record must refer to the other entity's AI record. The split is a least fixpoint, so reference
cycles terminate.

```java
@AgenticEntity public class OrderAction {
    @AgenticField(description = "Type") private String type;
    @AgenticField(description = "Clerk", channels = Channel.API) private String performedBy;
}
@AgenticEntity public class Shipment {
    @AgenticField(description = "Id") private Long id;
    @AgenticField(description = "Actions") private List<OrderAction> actions;
}
```

`OrderAction` splits on its own. `Shipment` declares nothing, but splits through its actions:
`ShipmentAiDto(Long id, List<OrderActionAiDto> actions)` next to
`ShipmentDto(Long id, List<OrderActionDto> actions)`. An entity that declares nothing and refers to
nothing that splits generates exactly today's single record.

Nested entities are projected recursively, and collections, iterables and arrays element by element,
in fields and in method returns alike. A reference cycle is cut at runtime as before: a revisited
instance maps to `null`.

### Versions first, then channels

Eligibility is not versioned. The version projection applies first (the fields active at
`ai.atlas.api.major`) and the channel projection second, so a build still has one DTO pair.
`@AgenticField(sinceVersion = 2, channels = Channel.AI)` means "added in v2, agents only": at major 1
the field is on no channel, and the entity does not split because of it.

### Processing rounds

An entity's records are generated in the processing round that registers it, from every entity
registered so far. A reference to a type from a later round is already a compile error, since the
Contract IR cannot record it, so a later round never changes whether an earlier entity splits. An
entity from a later round that refers to an earlier, split entity splits too.

## Empty intersections

Every projection must leave something to return.

- An operation whose channel leaves its returned entity with no eligible field is a compile ERROR
  on the method. Narrow the method's `channels`, or make a field eligible for the channel.
- A field eligible for a channel that refers to an entity with no field for that channel is a
  compile ERROR on the field. Leave the channel out of the field's `channels`, or make a field of the
  referenced entity eligible for it.

Neither case is silently dropped or answered with an empty record.

**Inputs.** Entities are never request bodies today: operations bind method parameters, whose
requiredness and constraints come from [`@AgenticParam` and Bean Validation](constraints-and-hints.md).
When entity-typed request bodies arrive, a *required* input field not eligible for an operation's
channel will be a compile ERROR, and an *optional* one will be left out of that channel's input
schema.

## The raw-entity path

Projection reaches only responses mapped to a DTO. A method without a resolvable
`@AgenticExposed(returnType)` returns the entity itself, which projection cannot reach. The runtime
still keeps the entity to its `@AgenticField` whitelist on both channels, but knows no channel, so
the raw entity carries fields of either channel:

- On REST, the runtime's `AgentSafeSerializer` keeps to `@AgenticField` fields, so it would serve
  AI-only fields.
- On MCP, the runtime writes every tool result through `AgentSafeToolCallResultConverter`: Spring
  AI's own tool-result `ObjectMapper` with the runtime's `AgentSafeModule` registered. The raw entity
  keeps to `@AgenticField` fields too, so it would serve API-only fields to the agent. Before the fix
  for [#50](https://github.com/gosha70/ai-atlas/issues/50), Spring AI serialized it without the
  module, and every getter reached the agent, including those of fields without `@AgenticField`.
  So does a tool the application serves through its own `ToolCallbackProvider`,
  `List<ToolCallbackProvider>`, `ToolCallback` or `List<ToolCallback>` bean. Where Spring AI gathers
  those beans for the MCP server, the runtime rebuilds each callback that would use Spring AI's
  default converter around `AgentSafeToolCallResultConverter`, keeping its definition and
  `returnDirect`. The beans themselves are left as they are. A tool naming its own
  `resultConverter` keeps it. A callback whose result conversion the runtime cannot see fails
  startup when it serves a tool the runtime registers. Otherwise it is served with a WARNING, or
  fails startup under `ai.atlas.mcp.fail-on-unprotected-tools=true`.
  Four paths stay outside the whitelist:
  - an entity used as a `Map` key is written with its `toString()`;
  - `ChatClient.tools(bean)` calls a tool in process with Spring AI's default converter; pass
    `AgentSafeToolCallbacks.agentSafe(provider)` with `.toolCallbacks(...)` instead;
  - an `@McpTool` method is serialized by Spring AI's annotation support, and one returning an
    entity is reported at startup;
  - a tool specification bean the application declares itself is served as it is.

  The README's JSON serialization section gives the remedies.
- An unannotated subtype of an entity, such as `VipCustomer extends Customer`, serializes as its
  nearest entity on both channels: `Customer`'s `@AgenticField` getters, never `VipCustomer`'s own.
  With the flag off, that also covers an `@AgenticField VipCustomer customer`, whose DTO still copies
  the raw `VipCustomer`.

So with the flag on, an exposed method that returns an `@AgenticEntity`, or a collection, iterable
or array of one, without a resolvable `returnType` is a compile ERROR on the method: declare
`returnType = Order.class`. A subtype of an entity counts too, even though `@AgenticEntity` is not
inherited: `VipOrder extends Order` returned without a `returnType` is an ERROR asking for
`returnType = Order.class`. Only operations active at the configured major are checked. `Optional`
and other wrapper return types remain unsupported: `Optional<Order>` with a `returnType` is rejected
as incompatible, as before. Other return shapes, such as `List<List<Order>>`,
`Iterable<? super Order>`, `Map<String, Order>` and `Stream<Order>`, are not checked: their raw
entities keep the `@AgenticField` whitelist at runtime, as above, but not the channel projection.

The same holds for fields. With the flag on, an `@AgenticField` whose type, or collection, iterable
or array element type, is an unannotated subtype of an entity, such as `VipCustomer customer` or
`List<VipCustomer> customers` where `VipCustomer extends Customer`, is a compile ERROR on the field:
its DTO would copy the raw `VipCustomer`, which the runtime serializes through `Customer`'s whitelist
but not its channel projection. Declare the field as `Customer`, or add
`@AgenticField(type = Customer.class)`. With the flag on, the hint also takes effect on a direct
field, so `@AgenticField(type = Customer.class) VipCustomer customer` maps through `CustomerDto` on
REST and `CustomerAiDto` on MCP, and the Contract IR records its reference to `Customer`. The hint
must be assignable from the field's type. With the flag off, a hint on a direct field still has no
effect and draws a WARNING.

There is no other runtime change: the controller calls `OrderDto.fromEntity`, the tool calls
`OrderAiDto.fromEntity`, both are `@Generated` ai-atlas types that `DtoResponseBodyAdvice` accepts,
and tool registration is unchanged.

## PII

With the flag on, an AI-eligible `@AgenticField` whose name matches a PII pattern (see
[PII detection](annotation-guide.md#pii-detection)) draws a WARNING on the field, as MCP tool results
carry it into agents' context. It is never an ERROR, even under `ai.atlas.strict`. Declaring
`channels = Channel.API` silences it.

## Constraints and `mcp-tools.json`

`mcp-tools.json` carries only input schemas and hints, so it does not change. Field constraints
reach only response schemas (DTO properties and OpenAPI), so they follow the API projection. If an
MCP output schema is emitted later, it will be generated from the AI projection.

## The Contract IR and the gate

Contract IR `irVersion` 3 records each field's **effective** channels, sorted and always present:
with the flag off, every field records `[AI, API]`, because that is what clients receive, so turning
the flag on with a declaration is gated as a real change. The IR records the declaration and never
the AI record's name. A version 1 or 2 baseline migrates exactly, every field to `[AI, API]`, with no
lock-mode difference; `atlasAccept` writes version 3.

For each field active at the baseline's major M:

| Change | Classification |
|--------|----------------|
| Loses channel C, and its entity is reachable from an operation active on C through fields on C | **Breaking** (output) |
| Loses channel C, and no operation on C reaches its entity | Compatible |
| Gains channel C | Compatible |
| The entity's AI record appears or disappears | Informational |
| The DTO name (`dtoName`) changes | Breaking, as before |

Channels have no lifecycle of their own, so the remedy for a breaking channel change is a new major
or `atlasAccept`. See [Contract governance](contract-governance.md#field-channels) for the full rules
and the migration.

## Errors and warnings

| Kind | When |
|------|------|
| ERROR | `ai.atlas.projections` is neither `true` nor `false` |
| ERROR | `@AgenticField(channels)` is empty, or mixes `INHERIT` with explicit channels |
| ERROR | An explicit `@AgenticField(channels)` while the flag is off |
| ERROR | An operation's channel leaves its returned entity with no eligible field (on the method) |
| ERROR | A field eligible for a channel refers to an entity with no field for that channel (on the field) |
| ERROR | An entity-returning method has no resolvable `returnType` while the flag is on |
| ERROR | An AI record name collides with another type in its package, or `aiDtoName` is not a Java identifier |
| WARNING | `@AgenticEntity(aiDtoName)` while the flag is off |
| WARNING | An AI-eligible field's name matches a PII pattern while the flag is on |
