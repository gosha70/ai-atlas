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
default. **Owner decision needed** marks what is still open.

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
  - This is a behaviour change for consumers who relied on the old default, so it goes in the
    changelog.
  - It can ship ahead of this phase as its own fix.
- **Owner decision needed:** what happens when the plugin's own version cannot be determined (a
  development build).
  - Recommended: fail with a message asking for an explicit `agentic { version }`.
  - Do not fall back to `project.version`.

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
  successful compilation. A missing or stale resource fails the release.
  - The processor records the resources it wrote in this compilation, with their digests.
  - The release copies exactly those files and verifies them. It never takes "whatever is in the
    class output".
  - The OpenAPI document is always required: `agentic { openApiEnabled }` is not wired to the
    processor today, so every compilation emits one.
  - `mcp-tools.json` is required exactly when `ai.atlas.constraints` is on.
  - The unversioned alias `openapi.json`, `deprecation-manifest.json` and `api-version.properties`
    are not snapshotted.

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
- **Owner decision needed — channel removals.** Fields have no per-channel lifecycle (Phase 4
  item 5), so no declaration deprecates "AI access to this field".
  - **Recommended:** deprecating the **whole field**, with `deprecatedSinceVersion`, qualifies a
    later channel removal. It warns every client of the field, including that channel's, so the
    obligation is met.
  - Channel-specific deprecation is not promised in this phase.
  - **Alternative:** channel removals always fail unless the policy is overridden. That is stricter,
    and leaves no lifecycle path until per-channel lifecycle exists.
- **Empty contracts — decided:** a release may represent the complete removal of a module's
  contract. The processor's `EmptyContract` document is released like any other contract, and every
  element it removes is subject to the removal policy. Without this, a module's disappearance could
  never be recorded.

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
  2. Tag the merged commit.
  3. The release job runs `agenticReleaseVerify` on the tag.
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

### 10. Publication semantics
- **Owner recommendation, to confirm:** deprecation credit comes only from **tagged** releases.
  A folder added in the same PR does not earn it.
- **Recommended mechanism:**
  - a release counts as published when the tag `v<version>` exists in the repository (the tag
    pattern is configurable);
  - a committed snapshot without its tag is **pending**: it is part of the history chain, but earns
    no deprecation credit;
  - `agenticRelease` refuses to create a release while another release is still pending, so at most
    one is pending at a time.
- **Owner decision needed — how the plugin learns about tags offline:**
  - Recommended: read local git tags (`git tag --list`), and fail with a clear message when the
    repository has no tags because it is a shallow clone; CI must fetch tags.
  - Alternative: an explicit `publishedVersions` list in the build, which is manual and can drift.
  - Alternative: a trusted `published.json` updated by the release job, which needs CI write access.

## Out of scope
- Pre-release versions, and releases on an older line (item 1).
- An `atlas release` CLI command (item 7), and any MCP tool that writes.
- Per-channel lifecycle and channel-specific deprecation (item 4).
- Publishing snapshots outside the repository (Maven classifiers, release assets).
- Collection safety and REST metadata (the rest of Phase 5). **Qualified tool names** (2.0).

## Acceptance criteria
- [ ] `agentic { version }` defaults to the plugin's own version, never `project.version`. A test
      releases a consumer as `2.0.0` and asserts the ai-atlas dependency version is unchanged.
- [ ] `agenticRelease` writes `.atlas/releases/<version>/` with the files in item 2. `release.json`
      records `manifestVersion`, the version, the API major, the previous release, `irVersion`, the
      policy and a digest of every other file.
- [ ] Every snapshotted resource comes from the same compilation as the IR. A missing, stale or
      unrecorded resource fails the release.
- [ ] A fresh contract not canonically equal to the accepted baseline fails and names
      `atlasAccept`. The snapshot keeps the baseline's bytes. Releasing never writes the baseline.
- [ ] Releasing an existing version fails and changes no file. A SNAPSHOT or
      non-`MAJOR.MINOR.PATCH` version, a version not above the latest release, an `apiMajor` below
      the latest release's, and a release while another is pending all fail.
- [ ] `ReleaseComparison` in the processor reuses `ContractComparison` and compares each release at
      its own published API major. `ContractGate` behaviour and messages are unchanged.
- [ ] A removal fails unless the element was published deprecated in at least one tagged release,
      at least one API major before the removing release. This covers fields, operations, channel
      losses and an empty contract.
- [ ] Every other breaking change within the same API major fails by default, including one
      accepted with `atlasAccept`. Overrides are explicit and recorded.
- [ ] Changelog entries come only from the release comparison. Tests pin the exact text for added,
      deprecated, removed and breaking entries. `.atlas/CHANGELOG.md` is regenerated and validated
      against history, and the root `CHANGELOG.md` is never touched.
- [ ] `agenticReleaseHistoryCheck` runs under `check` offline, without tags.
      `agenticReleaseVerify` checks the tag's snapshot and rejects changes to published snapshots
      relative to trusted history.
- [ ] Releasing the same inputs twice gives byte-identical files.
- [ ] Comparison tests cover nested references, projections, renamed identifiers, lifecycle
      boundaries and the empty contract, each at its release's own published API major.
- [ ] Docs cover the task, the layout, the version rules and the dependency-version default, the
      policy and its overrides, publication semantics, the accept-then-release sequence, and the CI
      recipe.
- [ ] No network, database, model call or live service anywhere in the release or its checks.
