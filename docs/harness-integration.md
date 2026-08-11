# Harness Integration Guide

AI-ATLAS has two integration surfaces. This guide covers both and shows how to wire the new
standalone surface into an AI coding harness (Claude Code, PI.dev, Cursor, IntelliJ assistants).

| Surface | When to use | Transport | Requires |
|---|---|---|---|
| **Standalone CLI / STDIO MCP** (`atlas.jar`, `atlas-mcp.jar`) | Build-time / harness-time: a hook or copilot drives generation directly | Process spawn (CLI), stdio (MCP) | A JDK — no running application |
| **Runtime SSE MCP server** (`modules/runtime`) | Live tool serving from a deployed Spring Boot app — the default | HTTP + SSE | The consumer's running Spring Boot app |

Both surfaces run the same generators through the same driver (`AtlasGenerator` in
`modules/processor`), so their output is byte-identical to the annotation-processing build
(`./gradlew :demo:compileJava`).

> **JDK required.** The standalone surfaces compile your sources in-process via
> `javax.tools.JavaCompiler` (`ToolProvider.getSystemJavaCompiler()`), which a JRE does not
> provide. Run them on a JDK, version 21 or later.

## Building the executables

```bash
./gradlew :modules:cli:shadowJar :modules:mcp-stdio:shadowJar
```

| Artifact | Path | Entry point |
|---|---|---|
| `atlas.jar` | `modules/cli/build/libs/atlas.jar` | `atlas` CLI |
| `atlas-mcp.jar` | `modules/mcp-stdio/build/libs/atlas-mcp.jar` | STDIO MCP server |

Both are self-contained fat jars — `java -jar` works with no other files. `./gradlew build` also
produces them; the published Maven artifacts carry them under the `all` classifier.

## The generation classpath (both standalone surfaces)

Neither `atlas.jar` nor `atlas-mcp.jar` bundles Spring — by design (spec FR-005). The generated
wrappers reference `@Tool`, `@Service`, and `@RestController`, so the **compile classpath of the
sources you generate from** must be supplied per invocation, and it must carry the Spring AI +
Spring Web types (plus everything else your entities and services import) — exactly what your
application's own `compileClasspath` already contains.

A convenient way to capture it is a small task in the consumer's `build.gradle.kts`:

```kotlin
// Writes the module's compile classpath to a file a hook or .mcp.json launcher can read.
tasks.register("writeAtlasClasspath") {
    val classpath = configurations.named("compileClasspath")
    val output = layout.buildDirectory.file("atlas-classpath.txt")
    inputs.files(classpath)
    outputs.file(output)
    doLast { output.get().asFile.writeText(classpath.get().asPath) }
}
```

```bash
./gradlew -q writeAtlasClasspath
java -jar atlas.jar generate --sources src/main/java \
    --classpath "$(cat build/atlas-classpath.txt)" --out build/atlas-gen
```

## Path 1a: the `atlas` CLI

```
atlas <command> --sources <path>[,<path>...] [--classpath <cp>] [-A key=value] [--json] ...
```

### Subcommands

| Command | Does | Writes files? |
|---|---|---|
| `atlas generate --sources <dir> --classpath <cp> --out <dir>` | Full generation: DTOs, MCP tools, REST controllers, OpenAPI spec into `--out` (under `sources/` and `resources/` roots, which the run replaces) | Yes |
| `atlas openapi --sources <dir> --classpath <cp> [--out <file>]` | Just the OpenAPI document — printed on stdout, or written to `--out` | Only with `--out` |
| `atlas inspect --sources <dir> --classpath <cp>` | Dry run: reports the `@AgenticExposed` services found and every artifact that *would* be generated. Runs fully in-memory | No |

### Common options

| Option | Meaning |
|---|---|
| `-s, --sources <path>` | Source root directory or single `.java` file. Repeatable, or comma-separated. Required. |
| `-c, --classpath <cp>` | Compile classpath for the sources (see above). Repeatable, or one path-separator-separated string. Entries are validated up front; a missing or unreadable entry fails fast. |
| `-A, --option <key=value>` | Annotation-processor option, e.g. `-Aai.atlas.api.major=2`. Must match the target build's options for output to match it. |
| `--json` | Machine-readable report on stdout (schema below). |

