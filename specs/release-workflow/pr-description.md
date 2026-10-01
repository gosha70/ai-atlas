# PR #62: Contract releases (proposed description, not published)

This file is a proposed PR description, drafted by the Phase G build agent for the lead's review.
It is **not** posted to GitHub (no `gh` commands were run). The lead fills in the `Validation`
section's placeholders before merge.

## Summary

Expands `agenticRelease` (already shipped at `bb98010` as a single Gradle task) to the full scope the
owner settled on 2026-09-29 (spike `3806e5d`, Phase 5 §10): immutable, **git-tagged** release
snapshots with a proved publication history, a contract-resources manifest that bounds exactly what
a release inspects, channel-aware deprecation credit restricted to tag-proved releases, and a split
between an offline internal-consistency check (`agenticReleaseHistoryCheck`, under `check`) and a
git-aware tag verification (`agenticReleaseVerify`, for the CI tag build).

See [`specs/release-workflow/spec.md`](spec.md) for the full acceptance criteria and the owner's four
rounds of decisions; [`plan.md`](plan.md) for the design and gap analysis; [`tasks.md`](tasks.md) for
the task breakdown this PR followed.

## Scope

In scope (see `spec.md` "Owner decisions"):
- Git access restricted to the Gradle plugin, offline and read-only, by shelling out to `git` (no
  JGit dependency).
- Snapshot writing, digest computation and staging relocated from the processor to the plugin
  (Phase F, draft item 9).
- `agenticReleaseVerify` requiring the configured release tag to resolve to `HEAD`.
- A shallow clone failing git-aware validation (`agenticRelease`, `agenticReleaseVerify`) while
  `agenticReleaseHistoryCheck` stays usable offline.
- The reserved contract-resources set, the two-path split (compile/accept writes; release only
  validates), the dependency boundary (no task dependency that could regenerate what a release
  validates), and the snapshot-equals-the-build comparison (IR canonical, artifacts byte for byte,
  configuration structural) — spec's second, third and fourth rounds.

Out of scope (unchanged by this PR): the compatibility gate itself (`atlasContractCheck`,
`atlasAccept`, lock mode), channel projections, constraints, collection safety — all already shipped
and only consumed here (e.g. the release validates `ai.atlas.collections` in its recorded
configuration, but does not change its behaviour).

## Acceptance criteria

