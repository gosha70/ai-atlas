---
feature_id: release-workflow
spec_mode: full
status: approved
date: 2026-09-29
origin:
  issue: gosha70/ai-atlas#58
  source: specs/release-workflow/origin-draft-3806e5d.md
  source_commit: 3806e5d (branch claude/phase5-release-spike, spike/phase5-release/ISSUE-DRAFT.md)
  source_sha256: 4c2a7a4aac0a8d9ac7a7dd206417e118db092751a84b3029cfc6ccdfe8986b7c
---

# Release workflow: specification

## Source of truth

The requirements are the owner-settled issue draft of epic #23, Phase 5 §10, at spike commit
`3806e5d`. It is copied, byte for byte, to
[`origin-draft-3806e5d.md`](origin-draft-3806e5d.md); its SHA-256 is recorded above, and
`git show 3806e5d:spike/phase5-release/ISSUE-DRAFT.md | shasum -a 256` reproduces it. That copy is
authoritative and is not edited: a change of requirement is a new draft commit and a new copy. The
spike's design report is copied beside it, as [`spike-report-3806e5d.md`](spike-report-3806e5d.md),
for rationale only.

Issue #58 links the earlier draft `d15fe77`, which PR #62 was first built from. The owner decided on
2026-09-29 to expand PR #62 to the `3806e5d` scope before merge; the review of PR #62 at `bb98010`
(untagged credit, missing or stale resources, channel-blind credit) is closed by the same scope.

The design and the gap analysis are in [`plan.md`](plan.md); the ordered tasks are in
[`tasks.md`](tasks.md).

## Owner decisions (2026-09-29)

In addition to the decisions the draft records:

- **Git access:** the Gradle plugin runs the `git` executable, read-only and offline. No JGit: the
  plugin keeps no runtime dependency. The processor has no git access.
- **Phase F is required:** snapshot writing, digests and staging move to the plugin (draft item 9).
- **`agenticReleaseVerify`** requires the configured release tag to resolve to `HEAD`.
- **A shallow clone** cannot establish a genuine first release and fails git-aware validation
  (`agenticRelease`, `agenticReleaseVerify`). The git-free `agenticReleaseHistoryCheck` stays
  usable.

Second round, the same day, settling the plan's remaining open questions (`plan.md` §7):

- **Empty contract (OQ-2, the plan's default reversed):** an empty contract produces a fresh,
  canonical empty `api.ir.json` and a `contract-resources.json` recording `"contract": "empty"`.
  When javac does not invoke the processor, the plugin produces both. Reserved resources already
  in the class output are detected before anything is written: an old non-empty IR is never kept,
  digested or treated as fresh. The release snapshot holds the accepted empty IR.
- **Reserved set (OQ-5):** the enumerated ai-atlas paths only, including the versioned OpenAPI
  files of every major (`openapi-v<N>.json`, any N ≥ 1). Unrelated files are ignored.
- **Tag name (OQ-8):** `release.json` records the resolved tag name, and later proofs use it.
- **Snapshot equals the build (OQ-4):** the IR compared canonically, generated artifacts byte for
  byte, the effective configuration structurally, including which artifacts are present or absent.
- **Multi-module (OQ-7):** documented: each module uses a unique tag pattern and keeps an
  independent release history.
- **OQ-9, OQ-10:** the plan's defaults (`failOnBreaking` inside `policy { }`, the aggregate
  changelog header kept; no new property putting verify under `check`).

## Acceptance criteria

Stated as in the draft, numbered in order. Each is closed by the tasks listed; a task names its
tests in `tasks.md`.