### Exit codes and stream contract

Exit codes are part of the contract, so a hook can branch on them:

| Code | Meaning |
|---|---|
| `0` | Run succeeded. |
| `1` | Run failed — compile errors, unreadable inputs, or nothing to emit. Under `--json`, stdout still carries a complete JSON document with `"status": "error"`. |
| `2` | The command line itself was wrong (unknown/missing option). Usage help on stderr; stdout stays empty even under `--json`. |

stdout carries the result (the JSON document under `--json`, otherwise the human summary or the
OpenAPI document). Warnings and errors always go to stderr, in both modes — a hook capturing
stdout gets parseable output, and a human watching the terminal still sees what went wrong.

### The `--json` schema

One line of JSON on stdout (pipe-friendly; the schema, not the whitespace, is the contract).
Every key is always present — on success and failure alike — so a consumer can read a field
without probing for it. Inapplicable values are `null` (objects/strings) or empty (arrays),
never omitted. Defined in `modules/cli/.../JsonOutput.java`.

```jsonc
{
  "schemaVersion": 1,                  // int, bumped only on a breaking change
  "command": "generate",               // "generate" | "openapi" | "inspect"
  "status": "ok",                      // "ok" | "error" — mirrors the process exit code
  "outputDir": "/abs/out",             // generate: the directory written; openapi/inspect: null
  "files": [                           // manifest of what this run emitted, [] on failure
    {
      "kind": "DTO",                   // DTO | MCP_TOOL | REST_CONTROLLER | OPENAPI | ...
      "relativePath": "app/FooDto.java",
      "path": "/abs/out/sources/app/FooDto.java",  // null for inspect (nothing was written)
      "source": true                   // true = generated Java source, false = resource
    }
  ],
  "counts": { "DTO": 4, "MCP_TOOL": 1 },   // per-kind totals, kinds with 0 omitted
  "openApi": {                             // null unless an OpenAPI document was produced
    "path": "/abs/openapi.json",           // where it was written, or null if not written
    "document": "{ ... }"                  // the spec text
  },
  "diagnostics": [                     // every compiler / processor message, javac's order
    { "severity": "WARNING", "message": "...", "source": "/abs/Foo.java" }
  ],
  "errors": [ "..." ],                 // human-readable failure reasons, [] when status is ok
  "services": [                        // inspect: qualified names of the @AgenticExposed
    "com.example.MyService"            //   services discovered; [] for other commands
  ]
}
```

Stability contract: within a `schemaVersion`, keys and value types are only ever added, never
removed or retyped. Consumers must ignore unknown keys.

### Example against the demo module

```bash
./gradlew :modules:cli:shadowJar
java -jar modules/cli/build/libs/atlas.jar inspect \
    --sources demo/src/main/java \
    --classpath "$DEMO_COMPILE_CLASSPATH" \
    --json | jq '.services, .counts'
```

## Path 1b: the STDIO MCP server

The STDIO server exposes generation as MCP tools a copilot can call directly — no Spring Boot
app, no network. It uses the MCP Java SDK's stdio transport; stdout is reserved for the protocol.

### `.mcp.json` registration

In the consumer project's `.mcp.json` (Claude Code) or the equivalent MCP client config:

```json
{
  "mcpServers": {
    "ai-atlas": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/atlas-mcp.jar"]
    }
  }
}
```

No server-level configuration exists — everything travels per-call in the tool arguments, so one
registered server serves any project.

### Tools

| Tool | Does | Writes files? |
|---|---|---|
| `atlas_inspect_services` | Dry run, fully in-memory: reports discovered services and every artifact that would be generated | No |
| `atlas_generate` | Full generation into the `out` directory | Yes |
| `atlas_openapi` | Just the OpenAPI document, in-memory, returned in the result | No |

Common arguments (all three tools):

| Argument | Type | Meaning |
|---|---|---|
| `sources` | `string[]` | Source root directories or individual `.java` files; directories are scanned recursively. |
| `classpath` | `string` | Compile classpath for the sources, one path-separator-separated string. Must carry the Spring AI + Spring Web types (see above). |
| `options` | `object` | Annotation-processor options as string-to-string pairs, e.g. `{"ai.atlas.api.major": "2"}`. |

