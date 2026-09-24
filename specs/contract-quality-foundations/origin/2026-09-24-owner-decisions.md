# Origin — owner decisions, 2026-09-24

Source: the owner's Claude Code session of 2026-09-24, after reviewing the
epic gosha70/ai-atlas#23 and a proposed split of it into auto-buildable
features. Recorded verbatim; the epic itself is the primary origin.

## The ask

> Now can you see how can I setup Claude Code to implement
> https://github.com/gosha70/ai-atlas/issues/23 with auto code review from
> DeepSeek; and in parallel capturing /updating the document with the
> progress and changes which I (human) can review offline without
> interrupting the development

The proposed plan split the epic into features (the epic itself asks for
this: "Keep this as the epic and create focused child issues for
implementation") and named the first one: Phase 0 plus the three Phase 1
items. The owner replied:

> Ok proceed to the Step 1

## Decisions the epic defers to the owner

The epic requires Phase 0 to choose the canonical parameter representation
explicitly ("Phase 0 should state which representation is canonical and treat
the correction as a declared change rather than a silent one"). Asked
directly, the owner chose:

| Question | Owner's answer |
|---|---|
| Phase 0: generated controllers take method arguments as query parameters, the generated OpenAPI says JSON body — which is canonical? | **Query params** — keep the controllers as they are and correct the OpenAPI document. |
| What should the first feature cover? | **Phase 0 + all three Phase 1 items** — REST/OpenAPI consistency, tool-name collision detection, missing-description diagnostics, Streamable HTTP. |
| Streamable HTTP: which transport is the runtime default? | **SSE stays the default; Streamable HTTP is opt-in.** |
| How is "strict mode" switched on? | **One `ai.atlas.strict` processor option**, reused by later epic items. |