| # | Criterion (draft, verbatim) | Closed by |
|---|---|---|
| AC1 | Prerequisite, shipped separately: `agentic { version }` defaults to the plugin's own version, and never falls back to `project.version`. An explicit value wins. A development build without an explicit value fails. A consumer released as `2.0.0` keeps its ai-atlas dependency version. The fix carries a changelog entry. | Done in PR #59 (merged); `DependencyVersionTest`, `DependencyVersionFunctionalTest` |
| AC2 | `agenticRelease` writes `.atlas/releases/<version>/` with the files in item 2. `release.json` records `manifestVersion`, the version, the API major, the previous release, `irVersion`, the policy and a digest of every other file. | C4, D1 |
| AC3 | The processor writes `contract-resources.json` with its effective configuration and the digests of the artifacts that configuration requires. The release copies exactly those artifacts. Tests set the options through manual `-A` arguments as well as through the extension. | C2, C6 |
| AC4 | Only the reserved set of ai-atlas resource paths is inspected: the `META-INF/ai-atlas/` files, `openapi-v<N>.json`, and the `openapi.json` alias. A listed file that is missing or mismatched fails, and so does an unlisted file within the set. Tests show that another processor's resources do not fail a release, and that a leftover `openapi-v1.json` after a major bump does. | C4, C6 |
| AC5 | An empty contract is recorded explicitly (`"contract": "empty"`), from `compileJava`'s effective arguments, and releases when the class output holds no stale contract resource. | C3a, C3b, C6 |
| AC6 | A fresh contract not canonically equal to the accepted baseline fails and names `atlasAccept`. The snapshot keeps the baseline's bytes. Releasing never writes the baseline. | C5 |
| AC7 | Releasing an existing version fails and changes no file. A SNAPSHOT or non-`MAJOR.MINOR.PATCH` version, a version not above the latest release, an `apiMajor` below the latest release's, and a release while another is pending all fail. | D4 |
| AC8 | `ReleaseComparison` in the processor reuses `ContractComparison` and compares each release at its own published API major. `ContractGate` behaviour and messages are unchanged. | A2 |
| AC9 | A removal fails unless the element was published deprecated in at least one tagged release, at least one API major before the removing release. This covers fields, operations and an empty contract. | B1, D4 |
| AC10 | A field or operation losing channel C passes only with enough tagged releases in which it was active, deprecated and visible on C, plus the API-major advance. A release in which it was deprecated but not visible on C earns no credit. | B1 (reviewer regression `ReviewChannelCreditTest`) |
| AC11 | Visibility is the gate's channel-aware reachability, shared code evaluated at each tagged release's own major. Tests cover an entity reachable only through an intermediate field excluding C (no credit), an operation not active on C (no credit), and nested chains. | A1, B1 |
| AC12 | Every other breaking change within the same API major fails by default, including one accepted with `atlasAccept`. Overrides are explicit and recorded. | Done at bb98010; kept green by B2 (DSL rename) |
| AC13 | Changelog entries come only from the release comparison. Tests pin the exact text for added, deprecated, removed and breaking entries. `.atlas/CHANGELOG.md` is regenerated and validated against history, and the root `CHANGELOG.md` is never touched. | E1 |
| AC14 | `agenticReleaseHistoryCheck` runs under `check` offline, without git, and checks internal consistency only. A test shows that a consistent rewrite of a published snapshot passes it, but fails `agenticReleaseVerify`. `agenticReleaseVerify` checks the tag's snapshot and rejects changes to published snapshots relative to trusted history. | E1, E2, E3 |
| AC15 | Tags follow the configured `tagName`. A tag qualifies only when its commit is an ancestor of `HEAD` and contains the matching snapshot and manifest, byte-identical to the working copy. | D1, D2, D3, D4, D5, D6 |
| AC16 | A new project with no snapshots and no matching tags releases. Any of these fails, naming what is missing: an untagged older snapshot, a tag without its snapshot, a shallow clone, or snapshots without a git repository. | D3, D6 |
| AC17 | Releasing the same inputs twice gives byte-identical files. | Done at bb98010; kept green by F1 |
| AC18 | Comparison tests cover nested references, projections, renamed identifiers, lifecycle boundaries and the empty contract, each at its release's own published API major. | B3 |
| AC19 | Docs cover the task, the layout, the version rules and the dependency-version default, the policy and its overrides, publication semantics, the accept-then-release sequence, and the CI recipe. | G1 |
| AC20 | No network, database, model call or live service anywhere in the release or its checks. | Done at bb98010; re-checked by G2 (D2 `git` calls are local and read-only) |
