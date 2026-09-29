Release snapshots and changelog: immutable released contracts under a deprecation policy (epic #23, Phase 5)

Child of #23. Delivers the epic's Phase 5, §10 "Add release workflow after the Contract IR exists".
The Contract IR, the gate, lock mode and `atlasAccept` decide what the team has **accepted**. This
phase records what was **shipped**, and polices what may leave the contract between shipments:

```
accepted baseline  ──agenticRelease──▶  .atlas/releases/<version>/   (immutable)
                                            │
previous release ─── compared with the gate's own comparison ──▶ changelog + deprecation policy
```

## Why

- **The baseline has no history.** `atlasAccept` overwrites `.atlas/api.ir.json`
  (`AcceptAction.java:69`). Nothing records the contract a given version shipped, so "what did 1.3.0
  publish?" can be answered only from git archaeology on a file that also moves between releases.
- **The gate cannot be reused as-is across releases.** It compares two documents at one major, the
  baseline's (`ContractComparison.java:90`). A declared removal (`removedInVersion = M+1`) is
  invisible at M by design. Between releases 1.x and 2.0 that removal is exactly what must be
  reported and policed. The spike's `aDeclaredRemovalInTheNextMajorAfterADeprecatedReleaseIsReleased`
  shows the release-level comparison finding it.
- **`atlasAccept` can accept a removal no client was warned about.** The gate's remedy for a
  removal is a lifecycle declaration or `atlasAccept`. Nothing checks that a *shipped* release ever
  carried the deprecation. The spike's `aRemovalThatWasNeverReleasedDeprecatedFailsTheRelease`
  shows an accepted removal of a never-deprecated field that only a release policy refuses.
- **No changelog is derived from the contract.** The gate's differences exist only in build output
  and `contract-diff.json`, which is overwritten on every compile.

## Scope

The feasibility spike is on branch `claude/phase5-release-spike`, in
`spike/phase5-release/REPORT.md`.
- **Prototype:** a Gradle task, `agenticRelease`. It snapshots the accepted IR, the OpenAPI document
  of the released major, `mcp-tools.json` when generated, `contract-diff.json`, a `CHANGELOG.md`
  section and a `release.json` manifest with SHA-256 digests to `.atlas/releases/<version>/`.
- **How it was proved:** 11 Gradle TestKit tests cover a repeated release, SNAPSHOT and ordering
  refusal, an unaccepted contract, a compatible release with its exact changelog, removals with and
  without the policy satisfied, a deprecation entry, tampering with a released file, and
  byte-for-byte determinism.
- **Same engine:** each IR is reduced to what it published at its own major, and then compared by
  `ContractGate.compare`. No classification is re-implemented.

Each item below carries the spike's recommendation. **Owner decision needed** marks what is still
open.

### 1. Version source and format
- **Owner decision needed.** Recommended: `agentic { releaseVersion }`, defaulting to
  `project.version`, strict `MAJOR.MINOR.PATCH`.
  - A `-SNAPSHOT` is refused with its own message: its contract can still change under the same
    name.
  - Both the version and `apiMajor` are recorded in `release.json`.
- **SemVer coupling:** off by default, with an opt-in `releaseVersionTracksApiMajor = true`. A
  product at 5.x may serve API v2.
- **Deferred:** pre-releases (`-rc.1`) and releases on an older line (`1.4.1` after `2.0.0`).
- **Alternatives:**
  - major-only releases (`v2/`), which cannot express a compatible minor and freeze a major after
    its first release;
  - a derived `<apiMajor>.<n>.0`, which cannot line up with the product version.
- **Note:** `project.version` already defaults `agentic { version }`, the ai-atlas dependency
  version (`AgenticPlugin.java:80-81`). Document the difference.

### 2. Snapshot layout and contents
- **Owner decision needed.** Recommended: `.atlas/releases/<version>/`, committed, holding:
  - `api.ir.json`, byte-identical to the accepted baseline;
  - `openapi-v<major>.json`;
  - `mcp-tools.json`, when `ai.atlas.constraints` generated it;
  - `contract-diff.json`, in the gate's format;
  - `CHANGELOG.md`;
  - `release.json` (version, `apiMajor`, `irVersion`, previous version, policy, digests).
- Full copies, not deltas. Written to a temporary sibling directory and moved into place atomically.
- **Alternatives:**
  - IR only, which regenerates OpenAPI only with the ai-atlas version that released it;
  - adding `deprecation-manifest.json` and `api-version.properties`, which are derivable and read by
    no client;
  - storing snapshots outside the repository, which is not offline and not reviewable.

### 3. Relationship to the baseline, lock mode and `atlasAccept`
- **Owner decision needed.** Recommended: a release snapshots the baseline, and **requires the
  compilation's IR to equal it byte for byte**. Otherwise it fails, naming `atlasAccept`.
  - The release never writes the baseline, and `atlasAccept` stays its only writer.
  - Lock mode is unchanged. For unlocked projects, a release is a lock check at release time.
- **The gate keeps comparing against the baseline**, not the latest release. Release-to-release
  rules live in the release policy (item 4).
- **Alternatives:**
  - the release advances the baseline, which adds a second writer and ships an unreviewed diff;
  - the release ignores the baseline, which lets the baseline and releases drift apart.

### 4. Deprecation policy
- **Owner decision needed.** Recommended: an `agentic { release { deprecation { … } } }` block,
  recorded in `release.json`, with these defaults:
  - `minReleases = 1`: the element was published **deprecated** in at least one earlier release;
  - `minMajors = 1`: at least one major lies between its declared deprecation major and the release
    that removes it.
- **What counts as a removal:**
  - a field or operation that the previous release published at its major and this release does not
    publish at its major (spike);
  - also, recommended: a field or operation losing a channel it is reachable on;
  - also, recommended: a module's whole contract disappearing.
- **Other breaking differences** (**owner decision needed**): recommended `failOnBreaking = true`
  when the release has the **same** `apiMajor` as the previous one. They were accepted with
  `atlasAccept`, so the release is the last line of defence. Across a major bump they are listed,
  not failed.
- **A violation** names the element path, the evidence found ("never released as deprecated", or
  "deprecated since major 1 in 1 release(s), removed in major 1") and the remedy.
- **Alternatives:** flat `agentic { releaseMin… }` knobs (the spike); per-element overrides on the
  annotations, which need an IR slot (rejected).
- **Channel deprecation** is not expressible, because the IR has no per-channel lifecycle (Phase 4
  item 5). A channel removal satisfies the policy only if the whole field was deprecated, or with
  `minReleases = 0`.

### 5. Changelog
- **Owner decision needed.** Recommended format: a Markdown section, `## <version> (API major N)`,
  then Breaking, Removed, Deprecated, Added, Changed and Informational. Each entry is one gate
  difference, named by the gate's element path.
  - Removed entries carry their deprecation evidence.
  - Deprecated entries carry the removal major and the message.
  - No date, so the output is deterministic.
- **Destination:** the release directory's `CHANGELOG.md`, plus `.atlas/CHANGELOG.md`, an aggregate
  regenerated from all release directories, newest first. **Never** the repository's hand-curated
  `CHANGELOG.md`.
- **Alternative:** append to the root `CHANGELOG.md`, which collides with hand edits and its
  `[Unreleased]` convention.

### 6. CI, offline and determinism
- **Decided by the spike:** the task reads only the class output, the baseline and
  `.atlas/releases/`. It makes no network, database or model call, and writes no timestamp, path or
  host. Re-releasing gives byte-identical files (test `releasesAreByteForByteDeterministic`).
- **Owner decision needed.** Recommended: run `agenticRelease` locally, in the release PR, and add
  `agenticReleaseCheck` to `check`. It:
  - verifies every release directory against its digests on every build;
  - when a release version is given (for example, CI's tag), fails if
    `.atlas/releases/<version>/api.ir.json` differs from the build's IR.
- **Alternatives:**
  - CI runs the release on the tag and uploads the snapshot as an asset, so the next release cannot
    diff against it offline;
  - CI pushes a commit after the tag, which needs write credentials.

### 7. CLI and `mcp-stdio`
- **Owner decision needed.** Recommended: Gradle only in this phase, with the engine in the
  processor (item 9), so that `atlas release <version>` for Maven and other builds is a thin
  follow-up.
- **No MCP tool that writes a release.** A read-only `atlas_release_preview`, returning the
  changelog section and any policy violations, is a possible later addition.

### 8. IR format
- **Decided: no IR change.** Each release keeps its own `irVersion`, and `IrJson.read` migrates
  older ones in memory (`IrJson.java:343-370`), so released files stay byte-immutable across
  upgrades.
- Release metadata goes in `release.json`, which gets its own `manifestVersion: 1`.

### 9. Where the comparison lives
- **Decided:** `ContractGate.compareReleases(previous, current)` in the processor.
  - It reduces each IR to what it published at its own major: the elements active there, with only
    the deprecation in effect there.
  - It then runs the same `ContractComparison`.
- `ReleasePolicy.check(history, differences, policy)` sits next to it.
- **Optional:** `atlasAccept` warns when the removal it accepts would fail the next release's
  policy.

## Out of scope
- Pre-release versions and releases on an older line (item 1).
- An `atlas release` CLI command (item 7), and any MCP tool that writes.
- Per-channel lifecycle and channel deprecation (Phase 4 item 5).
- Publishing snapshots outside the repository (Maven classifiers, release assets).
- Collection safety and REST metadata (the rest of Phase 5). **Qualified tool names** (2.0).

## Acceptance criteria
- [ ] `agenticRelease` writes `.atlas/releases/<version>/` with `api.ir.json`
      (byte-identical to the baseline), `openapi-v<major>.json`, `mcp-tools.json` when generated,
      `contract-diff.json`, `CHANGELOG.md` and `release.json` with a SHA-256 of every other file.
- [ ] Releasing an existing version fails and changes no file.
- [ ] A SNAPSHOT or non-`MAJOR.MINOR.PATCH` version, a version not above the latest release, and an
      `apiMajor` below the latest release's fail.
- [ ] A contract whose emitted IR differs from the baseline is not released, and the message names
      `atlasAccept`.
- [ ] The release comparison is `ContractGate.compareReleases`, which reuses `ContractComparison`.
      Declared removals across a major bump are reported as removals.
- [ ] Changelog entries are produced from that comparison only. Tests pin the exact text for an
      added, deprecated and removed element.
- [ ] Removing a published field or operation, or a channel it is reachable on, without satisfying
      the configured policy fails the release. The message names the element, the evidence and the
      remedy.
- [ ] With the same `apiMajor` as the previous release, other breaking differences fail under
      `failOnBreaking` (default true).
- [ ] A released file edited after release fails `agenticReleaseCheck` and the next release.
- [ ] Releasing the same inputs twice gives byte-identical files.
- [ ] `.atlas/CHANGELOG.md` is regenerated from all releases, newest first. The root `CHANGELOG.md`
      is never touched.
- [ ] Docs cover the task, the layout, the version rules, the policy and its defaults, the
      accept-then-release sequence, and the CI recipe.
- [ ] No network, database, model call or live service anywhere in the release or its check.
