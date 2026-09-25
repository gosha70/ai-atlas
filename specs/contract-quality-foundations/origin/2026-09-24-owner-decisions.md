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

## Owner review of the first draft (same day)

The owner reviewed the draft bundle against the current generators and asked for these
revisions before approving an unattended build, verbatim:

> 1. [P1] `void` support conflicts with the prohibition on controller changes. plan.md (line 72)
> leaves `RestControllerGenerator` unchanged, while FR-003 requires `void` responses. The generator
> currently emits `return service.method(...)` even for `void`, which does not compile. Allow a
> targeted generator fix and require a compile-testing fixture for a void-returning API method.
> 2. [P1] The consistency tasks miss dropped operations on shared paths. tasks.md (line 14) should
> include an API-only service with `find()` and `find(Long id)`. Its controller has valid GET and
> POST mappings at the same path, but `OpenApiGenerator.addServicePaths` replaces the entire
> `PathItem`, losing one operation. Calling every documented operation cannot detect the omission.
> Add a task to merge verbs into existing path items and assert controller-to-document coverage
> as well.
> 3. [P2] The JDK matrix requirement has only a single-environment verifier. verification.yaml
> (line 110) requires builds on JDK 17 and 21, but maps that requirement to one `./gradlew build`.
> Require evidence from both CI matrix legs, or an executable verifier that explicitly runs both
> environments.

How the revision answers each:

1. FR-006 allows exactly one generated-code change — `void` methods — in both
   `RestControllerGenerator` and `McpToolGenerator`, which share the defect; FR-007 requires the
   compile-testing fixture, on the API channel and on the default channels.
2. FR-001 requires merging into the existing path item; FR-007 requires the `find()` /
   `find(Long id)` fixture and two-way coverage at compile time and at runtime. Merging surfaces
   two consequences the fixture would otherwise hit: duplicate `operationId`s (FR-004) and two
   overloads on one POST path, which Spring rejects at startup (FR-002, a compile error).
3. FR-019's verifier is `scripts/build-on-jdk-matrix.sh`, which runs the build with Gradle on JDK 17
   and on JDK 21 and fails when either JDK is missing.

## Owner's second review (same day)

> The revision addresses the original three findings. Both added scope items are justified, but
> one correctness gap remains before approval:
>
> * [P1] The `operationId` naming rule can still produce duplicates. FR-004, lines 81–85 assigns
> `OrderService_find_get` to an overloaded `OrderService.find()`. Another service can legitimately
> declare a uniquely named method `OrderService_find_get()`, which must retain that same ID under
> the rule. Thus the prescribed algorithm contradicts the uniqueness requirement.
> Reserve all unchanged IDs first, then deterministically disambiguate qualified IDs against the
> entire document. Add this collision fixture to the required tests.
>
> The `void` fixes, path merging, bidirectional coverage, and compile-time rejection of ambiguous
> mappings are appropriate.
> The matrix script now invokes both JDK environments, but the reported builds excluding Javadoc
> do not establish that the full verifier passes. Keep that validation outstanding.

How the revision answers it: FR-004 now assigns IDs in two passes — unchanged (unique) method
names reserved first, then shared ones in (path, HTTP method) order with the smallest free `_2`,
`_3`, … suffix whenever the qualified candidate is taken anywhere in the document — and FR-007
requires the `OrderService_find_get()` fixture. Applying the same reasoning to FR-002 widened the
ambiguous-mapping check from one service to the whole compilation: paths use the service's simple
name, so same-named services in different packages collide too. FR-019's full verifier run stays
outstanding; it could not complete in the authoring sandbox, whose network policy blocks the
Javadoc link to docs.oracle.com.
