# Contract Releases

The [compatibility gate](contract-governance.md) protects the contract the team has **accepted**:
the baseline, `.atlas/api.ir.json`, which `atlasAccept` overwrites. A **release** records the
contract that was **shipped**, as an immutable, git-tagged snapshot, and polices what may leave the
contract between two shipments.

```
accepted baseline ──agenticRelease──▶ .atlas/releases/<version>/   (immutable, then tag and push)
                                          │
previous release ── ReleaseComparison.compare ──▶ changelog + deprecation policy
                                          │
tagged commit ──agenticReleaseVerify──▶ proves the tag is HEAD and the build matches the release
```

Three tasks:

| Task | Part of `check` | Needs git | Reads the build's class output | Writes |
|---|---|---|---|---|
| `agenticRelease` | no | yes (proves tags, pending) | yes (validates, never regenerates) | a new `.atlas/releases/<version>/` and `.atlas/CHANGELOG.md` |
| `agenticReleaseHistoryCheck` | **yes** | no | no | nothing |
| `agenticReleaseVerify` | no | yes (proves the tag is `HEAD`) | yes (validates, never regenerates) | nothing |

`agenticReleaseHistoryCheck` only checks that the committed `.atlas/releases/` snapshots are
internally consistent: every file against the digests in its own `release.json`, the `previous`
chain, and that `.atlas/CHANGELOG.md` equals what the release history would regenerate. It never
runs git and never reads the version being built, so it runs offline in every CI build, including a
shallow one. **A consistent rewrite of a published snapshot — every file re-digested so the
manifest and the bytes still agree with each other — passes `agenticReleaseHistoryCheck`, because
internal consistency does not require matching what was tagged. It fails both `agenticRelease` and
`agenticReleaseVerify`**, which reuse the tag proof below and compare the files with what the tagged
commit actually holds: that is the validation boundary between the two tasks.

The engine lives in the processor and the plugin's own `ReleaseSnapshots`/`ReleaseSnapshotHistory`
classes (not reflection, not a REST call): filesystem and digest work in the plugin, comparison,
policy, changelog and IR parsing in the processor, so other front-ends can reuse the latter.

## Releasing: accept, then release, then tag

```bash
./gradlew atlasAccept                                   # review and commit the baseline diff
./gradlew classes agenticRelease -Pversion=1.4.0        # or agentic { releaseVersion }
git add .atlas && git commit -m "Release contract 1.4.0"
git tag v1.4.0
git push && git push origin v1.4.0
```

**`agenticRelease` does not compile.** It validates the class output *as it finds it* and declares
no task dependency on `compileJava` or `classes` — only a `mustRunAfter` ordering — so that a task
it depends on can never repair the very inputs it is meant to validate (a tampered artifact must
fail the release, not get silently regenerated). Run `classes agenticRelease` together, as above;
`agenticReleaseVerify` has the same property for the same reason, so CI runs `classes
agenticReleaseVerify` too, never `agenticReleaseVerify` alone.

`agenticRelease`, once `classes` has produced a class output:

