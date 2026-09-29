# Phase 5 spike: release workflow and immutable released contracts (epic #23 §10)

Branch `claude/phase5-release-spike`, cut from `master` at `f9d0f68`, which is after Phases 0–4 and the
#50 fix. Unless a line says otherwise, every `file:line` below refers to `master` at `f9d0f68`. The
prototype's own files are listed in §6.

Abbreviations: `plugin/` is `modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/`, and
`processor/` is `modules/processor/src/main/java/com/egoge/ai/atlas/processor/`.

## TL;DR

- **A release can reuse the gate unchanged, but not by calling it the way the build does.** The gate
  compares two documents *at one major*: the baseline's `apiMajor` (`ContractComparison.java:90`).
  Between two releases the major can change. Compared at the old major, a declared removal is
  invisible: a field with `removedInVersion = 2` is still active at 1 in both documents. A
  declaration deleted after the move to major 2 looks like an undeclared break.
- **The prototype compares what each release *published*.** It reduces each IR to its projection at
  its own major, as a document whose elements are active at every major and keep only the deprecation
  in effect there (`ReleaseAction.published`). Then it calls `ContractGate.compare` on the two
  reduced documents. Every classification, reason and path comes from `ContractComparison`, and the
  release adds none of its own. The real phase should move the reduction into the processor as
  `ContractGate.compareReleases(previous, current)`, so the CLI can share it (Q9).
- **The prototype works end to end.** `agenticRelease`, behind nothing (a new task never runs
  unless asked), is covered by 11 TestKit tests, all passing. It does the following:
  - snapshots the accepted IR, the OpenAPI document of the released major, `mcp-tools.json` when
    generated, the comparison as `contract-diff.json`, a `CHANGELOG.md` section, and a `release.json`
    manifest with SHA-256 digests to `.atlas/releases/<version>/`;
  - refuses a released version, a version not above the latest release, a SNAPSHOT, a contract that
    was not accepted, and a released file edited after release;
  - fails a removal of a published field or operation that was not released as deprecated in at
    least N releases (default 1), at least M majors before the removing major (default 1);
  - is byte-for-byte deterministic: no timestamp, path or host is written.
- **Release fixes the baseline, it does not move it.** The prototype releases only when the
  compilation's IR is byte-identical to the committed baseline, which means `atlasAccept` has run.
  So release is a lock-mode check at the moment of release, and needs no new write path to the
  baseline (Q3).
- **No IR format change is needed.** Each release records its `irVersion`. `IrJson.read` already
  migrates older documents in memory (`IrJson.java:343-370`), so a release written at `irVersion` 3
  stays byte-immutable and still compares after an `irVersion` 4 (Q8).
- **The version source is the real open question.** The plugin already uses `project.version` as
  the *ai-atlas dependency version*: `agentic { version }` defaults to it (`AgenticPlugin.java:80-81`).
  The contract's major, `ai.atlas.api.major`, is a separate integer. The prototype adds
  `agentic { releaseVersion }`, defaulting to `project.version`, and accepts only `MAJOR.MINOR.PATCH`
  (Q1).

**Owner decisions (2026-09-29)** are recorded in `ISSUE-DRAFT.md`. They supersede the
recommendations in §7 where they differ:
- `agentic { version }` stops defaulting to `project.version`;
- a resource must come from the same compilation as the IR;
- the baseline is compared by canonical equality;
- a breaking change within the same API major fails by default, even after `atlasAccept`;
- channel loss and an empty contract count as removals;
- the history check is split from tag verification;
- deprecation credit comes from tagged releases.

---

## 1. The Gradle plugin tasks today

