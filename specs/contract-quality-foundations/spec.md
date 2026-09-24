---
spec_mode: full
feature_id: contract-quality-foundations
risk_category: integration
status: draft
date: 2026-09-24
---

# Spec: Contract quality foundations (epic #23, Phase 0 + Phase 1)

<!-- Project constitution: shared/skills/ — copilot-conventions, coding-standards, safety -->
<!-- Origin: GitHub issue gosha70/ai-atlas#23 (epic) + specs/contract-quality-foundations/origin/2026-09-24-owner-decisions.md. See plan.md `origin:` frontmatter. -->

The first feature cut from epic #23. It makes the surfaces ai-atlas generates tell the truth
about each other and closes three small, high-value gaps before the Contract IR work (Phase 2)
starts:

- **Phase 0**: the generated OpenAPI document must describe the generated REST controllers
  exactly. Today it does not: controllers take method arguments as query parameters
  (`@RequestParam`), while the document describes them as an `application/json` request body.
- **Phase 1**: detect MCP tool-name collisions at compile time; warn about AI tools that have
  no description of their own; offer MCP Streamable HTTP alongside SSE.

The owner chose the canonical parameter representation (query parameters), the transport
default (SSE; Streamable HTTP opt-in) and the strict-mode switch (one `ai.atlas.strict`
option) on 2026-09-24; see the origin transcript.

## User Scenarios

### US1: The OpenAPI document describes the controller that actually runs (Priority: HIGH)

**Given** a service exposed on the API channel, with methods that take arguments and methods that do not
**When** the processor generates its REST controller and the OpenAPI document
**Then** every operation in the document has the same HTTP method, path, parameter locations,
content types and response status as the generated controller, and a client generated from the
document can call the running controller successfully

### US2: Two AI tools can never share a name (Priority: HIGH)

**Given** two AI-exposed methods, possibly on different services, whose effective MCP tool name is the same (e.g. `OrderService.findById` and `CustomerService.findById`)
**When** the sources are compiled
**Then** compilation fails with an error at each conflicting method that names the tool and the other declaration, and adding an explicit `toolName` to one of them resolves it; tool names that were already unique are unchanged

### US3: AI tools are described well enough for a model to choose them (Priority: MEDIUM)

**Given** an AI-exposed method with no `description` of its own (it falls back to the class description or to `"Invokes <method>"`)
**When** the sources are compiled
**Then** the processor warns, naming the method; with `ai.atlas.strict=true` the warning is an error; API-only methods are not affected

### US4: The runtime MCP server can speak Streamable HTTP (Priority: MEDIUM)

**Given** a Spring Boot application using the ai-atlas runtime
**When** it sets `spring.ai.mcp.server.protocol=STREAMABLE`
**Then** the Atlas-generated tools are served over MCP Streamable HTTP; without the property the server stays on SSE exactly as today, and the standalone STDIO server is unchanged

## Requirements

### Phase 0 — REST and OpenAPI describe the same contract (US1)

- **FR-001**: For every method the processor exposes on the API channel and that is active at the
  configured `ai.atlas.api.major`, the generated OpenAPI document MUST contain exactly one operation,
  under the same path and HTTP method as the generated controller's mapping: path
  `{apiBasePath}/v{major}/{kebab-case service}/{kebab-case method}`, `GET` when the method takes no
  arguments and `POST` when it takes any. The document MUST contain no operation without a
  corresponding controller mapping.
