---
feature_id: release-workflow
spec_mode: full
risk_category: integration
justification: "Adds a persisted resource manifest to every compilation's class output, a new release.json shape, git-backed publication semantics in the Gradle plugin, two new tasks (one under check), and a renamed DSL block. Multi-module (processor + gradle-plugin), consumer-visible, and it changes what a release is allowed to credit."
status: approved
date: 2026-09-29
collaboration_mode: single
spec: specs/release-workflow/spec.md
origin:
  issue: gosha70/ai-atlas#58
  urls:
    - "branch claude/phase5-release-spike @ 3806e5d: spike/phase5-release/ISSUE-DRAFT.md (copied to specs/release-workflow/origin-draft-3806e5d.md)"
    - "branch claude/phase5-release-spike @ 3806e5d: spike/phase5-release/REPORT.md (copied to specs/release-workflow/spike-report-3806e5d.md)"
    - "PR gosha70/ai-atlas#62 @ bb98010: review findings P1 untagged credit (ContractRelease.java:137), P1 missing/stale resources (AgenticRelease.java:98-102), P2 channel-blind removal credit (ReleasePolicy.java:195-204); reviewer reproduction /tmp/ReviewChannelCreditTest.java"
  origin_claim: |
    Epic #23 Phase 5 §10, issue #58: record what was published as immutable snapshots under
    .atlas/releases/<version>/, derive a changelog from the Contract IR, and police what may leave
    the contract between publications with a deprecation policy. The owner-settled draft (3806e5d,
    "No owner decision remains open") supersedes the spike prototype: credit only from proved local
    git tags (tagName, default v{version}), a processor-written contract-resources.json manifest with
    reserved-path stale-file rejection, channel-aware removal credit using the gate's shared
    reachability, a git-free agenticReleaseHistoryCheck under check and a git-aware
    agenticReleaseVerify. The owner decided to expand PR #62 to this full scope before merge, and
    the three review findings on bb98010 must be closed.
---

# Implementation Plan: release workflow, expanded to the 3806e5d scope (PR #62)