| Task | Registered | Inputs | Outputs / effect |
|---|---|---|---|
| `compileJava` (main) | Java plugin; contract options added at `AgenticPlugin.java:120-128` | Sources; `ContractArguments` (`ContractArguments.java:54-63`): `-Aai.atlas.contract.baseline=<abs path>` (an input by **content only**, `@PathSensitive(NONE)`, `:38`), `-Aai.atlas.contract.locked`, `-Aai.atlas.constraints` when set; `ProjectionsArguments`; `-Aai.atlas.api.basePath/major/openapi.infoVersion/strict`, added in `afterEvaluate` to **every** `JavaCompile` (`AgenticPlugin.java:393-405`) | Class output with `META-INF/ai-atlas/api.ir.json` (`IrBuilder.java:329`), `META-INF/ai-atlas/contract-diff.json` when compared (`ContractGate.java:404-433`), `META-INF/openapi/openapi-v<major>.json` and the alias `openapi.json` (`OpenApiGenerator.java:74-78`, `:113`), `mcp-tools.json` with `constraints` on (`McpToolsResourceGenerator.java:44`), `api-version.properties` and `deprecation-manifest.json`. **The gate runs here** (`AgenticProcessor.java:165-167`). |
| `atlasContractCheck` | `AgenticPlugin.java:134-145`; `classes` depends on it (`:146`) | `compileJava`'s class output, `annotationProcessor` classpath, baseline (`@Internal`), `locked`, `apiBasePath`, `apiMajor` | None, so never up to date. When the class output declares no contract (`AtlasContractCheck.java:69-73`), it runs `EmptyContract.check` (`EmptyContract.java:61-77`) in the processor's class loader (`:76`). |
| `atlasAcceptCompile` | `AgenticPlugin.java:152-184` | Everything `compileJava` has, read lazily, **less** the `-Aai.atlas.contract.*` options (`:163-167`) | `build/atlas/accept/classes`, so it compiles even while the gate fails |
| `atlasAccept` | `AgenticPlugin.java:186-195` | `atlasAcceptCompile`'s output, processor classpath, baseline (`@Internal`, `AtlasAccept.java:46`), `apiBasePath`, `apiMajor` | Writes the emitted IR **byte for byte** to the baseline (`AcceptAction.java:56-74`, `:69`). First it prints `ContractGate.compare` and `documentDifferences` against the old baseline, grouped by classification (`:77-119`). No declared outputs, so it always runs. |

Every task that needs processor classes (`EmptyContract`, `ContractGate`, `IrJson`) runs them as a
worker action in an isolated class loader over the project's `annotationProcessor` classpath, and
reports a plugin/processor version mismatch with a remedy (`AgenticPlugin.java:206-222`). The
prototype's release action follows the same pattern.

## 2. How `ContractComparison` classifies changes

`ContractGate.compare(baseline, fresh)` (`ContractGate.java:345-347`) runs one
`ContractComparison` (`ContractComparison.java:87-102`):

- **One major, the baseline's.** `major = baseline.apiMajor()` (`:90`). Entities, fields and
  operations are compared only when active at it (`:133-153`, `:334-342`).
  `ContractProjection.isActive` is `sinceVersion <= M < removedInVersion` for fields and
  `apiSince <= M <= apiUntil` for operations (`ContractProjection.java:223-231`).
- **Document:** a changed `apiBasePath` is BREAKING INPUT (`:94-97`).
- **Entities (output):** a removed active entity is BREAKING (`:118-121`). A changed `dtoName`,
  `dtoPackage` or `includeTypeInfo` is BREAKING; `displayName` and `description` are compatible
  (`:155-166`). An AI record appearing or disappearing is INFORMATIONAL (`:115-117`).
- **Fields (output):**
  - removed: BREAKING "Responses no longer carry the field", with the remedy
    `removedInVersion = M+1` (`:175-177`, `:288-291`);
  - added: COMPATIBLE (`:183-188`);
  - `displayName`, `javaType`, `collectionKind`, `elementType`, `typeHint`, `reference`: BREAKING
    (`:193-203`);
  - `sensitive`: BREAKING only when it becomes true (`:204-207`);
  - `allowedValues`: BREAKING when values are added to a closed enum (`:208-215`);
  - `enumType`, `openEnum`, `checkCircularReference`, `description` and **`lifecycle`**:
    COMPATIBLE (`:216-221`);
  - output constraints: INFORMATIONAL (`:222-226`);
  - channels: losing one on which the entity is reachable is BREAKING, anything else is COMPATIBLE
    (`:233-277`).
