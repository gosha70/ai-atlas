# Phase 3 MCP spike: per-tool schema and hints on Spring AI 1.1.7 (epic #23)

Throwaway spike. It is a standalone Gradle build that is not in the root `settings.gradle.kts`, and it runs the
unchanged Atlas runtime classes. `SpikeEvidenceTest` boots the real app on a random port, once per transport,
and sends raw JSON-RPC over plain HTTP. Excerpts are from `evidence/`; SSE and STREAMABLE results are identical. Run it with `./gradlew :modules:runtime:classes :modules:annotations:classes && ./gradlew -p spike/phase3-mcp test`.

| # | Question | Verdict |
|---|----------|---------|
| 1 | The `@Tool`-derived schema drops constraints | **YES** (confirmed) |
| 2 | An explicit `ToolDefinition.inputSchema` replaces it, verbatim over SSE and Streamable | **YES** |
| 3 | Hints can be set without forking the auto-configuration | **YES**, but only through a `List<SyncToolSpecification>` bean, not through a `ToolCallback` |
| 4 | Anything validates arguments against the schema | **NO**: the schema is advisory. Only a `@Validated` bean enforces constraints |
| 5 | The hook keeps `LazyToolCallbackProvider`'s laziness | **YES**, with a name-collision caveat and a proxy bug in the current provider |

**Q1.** Only swagger `@Schema` on DTO/record fields survives; nothing on a method parameter does. `JsonSchemaGenerator.generateForMethodInput` calls `generateSchema(parameterType)` for each parameter.
It copies only `required`/`description` from `@ToolParam`/`@Schema`/`@JsonProperty`; its victools modules are Jackson, Swagger2, SpringAi (no Jakarta-validation). The generated schemas were:
```json
"derived_bean_validation": {"properties":{"count":{"type":"integer"},"code":{"type":"string"},
  "tags":{"type":"array","items":{"type":"string"}}}, ...}          // @Min @Max @Size @Pattern: all dropped
"derived_tool_param": {"properties":{"count":{"type":"integer","description":"Page size, 1..100"}}}
"derived_record_jakarta_only": {"page":{"properties":{"code":{"type":"string"},"count":{"type":"integer","format":"int32"}}}}
"derived_record_arg" (@Schema on record components): {"count":{..,"minimum":1,"maximum":100},"code":{..,"minLength":2,"maxLength":10,"pattern":"^[a-z]+$"}}
```

**Q2.** Tested with `MethodToolCallback.builder().toolDefinition(ToolDefinition.builder().inputSchema(SCHEMA)...)`, exposed from a
`ToolCallbackProvider` bean. `ToolCallbackConverterAutoConfiguration.syncTools` passes the string through
`McpToolUtils.toSyncToolSpecification` to `McpSchema.JsonSchema`. `explicit_schema`, both transports:
```json
{"type":"object","properties":{"count":{"type":"integer","description":"Page size","minimum":1,"maximum":100},
 "code":{"type":"string","minLength":2,"maxLength":10,"pattern":"^[a-z]+$"},
 "tags":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":3}},"required":["count","code"],"additionalProperties":false}
```
Caveat (source-read, not tested): `McpSchema.JsonSchema` keeps only `type, properties, required, additionalProperties, $defs, definitions`
with `@JsonIgnoreProperties(ignoreUnknown=true)`, so root keywords (`allOf`, `minProperties`) are dropped; keywords inside `properties` (a `Map`) survive.

**Q3.** `ToolDefinition` has no field for annotations, and `McpToolUtils.toSharedSyncToolSpecification` never calls
`Tool.builder().annotations(...)`, so no `ToolCallback` can carry hints. The supported hook is
`McpServerAutoConfiguration.mcpSyncServer(..., ObjectProvider<List<SyncToolSpecification>> tools, ...)`, which
flat-maps every bean of that type. The spike's bean `atlasToolSpecifications` takes the handler from `McpToolUtils.toSyncToolSpecification(callback)`,
rebuilds `McpSchema.Tool.builder()...inputSchema(McpJsonDefaults.getMapper(), SCHEMA).annotations(new ToolAnnotations(...))`
and returns `new SyncToolSpecification(tool, null, base.callHandler())`. The resulting `tools/list` entry for `hinted_explicit` (SSE and STREAMABLE) is:
```json
"annotations":{"title":"Hinted explicit tool","readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}
```
`McpSyncServerCustomizer` is the wrong hook: it is injected as an `Optional`, and Spring AI already defines `servletMcpSyncServerCustomizer`.

