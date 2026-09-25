---
feature_id: contract-quality-foundations
date: 2026-09-24
status: final
phase: build
mode: review
subject_provider: claude
peer_provider: deepseek
peer_profile: deepseek
runner_fingerprint: ae8dc147cf1264834bab35d116b6ab96b06618f438763cde3a90621d78036dba
verdict: PASS
blocking_findings_open: 0
target_ref: feature/contract-quality-foundations
rounds_completed: 1
attempt_count: 1
bypass: false
---

# Peer Review: contract-quality-foundations — Build Phase

**Reviewer**: deepseek
**Scope**: both
**Rounds**: 1
**Verdict**: PASS

## Summary

The change adds a Streamable HTTP transport test for the runtime MCP server, updates documentation, and adds a commented-out config line to the demo. The test is well-structured and exercises the real auto-configuration path, but it contains a fragile polling loop that reads a `MockHttpServletResponse` body while the response may still be streaming, and it relies on reflective classpath scanning that could silently pass with an empty tool list. Overall the change is low-risk and mostly test/doc-only, but a few correctness concerns warrant attention.

## Findings

- [warning] f-eed0938b: The polling loop calls `response.getContentAsString()` repeatedly on a `MockHttpServletResponse` while the underlying async/streaming write may still be in progress. `MockHttpServletResponse` is not thread-safe for concurrent read/write, and `getContentAsString` may return a partially-flushed buffer or throw if the response is still being written by the transport's async thread. This can produce flaky failures or a false "no response" assertion. (modules/runtime/src/test/java/com/egoge/ai/atlas/runtime/mcp/StreamableHttpTransportTest.java)
- [warning] f-bad03dbc: The test asserts `sseToolNames` contains exactly the two expected tools, but the streamable side only asserts equality with `sseToolNames`. If `registeredToolNames` returns an empty list (e.g., due to a bean-type resolution change), the SSE assertion catches it — good. However, `listToolsOverStreamableHttp` returns names parsed from `result.path("tools")`; if the JSON shape changes (e.g., `tools` missing), `forEach` yields nothing and the equality assertion fails with a confusing message rather than a clear "no tools returned" signal. (modules/runtime/src/test/java/com/egoge/ai/atlas/runtime/mcp/StreamableHttpTransportTest.java)
- [warning] f-476c2743: The test scans all `AutoConfiguration.imports` on the classpath and filters by package prefix `org.springframework.ai.mcp.server.`. This is brittle: a future Spring AI release that renames or relocates the streamable auto-configuration (or adds a conflicting one) will silently change which configs are loaded, and the `assertThat(imports).contains(...)` guard only checks presence, not that no *other* MCP server auto-config is also pulled in (e.g., a WebFlux variant). Loading both WebMvc and WebFlux MCP transports could produce a context that doesn't match production. (modules/runtime/src/test/java/com/egoge/ai/atlas/runtime/mcp/StreamableHttpTransportTest.java)
- [note] f-1a402d72: The parser splits on `\n` and treats any line starting with `{` as a JSON-RPC message. SSE frames can carry multi-line `data:` payloads (each line prefixed with `data:`), and a JSON body could legitimately contain a `{` at the start of a line inside a string. The current heuristic works for the simple `tools/list` response but is not a correct SSE parser. (modules/runtime/src/test/java/com/egoge/ai/atlas/runtime/mcp/StreamableHttpTransportTest.java)
- [note] f-f1b9f639: The doc states Streamable HTTP endpoint is `/mcp` "(Spring AI's default)". This couples the doc to a Spring AI implementation detail that may change; if Spring AI changes its default path, the doc silently becomes wrong. Also, the doc doesn't mention how to override the path if a consumer needs to. (docs/harness-integration.md)
- [note] f-27b2091b: The demo ships a commented-out `protocol: STREAMABLE` line. Commented config is easy to miss and can drift from the actual property name. Since the CHANGELOG and docs already document the property, the commented line adds little and risks becoming stale. (demo/src/main/resources/application.yml)