- **Operations (input, and output for returns):**
  - removed: BREAKING, with the remedy `apiUntil = M` plus a replacement with `apiSince = M+1`
    (`:306-309`, `:405-408`);
  - added: COMPATIBLE (`:327-331`);
  - `toolName`, lost channels, `rest.httpMethod`, `rest.path`, parameter names, removed input enum
    constants, and every return attribute: BREAKING (`:344-380`, `:390-403`);
  - `operationId` renumbering: BREAKING, naming the operations that caused it (`:312-325`);
  - input constraints and requiredness: through `ConstraintComparison` (`:367-368`);
  - `description`, `lifecycle` and hints: COMPATIBLE or INFORMATIONAL (`:381-386`).
- **Output:** `Difference(path, change, direction, before, after, classification, reason, remedy)`
  (`ContractGate.java:116-123`), sorted by path, then change (`ContractComparison.java:100`). The
  canonical `contract-diff.json` form is `ContractGate.diffJson` (`ContractGate.java:358-374`).
- **Lock mode** uses a second comparison, `documentDifferences` (`ContractGate.java:274-309`). It
  lists every element whose IR differs **at any major**, lifecycle included.

**What this means for a release.** A deprecation or a scheduled removal is only a COMPATIBLE
`lifecycle` difference at M. A declared removal changes nothing at M: that is the whole design, so a
declared transition passes the gate (`docs/contract-governance.md`, "Projection at a major"). A
release that spans a major therefore cannot be diffed by `compare(previousRelease, current)`. The
removal it must report and police is exactly what that call hides.

## 3. What a release would snapshot

| Artifact | Where it comes from | Deterministic? | In the prototype |
|---|---|---|---|
| Contract IR | The baseline, `.atlas/api.ir.json`, required byte-identical to the IR `compileJava` emitted | Yes, by design (`IrJson.write`, canonical form) | **Yes**, as `api.ir.json` |
| OpenAPI of the released major | `META-INF/openapi/openapi-v<major>.json` in the class output | Yes: the golden tests compare it byte for byte | **Yes**, as `openapi-v<major>.json`. Only the configured major exists in a build: earlier majors' documents are in earlier releases. The unversioned alias `openapi.json` is skipped. |
| `mcp-tools.json` | Class output; only with `ai.atlas.constraints` on | Yes: `IrJson.writeCanonical` (`McpToolsResourceGenerator.java:104`) | **Yes, when present** |
| Comparison | `ContractGate.diffJson` over the release comparison | Yes | **Yes**, as `contract-diff.json`, with `publishedMajor` = the previous release's major |
| Changelog section | Rendered from the same `Difference` list | Yes: no date | **Yes**, as `CHANGELOG.md` |
| Manifest | Version, `apiMajor`, `irVersion`, previous version, policy, SHA-256 of every other file | Yes | **Yes**, as `release.json` |
| `deprecation-manifest.json`, `api-version.properties` | Class output | Yes | No. Both are derivable from the IR at the major. |

Sample output from the prototype is in `sample-output/`. It covers 1.0.0, then 1.1.0, which adds
`note` and deprecates `legacy`, then 2.0.0, which moves to major 2 and so removes `legacy`. It also
has the logs of a refused 1.2.0, which removes `legacy` inside major 1, and of a repeated 2.0.0.

## 4. Where the version comes from today

- **`project.version`.** The root build sets it from `-Pversion`, else `0.1.0-SNAPSHOT`
  (`build.gradle.kts:3`). CI's release workflow passes the tag without its `v`
  (`.github/workflows/release.yml`, "Extract version from tag"). In a **consumer** project, the
  plugin already reads `project.version` for something else: `agentic { version }`, the ai-atlas
  **dependency** version, defaults to it (`AgenticPlugin.java:80-81`).