| # | Criterion (abbreviated) | Status | Closed by |
|---|---|---|---|
| AC1 | `agentic { version }` defaults to the plugin's own version, never `project.version` | Done (PR #59, merged before this PR) | `DependencyVersionTest`, `DependencyVersionFunctionalTest` |
| AC2 | `agenticRelease` writes `.atlas/releases/<version>/`; `release.json` records manifest/version/apiMajor/previous/irVersion/policy/digests | Done | C4, D1 — `ReleaseSnapshots.release`, `ReleaseManifest` |
| AC3 | Processor writes `contract-resources.json` with effective config and artifact digests; `-A` and extension both tested | Done | C2, C6 — `ContractResources`, `ResourceRecorder` (processor), `ReleaseResourcesFunctionalTest` |
| AC4 | Only the reserved set is inspected; a listed-missing/mismatched or unlisted-in-set file fails | Done | C4, C6 — `ContractResources.isReserved`, `ClassOutputResources.validate` |
| AC5 | Empty contract recorded explicitly (`"contract": "empty"`) from `compileJava`'s effective args | Done | C3a, C3b, C6 — `EmptyContractResourcesAction` |
| AC6 | A fresh contract not canonically equal to the baseline fails, naming `atlasAccept`; snapshot keeps baseline bytes | Done | C5 — `ReleaseSnapshots.accepted` |
| AC7 | Releasing an existing version / SNAPSHOT / non-MAJOR.MINOR.PATCH / lower apiMajor / pending-blocked release all fail, no file changed | Done | D4 — `ReleaseSnapshots.release` |
| AC8 | `ReleaseComparison` in the processor reuses `ContractComparison`; `ContractGate` behaviour/messages unchanged | Done | A2 |
| AC9 | A removal needs deprecated-and-published evidence at least one API major before removal | Done | B1, D4 — `ReleasePolicy.check`, `ReleaseSnapshots.release`'s pending refusal |
| AC10 | Channel-loss credit needs enough tagged releases where the element was active, deprecated and visible on the lost channel | Done | B1 — `ChannelRemovalCreditTest`, reviewer regression `ReviewChannelCreditTest` |
| AC11 | Channel visibility is the gate's channel-aware reachability, shared code, at each tagged release's own major | Done | A1, B1 — `ChannelReachability` |
| AC12 | Every other breaking change within a major fails by default, overrides explicit | Done (`bb98010`), kept green | B2 (policy DSL rename) |
| AC13 | Changelog entries come only from the release comparison; `.atlas/CHANGELOG.md` regenerated and validated; root `CHANGELOG.md` untouched | Done | E1 — `ReleaseChangelog`, `ReleaseSnapshotHistory.checkConsistency` |
| AC14 | `agenticReleaseHistoryCheck` offline under `check`; a consistent rewrite passes it but fails `agenticReleaseVerify` | Done | E1, E2, E3 — `AgenticReleaseHistoryCheck`, `AgenticReleaseVerify`, `ReleaseVerifyFunctionalTest` |
| AC15 | Tags follow `tagName`; a tag qualifies only as an ancestor of `HEAD` with a byte-identical, matching snapshot | Done | D1–D6 — `TagName`, `PublishedHistory.proveTag` |
| AC16 | A new project with no history releases; an untagged older snapshot / tag-without-snapshot / shallow clone / snapshot-without-repository each fail, naming what's missing | Done | D3, D6 — `PublishedHistory.verify`, `ReleaseHistoryFunctionalTest` |
| AC17 | Releasing the same inputs twice gives byte-identical files | Done (`bb98010`), kept green | F1 |
| AC18 | Comparison coverage: nested refs, projections, renames, lifecycle boundaries, empty contract, each at its own major | Done | B3 — `ReleaseComparisonTest`/`ReleaseComparisonBoundaryTest` |
| AC19 | Docs cover the task, layout, version rules and dependency-version default, policy/overrides, publication semantics, accept-then-release, CI recipe | Done | G1 (this PR) — `docs/contract-releases.md`, `docs/contract-governance.md`, `docs/processor-internals.md` |
| AC20 | No network, database, model call or live service in the release or its checks | Done (`bb98010`); re-checked | G2 (lead) — `git` calls are local, read-only, offline |

## Phases and commits

(`master..feature/release-workflow`, oldest first; see `git log --reverse --oneline master..HEAD`
for the authoritative list — abbreviated here by phase.)

- **Pre-expansion** (built from the earlier `d15fe77` draft, before the owner expanded scope to
  `3806e5d`): `b1e59da` docs, `eabb3a8` `agenticReleaseCheck` + `check`, `5945039` `agenticRelease`
  task, `a084854` release engine (`compareReleases`, policy, changelog, manifest). Superseded in
  shape (not in intent) by Phases A–F below: `agenticReleaseCheck` is replaced by
  `agenticReleaseHistoryCheck`/`agenticReleaseVerify`, `compareReleases` by `ReleaseComparison`, the
  `deprecation { }` DSL by `policy { }`.
- **Phase A — headroom refactors, no behaviour change**: `f4f4a43` (A1, shared channel
  reachability), `43f5f11` (A2, `ReleaseComparison.compare`), `0382bd6` (A3, `ReleaseTasks`
  extraction), `8f3441c` (A4, `ReleaseHistory` split), `6cc25bd` (A5, `EffectiveOptions`).
