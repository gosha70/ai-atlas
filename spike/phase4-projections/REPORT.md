# Phase 4 spike: per-channel field projections (epic #23 §7)

Branch `claude/phase4-projections-spike`, cut from `master` at `bc7e20a`, which is after Phases 0–3.
Unless a line says otherwise, every `file:line` below refers to `master` at `bc7e20a`. The
prototype's own files are named in §5.

## TL;DR

- **Today there is one DTO per entity per build.** It is the version projection at the configured
  `ai.atlas.api.major`, and the REST controller and the MCP tool both return it. Channels exist only
  on operations. Nothing below the operation knows about them: not the entity model, the DTO, the
  Contract IR or the runtime.
- **Compile-time projection works.** The prototype, behind `-Aai.atlas.projections=true`, adds
  `@AgenticField(channels = …)` and generates a second record, `OrderAiDto`, only for entities whose
  projections differ. The REST controller and OpenAPI keep `OrderDto`; the MCP tool returns
  `OrderAiDto`. Ten tests prove it. They load the generated classes and call them: REST through
  Jackson, MCP through Spring AI's own `MethodToolCallbackProvider`. They cover API-only and AI-only
  fields, one entity returned from AI-only and API-only methods, recursive nesting through a cycle
  and a collection, and byte-identical output with the flag off. The existing master golden test
  still passes.
- **The splitting is transitive, and that has a cost.** One API-only field on `OrderAction` makes
  `Order` and `Shipment` split too, because their AI records must refer to `OrderActionAiDto`. That
  cost drives the naming decision (Q2).
- **The gate cannot see this today.** The prototype proves it: moving a field to API-only removes
  it from every MCP response, yet the IR is byte-identical and `ContractGate.compare` returns no
  difference, even in lock mode. Phase 4 needs an IR change (Q4).
- **Two pre-existing gaps matter for the whitelist promise. Both are proved by tests.**
  1. A method with no resolvable `returnType` returns the raw entity. On MCP, Spring AI serializes
     it with its own `ObjectMapper`, which does not carry the runtime's `AgentSafeModule`, so
     **non-`@AgenticField` getters (an SSN in the test) reach the agent.** Projection cannot fix
     this path (Q7).
  2. `Optional<Entity>` with a `returnType` is rejected at compile time. Wrapper types are
     unsupported, so projection has nothing to do there today.
- **Runtime impact is none for the DTO paths.** Projection is purely compile-time. The runtime's
  `AgentSafeSerializer` is channel-unaware, so the raw-entity path needs a decision (Q7).

---

## 1. How `@AgenticExposed(channels = …)` is modelled and consumed today

| Step | Where | What happens |
|---|---|---|
| Declaration | `modules/annotations/.../AgenticExposed.java:70`, enum at `:75-82` | `Channel[] channels() default {INHERIT}`; `Channel` is `INHERIT`, `AI` or `API`. Applies at class and method level only. **Not on fields.** |
| Resolution | `processor/util/AttributeResolver.java:98-134` | Method `INHERIT` → class → framework default `{AI, API}` (`:28`). A method may only **narrow** the class set (`:126-131`, ERROR "must be a subset"). Mixing `INHERIT` with explicit values is an ERROR. |
| Method model | `processor/AgenticProcessor.java:463-467` (`buildMethodModel`) | Resolved into `MethodModel.channels` (`model/ServiceModel.java`, a `Set<String>` of `"AI"`/`"API"`). |
| Contract IR | `processor/contract/IrBuilder.java:229` (resolve), `:251` (REST mapping only when `API`), `:271` (sorted into `Operation.channels`) | `ContractIr.Operation.channels` (`contract/ContractIr.java:123`). Fields have **no** channel slot (`ContractIr.java:77-81`). |
| Projection | `contract/ContractProjection.java:263-281` | The IR operation is turned back into a `MethodModel` with its channels. `operationIds` covers API operations only (`:296-307`). |
| MCP tool class | `generator/McpToolGenerator.java:92-97` | Keeps `channels().contains("AI")` methods. No class is written when none remain (`:68-72`). |
| REST controller | `generator/RestControllerGenerator.java:84-88` | Keeps `channels().contains("API")` methods. |
| OpenAPI | `generator/OpenApiGenerator.java:247` (paths) | Only API operations become paths. **Component schemas are emitted for every registered entity, whatever channel returns it** (`:145-149`). |
| `mcp-tools.json` | `generator/McpToolsResourceGenerator.java:72`, `:122` | AI tools only (flag `ai.atlas.constraints`). Holds `inputSchema` and hint `annotations`. **No `outputSchema`**, so the response shape is not described there. |
| Gate | `contract/ContractComparison.java:268-273` | An operation losing a channel is BREAKING ("Clients of the [AI] channel lose the operation"); gaining one is compatible. |
| Runtime | `runtime/mcp/AgenticMcpConfiguration.java:104`, `:312-320` | Registers tools from the generated tool beans plus `mcp-tools.json`. The base `outputSchema` is passed through unchanged. Channels are never read at runtime: a channel is simply whether a generated class or method exists. |