**Q4.** `McpAsyncServer.toolsCallRequestHandler` looks up the tool by name and calls it. SDK 0.18.2 validates only
`structuredContent` against `outputSchema`. `MethodToolCallback` only runs Jackson conversion on the arguments. Evidence from `tools/call`:
```json
explicit_schema {count:500, code:"ABC!", tags:[4 items]} -> "invoked count=500 code=ABC! tags=[a, b, c, d]", isError:false
hinted_explicit {count:500, code:"x", extra:1}           -> "invoked count=500 code=x tags=null", isError:false
derived_bean_validation {count:500,...}                  -> "invoked count=500 ...", isError:false
validated_max (@Service @Validated, @Max(100))           -> "validatedMax.count: must be less than or equal to 100", isError:true
```
None of the schema constraints are enforced: the out-of-range value, the pattern, `maxItems` and the undeclared argument all reached the method.
Bean Validation works but needs `@Validated` plus `spring-boot-starter-validation`; the violation is a tool error, not JSON-RPC `-32602`.

**Q5.** The spec list is a lazy `AbstractList`, and its factory method touches no beans. It resolved while `mcpSyncServer`
was in creation (`isCurrentlyInCreation("mcpSyncServer") == true`). That is the same moment
`syncTools` calls `LazyToolCallbackProvider.getToolCallbacks()`, so laziness is preserved. The spike found two problems:
- **Name collision.** If the same tool comes from both the `@Tool` provider and a spec, startup fails with
  `IllegalArgumentException: Tool with name 'hinted_explicit' is already registered.` (`McpServer...assertNoDuplicateTool`).
  The `syncTools` converter de-duplicates only inside its own list.
- **Proxy bug (existing).** `LazyToolCallbackProvider.hasToolMethods` reads `bean.getClass().getMethods()`, so any proxied
  `@Service` (for example one annotated `@Validated` or `@Transactional`) is **silently not registered**. `evidence/atlas-lazy-provider-tool-names.txt` lacks
  `validated_max`. `MethodToolCallbackProvider` itself unwraps the proxy with `AopUtils.getTargetClass`.

## Recommended Phase 3 registration design
1. Keep generating `@Tool`. Also generate one spec per tool: a JSON input-schema string and `ToolAnnotations` values (processor constants or a `@Bean`).
2. In the runtime, replace `LazyToolCallbackProvider` with a lazy `List<SyncToolSpecification>` bean. It builds callbacks through `MethodToolCallbackProvider`, which handles proxies.
3. For each tool, wrap it with `McpToolUtils.toSyncToolSpecification`, then rebuild the `McpSchema.Tool` with the generated schema, `title` and `annotations`.
4. Every tool name comes from exactly one path. Tools without a generated spec fall back to the derived schema with no hints, in the same list.
5. Enforce constraints server-side: generate Bean Validation annotations and `@Validated` on the tool class, or validate arguments against the schema inside the wrapping `callHandler`.

## Spring AI 1.1.7 limitations and fallbacks
- `ToolCallback`/`ToolDefinition` cannot carry hints. Fallback: the `SyncToolSpecification` bean above. `ASYNC` mode needs a matching
  `List<AsyncToolSpecification>` (`McpToolUtils.toAsyncToolSpecification`). `STATELESS` mode disables `ToolCallbackConverterAutoConfiguration`
  (`NonStatelessServerCondition`) and needs `McpStatelessServerFeatures` specs. Neither mode was exercised here.
- Root-level schema keywords are lost in `McpSchema.JsonSchema`. Fallback: keep every constraint inside `properties.*`.
- There is no input validation anywhere in the call path. Fallback: Bean Validation or schema validation inside the wrapping handler (item 5).
