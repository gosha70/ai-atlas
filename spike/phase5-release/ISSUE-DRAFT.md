Release snapshots and changelog: immutable released contracts under a deprecation policy (epic #23, Phase 5)

Child of #23. Delivers the epic's Phase 5, §10 "Add release workflow after the Contract IR exists".
The Contract IR, the gate, lock mode and `atlasAccept` decide what the team has **accepted**. This
phase records what was **published**, and polices what may leave the contract between
publications:

```
accepted baseline  ──agenticRelease──▶  .atlas/releases/<version>/   (immutable once tagged)
                                            │
previous published release ── release comparison (processor) ──▶ changelog + release policy
```

## Why

- **The baseline has no history.** `atlasAccept` overwrites `.atlas/api.ir.json`
  (`AcceptAction.java:69`). Nothing records the contract a given version published.
- **The gate cannot be reused as-is across releases.** It compares two documents at one major, the
  baseline's (`ContractComparison.java:90`). A declared removal (`removedInVersion = M+1`) is
  invisible at M by design. Between releases 1.x and 2.0 that removal is exactly what must be
  reported and policed. The spike's `aDeclaredRemovalInTheNextMajorAfterADeprecatedReleaseIsReleased`
  shows a release-level comparison finding it.
- **Acceptance can erase obligations to published clients.** `atlasAccept` can accept a removal, or
  any other breaking change, that no published release warned about. The spike's
  `aRemovalThatWasNeverReleasedDeprecatedFailsTheRelease` shows an accepted removal of a
  never-deprecated field that only a release policy refuses. Acceptance changes the development
  baseline. It must not change what was promised to clients of a published release.
- **No changelog is derived from the contract.** The gate's differences exist only in build output
  and in `contract-diff.json`, which is overwritten on every compile.
- **The plugin conflates two versions.** `agentic { version }`, the ai-atlas **dependency** version,
  defaults to `project.version` (`AgenticPlugin.java:80-81`). Releasing a consumer as `2.0.0` with
  `-Pversion=2.0.0` silently selects ai-atlas `2.0.0`. A release workflow built on `project.version`
  makes that trap routine.

## Scope (owner decisions, 2026-09-29)

The feasibility spike is on branch `claude/phase5-release-spike`, in
`spike/phase5-release/REPORT.md`.
- **Prototype:** a Gradle task, `agenticRelease`. It snapshots the accepted IR, the OpenAPI document
  of the released major, `mcp-tools.json` when generated, `contract-diff.json`, a `CHANGELOG.md`
  section and a `release.json` manifest with SHA-256 digests to `.atlas/releases/<version>/`.
- **How it was proved:** 11 Gradle TestKit tests. They cover:
  - a repeated release;
  - SNAPSHOT and ordering refusal;
  - an unaccepted contract;
  - a compatible release with its exact changelog;
  - removals with and without the policy satisfied;
  - a deprecation entry;
  - a released file edited later;
  - byte-for-byte determinism.

  The build passed with javadoc excluded (`./gradlew build -x javadoc --continue`).
- **The prototype does not fix these decisions.** Its comparison lives in the plugin. It polices
  only field and operation removals, and treats every committed snapshot as published. The items
  below supersede it.

The owner accepted items 1–3 and 5–9 with the refinements stated. Item 4 strengthens the spike's
default. The follow-up decisions of the same day settle channel removals (item 4), offline tags and
history (item 10), development builds (item 1) and the resource manifest (item 2). No owner decision
remains open.

### 1. Version source and format
- **Decided:** `agentic { releaseVersion }`, defaulting to `project.version`, strict
  `MAJOR.MINOR.PATCH`.
  - A `-SNAPSHOT`, or any other non-release version, is refused, with its own message for a SNAPSHOT.
  - Tying the version to `apiMajor` is optional: `releaseVersionTracksApiMajor`, default false.
    When on, a release whose version major differs from `apiMajor` fails.
  - Deferred: pre-releases (`-rc.1`), and releases on an older line (`1.4.1` after `2.0.0`).
- **Decided: separate the dependency version.** A consumer's release version must never select the
  ai-atlas version.
  - `agentic { version }` defaults to **the plugin's own version**, no longer `project.version`.
    The plugin already resolves it for its mismatch message (`AgenticPlugin.java:224-232`).
  - An explicit `agentic { version }` still wins.
  - **Development builds:** when the plugin cannot determine its own version, an explicit
    `agentic { version }` is required, and the build fails with a message asking for it. It never
    falls back to the consumer's `project.version`.
  - **Shipped separately, before this phase,** as its own fix. That fix carries a changelog entry,
    because consumers relying on the old default change behaviour. Its tests cover:
    - the default: the plugin's own version, unaffected by `-Pversion` or `project.version`;
    - an explicit `agentic { version }`, which wins;
    - a development build without an explicit version, which fails.
  - This phase depends on that fix. It does not re-implement it.

### 2. Snapshot contents
- **Decided:** commit full copies, in `.atlas/releases/<version>/`, of:
  - `api.ir.json`;
  - `openapi-v<major>.json`;
  - `mcp-tools.json`, when generated;
  - `contract-diff.json`;
  - `CHANGELOG.md`;
  - `release.json`.
- **`release.json`** records:
  - its own `manifestVersion`;
  - the release version;
  - the API major;
  - the previous release;
  - the IR's `irVersion`;
  - the policy in force;
  - a SHA-256 of every other file.
- **Decided: one compilation.** The accepted IR and every generated resource come from the same
  successful compilation. A missing, unexpected or stale resource fails the release.
- **Decided: a resource manifest.** In the final round, the processor writes
  `META-INF/ai-atlas/contract-resources.json` next to the IR. It records:
  - the **effective processor configuration**, as the processor parsed it:
    - `ai.atlas.api.major`, `ai.atlas.api.basePath` and `ai.atlas.openapi.infoVersion`;
    - `ai.atlas.constraints` and `ai.atlas.projections`;
  - the artifacts that configuration requires, each with its path and SHA-256.

  The manifest is written whether the options came from the Gradle extension or from manual `-A`
  arguments in `options.compilerArgs`, where javac keeps the last value.
- **Required artifacts follow that effective configuration, never the extension's values:**
  - `api.ir.json`, always;
  - `openapi-v<major>.json`, always for a non-empty contract (`agentic { openApiEnabled }` is not
    wired to the processor today);
  - `mcp-tools.json`, exactly when the effective `ai.atlas.constraints` is true.
- **The release copies exactly the listed artifacts and verifies their digests.**
  - A listed artifact that is missing fails the release.
  - So does one whose digest differs.
  - So does any other ai-atlas contract resource in the class output: a leftover `openapi-v<N>.json`
    from another major, or `mcp-tools.json` with constraints now off.
  - It never takes "whatever is in the class output".
- **Decided: an empty contract is recorded explicitly.** javac does not run the processor for a
  compilation with no ai-atlas annotation, so the processor cannot write the manifest.
  - The plugin derives the effective configuration from `compileJava`'s final compiler arguments,
    including manual `-A` arguments, with javac's last-wins rule.
  - It builds the empty IR with the processor's `EmptyContract`.
  - It records `"contract": "empty"`, with `api.ir.json` as the only expected artifact.
  - The release fails when the class output still holds any ai-atlas contract resource, which must
    be stale output from an earlier compilation.
  - So a complete module removal stays releasable without ever accepting stale output.
- The unversioned alias `openapi.json`, `deprecation-manifest.json` and `api-version.properties` are
  not snapshotted.

### 3. Relationship to the baseline, lock mode and `atlasAccept`
- **Decided:** creating a release never accepts a change.
  - `agenticRelease` never writes the baseline, so `atlasAccept` stays its only writer.
  - Lock mode is unchanged.
- **Equality:** the fresh contract is compared with the accepted baseline by **canonical contract
  equality**: both are parsed and migrated in memory, then compared as values. Any difference fails
  and names `atlasAccept`. The snapshot preserves the **baseline's bytes** as committed.
- **The gate keeps comparing against the baseline**, not the latest release. Release-to-release
  rules live in the release policy (item 4).

### 4. Release policy
The obligations are to **published** clients, so they hold whatever `atlasAccept` accepted.

- **Decided — removals:** a removal passes only if the element was:
  - published **deprecated** in at least one earlier **published** release (item 10), and
  - its deprecation major is at least one API major below the major of the release that removes it.

  Both are defaults: `minDeprecatedReleases = 1`, `minApiMajorAdvance = 1`.
- **Decided — what counts as a removal**, comparing each release at its own published API major:
  - a field or operation the previous published release published and this one does not;
  - **a field losing a channel** on which its entity is reachable;
  - an **operation losing a channel**;
  - **the whole contract disappearing** (see "Empty contracts" below).
- **Decided — every other breaking change within the same API major fails the release by
  default,** including one already accepted with `atlasAccept`. Across an API-major advance,
  breaking changes are listed, not failed.
- **Relaxing either rule** needs an explicit override in the policy block, recorded in
  `release.json`.
- **A violation** names:
  - the element path;
  - the evidence found, such as "never published as deprecated" or "deprecated since major 1,
    removed in major 1";
  - the remedy.
- **Shape:** an `agentic { release { policy { … } } }` block.
- **Decided — channel removals.** Fields have no per-channel lifecycle (Phase 4 item 5), so
  whole-element deprecation can qualify a channel removal.
  - **A field losing channel C** in a release at API major N passes only when both hold:
    - At least `minDeprecatedReleases` **tagged** releases each had the field, at that release's own
      API major, **active**, **deprecated** (`deprecatedSinceVersion` in effect), and **visible on
      C**. Visible means the field lists C and its entity is reachable on C from an operation active
      on C, as the gate's reachability rule computes it.
    - `N − deprecatedSinceVersion ≥ minApiMajorAdvance`.
  - **An operation losing channel C** follows the equivalent rule. It needs tagged releases where
    the operation was active, deprecated (`apiDeprecatedSince` in effect) and listed C, and the same
    API-major advance.
  - A release where the element was deprecated but **not** visible on C earns no credit for
    removing C.
  - Channel-specific deprecation is **not** promised in this phase.
- **Empty contracts — decided:** a release may represent the complete removal of a module's
  contract.
  - The processor's `EmptyContract` document is released like any other contract, with the resource
    manifest recorded explicitly (item 2).
  - Every element it removes is subject to the removal policy.
  - Without this, a module's disappearance could never be recorded.

### 5. Changelog
- **Decided:** an immutable `CHANGELOG.md` per release, plus a deterministic `.atlas/CHANGELOG.md`,
  newest first. The repository's own `CHANGELOG.md` is never touched.
- **Format:** a Markdown section, `## <version> (API major N)`, then Breaking, Removed, Deprecated,
  Added, Changed and Informational.
  - Each entry is one release-comparison difference, named by the gate's element path.
  - No date.
- **Validation:** the aggregate is the concatenation of the per-release sections in release order.
  The history check (item 6) fails when it differs.

### 6. CI
- **Decided — split ordinary validation from release validation.**
  - **`agenticReleaseHistoryCheck`, under `check`.** It runs offline on every build, on any branch
    and in source archives, with no tag needed. It checks:
    - every release directory against its manifest;
    - the ordering and `previous` chain;
    - `.atlas/CHANGELOG.md` against the history.
  - **`agenticReleaseVerify`, in the release job only**, or when configured explicitly. It checks
    that the tag's version has a snapshot, that the snapshot equals what the tagged build produces,
    and the trusted-history rule in item 8.
- **Flow:**
  1. Generate the snapshot with `agenticRelease` and commit it in the release PR.
  2. Tag the merged commit with the configured tag name.
  3. The release job runs `agenticReleaseVerify` on the tag.
- **History in CI.** Any job that runs `agenticRelease` or `agenticReleaseVerify` first fetches the
  history and tags those tasks need, for example `actions/checkout` with `fetch-depth: 0` and
  `fetch-tags: true`. The checks themselves then run offline. `agenticReleaseHistoryCheck` needs no
  git at all.
- The task makes no network, database or model call and writes no timestamp, path or host.
  Re-releasing the same inputs gives byte-identical files.

### 7. Entry points
- **Decided:** Gradle first. CLI support (`atlas release`) is deferred. Writing a release stays
  outside MCP.
- A read-only MCP preview is not in this phase.

### 8. Formats and immutability
- **Decided:** no IR format change. Each release keeps its own `irVersion`, and `IrJson.read`
  migrates older ones in memory (`IrJson.java:343-370`). `release.json` is versioned independently.
- **Digests detect accidental modification; they do not make files immutable.**
  - `agenticReleaseVerify` rejects any modification or deletion of a snapshot that was **published**
    before, relative to trusted history: the tree of the latest published release tag.
  - The ordinary history check keeps the digest check.

### 9. Where the comparison lives
- **Decided:** a dedicated release comparison in the processor, transport-independent:
  - `ReleaseComparison.compare(previous, current)` reduces each IR to what it published at its own
    API major, then runs the gate's `ContractComparison`;
  - `ReleasePolicy.check(history, differences, policy)` sits next to it.
- **The plugin keeps the filesystem and Gradle work:** resources, snapshots, digests, git and
  staging.
- **Ordinary gate semantics are unchanged:** `ContractGate.check` and `compare`, their messages, and
  lock mode.
- **Tests run each release at its own published API major**, and cover:
  - nested entity references;
  - channel projections and reachability;
  - renamed identifiers: `dtoName`, `toolName`, `operationId`, REST path;
  - lifecycle boundaries: `sinceVersion`, `removedInVersion`, `apiUntil`, and deprecation starting at
    exactly the release's major;
  - an empty contract.

### 10. Publication semantics and history
- **Decided:** deprecation credit comes only from **tagged** releases. A snapshot folder added in
  the same PR earns none.
- **Decided — local git tags, with an explicit naming convention.**
  - The tag name is `agentic { release { tagName = "v{version}" } }` (default `v{version}`), and it
    is recorded in each `release.json`.
  - Only that exact name counts. `1.0.0` does not stand in for `v1.0.0`.
  - Tags are read from the local repository, offline, with an annotated tag peeled to its commit.
- **Decided — a tag must be proved.** A matching tag name is not enough. A tag qualifies only when
  all of these hold:
  - its commit's tree contains `.atlas/releases/<version>/release.json` and
    `.atlas/releases/<version>/api.ir.json`;
  - those files match the manifest's digests;
  - they are byte-identical to the working copy's;
  - the commit is an ancestor of `HEAD`.

  A tag that fails any of these fails the release or verification, naming the tag and the check.
- **Pending releases.** A committed snapshot whose tag does not exist yet is **pending**.
  - It is part of the history chain, but earns no credit.
  - Only the newest snapshot may be pending.
  - `agenticRelease` refuses to create a release while one is pending.
- **Decided — a first release is distinguished from incomplete history:**

  | State | Outcome |
  |---|---|
  | No snapshot directories and no tag matching the convention | **A genuine first release: valid.** No git history is needed beyond the repository itself. |
  | Snapshots exist, and every one except at most the newest is backed by a proved tag | Valid |
  | Any older snapshot without its tag, a matching tag without its snapshot, a shallow clone (`git rev-parse --is-shallow-repository`), or no git repository while snapshots exist | **Fail: the published history needed for validation is unavailable.** The message names what is missing and says to fetch tags and full history. |
- **Where this applies.** `agenticRelease` and `agenticReleaseVerify` apply these rules.
  `agenticReleaseHistoryCheck` (item 6) stays git-free, so ordinary branches and source archives
  still build.

## Out of scope
- Pre-release versions, and releases on an older line (item 1).
- An `atlas release` CLI command (item 7), and any MCP tool that writes.
- Per-channel lifecycle and channel-specific deprecation (item 4).
- Separating `agentic { version }` from `project.version` (item 1). It ships first, as its own fix,
  and this phase depends on it.
- Publishing snapshots outside the repository (Maven classifiers, release assets).
- Collection safety and REST metadata (the rest of Phase 5). **Qualified tool names** (2.0).

## Acceptance criteria
- [ ] Prerequisite, shipped separately: `agentic { version }` defaults to the plugin's own version,
      and never falls back to `project.version`. An explicit value wins. A development build
      without an explicit value fails. A consumer released as `2.0.0` keeps its ai-atlas dependency
      version. The fix carries a changelog entry.
- [ ] `agenticRelease` writes `.atlas/releases/<version>/` with the files in item 2. `release.json`
      records `manifestVersion`, the version, the API major, the previous release, `irVersion`, the
      policy and a digest of every other file.
- [ ] The processor writes `contract-resources.json` with its effective configuration and the
      digests of the artifacts that configuration requires. The release copies exactly those
      artifacts. A missing, mismatched or unlisted contract resource fails. Tests set the options
      through manual `-A` arguments as well as through the extension.
- [ ] An empty contract is recorded explicitly (`"contract": "empty"`), from `compileJava`'s
      effective arguments, and releases when the class output holds no stale contract resource.
- [ ] A fresh contract not canonically equal to the accepted baseline fails and names
      `atlasAccept`. The snapshot keeps the baseline's bytes. Releasing never writes the baseline.
- [ ] Releasing an existing version fails and changes no file. A SNAPSHOT or
      non-`MAJOR.MINOR.PATCH` version, a version not above the latest release, an `apiMajor` below
      the latest release's, and a release while another is pending all fail.
- [ ] `ReleaseComparison` in the processor reuses `ContractComparison` and compares each release at
      its own published API major. `ContractGate` behaviour and messages are unchanged.
- [ ] A removal fails unless the element was published deprecated in at least one tagged release,
      at least one API major before the removing release. This covers fields, operations and an
      empty contract.
- [ ] A field or operation losing channel C passes only with enough tagged releases in which it was
      active, deprecated and visible on C, plus the API-major advance. A release in which it was
      deprecated but not visible on C earns no credit.
- [ ] Every other breaking change within the same API major fails by default, including one
      accepted with `atlasAccept`. Overrides are explicit and recorded.
- [ ] Changelog entries come only from the release comparison. Tests pin the exact text for added,
      deprecated, removed and breaking entries. `.atlas/CHANGELOG.md` is regenerated and validated
      against history, and the root `CHANGELOG.md` is never touched.
- [ ] `agenticReleaseHistoryCheck` runs under `check` offline, without git.
      `agenticReleaseVerify` checks the tag's snapshot and rejects changes to published snapshots
      relative to trusted history.
- [ ] Tags follow the configured `tagName`. A tag qualifies only when its commit is an ancestor of
      `HEAD` and contains the matching snapshot and manifest, byte-identical to the working copy.
- [ ] A new project with no snapshots and no matching tags releases. Any of these fails, naming what
      is missing: an untagged older snapshot, a tag without its snapshot, a shallow clone, or
      snapshots without a git repository.
- [ ] Releasing the same inputs twice gives byte-identical files.
- [ ] Comparison tests cover nested references, projections, renamed identifiers, lifecycle
      boundaries and the empty contract, each at its release's own published API major.
- [ ] Docs cover the task, the layout, the version rules and the dependency-version default, the
      policy and its overrides, publication semantics, the accept-then-release sequence, and the CI
      recipe.
- [ ] No network, database, model call or live service anywhere in the release or its checks.