- **`ai.atlas.api.major`.** It is an integer, `agentic { apiMajorVersion }`, default 1
  (`AgenticPlugin.java:87`, `AgenticExtension.java:68`). It is passed as `-Aai.atlas.api.major`
  (`:402`), and parsed and validated as a positive integer (`AgenticProcessor.java:136-150`). It is
  the major the IR is emitted for, the URL prefix `/v<major>/`, the OpenAPI file name, and the
  baseline's "published major" the gate protects.
- **`ai.atlas.openapi.infoVersion`.** A free string, default `"<major>.0.0"` (`AgenticPlugin.java:88-89`),
  that goes only into OpenAPI `info.version`.
- Nothing ties the three together today. A project at `project.version = 3.4.1` can publish API
  major 1 with `info.version = 1.0.0`.

## 5. How the gate, baseline and `atlasAccept` interact today

1. `compileJava` emits the IR and compares it with the baseline at the baseline's major. Unlocked,
   it fails only on BREAKING. Locked, it fails on any `documentDifferences`, and on a missing
   baseline (`ContractGate.java:206-261`).
2. Unlocked, compatible changes pass without touching the baseline, so the **baseline can lag** the
   sources by any number of compatible changes.
3. `atlasAccept` is the only writer of the baseline, and writes the emitted IR byte for byte. That is
   how a breaking change, or a major bump, becomes the new published state.
4. A build at `apiMajor` above the baseline's is allowed and protects the old major. Below it is an
   ERROR (`ContractGate.java:226-232`).

So the baseline already means "the contract the team has accepted", but not "the contract that was
shipped". A release gives it that second meaning, by snapshot, without changing any of the above.

## 6. The prototype

**Files** (all new except the first two):
- `plugin/AgenticExtension.java`: `releaseVersion` (default `project.version`), `releasesDir`
  (default `.atlas/releases`), `releaseMinDeprecatedReleases` (default 1),
  `releaseMinDeprecatedMajors` (default 1).
- `plugin/AgenticPlugin.java`: registers `agenticRelease` (group `ai-atlas`), which depends on
  `classes`, so the gate and `atlasContractCheck` pass first.
- `plugin/AgenticRelease.java`: the task. It has no outputs and cannot be cached, so it always runs,
  like `atlasAccept`.
- `plugin/ReleaseAction.java`: the worker action, in the processor's class loader.
- `plugin/ReleaseChangelog.java`, `plugin/ReleaseManifest.java`, `plugin/ReleaseVersion.java`.
- `modules/gradle-plugin/src/functionalTest/.../AgenticReleaseFunctionalTest.java`: 11 tests.

**What `agenticRelease` does, in order:**
1. Parses the version: `MAJOR.MINOR.PATCH` only. A `-SNAPSHOT` gets its own message.
2. Refuses if `<releasesDir>/<version>` exists.
3. Requires the IR `compileJava` emitted to be **byte-identical to the baseline**, or tells you to run
   `atlasAccept`.
4. Reads every existing release, oldest first, and **verifies each file against its manifest's
   SHA-256**, so a released file edited later fails the next release.
5. Refuses a version not above the latest release, or an `apiMajor` below the latest release's.
6. Compares **published surfaces** with the gate's own `ContractGate.compare`. The previous release
   and the current IR are each reduced by `published(ir, commonMajor)` to their elements active at
   their own major, with lifecycle `(1, ∞)` and only the deprecation in effect at that major. The
   first release is compared with `EmptyContract.document`, so everything is `added`.
7. **Deprecation policy.** Every BREAKING `removed` difference on a field or operation is checked
   against the release history:
   - the number of releases that published the element deprecated must be at least
     `releaseMinDeprecatedReleases`;
   - `current major − deprecation major` must be at least `releaseMinDeprecatedMajors`.
   An entity's removal is the removal of its fields, which are checked individually. A violation
   fails with the element, the evidence found and the remedy.
