**Title:** Per-channel field projections: AI and API responses get their own safe shape (epic #23, Phase 4)

---

Child of #23. Delivers the epic's Phase 4, §7 "Add per-channel field projections". Operation-level
`channels = {AI, API}` already decides *which* operations each channel sees. This phase decides
*which fields* each channel receives from an entity. The effective projection is the intersection:

```
field eligible channels  ×  operation exposed channels  =  effective response projection
```

## Why

- **Both channels get the same DTO.** The processor generates one record per entity per build
  (`DtoGenerator`). The REST controller and the MCP tool both return it (`McpToolGenerator`,
  `RestControllerGenerator`). A field useful to a back-office REST client, such as an internal
  margin, goes into LLM context too. A field meant for agents, such as a plain-language summary,
  goes into the public REST and OpenAPI contract.
- **Nothing below the operation knows about channels.** Channels live only on `@AgenticExposed`.
  `@AgenticField`, the entity model, the DTO and the Contract IR have no channel slot.
- **The gate would miss the change.** The spike made a field API-only. That removed it from every
  MCP response, but the Contract IR stayed byte-identical and `ContractGate.compare` returned
  nothing, even in lock mode. Without an IR slot, per-channel shapes are exactly the undeclared
  breaking change this epic exists to catch.
- **A record's components cannot be removed at runtime** (epic §7). Projection has to be
  compile-time: a separate generated record per channel wherever the channels differ.

## Scope (owner decisions needed)

The feasibility spike is on branch `claude/phase4-projections-spike`, in
`spike/phase4-projections/REPORT.md`.
- **Prototype:** `-Aai.atlas.projections=true` adds `@AgenticField(channels)`. It generates
  `OrderAiDto` next to `OrderDto` only when the projections differ. The REST controller and OpenAPI
  keep `OrderDto`, and the MCP tool returns `OrderAiDto`.
- **How it was proved:** ten tests call the generated classes, REST through Jackson and MCP through
  Spring AI 1.1.7's `MethodToolCallbackProvider`.
- **Byte-identical when off:** with the flag off, output matches the master golden snapshot.

Each item below is marked **Owner decision needed**, with the spike's recommendation.

### 1. Field eligibility attribute — **Owner decision needed**
- **Recommended:** `@AgenticField(channels = …)`, reusing `AgenticExposed.Channel`.
  - The default `{INHERIT}` means **every channel**, so an undeclared field behaves exactly as today.
  - An empty array, or `INHERIT` mixed with explicit values, is an ERROR.
- **Alternatives:** booleans `ai()`/`api()`, which do not extend to more channels; or an
  entity-level default plus field override, which adds a third inheritance level. Defer that until
  asked.
- **The whitelist is unchanged.** Eligibility is read only on `@AgenticField` fields and can only
  remove a field from a channel. An unannotated field is on no channel.

### 2. Generated types and names — **Owner decision needed**
- **Recommended: split only when the projections differ.**
  - The API projection keeps the existing name, `XDto`, for REST, OpenAPI and Java consumers.
  - The AI projection is `XAiDto`, overridable with a new `@AgenticEntity(aiDtoName)`.
  - An entity with no eligibility declared anywhere in its reference graph generates exactly
    today's output.
- **Splitting is transitive.** An entity whose field refers, directly or through a collection or
  array, to a split entity splits too, so its AI record refers to the AI records. This is computed
  as a fixpoint so cycles terminate.
- **Collisions:** a derived `XAiDto` that collides with another type in the package is a compile ERROR.
- **AI record names are not on the wire.** MCP returns JSON and no output schema is emitted.
  Documentation says AI records can appear or disappear as declarations change.
- **Alternatives:**
  - Always generating two types renames every public DTO and every OpenAPI schema, a BREAKING
    `dtoName` change on every entity.
  - Runtime filtering (`@JsonView`) keeps both channels' fields in the type and the schema. It also
    fails on MCP, whose serializer ignores the Spring context configuration.

### 3. Empty intersections and input fields — **Owner decision needed**
- **Recommended — outputs:**
  - An operation whose channel leaves its returned entity with no eligible field is a compile ERROR
    on the method.
  - A field eligible for a channel that refers to an entity with no field for that channel is an
    ERROR on the field.
- **Alternatives:** silently dropping the referring field (a response shape changed by another
  entity's declaration), or an empty record.
- **Inputs:** today entities are never inputs. Parameters are bound from method parameters, and
  their requiredness and constraints come from Phase 3. The rule is written down now and enforced
  once entity-typed request bodies exist (Phase 5):
  - a *required* input field not eligible for an operation's channel is an ERROR;
  - an *optional* one is left out of that channel's input schema.

### 4. Contract IR version 3 and the gate — **Owner decision needed**
- **Recommended: `irVersion` 3** with `Field.channels`: sorted, always present, and recording the
  **effective** eligibility.
  - With the flag off, every field records `[AI, API]`, because that is what clients receive.
  - Turning the flag on with declarations is therefore gated as a real change.
- **Alternatives:**
  - Keep v2 with an optional key. That breaks the "missing v2 slot is malformed" rule, and older
  ai-atlas versions would silently drop the key.
  - No IR change. The spike proved the gate is blind then.
- **Migration is exact, not *unknown*.** Nothing before Phase 4 could narrow a field's channels, so
  every v1 or v2 field migrates to `channels = [AI, API]`. Lock mode needs no special case, and
  `atlasAccept` writes v3.
- **Gate rules**, per channel C, for fields active at the baseline major:
  - a field **loses** C: BREAKING OUTPUT, when the entity is reachable from an operation active on
    C; otherwise COMPATIBLE;
  - a field **gains** C: COMPATIBLE;
  - an AI record name appears or disappears: INFORMATIONAL;
  - the API record name keeps today's BREAKING `dtoName` rule.
- **Remedy text:** the field has no per-channel lifecycle (item 5), so the remedy is `atlasAccept`
  or a new major.
- The IR records the declaration only, never the derived AI DTO name.

### 5. Interaction with Phase 3 and with versions — **Owner decision needed**
- **Recommended — `mcp-tools.json`:** no change. It carries only `inputSchema` and hints.
  - Field constraints reach only response schemas (DTO and OpenAPI), so they follow the API
    projection automatically.
  - If an MCP `outputSchema` is emitted later, it is generated from the AI projection with the
    constraints of AI-eligible fields. That is tracked separately.
- **Recommended — versions:** eligibility is **unversioned**.
  - The version projection applies first and the channel projection second: still one DTO pair per
    build.
  - `sinceVersion = 2, channels = AI` means "added in v2, agents only".
- **Alternative:** per-channel lifecycle attributes such as `aiRemovedInVersion`, deferred until
  asked.

### 6. Opt-in flag — **Owner decision needed**
- **Recommended:** a new flag, `ai.atlas.projections`. The Gradle plugin exposes it as
  `agentic { projections = true }`.
  - It is independent of `ai.atlas.constraints`, so Phase 3 users get no new shapes on upgrade.
- **Alternatives:** reusing `ai.atlas.constraints`, which is a silent opt-in for existing users; or
  a general `ai.atlas.features` list, which changes Phase 3's shipped flag.
- **Off by default.** Generated output is byte-identical to today.
- **Declared eligibility with the flag off — Owner decision needed.**
  - **Recommended: ERROR.** A `channels = API` on a sensitive field that is still served to agents
    turns a safety declaration into a silent no-op.
  - **Alternative:** a WARNING that becomes an ERROR under `ai.atlas.strict`. The spike prototype
    implements a WARNING.
- **What stays on regardless:** the IR records effective eligibility with the flag off, as
  `[AI, API]` (item 4).

### 7. Runtime — **Owner decision needed**
- **No runtime change for generated DTOs.** Projection is compile-time.
  - The controller calls `XDto.fromEntity` and the tool calls `XAiDto.fromEntity`.
  - Both are `@Generated` ai-atlas types, so `DtoResponseBodyAdvice` accepts them.
  - Tool registration is unchanged.
- **The raw-entity path.** A method with no resolvable `returnType` returns the entity itself, and
  projection cannot reach it.
  - REST: `AgentSafeSerializer` whitelists but is channel-unaware.
  - MCP: Spring AI serializes with its own `ObjectMapper`, without `AgentSafeModule`.
- **Recommended:** with the flag on, an exposed method that returns an `@AgenticEntity` (or a
  collection or array of one) without a resolvable `returnType` is a compile ERROR.
- **Alternative:** a channel-aware runtime serializer plus a custom Spring AI
  `ToolCallResultConverter`. That is a runtime change duplicating the compile-time projection.

## Out of scope
- **The pre-existing MCP raw-entity leak** gets its own bug issue. The spike showed that a tool
  returning an entity without `returnType` serializes every getter, including non-`@AgenticField`
  ones, because Spring AI's serializer does not carry `AgentSafeModule`. It exists with or without
  this phase.
- **`Optional` and other wrapper return types.** They are rejected at compile time today when a
  `returnType` is declared.
- **Entity-typed request bodies**, and enforcing the input rule in item 3 (Phase 5, §9).
- **Emitting an MCP `outputSchema`.**
- **Per-channel lifecycle attributes.**
- **Collection safety, REST metadata and release snapshots** (Phase 5). **Qualified tool names** (2.0).

## Acceptance criteria
- [ ] A field can be eligible for API only, AI only, or both. An undeclared field is eligible for both.
- [ ] The effective response projection is the intersection of field eligibility and operation
      channel, applied after the version projection.
- [ ] REST controllers and OpenAPI component schemas use the API projection under the existing DTO
      names. MCP tools return the AI projection.
- [ ] One entity returned from an AI-only method and from an API-only method gets the right
      projection in each. The same holds for a method on both channels.
- [ ] Nested entities, collections, iterables and arrays are projected recursively. Reference cycles
      terminate. An entity that does not differ generates a single record, shared by both channels.
- [ ] A separate AI record is generated only for entities whose projections differ. A derived name
      collision fails compilation, and `@AgenticEntity(aiDtoName)` overrides the name.
- [ ] An operation whose channel leaves no eligible field fails compilation. So does a
      channel-eligible reference to an entity with no field for that channel.
- [ ] The whitelist model is preserved: a field without `@AgenticField` appears on no channel, as
      tests on both channels show.
- [ ] REST and MCP projections are independently testable. Tests call the generated controller and
      the generated tool through Spring AI and compare their payloads.
- [ ] The IR is version 3 with per-field effective `channels`. v1 and v2 baselines migrate to
      `[AI, API]` with no lock-mode difference, and `atlasAccept` writes v3.
- [ ] The gate classifies a field losing a channel it is reachable on as BREAKING OUTPUT, gaining a
      channel as compatible, and AI record names as informational.
- [ ] Declared eligibility with the flag off behaves as decided in item 6.
- [ ] With the flag on, an entity-returning exposed method without a resolvable `returnType` fails
      compilation, as decided in item 7.
- [ ] With the flag off, generated output is byte-identical to before (golden snapshot). With the
      flag on and no declarations, it is also byte-identical.
- [ ] The Gradle plugin exposes `agentic { projections = true }`, and the CLI passes the option
      through.
- [ ] Docs cover the attribute, the default, the naming and splitting rules, the flag, the runtime
      raw-entity caveat, and the IR v3 migration.
- [ ] No network, database, model call or live service anywhere in the processor or the gate.