## 2. How DTOs are generated today

- **One record per entity per build.** A build targets one major, `ai.atlas.api.major`.
  - `processEntities` (`AgenticProcessor.java:193-264`) scans each `@AgenticEntity` into the IR
    (`:223`, `FieldScanner.scanAll`).
  - It then projects the IR at that major (`:226`, `ContractProjection.entity` at `:244-261`, which
    drops inactive fields at `:248`).
  - Finally it calls `DtoGenerator.generate` once per entity (`:260`).
  - The name is `@AgenticEntity(dtoName)` or `{Simple}Dto`. The package is
    `@AgenticEntity(packageName)` or `{pkg}.generated` (`IrBuilder.java:140-147`).
- **Record shape.** `DtoGenerator.buildRecordSpec` (`generator/DtoGenerator.java:89-155`) produces:
  - one component per active field (`:96-99`, type from `resolvedFieldType` at `:327-336`);
  - the nested `FieldMeta` record and `CLASS_NAME`/`CLASS_DESCRIPTION`/`INCLUDE_TYPE_INFO`;
  - `FIELD_METADATA`, keyed by display name (`:131-137`);
  - a static `fromEntity(Entity)` factory (`:231-288`).
- **Entity → DTO mapping code lives in the generated DTO itself.** `XDto.fromEntity` calls the
  entity's getters (`resolveGetter`, `:313-320`). The generated wrappers only call it:
  - MCP: `McpToolGenerator.java:264-282`;
  - REST: `RestControllerGenerator.java:196-214`.
- **Nested entities.** `EntityRefResolver.resolve` (`util/EntityRefResolver.java:33-66`) finds a
  referenced `@AgenticEntity` in three places: the field type, the collection/array element type, or
  the `@AgenticField(type)` hint. The component becomes the referenced DTO, and mapping goes through
  `RefDto.fromEntity` (`DtoGenerator.java:293-307`). Cycles are cut by a `ThreadLocal` identity set
  (`_visiting`, `:140-149`, `:246-256`): a revisited instance maps to `null`. A reference to an entity
  with no active fields is an ERROR, and the referring DTO is skipped (`AgenticProcessor.java:239-258`).
- **Collections.** `FieldScanner` classifies fields as `NONE`, `COLLECTION`, `ITERABLE` or `ARRAY`
  (`util/FieldScanner.java:114-141`). Entity collections are always emitted as `List<RefDto>`
  (`DtoGenerator.java:332-334`).
- **Method returns.** `ReturnTypeValidator.resolveReturnKind` (`util/ReturnTypeValidator.java:31-49`)
  classifies a method's return. Collection, iterable and array returns map element by element to
  `List<Dto>` (`McpToolGenerator.java:159-169`, `:266-278`).
- **The DTO is used only when `returnType` resolves.**
  - `AttributeResolver.resolveReturnEntityType` (`AttributeResolver.java:66-75`) takes the method's
    `returnType`, else the class's (not for `void`). The **declared Java return type is never
    inferred**.
  - With no `returnType`, `returnDtoType` is `null` and the wrapper returns `service.m(..)` raw
    (`McpToolGenerator.java:198-199`, `RestControllerGenerator.java:189-190`).