8. Renders the changelog section from the same `Difference` list, in these sections:
   - **Breaking:** any other BREAKING difference, with the gate's reason;
   - **Removed:** with the deprecation evidence;
   - **Deprecated:** a `lifecycle` difference whose element is deprecated at the current major.
     This is the only `lifecycle` change a published surface can have;
   - **Added**, **Changed** (other compatible differences), **Informational**.
9. Writes everything to `.<version>.tmp` and moves it into place atomically.

**Tests** (`AgenticReleaseFunctionalTest`, 11, all pass):

| Test | Proves |
|---|---|
| `firstReleaseSnapshotsTheAcceptedIrAndOpenApiAndChangelog` | The snapshot's layout. `api.ir.json` is byte-identical to the baseline. The OpenAPI document has the v1 path. The changelog says "First release" and lists additions. The manifest carries a null `previous`. |
| `releasingTheSameVersionTwiceFails` | Immutability. The second run fails, and the manifest is unchanged. |
| `aVersionBelowTheLatestReleaseAndASnapshotAreRefused` | Ordering, and SNAPSHOT refusal. |
| `aContractThatWasNotAcceptedIsNotReleased` | A compatible change the gate lets through, but that was not accepted, is not released. |
| `aCompatibleChangeIsReleasedWithItsChangelog` | The exact changelog text for an added field. The `contract-diff.json` classification. The manifest's `previous`. |
| `aRemovalThatWasNeverReleasedDeprecatedFailsTheRelease` | `atlasAccept` accepted the breaking removal, but the release refuses it: "never released as deprecated". |
| `aRemovalInTheMajorItWasDeprecatedInFailsTheDefaultPolicy` | Deprecated in 1.0.0 and removed in 1.1.0: 0 majors < 1. |
| `aDeclaredRemovalInTheNextMajorAfterADeprecatedReleaseIsReleased` | The cross-major case in §2. `removedInVersion = 2`, released deprecated in 1.0.0, then 2.0.0 at major 2: the exact **Removed** entry, and `openapi-v2.json` is snapshotted. |
| `aDeprecationIsListedInTheChangelog` | The **Deprecated** entry, with its removal major and message. |
| `aReleasedFileEditedAfterReleaseIsDetected` | Digest verification of history. |
| `releasesAreByteForByteDeterministic` | Deleting a release, running `clean` and releasing again gives identical bytes for every file. |

The run used `./gradlew build -x javadoc -Porg.gradle.java.installations.paths=/usr/lib/jvm/java-17-openjdk-amd64,/usr/lib/jvm/java-21-openjdk-amd64 --continue`.
**The build passed with javadoc excluded.** This is not an unrestricted full build: `javadoc` was skipped as instructed. The plugin's functional suites pass: 46 tests, including these 11, with 0 failures and 0 skipped. The processor, runtime, CLI and demo tests in that build pass too, and no processor file was touched. JDK 17 had to be installed in the container for the toolchain.

**Known prototype limits, deliberately left for the real phase:**
- The surface reduction (`ReleaseAction.published`) lives in the plugin. It belongs in the processor
  (Q9).
- Only removals are policed. Other BREAKING differences, and a field or operation that **loses a
  channel**, are reported under **Breaking** but do not fail (Q4).
- No pre-release versions (`2.0.0-rc.1`), and no release on an older line (`1.4.1` after `2.0.0`)
  (Q1).
- The changelog is written only into the release directory (Q5).
- A module that declares no contract cannot be released, so removing a whole module's contract is
  not policed (Q4).
- No CLI or `mcp-stdio` entry point (Q7).
- The release directory holds a full IR per release. That is fine for text-diffable, reviewable
  history, but it grows linearly (Q2).

## 7. Open design questions for the owner

Each question gives options, trade-offs and a **recommendation**. `ISSUE-DRAFT.md` repeats them as
"Owner decision needed".

### Q1. Version source, format, and SNAPSHOT handling

