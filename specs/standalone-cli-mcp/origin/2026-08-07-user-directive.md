# Origin directive — standalone-cli-mcp

**Date:** 2026-08-07
**Source:** User message in the ai-atlas planning session (verbatim).

## User's words (verbatim)

> Also write a new issue feature for ai-atlas to support a similar standalone CLI so AI Harness can call

## Surrounding context (same session, user's stated overall goal)

The user is building a family of deterministic Java tools to expose to an AI harness so that "AI Harness can
be efficient and less expensive if companies invest in deterministic tooling around AI/vibe coding." The
"similar" refers to the sibling `ai-anvil` tool being planned in the same session, which is designed to be
standalone + STDIO-MCP-first so copilots (PI.dev, Claude Code) can call it via hook scripts or MCP.

## Canonical expanded statement

GitHub issue **gosha70/ai-atlas#12** — "Standalone CLI + STDIO MCP server so an AI harness can call ai-atlas
directly" — is the expanded, canonical form of this directive and the origin of record for this feature.

## Interpretation (target for alignment checks)

Deliver a process-spawnable surface for ai-atlas (an `atlas` CLI and a STDIO MCP server) so an AI coding
harness can invoke the generator directly — via a hook script or `.mcp.json` command — **without** a running
Spring Boot web app / SSE endpoint. The existing SSE-in-Spring-Boot runtime path must be preserved. No change
to the annotation contract or generated-code shapes; the new surfaces reuse the existing generators.