- **`Optional` and other wrappers are not supported.** `Optional<Order>` has `ReturnKind.NONE`.
  With `returnType = Order.class`, `validateReturnTypeCompat` checks `Optional` against `Order`,
  finds them incompatible, and the processor reports an ERROR (`ReturnTypeValidator.java:107-111`,
  `AgenticProcessor.java:425-431`). The test `optionalReturnWithAReturnTypeIsRejectedToday` proves
  it. Without `returnType`, the `Optional` is returned raw, as in the item above.

## 3. The whitelist safety model and where it is enforced

| Layer | Where | Enforcement |
|---|---|---|
| Scan | `util/FieldScanner.java:85-88` | A field without `@AgenticField` is skipped. It never enters `FieldModel`, the IR or any DTO. This is the structural guarantee. |
| DTO | `DtoGenerator.java:96-99`, `:258-273` | Record components and `fromEntity` getters come only from `@AgenticField` fields. PII cannot be added to the record at runtime. |
| PII lint | `util/PiiDetector.java:60` (called at `AgenticProcessor.java:273`) | WARNING for **unannotated** fields whose names look like PII. Advisory only. |
| REST runtime | `runtime/security/DtoResponseBodyAdvice.java:41-60` | For generated controllers, **logs a WARN** when the body is not an ai-atlas `@Generated` type. It does not block anything. |
| Raw-entity serialization | `runtime/json/AgentSafeModule.java:55-66`, `AgentSafeSerializer.java:113-135` | A Jackson module in the Spring context `ObjectMapper` serializes any `@AgenticEntity` instance through `@AgenticField` getters only. This covers the REST raw-return path. |
| **MCP raw-entity path** | Spring AI `DefaultToolCallResultConverter` → `JsonParser.toJson` (spring-ai-model 1.1.7) | Spring AI uses **its own static `ObjectMapper`, without `AgentSafeModule`**. A tool that returns an entity raw serializes every getter. The test `entityReturnedWithoutReturnTypeReachesMcpUnfilteredToday` shows `ssn` reaching the MCP result. **This bug predates Phase 4, and projection cannot close it on this path.** |

The prototype keeps the model intact. Eligibility is read only for fields that are already
`@AgenticField` (`ProjectionsOption.recordEntity`), and it can only **remove** a field from a
channel. The test `neverAnnotatedFieldIsOnNoChannel` checks that an unannotated `customerSsn` is on
neither channel.

## 4. The Contract IR today, and what the gate sees when a field changes channel

- **What the IR records.** `ContractIr.Field` (`contract/ContractIr.java:77-81`) records name,
  display name, types, reference (`TypeRef(entity, dto)`), enum data, `sensitive`, description,
  constraints (v2) and lifecycle. **There is no channel slot.**
- **What the gate compares.** Fields are compared per entity at the baseline's major
  (`ContractComparison.java:149-169`):
  - an active field that disappears is BREAKING OUTPUT ("Responses no longer carry the field", `:158`);
  - a new field is COMPATIBLE (`:165`).
- **What happens when a field becomes AI-only or API-only.** The field is still declared and active,
  so the IR does not change and the gate is silent. The test
  `contractIrAndGateDoNotSeeAFieldLeavingTheAiChannel` compiles a baseline and a fresh fixture that
  differ only by `channels = API` on `marginCents`:
  - `api.ir.json` is **byte-identical**;
  - `ContractGate.compare` returns **no differences**;
  - lock mode (`ContractGate.documentDifferences`) has nothing to diff either.

  Yet every MCP response lost `marginCents` (and `agentSummary`, if flipped to API, left every REST
  response and the OpenAPI schema). That is exactly the "undeclared breaking change" the epic's
  centrepiece exists to catch. **Phase 4 cannot ship without an IR slot.**
- **The DTO reference.** The IR records the field's and the operation's `reference.dto`, which is
  the base DTO name (`IrBuilder.java:378-386`, `:364-372`). An AI record name is not recorded. That
  is fine, because it is not on the wire (see Q2).

## 5. The prototype

