# Origin — owner decisions, 2026-09-26

Source: the owner's Claude Code session of 2026-09-26. The primary origin is epic
gosha70/ai-atlas#23, §5 ("Use one constraint model that can actually reach both REST and MCP
inputs") and §6 ("Emit MCP behavioural annotations, but do not treat them as authorization"). The
child issue is gosha70/ai-atlas#43. Phase 2 (#30) merged as #36, #37 and #39.

## The ask

After Phase 2 merged and the follow-up fixes #41 and #42 landed, the owner asked to proceed
with Phase 3. The owner delegated the Spring AI feasibility spike and asked for the issue to be
drafted, with these words:

> yes, delegate the spike and draft the issue

## Decisions the epic leaves open

These positions were proposed on 2026-09-26 and accepted by the owner, who asked for the issue
to be created from the draft that contains them:

| Question | Owner's answer |
|---|---|
| Precedence between Bean Validation and `@AgenticParam`/`@AgenticField` | **An override replaces only the constraints it sets.** The contract may differ from persistence. Self-contradictions are errors. An input override that is looser than Bean Validation is a WARNING. |
| Behavioural hints | **Explicit only.** Nothing is inferred from method names or HTTP shape. An AI tool with no declared hints gets a WARNING when the flag is on, and an ERROR under strict mode. |
| How constraints and hints reach MCP (Spring AI 1.1.7 drops them from `@Tool`) | **Keep `@Tool` and register a generated per-tool specification.** The runtime registers a `List<SyncToolSpecification>` carrying the generated schema and hints. Not `@McpTool`. The spike on branch `claude/phase3-mcp-spike` proved it. |
| Enforcing constraints (nothing in the MCP call path validates arguments) | **Option A:** the generated MCP tool class carries the contract constraints as Bean Validation annotations and is `@Validated`. The validation starter stays optional, and a startup WARNING says when the constraints are advisory. |
| Contract IR migration | **`irVersion` 2.** A version-1 baseline migrates with its constraints and hints *unknown*. Unknown-to-known is never breaking and is not a lock-mode difference. |
| Opt-in | **One flag, `ai.atlas.constraints`.** When it is off, generated output is byte-identical. The IR records constraints regardless. |
| Server types | **SYNC only** in this phase. ASYNC and STATELESS servers keep today's derived schemas, with no hints. |