`atlas_generate` additionally takes `out` (`string`) — the directory to write into. Unknown
arguments are rejected.

### Tool results

Each tool returns a single JSON text document mirroring the CLI's `--json` schema, with `tool`
and `summary` keys instead of `schemaVersion`/`command`. The `summary` line plus the `files`
manifest let an agent review what was (or would be) generated before writing anything into a
workspace (spec FR-007). Failed runs return the same document shape with `"status": "error"`
and the MCP `isError` flag set — there is only one contract to parse.

Verify the wiring without a harness using the
[MCP Inspector](https://github.com/modelcontextprotocol/inspector):

```bash
npx @modelcontextprotocol/inspector java -jar modules/mcp-stdio/build/libs/atlas-mcp.jar
```

## Sample Claude Code hook

A `PostToolUse` hook that dry-runs generation after Claude edits a Java file, feeding errors back
so the agent can self-correct. Save as `.claude/hooks/atlas-verify.sh` (and `chmod +x` it):

```bash
#!/usr/bin/env bash
# Dry-run ai-atlas generation after a Java file edit; report failures back to the agent.
set -euo pipefail

ATLAS_JAR="${ATLAS_JAR:-modules/cli/build/libs/atlas.jar}"
SOURCES="${ATLAS_SOURCES:-src/main/java}"
CLASSPATH_FILE="${ATLAS_CLASSPATH_FILE:-build/atlas-classpath.txt}"   # see writeAtlasClasspath

# Hook input arrives as JSON on stdin; only react to .java edits.
file_path=$(jq -r '.tool_input.file_path // empty')
[[ "$file_path" == *.java ]] || exit 0
[[ -f "$ATLAS_JAR" && -f "$CLASSPATH_FILE" ]] || exit 0   # not built yet — stay silent

if ! result=$(java -jar "$ATLAS_JAR" inspect \
        --sources "$SOURCES" \
        --classpath "$(cat "$CLASSPATH_FILE")" \
        --json); then
    echo "ai-atlas: generation would fail after editing $file_path" >&2
    jq -r '.errors[], (.diagnostics[] | select(.severity == "ERROR") | .message)' \
        <<<"$result" >&2
    exit 2   # exit 2 feeds stderr back to Claude for self-correction
fi

jq -r '"ai-atlas: OK — \(.files | length) artifact(s) from \(.services | length) service(s)"' \
    <<<"$result"
```

Register it in the consumer project's `.claude/settings.json`:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          { "type": "command", "command": "bash .claude/hooks/atlas-verify.sh" }
        ]
      }
    ]
  }
}
```

The same shape works for any harness that can shell out: run `atlas inspect --json` (or
`generate --json`), branch on the exit code, and parse stdout.

## Path 2: the runtime SSE MCP server (unchanged)

The original integration path — unchanged by the standalone surfaces (spec FR-008) and still the
default for live tool serving. When a Spring Boot app depends on `modules/runtime`,
auto-configuration registers every `@Service` bean with `@Tool` methods (the shape of the
generated MCP tool wrappers) with the Spring AI MCP server, which serves them over HTTP + SSE:

```bash
./gradlew :demo:bootRun     # demo: REST + MCP SSE on port 8080
```

- MCP endpoint: `http://localhost:8080/sse` — connect with any MCP client, e.g. the MCP
  Inspector.
- Toggle: `ai.atlas.mcp.enabled` (default `true`); server identity via the standard
  `spring.ai.mcp.server.*` properties. See the demo's `application.yml`.
- Use this path when tools must be served continuously from a deployed application; use the
  CLI/STDIO path when a harness needs to *drive generation* at build/edit time.

The SSE path is guarded by a regression test,
`modules/runtime/src/test/java/com/egoge/ai/atlas/runtime/mcp/SseUnchangedTest.java`, which pins
the runtime MCP wiring (tool discovery behaviour, its conditional activation, and the SSE
transport on the classpath) so the standalone surfaces cannot drift it.