| Option | Trade-offs |
|---|---|
| **A. `agentic { releaseVersion }`, default `project.version`, strict `MAJOR.MINOR.PATCH`** (prototype) | Works with the usual `-Pversion=` tag flow, and the CLI or CI can override it. One more property. It inherits the existing oddity that `project.version` also feeds `agentic { version }`. |
| B. Major only: the release *is* `ai.atlas.api.major` (`.atlas/releases/v2/`) | Simple, and matches the gate's unit. It cannot express "1.1 added a field", and one immutable snapshot per major forbids any further compatible change in that major. Rejected. |
| C. Derive it: `<apiMajor>.<n>.0`, with n the count of releases in the major | No configuration. It is surprising, and it cannot line up with the product version. |

**SemVer coupling.** Should `releaseVersion.major` equal `apiMajor`? Coupling makes "a breaking
change needs a new major" enforceable (see Q4). It breaks products whose version runs ahead of their
API major, e.g. product 5.x serving API v2.
- **Recommendation: A, strict `MAJOR.MINOR.PATCH`, no coupling by default.**
  - Record both the version and `apiMajor` in the manifest.
  - Add an opt-in `releaseVersionTracksApiMajor = true` that fails a release whose version major
    differs from `apiMajor`.
- **SNAPSHOT:** refuse. A SNAPSHOT's contract can still change under the same name, which is exactly
  what immutability forbids. Use `atlasAccept` and the baseline for in-flight work.
- **Pre-releases** (`-rc.1`): defer. When they are wanted, allow them as immutable releases ordered
  by SemVer precedence, which the deprecation-release count ignores.
- **Backports** (`1.4.1` after `2.0.0`): defer. The previous release is then the highest release
  *below* the version, not the latest. The prototype refuses these.

### Q2. Snapshot layout and contents

| Option | Trade-offs |
|---|---|
| A. IR only | Minimal. The IR is the source of truth and can regenerate the rest, but only with the ai-atlas version that released it. A consumer who wants the OpenAPI of 1.3.0 must rebuild. |
| **B. IR + OpenAPI of the released major + `mcp-tools.json` when generated, plus `contract-diff.json`, `CHANGELOG.md` and `release.json`** (prototype) | Every artifact a client consumes is frozen as shipped, even after a generator fix changes today's output. That is the point of "immutable released specs". It costs about 3× the bytes of A. |
| C. B plus `deprecation-manifest.json` and `api-version.properties` | Both are runtime inputs, derivable from the IR at the major, that no client reads. Noise. |

**Layout:** `.atlas/releases/<version>/` next to the baseline, committed. The alternative is a
separate artifact, such as a Maven classifier or a GitHub release asset. That is less reviewable,
and the gate needs the files offline.
- **Recommendation: B, in `.atlas/releases/<version>/`.**
- Keep full copies, not deltas: each file stays independently diffable and verifiable.
- Revisit only if a repository reports that the size matters.

### Q3. Relationship to the committed baseline, lock mode and `atlasAccept`

| Option | Trade-offs |
|---|---|
| **A. Release snapshots the baseline, and requires the emitted IR to equal it byte for byte** (prototype) | One writer of the baseline, `atlasAccept`, as today. A release is a lock-mode check at release time even for unlocked projects: every contract change is accepted before it ships. Unlocked teams run `atlasAccept` before releasing. The failure message says so. |
| B. Release **advances** the baseline: it writes the emitted IR to both the baseline and the snapshot | One command. It gives the baseline a second writer, and it releases a contract no one reviewed as a diff. That undermines lock mode's "every change goes through `atlasAccept`". |
| C. Release snapshots the emitted IR and ignores the baseline | The baseline and the releases drift apart. The gate then protects a state that was never shipped. |

**Should the gate also compare against the latest release?** No. The baseline already protects the
published major between releases. The release policy (Q4) is where release-to-release rules live.

- **Recommendation: A.** Do not change lock mode.
- `atlasAccept` keeps working after a release. It may accept, for example, a breaking change that
  the **next** release's policy then refuses. That is intended: acceptance means "reviewed",
  release means "shipped under policy".