**Option:** `-Aai.atlas.projections=true|false`, default off. It is registered in
`AgenticProcessor`'s `@SupportedOptions`. The Gradle plugin is not wired; it would be
`agentic { projections = true }`, like `constraints` in `plugin/ContractArguments.java:34`.

**Annotation:** `AgenticField.channels()` (`modules/annotations/.../AgenticField.java`) reuses
`AgenticExposed.Channel`.
- The default `{INHERIT}` means eligible for every channel.
- Explicit values narrow the field.
- An empty array, or `INHERIT` mixed with explicit values, is an ERROR.
- With the flag off, an explicit value is a WARNING and is ignored.

**Rule:** the effective projection is *field eligible channels* × *operation channel*. The channel
projection is applied **after** the version projection, so lifecycle is resolved first and channel
second.

**Generation** (`processor/generator/ProjectionsOption.java`, wired in `AgenticProcessor` with
about 15 lines):
- `view(registry, channel)` gives each entity model with only the fields eligible for the channel,
  named with that channel's DTO. `DtoGenerator` then generates either channel **unchanged**, because
  references resolve inside the view.
- The API view keeps the existing name, `OrderDto`. It is used by the REST controller and by
  OpenAPI's component schemas (`openApiEntities`).
- An AI record, `OrderAiDto`, is generated **only for entities that split**. An entity splits when
  its own AI and API field sets differ, or when a field refers to an entity that splits. That is
  computed as a least fixpoint, so the `Order ↔ OrderAction` cycle terminates.
- `withAiReturns` points the MCP tool's `returnDtoType` at the AI record. The REST controller and
  OpenAPI keep the IR-derived API type.
- An operation whose channel leaves the returned entity with no eligible field is an ERROR on the
  method. A nested field that refers to an entity with no field for that channel is also an ERROR.
- The IR, `mcp-tools.json` and the runtime are **unchanged**.

**Sample output** is in `sample-output/`: the fixture sources in `input/`, then the generated
records, controller, tool, OpenAPI document and IR. `OrderAiDto` is
`(id, status, agentSummary, List<OrderActionAiDto> actions, CustomerDto customer)`, while `OrderDto`
is `(id, status, marginCents, List<OrderActionDto> actions, CustomerDto customer)`. `Customer`
doesn't differ, so both channels share `CustomerDto`.

**Tests** are in `modules/processor/src/test/java/.../ProjectionsSpikeTest.java`. All 10 pass.
Each proves:

| Test | Proves |
|---|---|
| `apiOnlyFieldIsOnRestAndOpenApiAndAbsentFromMcp` | The API-only `marginCents` is in the `OrderDto` components, the OpenAPI `OrderDto` schema and the REST JSON. It is absent from the MCP result, which went through Spring AI. The AI-only `agentSummary` is the opposite. The OpenAPI document has no `OrderAiDto` schema. |
| `sameEntityFromAnAiOnlyAndAnApiOnlyMethodGetsEachChannelsProjection` | `forAgent` (AI only) exists only on the tool and returns `OrderAiDto`; `forApi` (API only) exists only on the controller and returns `OrderDto`. Their payloads differ as expected. |
| `nestedEntitiesAndCollectionsAreProjectedRecursively` | `OrderActionAiDto` drops `performedBy`. `Shipment` splits transitively (`List<OrderActionAiDto>`). `Customer` does not split. Nested action JSON differs per channel. A `List<Order>` return is projected element by element. |
| `operationWhoseChannelHasNoEligibleFieldIsAnError` | Channels × eligibility = ∅ is a compile ERROR on the method. |
| `neverAnnotatedFieldIsOnNoChannel` | The whitelist is preserved. |
| `flagOnWithoutEligibilityDeclaredIsByteIdenticalToFlagOff` | With the flag on and no declarations, every generated source and resource is byte-identical to flag off. |
| `flagOffIgnoresEligibilityWithAWarningAndIsByteIdentical` | With the flag off and eligibility declared, there is a WARNING and output byte-identical to sources without it. |
| `contractIrAndGateDoNotSeeAFieldLeavingTheAiChannel` | The gate blind spot from §4. |
| `optionalReturnWithAReturnTypeIsRejectedToday` / `entityReturnedWithoutReturnTypeReachesMcpUnfilteredToday` | Characterise today's gaps (§2, §3). |