- **FR-002**: Query parameters are the canonical representation of method arguments. The generated
  controller MUST keep binding each argument with `@RequestParam` (unchanged), and the OpenAPI
  operation MUST describe each argument as a `parameters` entry with `in: query`, the argument's
  name, its schema (the existing Java-type mapping), its description when one is declared, and
  `required: true` (matching `@RequestParam`'s default). The operation MUST NOT declare a
  `requestBody`.
- **FR-003**: Every generated OpenAPI operation MUST declare response status `200`, with content
  matching what the generated controller returns: for a generated DTO, `application/json` with the
  DTO schema reference (an array of it for collection, iterable and array returns); for `void`, no
  content; for `String`, `text/plain` with a string schema; for a boxed or primitive number or
  boolean, `application/json` with the mapped scalar schema; for a collection or array of those,
  `application/json` with an array of the mapped scalar schema; for any other type,
  `application/json` with an object schema.
- **FR-004**: A demo integration test MUST read the generated `openapi-v{major}.json` from the
  demo's classpath and call every operation it describes against the running demo controllers
  (MockMvc), placing each argument where the document says (query parameters with representative
  values), and MUST assert that each call is answered with the documented status and a response
  `Content-Type` compatible with the documented media type. A processor golden test MUST pin the
  generated controller and OpenAPI operation for representative methods: no arguments, one scalar
  argument, several arguments, a DTO return, a collection return and a non-DTO return.
- **FR-005**: The correction MUST be declared, not silent: `CHANGELOG.md` MUST gain, under
  `[Unreleased]`, an entry whose heading contains the text `REST parameters are query parameters`,
  stating that query parameters are canonical, that the REST wire format of running controllers is
  unchanged, and that clients generated from an earlier OpenAPI document (which described a JSON
  request body) must be regenerated. `docs/annotation-guide.md` and `docs/processor-internals.md`,
  where they describe the generated REST controller, MUST state that method arguments are query
  parameters in both the controller and the OpenAPI document.

### Phase 1 — MCP tool-name collisions (US2)

- **FR-006**: The processor MUST compute the effective MCP tool name (the method's explicit
  `toolName` when non-empty, else the method name) of every method exposed on the AI channel and
  active at the configured `ai.atlas.api.major`, across all services in the compilation, and MUST
  report a compile ERROR when two or more such methods share an effective name. Overloads of one
  method in one class count as a collision.
- **FR-007**: The collision ERROR MUST be reported on each conflicting method element, and each
  message MUST name the colliding tool name, list the other declaration(s) as
  `fully.qualified.ServiceClass#method`, and state that an explicit `toolName` on
  `@AgenticExposed` resolves it.
- **FR-008**: Non-colliding effective tool names MUST be unchanged: the generated `@Tool(name = …)`
  of a method that does not collide is byte-identical to the output before this feature. Methods
  that are not on the AI channel, or not active at the configured major, MUST NOT take part in the
  collision check.

### Phase 1 — AI tool description diagnostics (US3)

- **FR-009**: For every method exposed on the AI channel and active at the configured major whose
  own `@AgenticExposed` has no non-empty `description`, the processor MUST emit a WARNING on the
  method that names it as `fully.qualified.ServiceClass#method`, gives its effective tool name, and
  says which fallback was used (the class-level description, or `Invokes <method>`).
- **FR-010**: The processor MUST accept a new option `ai.atlas.strict` (`true` or `false`, case
  insensitive, default `false`), declared in `@SupportedOptions` and exposed by the Gradle plugin's
  `agentic { }` extension. When it is `true`, the FR-009 diagnostic MUST be reported as an ERROR
  instead of a WARNING. Any other value MUST be a compile ERROR that names the option and the value.
- **FR-011**: Methods exposed only on the API channel MUST NOT produce the FR-009 diagnostic, and
  the generated MCP tool descriptions MUST keep carrying their version and deprecation guidance
  unchanged.
- **FR-012**: The demo module MUST compile with no ai-atlas WARNING: every AI-exposed demo method
  that lacks a method-level description (e.g. `OrderService.findByStatus`) gains one.

### Phase 1 — Streamable HTTP alongside SSE (US4)

- **FR-013**: With `spring.ai.mcp.server.protocol=STREAMABLE`, an application using the ai-atlas
  runtime MUST serve the Atlas-generated tools over MCP Streamable HTTP at Spring AI's streamable
  endpoint (default `/mcp`), and an MCP `tools/list` over that transport MUST return the same tool
  names as the SSE transport returns for the same application.
- **FR-014**: With no `spring.ai.mcp.server.protocol` set, the runtime MUST keep serving over SSE
  (`GET /sse`, `POST /mcp/message`) exactly as before; `SseUnchangedTest` MUST pass unmodified.
- **FR-015**: `docs/harness-integration.md` MUST describe both HTTP transports, the property that
  selects one, that SSE is the default, and the Streamable HTTP endpoint; the demo's
  `application.yml` MUST show the property, commented out. The standalone STDIO server
  (`modules/mcp-stdio`) MUST be unchanged, with its tests passing.

### Whole feature

- **FR-016**: `./gradlew build` MUST pass on JDK 17 and JDK 21, the same matrix CI runs.

## Constraints / What NOT to Build

- No change to the REST wire format of generated controllers: the Phase 0 fix corrects the
  document, not the controllers (owner decision, 2026-09-24).
- No renaming of existing tool names and no qualified-name default (`service_method`): that would
  break MCP clients in 1.x; the epic defers it to a 2.0 decision.
- No Contract IR, `.atlas/api.ir.json`, compatibility gate or lock mode: Phase 2, a later feature.
- No constraint model, enum values on parameters, `@AgenticParam`, behavioural annotations, field
  projections, collection-safety policy, explicit REST verb/path metadata or release workflow:
  Phases 3–5.
- No change to how complex (non-scalar) method arguments bind: `@RequestParam` behaviour for them
  is out of scope; the document describes them with the existing type mapping.
- No new ai-atlas transport property: the transport is selected with Spring AI's own
  `spring.ai.mcp.server.protocol`, so the two cannot disagree.
- No change to `modules/cli` or `modules/mcp-stdio` behaviour; they pass options through
  unchanged, so `ai.atlas.strict` reaches them as any other `-A` option.
- No network I/O, LLM calls or live databases in any test.

## Key Entities

- **Effective tool name**: the name a method is published under as an MCP tool — its explicit
  `@AgenticExposed(toolName)` when non-empty, otherwise the Java method name.
- **Canonical parameter representation**: how a generated REST operation receives method
  arguments; for this feature, query parameters (`@RequestParam` / `in: query`).
- **Strict mode**: the `ai.atlas.strict` processor option; when `true`, ai-atlas quality
  diagnostics that are warnings by default are errors. FR-009 is the first; later epic items reuse it.
- **HTTP transport**: how the runtime MCP server talks to clients over HTTP — SSE (default) or
  Streamable HTTP (opt-in), chosen by `spring.ai.mcp.server.protocol`.

## Success Criteria

1. **US1 / FR-001–FR-004**: the demo integration test calls every operation in the generated
   OpenAPI document, exactly as the document describes it, and every call succeeds with the
   documented status and content type.
2. **US1 / FR-005**: `CHANGELOG.md` and the docs state that query parameters are canonical and what
   that means for existing clients.
3. **US2 / FR-006–FR-008**: a compilation with two AI methods named `findById` on different services
   fails with an error at both; adding `toolName` to one makes it compile; every tool name the
   demo generates is unchanged.
4. **US3 / FR-009–FR-012**: an AI method without its own description warns; with
   `-Aai.atlas.strict=true` the build fails; the demo compiles with no ai-atlas warning.
5. **US4 / FR-013–FR-015**: the same application lists the same tools over Streamable HTTP and over
   SSE; with no property it is SSE exactly as today.
6. **FR-016 / no regressions**: `./gradlew build` is green on JDK 17 and 21.