- Document the sequence: accept, then release.

### Q4. Deprecation policy: shape and defaults

What counts as removing a published element? The prototype polices BREAKING `removed` on fields and
operations. Candidates to add:
- (a) a field losing a channel it is reachable on;
- (b) an operation losing a channel;
- (c) the whole contract disappearing, a module with no annotations.

**Policy knobs:**

| Knob | Default proposed | Why |
|---|---|---|
| `minDeprecatedReleases` | **1** | A client must have been able to see the deprecation in at least one shipped release. |
| `minDeprecatedMajors` | **1** | An element cannot be removed in the major it was deprecated in. That matches the gate's remedy text (`removedInVersion = M+1`, `apiUntil = M`). With 0 it can be removed in a minor, which only `atlasAccept` could have let through. |
| `failOnBreaking` (not in the prototype) | **true** | Any other BREAKING difference inside the same `apiMajor` fails the release. It was already accepted, so the release is the last line of defence. When `apiMajor` increased, BREAKING differences are expected and only listed. |

| Option | Trade-offs |
|---|---|
| **A. Flat knobs on `agentic { }`** (prototype: `releaseMinDeprecatedReleases`, `releaseMinDeprecatedMajors`) | Simple, and visible in the build file. Grows with each knob. |
| B. A nested `agentic { release { deprecation { minReleases; minMajors } } }` block | Groups well. More Gradle DSL code. |
| C. Per-element overrides, such as `@AgenticField(minDeprecatedReleases = 0)` | Fine-grained. Moves release policy into sources and needs an IR slot. Rejected. |

**Recommendation:**
- Use B for the real phase; the names are cosmetic.
- Defaults: 1 and 1.
- Police (a), (b) and (c) as removals.
- Add `failOnBreaking = true` scoped to "same `apiMajor`".
- Record the policy in `release.json`, as the prototype does, so a later audit knows what was
  enforced.

### Q5. Changelog format and destination

| Option | Trade-offs |
|---|---|
| **A. A per-release `CHANGELOG.md` section inside the release directory** (prototype) | Immutable with the release, and verified by digest. Readers must look in N files. |
| B. Also prepend the section to a project-level contract changelog, `.atlas/CHANGELOG.md` | One readable history, rebuildable from the release directories at any time. It is a second generated file to keep in sync, but it is derived, so it can always be regenerated. |
| C. Append to the repository's `CHANGELOG.md` | That file is human-curated, Keep a Changelog style. Machine edits would collide with hand edits and its `[Unreleased]` convention. |

**Format:** Markdown, `## <version> (API major N)`, then Breaking, Removed, Deprecated, Added,
Changed and Informational, each entry naming the element path in code. The element paths are the
gate's, so they match compile errors and `contract-diff.json`. There is no date, which keeps the
output deterministic; the date lives in git.

**Recommendation:**
- A, plus B as a regenerated aggregate, never C.
- Consider a `--changelog-only` dry-run mode that prints the section without writing anything, so
  the section can be pasted into a PR.

### Q6. CI usage: offline and deterministic?

**Proved:**
- The task reads only the class output, the baseline and `.atlas/releases/`.
- It makes no network call, and writes no timestamp, path or host.
- A repeat run gives identical bytes.

**How it runs in CI** (`.github/workflows/release.yml` builds on a pushed tag): a tag checkout
cannot commit the snapshot back.

| Option | Trade-offs |
|---|---|
| **A. Release locally in the release PR; CI verifies** | A new `agenticReleaseCheck`, part of `check`, verifies every release directory's digests. On a tag, it also fails when `.atlas/releases/<tag version>/api.ir.json` differs from the build's IR. The snapshot is reviewed like any other diff. One extra command for the releaser. |
| B. CI runs `agenticRelease` on the tag and uploads the snapshot as a release asset | Automatic. The snapshot is not in the repository, so the next release cannot diff against it offline. |
| C. CI runs it and pushes a commit | Needs write credentials in CI, and produces a commit after the tag. |