**Golden comparison with `master`.** `IrRewireGoldenTest` is the snapshot of `master`'s generated
output for 10 fixtures plus the demo, with the flag off. It still passes: 13 tests, 0 skipped,
including `demoOutputMatchesTheGoldenSnapshot`.

**Known prototype limits, deliberately left for the real phase:**
- no IR slot and no gate rules;
- no Gradle or CLI wiring;
- no collision check for the derived `…AiDto` name;
- the split is computed over the entities registered so far, so an entity arriving in a later
  processing round is not seen by earlier rounds;
- the no-`returnType` MCP path is not addressed (Q7).

## 6. Open design questions for the owner

Each question gives options, trade-offs and a **recommendation**. `ISSUE-DRAFT.md` repeats them as
"Owner decision needed".

### Q1. Attribute shape and default

| Option | Trade-offs |
|---|---|
| **A. `AgenticField.channels()` reusing `AgenticExposed.Channel`, default `{INHERIT}` = every channel** (prototype) | One vocabulary with the operation attribute, and it extends to a third channel. A field with no declaration stays on both channels, so existing code is unchanged. "INHERIT" reads oddly at field level, where nothing is inherited today. |
| B. Separate booleans `ai()` / `api()`, default `true` | Simple to read. It does not extend to more channels, and `ai=false, api=false` is a second way to say "not annotated". |
| C. Entity-level default plus field override (`@AgenticEntity(channels)`) | Useful for "this whole entity is API-only". It adds a third inheritance level, and an entity used by both channels rarely wants a default other than both. |

**Recommendation: A, with no declaration meaning both channels.** That keeps the whitelist as the
sole gate on visibility, and channels only subtract. The rules:
- an empty array, or `INHERIT` mixed with explicit values, is an ERROR (as the prototype does);
- the Javadoc says `INHERIT` means "every channel the operation is exposed on";
- defer C until someone asks for it.

### Q2. Naming and number of generated types

| Option | Trade-offs |
|---|---|
| **A. Split only when projections differ.** The API keeps `XDto`, and the AI gets `XAiDto` (prototype). | Zero renames. REST, OpenAPI and Java consumers keep every existing name. An entity with no declarations produces exactly today's output. The AI record name never reaches the wire, because MCP returns JSON and there is no `outputSchema`. Cost: splitting is **transitive**, so one AI/API difference deep in a graph creates `…AiDto` for every ancestor that refers to it (`Shipment` in the test). Adding or removing a declaration can make an AI type appear or disappear. |
| B. Always two types, `XApiDto` and `XAiDto` | Predictable, with a split for every entity. It **renames every public DTO and every OpenAPI schema**, which the gate reports as a BREAKING `dtoName` change on every entity. It doubles the generated classes. |
| C. One record per entity with runtime filtering (`@JsonView` or mix-ins) | No new types. A record's components cannot be removed, so the Java type and the OpenAPI schema still describe both channels. Filtering depends on the serializer: Spring AI's `JsonParser` ignores the Spring context's configuration (§3), so AI filtering would silently fail. It contradicts the epic ("a record's components cannot be removed at runtime"). |

**Recommendation: A.** Add three things:
- `@AgenticEntity(aiDtoName)` to override the derived name;
- a compile ERROR when `XAiDto` collides with another generated or declared type in the package;
- documentation that AI records are wire-invisible and may appear or disappear as declarations
  change. That is why the gate treats their names as informational (Q4).

### Q3. Empty intersections, and input fields that are not eligible for a channel

| Option | Trade-offs |
|---|---|
| **A. Compile ERROR** when an operation's channel leaves its returned entity with no eligible field, or when a field eligible for a channel refers to an entity with no field for that channel (prototype) | Explicit, per the epic's "explicit metadata wins". The fix is local: narrow the operation, or declare the reference field's channels. |
| B. Silently drop the referring field from that channel, with a WARNING | Less friction, but the shape of a response changes because of a declaration on another entity. That is surprising, and the gate would have to model it. |
| C. Generate an empty record | Valid Java, but an operation that returns `{}` is useless to a client and hides a mistake. |