1. Checks the version: strict `MAJOR.MINOR.PATCH` (see [The version](#the-version)).
2. Resolves the git tag name (see [Tags](#tags-pending-and-proof)) and, when a repository is found,
   requires `.gitattributes` to mark the release files `-text` (see
   [`.gitattributes`](#gitattributes-line-endings)).
3. Proves the published history: every earlier release not already pending must be backed by a
   proved tag, and the previous release, if unpublished, blocks this one (see
   [Tags, pending and proof](#tags-pending-and-proof)).
4. Validates the class output against `META-INF/ai-atlas/contract-resources.json` (see
   [`contract-resources.json`](#the-contract-resources-manifest)): every listed artifact present with
   a matching digest, no unlisted reserved file, every required artifact listed.
5. Requires the IR the build emitted to be **byte-identical to the baseline**. Otherwise it fails
   and names `atlasAccept`: every contract change is accepted before it ships, even in a project
   that does not use lock mode. The release never writes the baseline; `atlasAccept` stays its only
   writer, and the gate keeps comparing against the baseline, not the latest release.
6. Refuses a version not above the latest release, and an `apiMajor` below the latest release's.
7. Compares the release with the previous one, and checks the [deprecation policy](#the-policy).
8. Writes the release directory to a temporary sibling, `.<version>.tmp`, and moves it into place
   in one step, then regenerates the [aggregate changelog](#the-changelog).

Nothing is written when the release is refused. It makes no network, database or model call, and
writes no timestamp, host or path, so releasing the same inputs again gives byte-identical files.

After `agenticRelease` succeeds, commit `.atlas/releases/<version>/` and `.atlas/CHANGELOG.md`, tag
that commit, and push the tag. **The release just made is itself unpublished (`PENDING`) until that
tag exists and is pushed**: the next `agenticRelease` refuses to run until it is tagged (see
[Tags, pending and proof](#tags-pending-and-proof)).

## The version

The release version comes from `agentic { releaseVersion }`, which defaults to the project version:

```kotlin
agentic {
    releaseVersion.set("1.4.0")               // default: project.version
    releaseVersionTracksApiMajor.set(true)    // default: false
}
```

- It must be `MAJOR.MINOR.PATCH`, such as `1.4.0`. A `-SNAPSHOT` is refused with its own message:
  a snapshot's contract can still change under the same name, which is what a release forbids.
  Pre-releases (`2.0.0-rc.1`) and build suffixes are refused too.
- Releases are ordered numerically, and each must be **higher than the last release**. Releasing on
  an older line (`1.4.1` after `2.0.0`) is not supported yet.
- The contract's `apiMajor` never goes down from one release to the next, regardless of the
  version: a release whose `apiMajor` is below the latest release's `apiMajor` is refused.
- The version is independent of the contract's `apiMajor` (`agentic { apiMajorVersion }`, the
  `/v<major>/` of the URLs), so a product at 5.x can serve API v2. `releaseVersionTracksApiMajor` is
  **opt-in** (default `false`); set it to `true` to require the release version's major to equal the
  contract's `apiMajor`.
- `release.json` records both the version and the `apiMajor`.

**`agentic { version }` is not the release version, and never follows it.** `agentic { version }` is
the version of the `annotations`, `processor` and `runtime` dependencies the plugin adds. It
defaults to **the plugin's own version**, never to the project version and never to
`releaseVersion`, so releasing a consumer project as `2.0.0` leaves its ai-atlas dependency version
unchanged (fixed by #59; `DependencyVersionTest`, `DependencyVersionFunctionalTest`):

```kotlin
version = "1.4.0"            // the product, and so the default release version

// agentic { version } unset: the ai-atlas dependencies stay the plugin's own version
```

A development build without an explicit `agentic { version }`, where the plugin cannot determine
its own version (classes loaded without the version resource its build writes and without jar
metadata), fails rather than silently falling back to the project version; set
`agentic { version.set("<ai-atlas version>") }` explicitly in that case. Set `agentic { version }`
only to pin a different ai-atlas version than the plugin's own.

## The release snapshot

Each release is a committed directory, `.atlas/releases/<version>/` by default, holding full
copies:

| File | Content |
|---|---|
| `api.ir.json` | The accepted baseline, byte for byte, with the `irVersion` it was written with |
| `openapi-v<major>.json` | The OpenAPI document of the released `apiMajor`, when generated |
| `mcp-tools.json` | The MCP tool specifications, when `agentic { constraints }` generated them |
| `contract-diff.json` | The comparison with the previous release, in the gate's `contract-diff.json` form |
| `CHANGELOG.md` | The release's changelog section |
| `release.json` | The manifest |

`release.json` has its own format version, independent of the IR's `irVersion`:

```json
{
  "manifestVersion": 1,
  "version": "1.4.0",
  "apiMajor": 1,
  "irVersion": 3,
  "previous": "1.3.0",
  "tagName": "v1.4.0",
  "policy": {
    "minDeprecatedReleases": 1,
    "minApiMajorAdvance": 1,
    "failOnBreaking": true
  },
  "sha256": {
    "CHANGELOG.md": "…",
    "api.ir.json": "…",
    "contract-diff.json": "…",
    "openapi-v1.json": "…"
  },
  "contractResources": { "…": "the release's contract-resources.json, embedded verbatim" }
}
```

`tagName` is the **resolved** tag name (the template with `{version}` substituted, such as
`"v1.4.0"`), recorded once at release time. Later proofs — the next `agenticRelease`'s pending
check, and every `agenticReleaseVerify` — read this recorded name, not whatever
`agentic { release { tagName } }` happens to be configured as when they run, so changing the
template afterwards does not strand earlier releases.

`contractResources` embeds the class output's own `contract-resources.json` verbatim (see below),
so a release's manifest alone records which artifacts it should hold and the effective
`ai.atlas.*` configuration it was built with.

Released files never change. An ai-atlas upgrade that moves the IR to a new `irVersion` reads an
older release's `api.ir.json` through the same in-memory migration as an older baseline, so a
release written at `irVersion` 3 keeps its bytes and still compares with later releases.

A module that declares no `@AgenticEntity` or `@AgenticExposed` releases the **empty contract**, the
canonical document `atlasAccept` or `compileJava` wrote for it, with `contractResources.contract`
recorded as `"empty"` and no OpenAPI document.

## The contract-resources manifest

Every compilation (declaring something, or empty) writes `META-INF/ai-atlas/contract-resources.json`
next to the Contract IR: its effective `ai.atlas.*` configuration (`apiBasePath`, `apiMajor`,
`collections`, `constraints`, `openApiInfoVersion`, `projections`) and the SHA-256 of every
**reserved** artifact it produced. The **reserved set** is fixed and small: `api.ir.json`,
`contract-diff.json`, `mcp-tools.json`, the API-version-properties and deprecation-manifest files
under `META-INF/ai-atlas/`, the `contract-resources.json` manifest itself, the `openapi.json` alias,
and `openapi-v<N>.json` **for every API major `N` ≥ 1** that was ever generated. An unlisted file
outside this set — another annotation processor's `META-INF/foo/bar.json`, say — is never inspected
and never fails a release.

`agenticRelease` and `agenticReleaseVerify` both validate the class output against this manifest,
read-only, before snapshotting or comparing:

- every artifact the manifest lists must exist with a matching digest (a modified or missing
  artifact fails, naming the path);
- every **reserved** path present in the class output must be listed in the manifest (a stale
  leftover — `openapi-v1.json` surviving a bump to major 2, or `mcp-tools.json` left after
  constraints were turned off, with no `clean` in between — fails, naming the file and the remedy,
  "Run clean and rebuild before releasing");
- every artifact the manifest's own configuration *requires* (the IR always; the current major's
  OpenAPI document when the contract is non-empty; `mcp-tools.json` when constraints are on) must be
  listed, or it is reported as a processor bug.

Only the artifacts `ContractResources.snapshotted` selects — the IR, the current major's versioned
OpenAPI document, and `mcp-tools.json` — are copied into the release snapshot; the alias
`openapi.json` and the other `META-INF/ai-atlas/*` bookkeeping files are not.

**Two paths, two authorities.** The **compile/accept path** — `compileJava`'s own processor output
when something is declared, or, for an empty module, `compileJava.doLast`'s writer, and
`atlasAccept`'s compilation — is the only code allowed to write or regenerate these reserved
resources, and only after confirming the class output declares no contract (so removing every
annotation and rebuilding, without `clean`, still produces a fresh empty `api.ir.json` rather than
keeping a stale non-empty one). The **release path** (`agenticRelease`, `agenticReleaseVerify`)
*never* writes, regenerates or repairs anything: it validates the class output exactly as
`compileJava` or `atlasAccept` left it and fails, naming the path, on anything stale, missing or
mismatched. A failed release leaves the class-output artifacts, `contract-resources.json` and
`.atlas/CHANGELOG.md` unchanged, and creates no release directory.

## Tags, pending and proof

`agentic { release { tagName } }` is a template with exactly one `{version}` placeholder, defaulting
to `v{version}`:

```kotlin
agentic {
    release {
        tagName.set("v{version}")   // default
    }
}
```

Only the exact rendered name counts as a match: `"1.0.0"` never stands in for `"v1.0.0"`, and
neither does a pre-release such as `"v1.0.0-rc.1"`.

A release's tag is the proof that it was actually published, not merely written to disk. For every
committed `.atlas/releases/<version>/`, oldest first:

- its `release.json`'s recorded `tagName` must exist as a tag in the repository, **unless** it is
  the **newest** committed snapshot, in which case a missing tag makes it **PENDING** rather than a
  failure;
- an older snapshot (not the newest) with no matching tag fails outright, naming the fetch remedy;
- a tag that does exist is **proved**: it peels to a commit, that commit is an ancestor of `HEAD`,
  its tree holds `release.json` and `api.ir.json` whose IR digest matches the manifest's own, and
  both files are byte-identical to the working copy's — a tag whose tree disagrees with the working
  copy fails, naming that the snapshot "was changed after it was tagged";
- a tag matching the `tagName` template with **no** corresponding snapshot directory is an orphan
  tag and fails (see [Adopting ai-atlas in a repository with existing tags](#adopting-ai-atlas-in-a-repository-with-existing-tags)).

**Only the newest committed snapshot may be `PENDING`.** `agenticRelease` refuses to make a *new*
release while the previous one is pending, naming the tag to create: "Release 1.3.0 is pending: tag
its commit as v1.3.0 and push the tag, then release." Tag and push the previous release before
making the next one.

Only a `PUBLISHED` (tag-proved) release earns deprecation credit (see [The policy](#the-policy)); a
pending release counts in the version/major ordering but contributes no evidence.

### First release vs. incomplete history

A brand-new project with no `.atlas/releases/` directory and no tags matching the template releases
normally: a genuine first release needs no git history at all. Each of the following, by contrast,
means the **history that does exist cannot be trusted**, and fails naming what is missing, together
with "Fetch tags and full history, e.g. `actions/checkout` with `fetch-depth: 0` and
`fetch-tags: true`.":

- an **untagged older snapshot** (not the newest) — a committed release with no tag to prove it;
- a **tag with no snapshot** — an orphan tag matching the template;
- a **shallow clone** — `git rev-parse --is-shallow-repository` says `true`; a shallow clone can
  never establish a genuine first release nor prove a tag's ancestry, whatever is committed on disk;
- **committed snapshots with no git repository at all** (for example `GIT_CEILING_DIRECTORIES`
  cutting the plugin off from a repository that does exist).

## `.gitattributes`: line endings

`agenticRelease`, when it finds a git repository, requires every file it is about to write under the
release directory, and the aggregate changelog, to be marked **binary** (`-text`) in
`.gitattributes` — checked with `git check-attr --cached`, i.e. read **from the index**, so a line
added to the working tree but never staged does not count, because the commit that gets tagged
would lack it. Without it, the fix fails before writing anything:

```
[ai-atlas] git would convert the line endings of released files on checkout, as Git for Windows does
by default, so they would no longer match the digests release.json records. Add these lines to
.gitattributes, commit them, then release:
  .atlas/releases/** -text
  .atlas/CHANGELOG.md -text
```

**Why:** `core.autocrlf`, the default on Git for Windows, rewrites `\n` to `\r\n` on checkout for any
file git considers text. If the released JSON and changelog files were subject to that conversion, a
checkout on Windows would leave them with different bytes than the ones `release.json`'s SHA-256
digests record, and every tag proof (and every CI build) would see them as "changed after release."
Marking the whole release tree and the aggregate changelog `-text` makes a checkout byte-identical
everywhere. The simplest fix covering the default layout is one line:

```gitattributes
.atlas/** -text
```

The attribute **must be committed** before releasing, not merely present in the working tree: it is
read from the index, so stage and commit `.gitattributes` first, then release.

## The comparison

A release is compared with the previous one by `ReleaseComparison.compare(previous, current)`. It
reuses the gate's own comparison (`ContractComparison`), with its classifications, reasons and
element paths, over what each release **published**: each IR reduced to the elements active at its
own `apiMajor`, each keeping only the deprecation in effect there.

This differs from the build's gate, which compares both documents at the baseline's major. There,
a declared removal (`removedInVersion = M+1`, `apiUntil = M`) is invisible at M by design. Between
a release at major 1 and one at major 2, that removal is exactly what must be reported, so the
release comparison reports it as a removal. The first release is compared with the empty document,
so every published element is added.

## The policy

```kotlin
agentic {
    release {
        policy {
            minDeprecatedReleases.set(1)   // default: 1
            minApiMajorAdvance.set(1)      // default: 1
            failOnBreaking.set(true)       // default: true
        }
    }
}
```

A **removal** is any of:

- a field or operation the previous release published that this release does not publish;
- a field or operation losing a channel it is reachable on, as the gate's
  [channel rules](contract-governance.md#field-channels) classify it, evaluated with the same
  channel-aware reachability the gate uses, at **each earlier release's own major**;
- a module's whole contract disappearing, which removes each of its elements.

A removal passes only when the element was released **deprecated** (`@AgenticField(deprecatedSinceVersion)`
or `@AgenticExposed(apiDeprecatedSince)` in effect at that release's major) in at least
`minDeprecatedReleases` earlier **published** releases — a pending or unpublished release earns no
credit — and at least `minApiMajorAdvance` majors lie between its deprecation major and the
`apiMajor` of the release that removes it. With the defaults, an element is deprecated in one
release and removed in a later major, which matches the gate's own remedy (`removedInVersion = M+1`,
`apiUntil = M`). An entity's removal is the removal of its fields, each checked on its own.

A channel has no lifecycle of its own, so a channel removal passes only when the whole field or
operation was released deprecated **and visible on that channel** in each counted release, or with
`minDeprecatedReleases = 0` and `minApiMajorAdvance = 0`. A release where the element was deprecated
but not reachable on the lost channel (for example, through an intermediate field that already
excludes that channel, or an operation that never listed it) earns no credit.

With `failOnBreaking`, **any other breaking difference** fails a release with the same `apiMajor` as
the previous release. It was accepted with `atlasAccept`, so the release is the last line of
defence. Across a major bump, breaking differences are expected: they are listed in the changelog
and do not fail.

A violation names the element path, the evidence found and the remedy:

```
[ai-atlas] Release 1.1.0 violates the release policy (released deprecated in at least 1 release(s),
removed at least 1 major(s) after deprecation, failOnBreaking = true) at 1 element(s):
  field shop.Order#legacy (removed): removed in API major 1, but never released as deprecated. The
  policy needs it released deprecated in at least 1 release(s), and removed at least 1 major(s)
  after its deprecation major. Restore it, declare @AgenticField(deprecatedSinceVersion = N) and
  release that before removing it; or relax agentic { release { policy { minDeprecatedReleases;
  minApiMajorAdvance; failOnBreaking } } }.
```

`atlasAccept` can still accept a change the next release's policy refuses: accepting means
"reviewed", releasing means "shipped under the policy". The policy in force is recorded in each
`release.json`.

## The changelog

Each release's `CHANGELOG.md` is a Markdown section rendered from the comparison only. Each entry
is one difference, named by the gate's element path, so it matches compile errors and
`contract-diff.json`:

```markdown
## 2.0.0 (API major 2)

Compared with 1.1.0 (API major 1).

### Removed

- `field shop.Order#legacy` (output), deprecated since major 1 (first released deprecated in 1.1.0, 1 release(s))
```

The sections, in order and each only when it has an entry, are **Breaking** (with the gate's
reason), **Removed** (with the deprecation evidence), **Deprecated** (with the removal major and the
message or replacement), **Added**, **Changed** and **Informational**. There is no date: it lives
in version control.

Every release also regenerates `.atlas/CHANGELOG.md` (`agentic { release { changelog } }`), the
sections of all releases, newest first. It is derived, so it can always be regenerated. The
repository's own hand-written `CHANGELOG.md` is never touched. `agenticReleaseHistoryCheck` fails
if the aggregate changelog does not equal what the committed releases would regenerate (it was
edited by hand after release).

## `agenticReleaseHistoryCheck`: internal consistency, offline

`agenticReleaseHistoryCheck` is part of `check`, runs with no git and no network, and never reads
the version being built. It verifies, for every committed `.atlas/releases/<version>/`:

- every file it holds matches the digest `release.json` records for it, and it holds no extra or
  missing file;
- its `release.json`'s `previous` equals the directory version preceding it (`null` for the first);
- `.atlas/CHANGELOG.md` equals what regenerating the history from these directories would produce.

With no release directory and no changelog file, it succeeds silently. **It proves nothing about
git tags or `HEAD`** — a committed snapshot and its manifest can be mutually consistent (all digests
re-computed together) while still not being what was actually tagged and shipped; catching that is
`agenticReleaseVerify`'s job, not this one's.

## `agenticReleaseVerify`: the tag build

`agenticReleaseVerify` is **not** part of `check`; it needs git and reads the build's class output.
Given `agentic { releaseVersion }` (CI sets it with `-Pversion`), it:

1. Reuses the **same** tag proof `agenticRelease` uses (`PublishedHistory`): any committed snapshot
   with an unproved, orphaned or pending tag fails here too, fail-closed, exactly as at release time.
2. Additionally requires the resolved tag to peel to a commit that is **exactly `HEAD`** — not merely
   an ancestor. On the commit that is not `HEAD`: "Tag v1.4.0's commit is not HEAD: HEAD must be the
   exact commit tagged v1.4.0 to verify release 1.4.0." Build the tag itself, not a descendant of it.
3. Validates the build's class output against `contract-resources.json`, exactly as `agenticRelease`
   does, and compares it with the release:
   - the IR **canonically** equal (structural equality, migrated `irVersion` included — not a byte
     comparison, since in-memory migration can change bytes);
   - every snapshotted artifact (the versioned OpenAPI document, `mcp-tools.json`) **byte for byte**;
   - the **same set** of snapshotted artifacts present on both sides — an artifact the build produces
     that the release does not, or the reverse, fails naming the extra or missing file: "the build's
     effective configuration differs from the one it was released with";
   - the released `contractResources.configuration` **structurally** equal to the build's effective
     `ai.atlas.*` configuration (so a flag such as `constraints` or `collections` flipped after
     release is caught even when it happens not to change any artifact's bytes).

It writes no file, and, like `agenticRelease`, has no task dependency on `compileJava` or `classes`
that could regenerate the very output it is verifying — only `mustRunAfter`.

**The validation boundary (AC14).** A release snapshot that is edited and then made internally
consistent again (every digest recomputed to match the edited bytes) passes
`agenticReleaseHistoryCheck`, because that task only checks the manifest against its own files. It
fails `agenticReleaseVerify` (the tagged commit's tree no longer matches the working copy) and fails
`agenticRelease` (the next release's history proof encounters the same mismatch). This is
`agenticReleaseVerify`'s entire reason to exist: it is the only task that checks a release against
what was actually tagged, not merely against itself.

## CI recipe

Release in the release pull request, and let CI verify the tag:

1. On the release branch: `./gradlew atlasAccept` if the contract changed, then
   `./gradlew classes agenticRelease -Pversion=1.4.0`, then commit `.atlas/` with the change (see
   [Releasing: accept, then release, then tag](#releasing-accept-then-release-then-tag)).
2. Tag the commit (`git tag v1.4.0`) and push both the commit and the tag.
3. Every CI build runs `check`, so `agenticReleaseHistoryCheck` verifies every committed release
   directory's internal consistency, offline, on every push and PR.
4. The **tag build** proves the tag is what it claims to be:

   ```yaml
   - uses: actions/checkout@v4
     with:
       fetch-depth: 0      # full history: agenticReleaseVerify proves tag ancestry
       fetch-tags: true    # the tags themselves: a default checkout can omit them
   - name: Verify the tagged release
     run: ./gradlew classes agenticReleaseVerify -Pversion="${GITHUB_REF_NAME#v}"
   ```

   A default, shallow `actions/checkout` (its default `fetch-depth: 1`, and older versions that do
   not fetch tags by default) fails as a shallow clone: fetch full history and tags on the tag build.

`agenticRelease` itself is never run in CI: a tag checkout cannot commit the snapshot back, and the
next release needs the snapshot already in the repository to compare with and to prove pending.

## Adopting ai-atlas in a repository with existing tags

The default `tagName` template, `v{version}`, **collides** with the common convention of tagging
product releases `v1.2.3`. If the repository already has tags in that shape that are **not**
ai-atlas contract releases, `agenticRelease` and `agenticReleaseVerify` detect a tag matching the
template with no corresponding snapshot and fail it as an orphan tag, naming the remedy: give the
contract's releases their own, distinct pattern:

```kotlin
agentic {
    release {
        tagName.set("api-v{version}")
    }
}
```

### Multi-module repositories

Each module that releases a contract needs its **own, unique** `tagName` pattern (for example
`api-v{version}` for one module and `orders-api-v{version}` for another), and keeps its **own,
independent** release history under its own `release.directory` (by default
`<module>/.atlas/releases`). A module's `agenticRelease`, `agenticReleaseHistoryCheck` and
`agenticReleaseVerify` only ever look at that module's own releases directory and its own tag
pattern; they never cross-reference another module's tags or snapshots.

## Configuration

```kotlin
agentic {
    releaseVersion.set("1.4.0")                          // default: project.version
    releaseVersionTracksApiMajor.set(false)              // default: false
    release {
        directory.set(file(".atlas/releases"))           // default: <projectDir>/.atlas/releases
        changelog.set(file(".atlas/CHANGELOG.md"))       // default: <projectDir>/.atlas/CHANGELOG.md
        tagName.set("v{version}")                        // default: v{version}
        policy {
            minDeprecatedReleases.set(1)                 // default: 1
            minApiMajorAdvance.set(1)                    // default: 1
            failOnBreaking.set(true)                     // default: true
        }
    }
}
```

Like `atlasAccept`, all three release tasks load their engine from the project's
`annotationProcessor` classpath, so the plugin and the processor must be the same ai-atlas version;
a mismatch is reported clearly, naming both versions and the remedy (align `agentic { version }`
with the plugin's version, or remove the pin).