**Recommendation: A.**
- Add `agenticReleaseCheck`. It is cheap and offline, and it also catches hand edits to released
  files on every build, not only on the next release.
- Keep `agenticRelease` itself manual.

### Q7. CLI and `mcp-stdio`

- The CLI (`atlas generate`, `openapi` and `inspect`; `AtlasCli.java:41`) and the STDIO MCP server
  (`atlas_generate`, `atlas_openapi` and `atlas_inspect_services`; `AtlasMcpServer.java:49-53`)
  already pass the gate options through, and report gate failures.
- A release writes to the repository and must be deliberate.

| Option | Trade-offs |
|---|---|
| A. `atlas release <version>` in the CLI, and no MCP tool | Non-Gradle builds (Maven, Bazel) get releases. It needs the processor-side `compareReleases` (Q9) so both front-ends share it. |
| B. Also an MCP `atlas_release` tool | An agent could cut releases. Irreversible writes through an agent tool are the wrong default. |
| **C. Gradle only in Phase 5, with the engine in the processor so A is a thin follow-up** | Smallest scope. Maven users wait. |

**Recommendation: C now, A as a follow-up.** An MCP `atlas_release_preview` could come later: it
would be read-only, returning the changelog section and policy violations without writing. Never add
a writing MCP release tool.

### Q8. Does the IR format need to change?

**No.**
- Every release stores its own IR with its `irVersion`.
- `IrJson.read` migrates older versions in memory (`IrJson.java:343-370`), so released files stay
  byte-immutable across ai-atlas upgrades.
- The deprecation evidence the policy needs is already in the IR: `deprecatedSinceVersion`,
  `apiDeprecatedSince`, and the `apiMajor` of each release.
- Release metadata (version, previous version, policy, digests) belongs in `release.json`, not the
  IR. The IR describes a contract, not a shipment.

**One caveat:** the IR has no deprecation slot for *channels*. Deprecating "AI access to this field"
is not expressible, so a channel removal (Q4 a) can satisfy the policy only by `minReleases = 0`, or
by deprecating the whole field. Keep it that way until per-channel lifecycle is asked for, as Phase
4 did.

- **Recommendation:** no IR change. Version `release.json` with its own `"manifestVersion": 1`
  field, which the prototype does not have yet.

### Q9. Where the release comparison lives

| Option | Trade-offs |
|---|---|
| A. In the plugin (prototype) | Fast to prove. The CLI cannot reuse it, and the reduction duplicates a little of the projection's notion of "active". |
| **B. `ContractGate.compareReleases(ContractIr previous, ContractIr current)` in the processor**, which reduces each to its published surface and calls the same `ContractComparison` | One engine for the build, `atlasAccept`, the release and the CLI. Unit-testable next to the gate's tests. The remedy text for a removal across majors can then name the release policy instead of `removedInVersion = M+1`. |

**Recommendation: B.** Also move the deprecation-evidence lookup there, as
`ReleasePolicy.check(history, differences, policy)`, so `atlasAccept` could warn: "accepting this
removal will fail the next release".

## 8. What I would put in the phase (summary)

- `agenticRelease` with `releaseVersion` (strict SemVer, SNAPSHOT refused) and the snapshot layout in
  Q2-B.
- Release only the accepted baseline (Q3-A).
- `ContractGate.compareReleases` and `ReleasePolicy` in the processor (Q9-B).
- A policy block with defaults of 1 release and 1 major. Channel and whole-contract removals are
  policed. Breaking changes within the same major fail (Q4).
- A per-release changelog, plus a regenerated `.atlas/CHANGELOG.md` (Q5).
- `agenticReleaseCheck` in `check` for digests and tag consistency (Q6-A).
- No IR change. Version `release.json` (Q8).
- Gradle only. `atlas release` in the CLI as a follow-up, and no writing MCP tool (Q7).