**Branch**: `feature/release-workflow` (PR #62, head `bb98010`)
**Authoritative spec**: `specs/release-workflow/origin-draft-3806e5d.md` (called "the draft" below;
items are `D<n>`, acceptance criteria `AC<n>` in the draft's order).
**Background**: `specs/release-workflow/spike-report-3806e5d.md`.

Abbreviations: `proc/` = `modules/processor/src/main/java/com/egoge/ai/atlas/processor/`,
`plug/` = `modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/`. Line numbers refer to
`bb98010`.

## 1. Requirements (confirmed)

- The draft is the spec. It says "No owner decision remains open". The PR's prototype-derived
  behaviour is superseded wherever the two differ.
- The owner decided to **expand PR #62** to the draft's full scope before merge.
- Three review findings on `bb98010` must be closed:
  - **F1 [P1]:** untagged snapshots earn publication credit. Closed by D10, in Phase D.
  - **F2 [P1]:** releases accept missing or stale generated resources. Closed by D2, in Phase C.
  - **F3 [P2]:** channel-removal credit ignores visibility on the removed channel. Closed by D4, in
    Phase B. The reviewer's test becomes a permanent regression test.
- Prerequisite D1 (the dependency version defaults to the plugin's own version) shipped separately
  as PR #59 (`c5b4c41`). This plan does not touch it. Follow-ups are in flight on
  `fix/plugin-version-review-followups`, outside this plan.
- Module rules (CLAUDE.md) hold:
  - `annotations` has zero dependencies.
  - `processor` runs at compile time only, and gets **no git access**.
  - `gradle-plugin` has no runtime dependencies. It uses configuration-avoidance APIs and is
    tested with TestKit.
  - Checkstyle caps every file, tests included, at 500 lines.

## 2. Gap analysis

Status key:
- **done**: implemented as the draft says.
- **partial**: implemented in part.
- **missing**: not implemented.
- **conflicts**: implemented differently from the draft, and must change.

### 2a. Draft items and decisions (52 rows)

| # | Draft requirement | Status | Evidence at `bb98010` | Closed by |
|---|---|---|---|---|
| D1.1 | `agentic { releaseVersion }`, default `project.version` | done | `plug/AgenticPlugin.java:102`, `AgenticExtension.java:141` | — |
| D1.2 | Strict `MAJOR.MINOR.PATCH`; SNAPSHOT has its own message | done | `proc/release/ReleaseVersion.java:34-47` | — |
| D1.3 | `releaseVersionTracksApiMajor`, default false | done | `ContractRelease.java:148-152`, `AgenticPlugin.java:103` | — |
| D1.4 | `agentic { version }` defaults to the plugin's own version; an explicit value wins | done (PR #59) | `AgenticPlugin.java:89`, `PluginVersion.java:33-69` | — |
| D1.5 | A development build without an explicit `version` fails | done (PR #59) | `PluginVersion.java:33-41` | — |
| D1.6 | Pre-releases and older-line releases deferred, so refused | done | `ReleaseVersion.java:42`, `ContractRelease.java:139-142` | — |
| D2.1 | Snapshot holds IR, `openapi-v<major>`, `mcp-tools` when generated, diff, CHANGELOG, `release.json` | partial | `ContractRelease.java:171-181`. Resources are copied only "if present" (`AgenticRelease.java:98-102`). | C4 |
| D2.2 | `release.json` records `manifestVersion`, version, major, previous, `irVersion`, policy and digests | partial | `ReleaseManifest.java:34-74`. No `tagName` (D10), no resource-manifest content (D2 table). | C4, D1 |
| D2.3 | One compilation: a missing, unexpected or stale resource fails | missing (**F2**) | `AgenticRelease.java:98-102` | C4 |
| D2.4 | The processor writes `META-INF/ai-atlas/contract-resources.json` in the final round | missing | `proc/AgenticProcessor.java:165-189` writes no manifest | C1, C2 |
| D2.5 | The manifest records the **effective** config (basePath, major, infoVersion, constraints, projections), manual `-A` included, last value wins | missing | — | C2, C3 |
| D2.6 | Required artifacts follow the effective config, never the extension's values | conflicts | The OpenAPI path uses the extension's major: `AgenticRelease.java:100-101`, `AgenticPlugin.java:240` | C4 |
| D2.7 | Reserved ai-atlas paths are defined in one place | missing | Scattered: `ContractIr.java:37`, `ContractGate.java:43`, `McpToolsResourceGenerator.java:44`, `ApiVersionPropertiesGenerator.java:21`, `DeprecationManifestGenerator.java:28`, `OpenApiGenerator.java:66`. Duplicated in `plug/ContractDeclarations.java:56-58`. | C1 |
| D2.8 | The release checks the reserved set against the manifest (see below the table) | missing (**F2**) | — | C4, C6 |
| D2.9 | Empty contract: the plugin derives the effective config from `compileJava`'s final args and writes a manifest with `"contract": "empty"` | conflicts | The empty IR is built from the extension's basePath and major: `ReleaseAction.java:122-129`. No manifest, no stale check. | C3, C4 |
| D2.10 | The `openapi.json` alias, `deprecation-manifest` and `api-version.properties` are not snapshotted | done | `ContractRelease.java:171-181` | — |
| D3.1 | The release never writes the baseline; lock mode is unchanged | done | `ContractRelease.java:297-309` only reads it | — |
| D3.2 | **Canonical** contract equality with the baseline, naming `atlasAccept` | conflicts | Byte equality: `ContractRelease.java:303` | C5 |
| D3.3 | The snapshot keeps the baseline's bytes | done | `ContractRelease.java:172` | — |
| D3.4 | The gate keeps comparing against the baseline | done | `ContractGate.check`/`compare` untouched | — |
| D4.1 | Removal needs a deprecation in ≥`minDeprecatedReleases` **published** releases and a major advance ≥`minApiMajorAdvance`, defaults 1 and 1 | partial (**F1**) | `ReleasePolicy.java:161-213`. Every snapshot counts as published (`ContractRelease.java:137`, `ReleasePolicy.java:195-208`). | B1, D4 |
| D4.2 | Removals are: field or operation removed, field losing a reachable channel, operation losing a channel, whole contract gone | done | `ReleasePolicy.java:139-149`; `ReleasePolicyTest.java:146` | — |
| D4.3 | Any other breaking change in the same major fails by default; across a major it is listed | done | `ReleasePolicy.java:183-189` | — |
| D4.4 | Overrides are explicit and recorded in `release.json` | done | `ReleaseManifest.java:67-71` | — |
| D4.5 | Shape `agentic { release { policy { … } } }`, named `minDeprecatedReleases` and `minApiMajorAdvance` | conflicts | `release { deprecation { minReleases; minMajors; failOnBreaking } }`: `ReleaseSpec.java:61-73`, `DeprecationSpec.java:19-33`, `ReleasePolicy.java:39,58` | B2 |
| D4.6 | A violation names the path, the evidence and the remedy | done | `ReleasePolicy.java:106-112,215-235` | — |
| D4.7 | Field channel-loss credit counts only **tagged** releases where the field was active, deprecated and **visible on C** at that release's own major | missing (**F3**) | The path alone is counted: `ReleasePolicy.java:195-208`, `ReleaseElements.java:56-71` | B1 |
| D4.8 | Visibility is the gate's reachability rule, extracted from `ContractComparison` and parameterized by document and major | missing | A private instance method bound to the baseline: `ContractComparison.java:84,251-274` | A1 |
| D4.9 | Operation channel-loss credit counts tagged releases where the operation was active, deprecated and listed C | missing | Channel-blind: `ReleasePolicy.java:195-208` | B1 |
| D4.10 | The empty contract is released and each removal policed | partial | Policy done (`ReleasePolicyTest.java:146`); manifest missing (D2.9) | C3, C4 |
| D5.1 | Per-release `CHANGELOG.md` plus `.atlas/CHANGELOG.md`, newest first; the root changelog is untouched | done | `ContractRelease.java:181,386-394`, `ReleaseChangelog.java:108-114`, functional test `:125` | — |
| D5.2 | Format `## <v> (API major N)`, then Breaking, Removed, Deprecated, Added, Changed, Informational | done | `ReleaseChangelog.java:56-100` | — |
| D5.3 | The history check fails when the aggregate differs from the history | missing | `ReleaseCheckAction.java:44` only calls `history()` | E1 |
| D6.1 | `agenticReleaseHistoryCheck` under `check`, git-free: digests, ordering, `previous` chain, aggregate changelog | partial | Named `agenticReleaseCheck` (`AgenticPlugin.java:65,243-255`). Digests only. No chain or changelog check. Also carries a version-match duty that belongs to verify (`AgenticReleaseCheck.java:54-58`). | E1 |
| D6.2 | `agenticReleaseVerify` checks the tag's snapshot, that it equals the build, and trusted history | missing | A precursor, `ContractRelease.checkReleased` at `:239-253`, compares IR bytes only, with no tag | E2 |
| D6.3 | CI recipe: fetch history and tags, then run verify on the tag | conflicts | `docs/contract-releases.md:228-243` uses `agenticReleaseCheck --release-version` | G1 |
| D6.4 | No network, database or model call; offline | done | Engine reads files only | — |
| D6.5 | Byte-identical re-release | done | `ContractReleaseTest.theSameInputsGiveTheSameBytes`; functional `releasesAreByteForByteDeterministic` | — |
| D7.1 | Gradle only; no CLI, no MCP writer | done | — | — |
| D8.1 | No IR format change; `release.json` versioned on its own | done | `ReleaseManifest.java:38` | — |
| D8.2 | The git-aware tasks reject a change to a published snapshot, relative to proved tag trees | missing | Digests only: `ContractRelease.java:340-384` | D3, D4, E2 |
| D8.3 | The git-free check keeps the digest check | done | `ContractRelease.java:367-376` | — |
| D9.1 | `ReleaseComparison.compare(previous, current)` in the processor, each release at its own major | partial | Named `ContractGate.compareReleases` (`ContractGate.java:349-353`) plus `ReleaseSurface` | A2 |
| D9.2 | `ReleasePolicy.check(history, differences, policy)` sits next to it | done | `ReleasePolicy.java:161` | — |
| D9.3 | The plugin keeps the filesystem work: resources, snapshots, digests, git, staging | conflicts | The processor's `ContractRelease` writes snapshots, digests and staging (`:126-199,278-284`) | F1 (OQ-1) |
| D9.4 | Comparison tests cover the list below the table, each at its own major | partial | `CompareReleasesTest` covers declared removal, deprecation, not-yet-active and channel loss. The rest is missing. | B3 |
| D10.1 | Credit only from **tagged** releases | missing (**F1**) | `ContractRelease.java:137` | B1, D4 |
| D10.2 | `release { tagName = "v{version}" }`, recorded in `release.json`, exact name only, annotated tags peeled | missing | — | D1, D2 |
| D10.3 | A tag is proved (see below the table) | missing | — | D3 |
| D10.4 | Pending: only the newest snapshot may lack its tag; it earns no credit; `agenticRelease` refuses while one is pending | missing | — | D3, D4 |
| D10.5 | First-release versus incomplete-history table, shallow clone and no-git included | missing | — | D3, D6 |
| D10.6 | Validation boundary: only the git-aware tasks award credit or enforce immutability | missing | `agenticRelease` credits every snapshot | D4, E1, E2 |

Detail for the longer rows:
- **D2.8** fails the release in each of these cases:
  - a listed file is missing, or its digest differs;
  - a reserved-set file is not listed (stale).

  Files outside the set are never inspected. The release copies exactly the listed snapshotted
  artifacts.
- **D9.4** covers:
  - nested entity references;
  - projections and reachability;
  - renamed identifiers: `dtoName`, `toolName`, `operationId` and REST path;
  - the lifecycle boundaries `sinceVersion`, `removedInVersion`, `apiUntil`, and a deprecation
    starting at exactly the release's major;
  - the empty contract.
- **D10.3** proves a tag only when all of these hold:
  - its commit's tree contains the version's `release.json` and `api.ir.json`;
  - those match the manifest's digests;
  - they are byte-identical to the working copy's;
  - the commit is an ancestor of `HEAD`.

**Headline:** 52 rows. 22 done, 7 partial, 17 missing, 6 conflicts.

### 2b. Acceptance criteria (20)

| AC | Summary | Status | Closed by |
|---|---|---|---|
| AC1 | The `agentic { version }` prerequisite | done (PR #59) | — |
| AC2 | `agenticRelease` writes the D2 files; `release.json` has the listed fields | partial | C4, D1 |
| AC3 | The processor writes `contract-resources.json`; tests cover manual `-A` and the extension | missing | C2, C6 |
| AC4 | Only the reserved set is inspected; missing, mismatched and unlisted files fail; another processor's file passes; a leftover `openapi-v1.json` fails | missing | C4, C6 |
| AC5 | The empty contract is recorded explicitly from `compileJava`'s effective args | missing | C3, C6 |
| AC6 | Canonical equality naming `atlasAccept`; baseline bytes kept; the baseline never written | partial | C5 |
| AC7 | Refusals: existing version, SNAPSHOT, format, ordering, major going down, **pending** | partial (pending missing) | D4 |
| AC8 | `ReleaseComparison` reuses `ContractComparison` at each release's own major; the gate is unchanged | partial (naming) | A2 |
| AC9 | A removal needs a **tagged** deprecated release, a major earlier, for fields, operations and the empty contract | partial | B1, D4 |
| AC10 | Channel loss needs tagged releases where the element was active, deprecated and visible on C | missing | B1 |
| AC11 | Shared reachability; tests for an intermediate field excluding C, an operation not on C, and nested chains | missing | A1, B1 |
| AC12 | Other breaking changes in the same major fail; overrides are explicit and recorded | done | — |
| AC13 | Changelog only from the comparison; exact texts pinned; aggregate validated; root untouched | partial (validation missing) | E1 |
| AC14 | The history check is offline and git-free; a consistent rewrite passes it but fails verify | missing | E1, E2, E3 |
| AC15 | Tags follow `tagName`; the proof rules | missing | D1-D6 |
| AC16 | A new project releases; each incomplete-history case fails, naming what is missing | missing | D3, D6 |
| AC17 | Byte-identical re-release | done | — |
| AC18 | Comparison tests cover the D9.4 list | partial | B3 |
| AC19 | Docs: task, layout, versions, policy, publication, accept-then-release, CI recipe | partial (describes superseded behaviour) | G1 |
| AC20 | No network, database, model call or live service | done | — |

**Headline:** 20 ACs. 4 done, 8 partial, 8 missing.

### 2c. Renamed or new public surface

| PR #62 (`bb98010`) | Draft (target) |
|---|---|
| task `agenticReleaseCheck` (under `check`, optional `--release-version`) | `agenticReleaseHistoryCheck` (under `check`, git-free, no version) **plus** new `agenticReleaseVerify` (not under `check`) |
| `agentic { release { checkVersion } }` | removed. `agenticReleaseVerify` uses `agentic { releaseVersion }`, which CI sets with `-Pversion`. |
| `agentic { release { deprecation { minReleases; minMajors; failOnBreaking } } }` | `agentic { release { policy { minDeprecatedReleases; minApiMajorAdvance; failOnBreaking } } }` (see OQ-9) |
| none | `agentic { release { tagName } }`, default `"v{version}"` |
| `ContractGate.compareReleases` + `ReleaseSurface` | `ReleaseComparison.compare(previous, current)` in `proc/contract/` |
| `release.json` `policy.{minReleases,minMajors}` | `policy.{minDeprecatedReleases,minApiMajorAdvance,failOnBreaking}`, plus new `tagName` and `contractResources` keys. `manifestVersion` stays 1, since the format was never released. |
| none | `META-INF/ai-atlas/contract-resources.json` in every contract-bearing class output |

## 3. Design

### 3.1 Decision D-GIT: how the plugin reads git offline

**Decision: shell out to the `git` executable. Do not add JGit.** This is flagged for owner
confirmation as OQ-0.

Why not JGit:
- CLAUDE.md, "Module Architecture Rules", says `gradle-plugin → depends on nothing at runtime`.
  JGit would be the plugin's first runtime dependency: about 3 MB plus transitive dependencies on
  every consumer's buildscript classpath.
- Its version could clash with other plugins that bundle JGit.

Why `git`:
- The draft names `git rev-parse --is-shallow-repository` itself.
- The draft's CI recipe already requires a full clone with tags.

How it runs:
- Only in the plugin, as new class `plug/GitRepository.java`, JDK `ProcessBuilder` only.
- It runs inside the release and verify worker actions, where the other plugin classes already run.
- Always `git -C <dir> …`, with an argument vector and never a shell. Environment
  `GIT_TERMINAL_PROMPT=0`. A bounded timeout, which fails the task when exceeded.
- It uses only read-only plumbing:

| Need | Command |
|---|---|
| Is this a repository? | `git rev-parse --is-inside-work-tree` |
| Is it shallow? | `git rev-parse --is-shallow-repository` |
| Releases dir relative to the repo top | `git -C <releasesDir or nearest existing parent> rev-parse --show-prefix`. This avoids real-path problems such as `/tmp` vs `/private/tmp`. |
| Tags | `git for-each-ref --format=%(refname:strip=2) refs/tags` |
| Peel a tag to its commit | `git rev-parse --verify --quiet refs/tags/<name>^{commit}` |
| Ancestry | `git merge-base --is-ancestor <commit> HEAD`: exit 0 means yes, 1 means no, anything else fails |
| Blob presence and bytes | `git cat-file -e <commit>:<path>`, then `git cat-file --filters <commit>:<path>`. `--filters` applies eol and smudge rules, so "byte-identical to the working copy" is judged as a checkout on this machine would be. |

If the `git` executable is missing, treat it as "no git repository" in the D10.5 table. That fails
when snapshots exist, and is otherwise a first release.

### 3.2 F3: channel-aware removal credit (processor only)

**New `proc/contract/ChannelReachability.java`** (public, final, static). It is extracted from
`ContractComparison.reachable` (`:251-274`) and parameterized by document and major:

```java
public static Set<String> entities(ContractIr ir, int major, String channel);
public static boolean fieldVisible(ContractIr ir, int major, String entityClass, String field, String channel);
public static boolean operationListed(ContractIr ir, int major, String operationId, String channel);
```

- `fieldVisible` holds when all of these do:
  - the field is active at `major` and lists `channel`;
  - `entityClass` is in `entities(ir, major, channel)`.
- `entities` uses the gate's rule unchanged. It starts from operations active at `major` that list
  `channel` and return an entity reference. It then walks reference fields that are active at
  `major` and list `channel`.
- `operationListed` holds when the operation is active at `major` and lists `channel`.
- `ContractComparison` delegates to `ChannelReachability.entities(baseline, major, c)`, keeping
  its per-channel cache. The gate's behaviour and messages do not change. That removes about
  20 lines from a 472-line file.

**`ReleasePolicy` changes** (`proc/release/ReleasePolicy.java`, 240 lines):
- `record Release(ReleaseVersion version, ContractIr ir, boolean published)`. Only
  `published == true` releases earn credit (D10.1).
- Evidence rules:
  - **`removed`** (field or operation): count published earlier releases where the element was
    active and deprecated at the release's own major. This is today's rule with the published
    filter added.
  - **Field `channels.<C>`:** count published earlier releases `r` where the field was active,
    deprecated, **and** `ChannelReachability.fieldVisible(r.ir, r.ir.apiMajor(), class, field, C)`.
  - **Operation `channels`:** for **each lost channel C**, taken from the difference's
    before/after lists, count published releases where the operation was active, deprecated and
    `operationListed(…, C)`. The difference passes only if every lost channel passes. Evidence and
    the violation name the failing channel.
  - `deprecatedSince` is the minimum over the crediting releases, as today. The rule
    `N − deprecatedSince ≥ minApiMajorAdvance` is unchanged.
- The helpers in `ReleaseElements` gain a `channel` overload. They never re-implement
  reachability.

**Regression test:** the reviewer's reproduction (`/tmp/ReviewChannelCreditTest.java`) is added
nearly verbatim as `ChannelRemovalCreditTest.invisibleDeprecationMustNotEarnAiRemovalCredit`.
Only two things change:
- the call becomes `ReleaseComparison.compare(exposed, removed)`;
- `ReleaseFixtures.release(...)` now builds a published release.

It must fail on `bb98010` and pass after B1.

### 3.3 F2: the contract resource manifest

**Processor, new `proc/contract/ContractResources.java`** (public). This is "the paths in one
place":
- `static final String MANIFEST_PATH = "META-INF/ai-atlas/contract-resources.json"`.
- The reserved set is built from the existing constants:
  - `ContractIr.RESOURCE_PATH`, `ContractGate.DIFF_RESOURCE_PATH`,
    `McpToolsResourceGenerator.RESOURCE_PATH`, `ApiVersionPropertiesGenerator.RESOURCE_PATH`,
    `DeprecationManifestGenerator.RESOURCE_PATH` and `MANIFEST_PATH`;
  - `OpenApiGenerator.RESOURCE_DIR + "openapi.json"`;
  - the pattern `META-INF/openapi/openapi-v[1-9][0-9]*.json`.
- `static boolean isReserved(String classOutputRelativePath)`.
- `static Set<String> snapshotted(Manifest m)`: the IR, `openapi-v<N>` and `mcp-tools`.
- `static Set<String> required(Manifest m, boolean nonEmptyIr)`:
  - `api.ir.json`, always;
  - `openapi-v<major>`, when the IR is non-empty;
  - `mcp-tools.json`, exactly when `constraints` is true.
- `record Manifest(String contract, EffectiveOptions configuration, SortedMap<String,String> artifacts)`,
  with `write()` (canonical, through `IrJson.writeCanonical`) and `read(String)`, strict like
  `ReleaseManifest.read`.

**Processor, new `proc/contract/EffectiveOptions.java`:**
- `record EffectiveOptions(String apiBasePath, int apiMajor, String openApiInfoVersion, boolean constraints, boolean projections)`.
- `resolveVersionConfig` (`AgenticProcessor.java:116-158`) moves here verbatim, with identical
  messages. `AgenticProcessor` drops to about 460 lines, which makes room for the manifest call.
- `static EffectiveOptions fromArguments(Map<String,String> lastWinsOptions)` is the parser the
  plugin uses for the empty contract (C3). It applies the same defaults and validation, so the
  processor and the plugin share one implementation.

**Processor, digest capture: new `proc/contract/ResourceRecorder.java`:**
- A `ProcessingEnvironment` and `Filer` wrapper installed in `AgenticProcessor.init`:
  `super.init(ResourceRecorder.wrap(env))`.
- For `createResource(CLASS_OUTPUT, "", path)` with `isReserved(path)`, the returned `FileObject`
  tees `openOutputStream()` into a SHA-256. `openWriter()` is wrapped as a UTF-8
  `OutputStreamWriter` over that stream.
- Every other call delegates untouched, so Gradle's incremental-processing Filer wrapper still
  sees each call.
- No generator or `ContractGate` signature changes. This matters because `ContractGate.java` and
  `AgenticProcessor.java` sit at 499 lines.
- At the end of the `processingOver()` block, `AgenticProcessor` writes the manifest with
  `contract: "declared"` and the recorder's digests. The manifest never lists itself.

`contract-resources.json` (canonical, sorted keys):

```json
{
  "manifestVersion": 1,
  "contract": "declared",
  "configuration": {
    "ai.atlas.api.basePath": "/api",
    "ai.atlas.api.major": 2,
    "ai.atlas.constraints": true,
    "ai.atlas.openapi.infoVersion": "2.0.0",
    "ai.atlas.projections": false
  },
  "artifacts": {
    "META-INF/ai-atlas/api-version.properties": "<sha256>",
    "META-INF/ai-atlas/api.ir.json": "<sha256>",
    "META-INF/ai-atlas/contract-diff.json": "<sha256>",
    "META-INF/ai-atlas/deprecation-manifest.json": "<sha256>",
    "META-INF/ai-atlas/mcp-tools.json": "<sha256>",
    "META-INF/openapi/openapi-v2.json": "<sha256>",
    "META-INF/openapi/openapi.json": "<sha256>"
  }
}
```

The empty-contract variant has `"contract": "empty"`. Its only artifact is
`META-INF/ai-atlas/api.ir.json`, a real file holding `EmptyContract.json(basePath, major)`, whose
digest the manifest records (OQ-2, decided).

**Plugin, empty contract (C3):**
- **Arguments:** new `plug/EffectiveCompilerArguments.java` reads `compileJava`'s
  `getOptions().getAllCompilerArgs()` through a configuration-time `Provider<List<String>>`, which
  is configuration-cache safe. It collects `-Akey=value` pairs, last value winning, and hands the
  map to the worker. The worker calls `EffectiveOptions.fromArguments`. `getAllCompilerArgs()`
  returns `compilerArgs` followed by the argument providers, which matches javac's order.
- **Writer:** new `plug/EmptyContractResourcesAction.java`, a worker in the processor's class
  loader, writing into **`compileJava`'s destination directory** (OQ-2, decided):
  1. First, it lists the reserved files already present. It never reads, keeps or digests an
     existing `api.ir.json`: that file is from an earlier compilation, so treating it as this
     compilation's would be the silent-overwrite error the owner ruled out.
  2. It writes a freshly built canonical `EmptyContract.json` to `api.ir.json`, digests the bytes
     it wrote, and writes the manifest. When step 1 found an `api.ir.json` with other bytes, it
     logs one line naming the replaced stale file. The log is informational: what authorizes the
     replacement is the caller's confirmation that the class output declares no contract
     (owner correction, third round; see `spec.md`). This is the compile/accept path only.
  3. Any other reserved file found in step 1 (for example a leftover `openapi-v1.json` or
     `mcp-tools.json`) is left in place. It is not in the empty manifest, so the release fails on
     it as an unlisted reserved file, naming it and `clean`. The build itself, including
     `atlasContractCheck`, is unaffected, so removing every annotation without `clean` still
     builds once accepted.
  4. **The release path never writes** (owner correction, third round): `ClassOutputResources`
     only reads, and the release never runs the writer. A stale or mismatched reserved resource
     it encounters fails the release; it is never repaired and then passed.
  5. **No repairing dependency** (fourth round): `agenticRelease` has no task dependency on
     `compileJava` or `classes`. Its class output is an `@Internal` file collection built from a
     plain `project.provider`, which carries no producer, and it is ordered with `mustRunAfter`.
     The task has no outputs, so it always runs. The same applies to `agenticReleaseVerify` (E2).
- **Where the writer runs** is settled by a time-boxed spike, C3a. Two options:
  - **Preferred:** a `compileJava.doLast` action that submits through `WorkerExecutor`. The file is
    then part of `compileJava`'s tracked output, cached and up to date.
  - **Fallback:** `atlasContractCheck`, which already runs the empty-contract worker, writes the
    file. The cost is that `compileJava` of an empty module is never up to date. It is documented.
  - The spike's acceptance: a second build leaves `compileJava` UP-TO-DATE, and
    `--configuration-cache` passes.

**C3a decision (2026-09-29, spike run against `:demo:compileJava` with a throwaway `buildSrc`
plugin, discarded, not committed):** the preferred option holds — the writer runs from
`compileJava.doLast`, submitted through an injected `WorkerExecutor`, writing into
`destinationDirectory`. Evidence:
- A second build with no source change left `compileJava` `UP-TO-DATE`: the file written in
  `doLast` becomes part of the output-directory snapshot Gradle records after the task's actions
  finish, so it does not itself invalidate a later up-to-date check.
- `--configuration-cache` stored and then reused cleanly, *provided* the `doLast` action is a
  plain compiled class (a `WorkAction` plus an `Action<Task>`/`Plugin` obtaining `WorkerExecutor`
  through constructor or method injection), not a lambda written inline in a `.gradle.kts` script:
  a script-inline lambda closes over an implicit script-object reference (`this$0` on a
  Kotlin-DSL-generated closure class) that the configuration cache rejects regardless of what the
  lambda actually touches. This is not a new constraint on the real design: `EmptyContractAction`,
  `AgenticRelease`, `AtlasContractCheck` and every other task/worker in `plug/` are already plain
  compiled classes, never script-inline lambdas, so `EmptyContractResourcesAction` follows the
  same shape and is unaffected.
- **Owner correction (2026-09-29), folded into the writer's authorization rule:** the
  `compileJava.doLast` writer may replace stale reserved output (a leftover non-empty `api.ir.json`
  from an earlier compilation) only *after* confirming, via `ContractDeclarations`, that the class
  output declares no `@AgenticEntity`/`@AgenticExposed` at all. That confirmation — not the
  replacement log line — is what makes the regeneration valid; logging remains a courtesy. The
  release path (`agenticRelease`, `ClassOutputResources`) never runs this writer, directly or via a
  task dependency, and never repairs, regenerates or otherwise writes into the class output it
  validates: it only reads and fails, naming the path. Because `agenticRelease` already
  `dependsOn(classes)` and not `atlasContractCheck` (see `ReleaseTasks.configure`), wiring the
  writer into `compileJava.doLast` keeps this separation for free — release validation runs after
  compilation without ever invoking the writer itself.

**Plugin, release-side validation: new `plug/ClassOutputResources.java`**, running in the worker:
1. Pick the class output. If it declares a contract (`ContractDeclarations.declaringOutput`), the
   manifest must exist, or the release fails. Otherwise the empty manifest must exist.
2. Walk the class output, keeping only paths where `ContractResources.isReserved`.
3. Fail, naming each path, when:
   - a listed artifact is missing (the empty contract's `api.ir.json` included, OQ-2);
   - a digest does not match;
   - a reserved file is not listed (stale);
   - a `required(...)` artifact is not listed (a processor bug, fail loudly).
4. Return the bytes of `snapshotted(manifest)` and the manifest JSON.
   - `AgenticRelease` no longer looks up `openapi-v<extension major>`.
   - `ContractDeclarations.OPENAPI_PATH` and `MCP_TOOLS_PATH` are deleted.

**`release.json`** gains a `tagName` key (§3.4) and `"contractResources": { …verbatim manifest
object… }`, and renames the policy keys. `ContractRelease.Request` replaces `openApi` and
`mcpTools` with:
- `Map<String, byte[]> artifacts`, keyed by snapshot file name;
- `String contractResourcesJson`;
- `String tagName`;
- `Set<ReleaseVersion> published` (§3.4).

**Canonical equality (D3.2, C5):** `ContractRelease.accepted` parses both documents with
`IrJson.parse`, which migrates older versions, and compares `ContractIr` values. It keeps and
snapshots the **baseline's bytes**. The failure message still names `atlasAccept`. C5 must
confirm that `ContractIr` has structural `equals` throughout, with no arrays.

### 3.4 F1: tags, proof, pending and history (plugin)

- **`plug/TagName.java`:**
  - Parses the template: exactly one `{version}`, and the rendered name must pass basic ref-name
    rules.
  - `render(ReleaseVersion)`.
  - `match(String tag)` returns the version, or empty. Only strict `MAJOR.MINOR.PATCH` matches,
    so `1.0.0` never stands in for `v1.0.0`.
  - It is configured by `agentic { release { tagName } }`, default `"v{version}"`.
  - The rendered name is written to each new `release.json` as `"tagName": "v1.4.0"`. OQ-8 covers
    the resolved name versus the template.
- **`plug/GitRepository.java`**, per 3.1.
- **`plug/PublishedHistory.java`:** pure logic over the snapshot versions, the matching tags and a
  per-tag prover. It returns `Verdict(Map<ReleaseVersion, Status{PUBLISHED, PENDING}>)`, or throws
  `GradleException` with the draft's wording and remedy: "fetch tags and full history, e.g.
  `actions/checkout` with `fetch-depth: 0` and `fetch-tags: true`". The D10.5 table is implemented
  literally:

| State | Outcome |
|---|---|
| No snapshots, and no matching tag (or no repository, or no `git`) | first release: valid |
| Shallow repository | fail, whatever the snapshots (OQ-6) |
| Snapshots exist and there is no repository or no `git` | fail |
| A matching tag with no snapshot directory | fail, naming the tag |
| A snapshot other than the newest with no tag | fail, naming the version and the expected tag name |
| The newest snapshot with no tag | PENDING: part of the chain, no credit |
| A tag that fails any proof step (tree lacks `release.json`/`api.ir.json`, digest mismatch, not byte-identical to the working copy, not an ancestor of `HEAD`, does not peel to a commit) | fail, naming the tag and the failed check |
| Otherwise | PUBLISHED |

- **Wiring into `agenticRelease` (D4):**
  - `ReleaseAction` computes the verdict and passes `published` into
    `ContractRelease.Request`.
  - `ContractRelease` refuses when any existing snapshot is PENDING: "Release X is pending: tag
    commit … as `v X` and push the tag, then release." It builds each
    `ReleasePolicy.Release(version, ir, published)`.
  - Before D4, the transitional B1 behaviour marks every snapshot published, which keeps the build
    green.

### 3.5 Task split: history check and verify (E)

- **`agenticReleaseHistoryCheck`**, renamed from `agenticReleaseCheck`, under `check`. Git-free.
  - It runs `ReleaseHistory.checkConsistency(releasesDir, changelog)` in the processor. That
    covers:
    - every directory against its manifest, including digests and extra or missing files, as
      today;
    - names are strict versions, in ascending order;
    - each manifest's `previous` equals the preceding directory's version, and the first is null;
    - `.atlas/CHANGELOG.md` equals `aggregateChangelog(releases)` byte for byte.
  - With no release directory and no changelog it succeeds silently.
  - `--release-version` and `checkVersion` are removed.
- **`agenticReleaseVerify`** (new; not under `check`; group `ai-atlas`; depends on `classes`),
  steps in order:
  1. `version = agentic { releaseVersion }`, strict.
  2. `PublishedHistory` verdict. Incomplete history fails.
  3. The tag `tagName(version)` exists and is proved. It must peel to `HEAD`'s commit (OQ-3).
  4. `.atlas/releases/<version>/` exists.
  5. `ClassOutputResources` validates the build's output.
  6. The snapshot equals the build, and `release.json`'s `contractResources.configuration` equals
     the build's effective configuration (OQ-4):
     - the snapshot's IR is canonically equal to the emitted IR;
     - each snapshotted resource is byte-equal to the build's;
     - the set of snapshotted files equals `snapshotted(build manifest)`.
  7. `checkConsistency`.

  It writes nothing.

### 3.6 Relocation of the filesystem work (D9.3; required, OQ-1 decided)

Default, following the draft:
- `ContractRelease` and `ReleaseHistory` move to the plugin as `plug/ReleaseSnapshots.java` and
  `plug/ReleaseSnapshotHistory.java`. They still run inside the worker and call processor classes.
- The processor keeps:
  - `ReleaseComparison`, `ReleasePolicy`, `ReleaseChangelog`, `ReleaseManifest` (a pure format,
    which needs Jackson from the processor) and `ReleaseVersion`;
  - `ContractResources` and `EffectiveOptions`.
- Their tests move to `modules/gradle-plugin/src/test`, which needs
  `testImplementation(project(":modules:processor"))`. That is a test-only scope, so the module
  rule holds.
- This is scheduled **last** (Phase F), as a mechanical move, so the owner can drop it without
  disturbing Phases A-E.

## 4. Test strategy

- **Processor (JUnit 5 + Google compile-testing; never mock `ProcessingEnvironment`):**
  - `ChannelReachabilityTest`:
    - direct return;
    - a nested chain `Order → Customer → Address`;
    - an intermediate field excluding C, which gives no visibility;
    - an operation not listing C, which gives no visibility;
    - an inactive field or operation at the major.
  - `ChannelRemovalCreditTest`:
    - the reviewer's case, verbatim;
    - credit from a release where the field was visible;
    - no credit from an unpublished (pending) release;
    - operation channel loss with and without credit;
    - multi-channel operation loss naming the failing channel.
  - `ReleaseComparisonTest`, renamed from `CompareReleasesTest`, gains the D9.4 list at each
    release's own major: nested references, `dtoName`, `toolName`, `operationId` and REST-path
    renames, and the `sinceVersion`, `removedInVersion`, `apiUntil` and deprecation-at-exactly-M
    boundaries, plus the empty contract.
  - `ContractResourcesTest`: format round trip, the reserved matcher, the required set.
  - `EffectiveOptionsTest`: messages identical to today's; last value wins.
  - `ContractResourcesProcessorTest` (compile-testing):
    - digests equal `generatedFile` bytes;
    - `constraints=true` lists `mcp-tools`;
    - major 2 lists `openapi-v2` and the alias;
    - a baseline configured lists `contract-diff`;
    - duplicate `-A` values are recorded last-wins;
    - the existing golden tests stay byte-identical.
  - `ContractReleaseTest` and `ReleaseHistoryTest`: canonical equality with an older-`irVersion`
    baseline, the pending refusal, the chain check, the changelog check. Split files so each stays
    under 500 lines.
- **Plugin unit (`src/test`):**
  - `TagNameTest`.
  - `PublishedHistoryTest`, with a fake repository: every table row and every proof failure.
  - `GitRepositoryTest`, against a real `git init` in `@TempDir`: shallow via
    `git clone --depth 1 file://…`; annotated and lightweight tags; ancestry; `cat-file`.
  - `ClassOutputResourcesTest`, with temp class outputs: missing, mismatch, stale,
    another processor's file ignored.
  - `EffectiveCompilerArgumentsTest`: last value wins.
- **Plugin functional (TestKit), new `GitFixture` helper in `src/functionalTest`:**
  - `init`, `commitAll`, `tag`, `annotatedTag`, `shallowClone`, all through `ProcessBuilder` on
    the temp project.
  - Repo-local `user.name`, `user.email` and `commit.gpgsign=false` / `tag.gpgSign=false`. These
    apply to the throwaway test repository only.
  - Test files, each under 500 lines:
    - `AgenticReleaseFunctionalTest` (basic);
    - `ReleasePolicyFunctionalTest`, with multi-release tests that tag each release;
    - `ReleaseResourcesFunctionalTest`, covering AC3, AC4 and AC5:
      - a stale file planted by a build-script task that runs after `compileJava`, simulating
        Gradle's retained aggregating output;
      - `constraints` set through `options.compilerArgs`;
      - the major set by a user `CommandLineArgumentProvider`;
      - another processor's resource ignored;
    - `ReleaseHistoryFunctionalTest`, covering AC15 and AC16: the no-repository case uses
      `GIT_CEILING_DIRECTORIES`, merged into the inherited environment;
    - `ReleaseVerifyFunctionalTest`, covering AC14: a consistent rewrite with new digests passes
      `agenticReleaseHistoryCheck` and fails `agenticReleaseVerify` and `agenticRelease`.
- **Gates at every commit:**
  - `./gradlew :modules:processor:test :modules:gradle-plugin:test :modules:gradle-plugin:functionalTest checkstyleMain checkstyleTest`.
  - A full `./gradlew build`, javadoc included, at each phase end.

## 5. Delegation (optional)

| Lane | Role | Tasks |
|---|---|---|
| Processor | Annotation Processor Engineer | A1, A2, A4, A5, B1-B3, C1, C2, C5 |
| Plugin | Gradle Plugin Developer | A3, C3, C4, D1-D4, E1, E2, F1 |
| Tests and docs | QA and Integration Engineer | C6, D5, D6, E3, then G1 and G2 with the lead |

Tasks run sequentially per `tasks.md`. After Phase A, lane B (processor) and D1-D3 (plugin, pure)
can run in parallel. They own disjoint files.

## 6. Risks

| Risk | Mitigation |
|---|---|
| `AgenticProcessor`, `ContractGate` (499 lines), `ContractProjection` (500) and `IrJson` (492) sit at the cap | A5 extraction first. The Filer-wrapper design keeps `ContractGate` and the generators unchanged. No edits to `ContractProjection` or `IrJson`. |
| The recorder's UTF-8 `openWriter` changes the bytes of `OpenApiGenerator`, `ApiVersionPropertiesGenerator` and `DeprecationManifestGenerator` when javac's `-encoding` is not UTF-8 | JSON must be UTF-8 (RFC 8259), and Java 21 defaults to UTF-8. The golden tests must stay byte-identical. Note it in the CHANGELOG. |
| The empty-manifest writer placement (compileJava up to date, configuration cache) | C3a spike with explicit acceptance, plus a documented fallback |
| `git` missing or unusual in CI or dev (Windows `autocrlf`) | `cat-file --filters`. Document `.atlas/releases/** -text` in `.gitattributes`. A missing `git` follows the no-repository row. |
| Test environment: temp dirs inside a parent repo, a global git config with signing | `GIT_CEILING_DIRECTORIES`; repo-local config in `GitFixture` |
| Configuration cache: arguments added to `compileJava` in `doFirst` are invisible to the configuration-time provider | Documented. Declared contracts are unaffected, since the processor's own manifest is authoritative. |
| The pending rule changes every multi-release functional test | D5 migrates the tests to tagged history **before** D4 turns the rule on, so each commit stays green |
| Scope creep from the relocation (F1) | Isolated last phase, owner-gated (OQ-1) |

## 7. Open questions for the owner

None blocks Phases A-E. Each has a recommended default that the plan follows unless the owner
overrides it.

**Second round, 2026-09-29:** OQ-2 decided against the default (see `spec.md`); OQ-4, OQ-5,
OQ-7 and OQ-8 approved with the refinements recorded in `spec.md`; OQ-9 and OQ-10 keep their
defaults. No question remains open.

**Owner decisions, 2026-09-29:** OQ-0 decided (shell out to `git`); OQ-1 decided (Phase F is
required); OQ-3 decided (verify requires the tag at `HEAD`); OQ-6 decided (a shallow clone fails
git-aware validation; the git-free history check stays usable). The other questions keep their
defaults, not yet confirmed by the owner.

- **OQ-0 (confirm the decision): git access by shelling out to `git`, not JGit.**
  - Why: JGit would break "gradle-plugin depends on nothing at runtime".
  - Default: shell out.
- **OQ-1: D9.3 relocation.** The draft allocates snapshots, digests and staging to the plugin.
  PR #62 keeps them in the processor's `ContractRelease` for later CLI reuse.
  - Default: move them (Phase F, about 2-3 hours, mechanical).
  - Alternative: keep them and record the divergence in the PR and docs.
- **OQ-2 — decided, default reversed: see `spec.md` and §3.3 (a real, freshly written file).**
  The original question: **the empty contract's `api.ir.json`.** The draft lists it as "the only expected
  artifact", but also says "any other file in the reserved set present fails".
  - Default: it is **virtual**. The manifest records the `EmptyContract` digest, and the file is
    not expected in the class output.
  - Consequence: a present `api.ir.json` is stale and fails.
- **OQ-3: must `agenticReleaseVerify` require `tagName(version)` to peel to `HEAD`?**
  - Default: yes. "The tagged build" means `HEAD` is the tag.
- **OQ-4: what "the snapshot equals what the tagged build produces" covers.**
  - Default: compilation-derived artifacts only: the IR (canonical), `openapi-v<N>`, `mcp-tools`,
    and the effective configuration.
  - `contract-diff.json` and `CHANGELOG.md` are pinned by the digest and tag proof, not
    recomputed.
- **OQ-5: the reserved set.** AC4 says "the `META-INF/ai-atlas/` files". The D2 table enumerates
  specific files.
  - Default: the enumerated files and patterns only. Another `META-INF/ai-atlas/*` file in the
    class output is not inspected.
- **OQ-6: a shallow clone with no snapshots.**
  - Default: fail, reading "a shallow clone" in the D10.5 list as unconditional.
- **OQ-7: multi-module repositories.** A module sharing the default `v{version}` convention fails
  on "a tag without its snapshot" for versions it did not release.
  - Default: no code change. Document a per-module `tagName` such as `orders-v{version}`.
- **OQ-8: what `release.json` records for the tag.**
  - Default: the resolved name, `"tagName": "v1.4.0"`.
  - A snapshot's proof uses its **recorded** name, so a later template change does not orphan
    older releases. Orphan-tag detection and new releases use the configured template.
- **OQ-9: naming.**
  - The draft does not name the same-major breaking override. Default: keep `failOnBreaking`
    inside `policy { }`.
  - The aggregate changelog keeps its title and "do not edit" note before the concatenated
    sections. The draft says "concatenation". Default: keep the header.
- **OQ-10: "or when configured explicitly" for `agenticReleaseVerify`.**
  - Default: no new property. Users run it or wire `check.dependsOn` themselves.

## 8. Out of scope (per the draft)

- Pre-releases and older-line releases.
- The `atlas release` CLI and any writing MCP tool.
- Per-channel lifecycle.
- Publishing snapshots outside the repository.
- Changing `atlasContractCheck` to use effective args. It is Phase 1-4 code, and the draft
  addresses only the release.
- The `ai.adam` stale reference in `incremental.annotation.processors`, which is unrelated.