**Inputs:** today entities never appear as **inputs**.
- REST binds method parameters with `@RequestParam`, and MCP uses method parameters.
- Requiredness and constraints on inputs come from parameters (Phase 3), not from `@AgenticField`.

So "a required or constrained input field that is not eligible for a channel" cannot occur in
Phase 4. It becomes real only when entity-typed request bodies exist (Phase 5, §9 REST metadata).

**Recommendation:** A for outputs. For inputs, write the rule down now and enforce it when entity
inputs arrive: a required input field not eligible for a channel the operation is exposed on is an
ERROR, and an optional one is dropped from that channel's input schema.

### Q4. Contract IR changes and gate rules

| Option | Trade-offs |
|---|---|
| **A. `irVersion` 3 with `Field.channels`** (sorted, always present, recording the **effective** eligibility) | The gate can classify per-channel output changes. Needs a migration (below). |
| B. Stay on `irVersion` 2 and add an optional `channels` key | No version bump. It breaks Phase 3's rule that "a v2 document with a missing slot is malformed". An older ai-atlas reading a newer baseline would silently drop the slot. |
| C. No IR change (prototype) | The gate is blind (§4, proved). Rejected. |

**Migration of a v1 or v2 baseline** is **exact, not "unknown".** Before Phase 4 nothing could
narrow a field's channels, so every migrated field is `channels = [AI, API]`. This is unlike Phase
3's unknown constraints: there is no unknown→known class, and lock mode needs no special case.

**Record the effective eligibility, not the declared one.** With the flag off, a declared
`channels = API` is ignored and the field is still served to MCP. So the IR must say `[AI, API]`:
the IR describes what clients receive. Turning the flag on then correctly shows up as a BREAKING AI
output change. This differs from Phase 3, which recorded constraints regardless of its flag,
because constraints hold whatever the flag says; eligibility only takes effect with it.

**Gate rules**, per channel C and applied to fields active at the baseline major:
- A field **loses** C → **BREAKING OUTPUT** "clients of the C channel no longer receive the field".
  Report it only if the entity is reachable, through a field chain, from an operation active on C.
  Otherwise it is COMPATIBLE, because nobody on C could see it.
- A field **gains** C → **COMPATIBLE**, the same as today's "field added".
- The appearance or disappearance of an AI record name → **INFORMATIONAL** (not on the wire). The
  API record name keeps today's BREAKING `dtoName` rule.
- **Remedy text:** there is no per-channel lifecycle, so the remedy is `atlasAccept`, or a new major
  in which the field is re-declared. See Q5 for per-channel lifecycle.

**Recommendation:** A, with this migration and these rules. Record the declaration (`channels`)
only, never the derived AI DTO name.

### Q5. Interaction with Phase 3 (constraints, `mcp-tools.json`) and with version projection

**`mcp-tools.json` and constraints:**
- `mcp-tools.json` carries only `inputSchema` and hint `annotations`. Field constraints reach only
  DTO and OpenAPI **response** schemas (`ConstraintSurfaces.applyOpenApi`, `:158`, via
  `OpenApiGenerator.java:188-189`), so they follow the API projection automatically in the
  prototype.
- **Recommendation:** no change to `mcp-tools.json` in Phase 4.
- If ai-atlas later emits an MCP `outputSchema`, generate it from the AI projection, carrying the
  field constraints of AI-eligible fields. The runtime already passes `base.outputSchema()` through
  (`AgenticMcpConfiguration.java:320`). Track that separately.

**Version projection:**
- Lifecycle and channel are **orthogonal**: version first, then channel (as the prototype does), and
  still one DTO pair per build. `sinceVersion = 2, channels = AI` means "added in v2, agents only".
- Per-channel lifecycle, such as "API until v3, AI forever", is **not** expressible.

| Option | Trade-offs |
|---|---|
| **A. Unversioned eligibility** (recommended) | Narrowing a channel is BREAKING and needs `atlasAccept` or a major bump. Simple. |
| B. Per-channel lifecycle attributes (`aiRemovedInVersion`, …) | Can express gradual retirement per channel. Doubles the lifecycle surface and its validation matrix. |

