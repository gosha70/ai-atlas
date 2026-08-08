# Tasks: Standalone CLI + STDIO MCP Server

<!-- One owner per file. [P] = parallelizable within the story group. [US#] traces to spec.md. -->
<!-- Each `## US<n>:` group is one auto-build phase (fresh session). Milestone every 2 phases. -->

## US1: Programmatic generation driver

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 1 | | Add `GenerationResult`, `GeneratedFile`, `Diagnostic` result model | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/driver/{GenerationResult,GeneratedFile,Diagnostic}.java` | Annotation Processor Engineer | [x] |
| 2 | | Implement `AtlasGenerator` — in-process `JavaCompiler` run with `AgenticProcessor` registered, captures generated sources + OpenAPI + diagnostics | `modules/processor/src/main/java/com/egoge/ai/atlas/processor/driver/AtlasGenerator.java` | Annotation Processor Engineer | [x] |
| 3 | | Golden test: driver output byte-identical to `:demo:compileJava`; assert `@Generated` marker | `modules/processor/src/test/java/com/egoge/ai/atlas/processor/driver/AtlasGeneratorGoldenTest.java` | Annotation Processor Engineer | [x] |

**Checkpoint US1** — verify before continuing:
- [x] `./gradlew :modules:processor:test` green
- [x] Driver *module* declares no Spring dependency; generation compiles against a caller-supplied classpath that includes Spring AI/Web types (like the processor tests), returning a populated `GenerationResult`

---

## US2: CLI generate / openapi with JSON mode

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 4 | | New `modules/cli` (picocli + **Shadow fat jar** → `atlas.jar`); add picocli + shadow plugin to version catalog + `settings.gradle.kts` | `modules/cli/build.gradle.kts`, `gradle/libs.versions.toml`, `settings.gradle.kts` | Team Lead | [ ] |
| 5 | | `AtlasCli` root command + `GenerateCommand` + `OpenApiCommand` over `AtlasGenerator` | `modules/cli/src/main/java/com/egoge/ai/atlas/cli/{AtlasCli,GenerateCommand,OpenApiCommand}.java` | Team Lead | [ ] |
| 6 | [P] | `JsonOutput` — stable JSON schema + writer for `--json`; stdout=JSON, stderr=diagnostics, non-zero exit on error | `modules/cli/src/main/java/com/egoge/ai/atlas/cli/JsonOutput.java` | Team Lead | [ ] |
| 7 | [P] | Tests: subcommands, `--json` shape, exit codes; assert no Spring Web on runtime classpath | `modules/cli/src/test/java/com/egoge/ai/atlas/cli/AtlasCliTest.java` | Team Lead | [ ] |

**Checkpoint US2** — verify before continuing:
- [ ] Shadow `atlas.jar` builds; `java -jar modules/cli/build/libs/atlas.jar generate --sources demo/src/main/java --classpath <demo compile cp> --out build/atlas-gen` matches the demo build output
- [ ] `--json` emits documented stable JSON; error path returns non-zero

---

## US3: CLI inspect (dry run)

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 8 | | `InspectCommand` — dry-run listing of exposed services + would-be tools/endpoints/DTOs as JSON (no file writes) | `modules/cli/src/main/java/com/egoge/ai/atlas/cli/InspectCommand.java` | Team Lead | [ ] |
| 9 | [P] | Test `inspect --json` output and no-write guarantee | `modules/cli/src/test/java/com/egoge/ai/atlas/cli/InspectCommandTest.java` | Team Lead | [ ] |

**Checkpoint US3** — verify before continuing:
- [ ] `inspect --json` lists the demo's exposed services without writing any file

---

## US4: STDIO MCP server

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 10 | | New `modules/mcp-stdio` (MCP Java SDK + **Shadow fat jar** → `atlas-mcp.jar`); add mcp-sdk to catalog + `settings.gradle.kts` | `modules/mcp-stdio/build.gradle.kts`, `gradle/libs.versions.toml`, `settings.gradle.kts` | MCP & Spring AI Specialist | [ ] |
| 11 | | `AtlasMcpServer` `main()` — stdio transport; register `atlas_inspect_services`, `atlas_generate`, `atlas_openapi` over `AtlasGenerator`; results include file manifest + summary | `modules/mcp-stdio/src/main/java/com/egoge/ai/atlas/mcp/AtlasMcpServer.java` | MCP & Spring AI Specialist | [ ] |
| 12 | [P] | Round-trip test: list tools + call each; assert manifest/summary; no Spring Web on classpath | `modules/mcp-stdio/src/test/java/com/egoge/ai/atlas/mcp/AtlasMcpServerTest.java` | MCP & Spring AI Specialist | [ ] |

**Checkpoint US4** — verify before continuing:
- [ ] Round-trip test passes; three tools listed and callable with no Spring Boot app running
- [ ] Shadow `atlas-mcp.jar` builds and starts a stdio server via `java -jar` (matches the `.mcp.json` launch)

---

## US5: Harness integration docs + SSE regression guard

| # | [P] | Task | File(s) | Owner | Done |
|---|-----|------|---------|-------|------|
| 13 | | `docs/harness-integration.md`: CLI usage, `.mcp.json` stdio snippet, sample Claude Code hook, and the existing SSE path | `docs/harness-integration.md` | Team Lead | [ ] |
| 14 | [P] | Assertion/test that runtime SSE MCP wiring is unchanged (FR-008) | `modules/runtime/src/test/java/com/egoge/ai/atlas/runtime/mcp/SseUnchangedTest.java` | MCP & Spring AI Specialist | [ ] |

**Checkpoint US5** — verify before continuing:
- [ ] Docs cover both paths + a runnable hook example
- [ ] SSE path assertion passes

---

## Final Verification

- [ ] Java compiles: 0 errors across all modules
- [ ] Linter/warnings: no new warnings introduced
- [ ] `./gradlew build` — all modules + tests green (no regressions)
- [ ] No `[NEEDS CLARIFICATION]` markers remain in spec.md
- [ ] CLI `--json` and MCP tool outputs are documented in `docs/harness-integration.md`
- [ ] Runtime SSE MCP path unchanged (FR-008)
