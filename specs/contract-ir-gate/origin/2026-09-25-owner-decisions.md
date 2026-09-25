# Origin — owner decisions, 2026-09-25

Source: the owner's Claude Code session of 2026-09-25. The primary origin is epic
gosha70/ai-atlas#23 §1 ("Introduce a version-neutral Contract IR and compatibility gate") and its
"Contract acceptance / lock mode" child. The first feature of the epic (#25, Phase 0 + Phase 1)
merged as #27.

## The ask

After #27 merged, the owner asked to continue the epic:

> Proceed further, when the plan ready I would like to pass the build to Claude Code

## Decisions the epic leaves open

Asked directly on 2026-09-25, the owner chose:

| Question | Owner's answer |
|---|---|
| Scope: the epic lists "Contract IR + compatibility diff engine" and "Contract acceptance / lock mode" as separate children. | **One feature: IR + gate + accept + lock.** |
| Which contract must stay backward compatible? | **The baseline's published major.** The gate projects old and new IR at the major recorded in the baseline. Setting `removedInVersion` to a later major is a declared change and passes. Deleting a field, or removing it within that major, fails and names the field. Deleting something already removed in an earlier major is allowed cleanup. |
| Response enums: can clients tolerate new values? | **Closed by default.** Adding a value to a response enum is breaking unless the field declares `@AgenticField(openEnum = true)`. |
| Should the generators (DTO, MCP, REST, OpenAPI) be rewired to project from the IR in this feature? | **Yes — rewire now**, per the epic's target architecture ("REST, MCP, DTOs, and OpenAPI must be projections of the same normalised contract model"). |