**Recommendation: A** for Phase 4; revisit B only on demand.

### Q6. The opt-in flag

| Option | Trade-offs |
|---|---|
| **A. New flag `ai.atlas.projections`** (Gradle: `agentic { projections = true }`) (prototype) | Independent adoption. Users of `ai.atlas.constraints` do not suddenly get split DTOs or changed REST shapes. Matches the epic's "shape changes behind an opt-in flag". One more flag to document. |
| B. Reuse `ai.atlas.constraints` | Fewer flags. It couples two unrelated shape changes, and anyone already on `constraints = true` would get new shapes on upgrade, a silent opt-in. |
| C. A general `ai.atlas.features=constraints,projections` | Scales to later phases. It changes Phase 3's shipped flag. |

**Recommendation: A.** Also decide what a declared eligibility does with the flag off:
- The prototype emits a WARNING.
- **I recommend an ERROR.** A developer who writes `channels = API` on a sensitive field expects
  agents not to see it. Honouring the build while serving the field to MCP turns a
  safety declaration into a silent no-op, and the fix is one flag.
- The alternative is WARNING by default and ERROR under `ai.atlas.strict`, as #25's diagnostics do.

### Q7. Runtime impact

**For generated DTO paths:** none, confirmed.
- Projection is compile-time: the controller calls `XDto.fromEntity` and the tool calls
  `XAiDto.fromEntity`.
- Both are `@Generated("com.egoge.ai.atlas.processor")`, so `DtoResponseBodyAdvice` accepts them.
- Tool registration is unchanged: the prototype needed no runtime change, and the MCP call went
  through Spring AI's stock `MethodToolCallbackProvider`.

**For the raw-entity path**, a method with no resolvable `returnType`, projection cannot help:
- On REST, `AgentSafeSerializer` whitelists `@AgenticField` but has no channel, so it would serve
  AI-only fields.
- On MCP, the whitelist does not apply at all (§3, proved).

| Option | Trade-offs |
|---|---|
| **A. With projections on, ERROR when an exposed method returns an `@AgenticEntity`** (or a collection or array of one) **without a resolvable `returnType`** | Compile-time and deterministic, with no runtime change. Forces the DTO path wherever projection matters. Can be relaxed later by inferring `returnType` from the declared return type, which invents no behaviour. |
| B. A channel-aware runtime serializer | Assume API on REST, and add a custom Spring AI `ToolCallResultConverter` for MCP. A runtime change, and the channel of a call has to be inferred at runtime. It duplicates the compile-time projection. |
| C. Do nothing | Leaves an AI-only field on REST, and all getters on MCP, for these methods. |

**Recommendation:** A in Phase 4. Separately, file the MCP raw-entity leak, which exists
**today** and is independent of this phase, as its own bug. Its fix is either A applied always, or
registering a `ToolCallResultConverter` that uses the context `ObjectMapper`.

### Q8. Smaller points to confirm

- **`FIELD_METADATA` and `@AgenticField(name)`.** Each record carries only its own channel's fields,
  as in the prototype. The duplicate-display-name check stays per entity, across all fields.
- **PII lint.** `PiiDetector` still sees only unannotated fields. Should an **AI-eligible** field
  whose name matches a PII pattern also warn, since LLM context is the higher-risk channel?
  **Recommendation:** yes, as a WARNING, and only with the flag on.
- **Later processing rounds.** The split must be computed over all rounds. The real implementation
  should generate records from the final projection, as Phase 2 did for the aggregate resources
  (ADR-9), and not per round.

## 7. What I would put in the phase (summary)

- `@AgenticField(channels)` (Q1-A).
- Split-only-when-different naming with `aiDtoName` and a collision check (Q2-A).
- ERRORs on empty intersections (Q3-A).
- IR v3 with exact migration and per-channel gate rules (Q4-A).
- No `mcp-tools.json` change, and unversioned eligibility (Q5).
- A new `ai.atlas.projections` flag, and an ERROR for declarations with it off (Q6).
- A required `returnType` for entity returns with the flag on (Q7-A).
- A separate bug issue for the MCP raw-entity leak.