- **Phase B — channel-aware credit**: `4a50513` (B1, published-only, channel-aware evidence),
  `1f52f17` (B2, `policy { }` rename), `78835ee` (B3, comparison coverage per release's own major).
- **Phase C — the contract-resources manifest**: `7c04a78` (C1, reserved paths/manifest format),
  `e9a5d37` (C2, processor writes `contract-resources.json`), `8041f02`/`dbfaf2e` (C3a/C3b, empty
  contract from `compileJava`), `468ba98` (empty-contract linkage-failure surfaces from `compileJava`,
  not `atlasContractCheck`), `af13fdb`/`4c34b36` (C4, the release validates, never writes, the
  manifest), `18f99a3`/`c8431e5` (C5/C6, canonical equality, resource functional tests),
  `626cdf8`/`9d283f1` (the compile/accept-vs-release two-path split, owner-corrected),
  `878db54`/`cd8867e`/`b4dbeb3`/`c34277f` (the dependency-boundary fixes: no task dependency that
  could regenerate what a release validates), `ff609ba`.
- **Phase D — tags, proof, pending, history**: `3e3ee70` (D1, `TagName`), `a32c95b` (D2,
  `GitRepository`, offline read-only shell-out), `a08676a` (D3, `PublishedHistory`), `2fe87cf` (D5,
  functional tests migrated to tagged history, landed before D4 per the dependency order),
  `1d6f8fb` (D4, publication wired into `agenticRelease`), `c3062f0` (D6, history functional tests),
  plus hardening fixes `9d5b0a6`, `f41503a`, `988c215`, `caf2d40`.
- **Phase E — history check / verify split**: `845ed6e` (E1, `agenticReleaseHistoryCheck`),
  `a5b220b` (E2, `agenticReleaseVerify`), `6800fca` (E3, verify functional tests), plus
  `4319bf9`, `e421f55`, `95cd3a4`, `1054adb` (the `.gitattributes`/eol-conversion fix and its test).
- **Phase F — relocate filesystem work to the plugin**: `c79500e` (widen `ReleaseManifest`'s JSON
  helpers), `32b5423` (F1, move snapshot writing/digests/staging from the processor's
  `ContractRelease`/history classes into the plugin's `ReleaseSnapshots`/`ReleaseSnapshotHistory`).
- **Phase G — docs and final verification** (this change): `docs/contract-releases.md` rewritten;
  `docs/contract-governance.md` and `docs/processor-internals.md` cross-references updated;
  `CHANGELOG.md` `[Unreleased]` "Contract releases" section replaced to describe final behaviour;
  `scripts/check-contract-docs.sh` required terms updated; the stale `ReleaseAction.java:23` Javadoc
  comment corrected (`ReleaseSnapshots` is in the plugin, not the processor); this PR description.

## Notable decisions

- **Git access is plugin-only, offline, read-only shell-out** (`GitRepository`): no JGit dependency,
  every `GIT_*` environment variable stripped before each `git` invocation, a bounded timeout, and no
  command that can write a ref, an object, the config, or even refresh the index
  (`GIT_OPTIONAL_LOCKS=0`).
- **The release never repairs its own inputs.** `agenticRelease` and `agenticReleaseVerify` declare
  no task dependency on `compileJava`/`classes` (only `mustRunAfter`), so a tampered or stale
  artifact fails the release by name instead of silently being regenerated. This was the subject of
  the spec's fourth round and several of the Phase C/D fix commits above.
- **Two authorities for reserved resources**: the compile/accept path (processor + `compileJava`'s
  empty-contract writer) is the only code that writes or regenerates them; the release path only
  validates. A regression test distinguishes the two explicitly.
- **Tag name is recorded, not recomputed**: `release.json.tagName` is the resolved name at release
  time, so changing `agentic { release { tagName } }` later does not strand earlier releases'
  proofs.
- **Only the newest snapshot may be pending.** This keeps "first release, no history yet" distinct
  from "history exists but can't be trusted" (shallow clone, missing tags, no repository), each with
  its own, named failure.
- **`agenticReleaseHistoryCheck` vs. `agenticReleaseVerify` is a deliberate validation-boundary
  split**: the former is fast, offline, and runs in every CI build via `check`; the latter is the
  only task that proves a release against the actual tagged commit, and is reserved for the tag
  build.

## Validation

*(Filled in by the lead as part of G2 — final verification and origin alignment. Placeholders
below.)*

- CI result (all four builds): `<TODO — lead fills in from the PR's CI run>`
- `scripts/check-origin-alignment.sh release-workflow` exit code: `<TODO>` (the Phase G build agent's
  worktree had no such script; see the build agent's handback report for the exact wording it used)
- `/team-review` re-review against the three findings and every gap-table row: `<TODO>`
- Final full `./gradlew build` (javadoc + `functionalTest` included): `<TODO>`
- `./gradlew :demo:compileJava`, demo class output holds `contract-resources.json`: `<TODO>`
