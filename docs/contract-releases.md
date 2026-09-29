# Contract Releases

The [compatibility gate](contract-governance.md) protects the contract the team has **accepted**:
the baseline, `.atlas/api.ir.json`, which `atlasAccept` overwrites. A **release** records the
contract that was **shipped**, as an immutable snapshot, and polices what may leave the contract
between two shipments:

```
accepted baseline  ──agenticRelease──▶  .atlas/releases/<version>/   (immutable)
                                            │
previous release ── ContractGate.compareReleases ──▶ changelog + deprecation policy
```

Releasing is a Gradle task in this version. The engine lives in the processor, like the gate, so
other front-ends can follow.

## Releasing: accept, then release

```bash
./gradlew atlasAccept                         # review and commit the baseline diff
./gradlew agenticRelease -Pversion=1.4.0      # or agentic { releaseVersion }
git add .atlas && git commit -m "Release contract 1.4.0"
```

`agenticRelease` runs after `classes`, so the gate and `atlasContractCheck` have passed. It then:

1. Checks the version: strict `MAJOR.MINOR.PATCH` (see [The version](#the-version)).
2. Refuses a version that is already released. A released directory is never overwritten.
3. Requires the IR the build emitted to be **byte-identical to the baseline**. Otherwise it fails
   and names `atlasAccept`: every contract change is accepted before it ships, even in a project
   that does not use lock mode. The release never writes the baseline; `atlasAccept` stays its only
   writer, and the gate keeps comparing against the baseline, not the latest release.
4. Verifies every earlier release against its digests (see [Verification](#verification-agenticreleasecheck)).
5. Refuses a version not above the latest release, and an `apiMajor` below the latest release's.
6. Compares the release with the previous one, and checks the [deprecation policy](#the-deprecation-policy).
7. Writes the release directory to a temporary sibling, `.<version>.tmp`, and moves it into place
   in one step, then regenerates the [aggregate changelog](#the-changelog).

Nothing is written when the release is refused. The task reads only the class output, the baseline and
the release directory. It makes no network, database or model call, and writes no timestamp, host
or path, so releasing the same inputs again gives byte-identical files.

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
- Releases are ordered numerically, and each must be above the latest one. Releasing on an older
  line (`1.4.1` after `2.0.0`) is not supported yet.
- The version is independent of the contract's `apiMajor` (`agentic { apiMajorVersion }`, the
  `/v<major>/` of the URLs), so a product at 5.x can serve API v2. With
  `releaseVersionTracksApiMajor = true`, a release fails unless its version's major equals the
  contract's `apiMajor`.
- `release.json` records both the version and the `apiMajor`.

**`agentic { version }` is not the release version.** `agentic { version }` is the version of the
ai-atlas dependencies the plugin adds. It defaults to the plugin's own version, never to the
project version, so a project leaves it unset and lets `releaseVersion` follow its project version:

```kotlin
version = "1.4.0"            // the product, and so the release version

// agentic { version } unset: the ai-atlas dependencies are the plugin's own version
```

Set `agentic { version }` only to pin a different ai-atlas version.

## The release snapshot

Each release is a committed directory, `.atlas/releases/<version>/` by default, holding full copies:

| File | Content |
|---|---|
| `api.ir.json` | The accepted baseline, byte for byte, with the `irVersion` it was written with |
| `openapi-v<major>.json` | The OpenAPI document of the released `apiMajor`, when generated |
| `mcp-tools.json` | The MCP tool specifications, when `agentic { constraints }` generated them |
| `contract-diff.json` | The comparison with the previous release, in the gate's `contract-diff.json` form; `publishedMajor` is the previous release's major, `0` for the first release |
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
  "policy": {
    "minReleases": 1,
    "minMajors": 1,
    "failOnBreaking": true
  },
  "sha256": {
    "CHANGELOG.md": "…",
    "api.ir.json": "…",
    "contract-diff.json": "…",
    "openapi-v1.json": "…"
  }
}
```

Released files never change. An ai-atlas upgrade that moves the IR to a new `irVersion` reads an
older release's `api.ir.json` through the same in-memory migration as an older baseline, so a
release written at `irVersion` 3 keeps its bytes and still compares with later releases.

A module that declares no `@AgenticEntity` or `@AgenticExposed` releases the empty contract, the
document `atlasAccept` writes for it, without an OpenAPI document.

## The comparison

A release is compared with the previous one by `ContractGate.compareReleases(previous, current)`.
It reuses the gate's own comparison, with its classifications, reasons and element paths, over
what each release **published**: each IR reduced to the elements active at its own `apiMajor`,
each keeping only the deprecation in effect there.

This differs from the build's gate, which compares both documents at the baseline's major. There,
a declared removal (`removedInVersion = M+1`, `apiUntil = M`) is invisible at M by design. Between
a release at major 1 and one at major 2, that removal is exactly what must be reported, so the
release comparison reports it as a removal. The first release is compared with the empty document,
so every published element is added.

## The deprecation policy

```kotlin
agentic {
    release {
        deprecation {
            minReleases.set(1)         // default: 1
            minMajors.set(1)           // default: 1
            failOnBreaking.set(true)   // default: true
        }
    }
}
```

A **removal** is any of:

- a field or operation the previous release published that this release does not publish;
- a field or operation losing a channel it is reachable on, as the gate's
  [channel rules](contract-governance.md#field-channels) classify it;
- a module's whole contract disappearing, which removes each of its elements.

A removal passes only when the element was released **deprecated** (`@AgenticField(deprecatedSinceVersion)`
or `@AgenticExposed(apiDeprecatedSince)` in effect at that release's major) in at least
`minReleases` earlier releases, and at least `minMajors` majors lie between its deprecation major and
the `apiMajor` of the release that removes it. With the defaults, an element is deprecated in one
release and removed in a later major, which matches the gate's own remedy (`removedInVersion = M+1`,
`apiUntil = M`). An entity's removal is the removal of its fields, each checked on its own.

A channel has no lifecycle of its own, so a channel removal passes only when the whole field or
operation was released deprecated, or with `minReleases = 0` and `minMajors = 0`.

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
  release that before removing it; or relax agentic { release { deprecation { minReleases;
  minMajors; failOnBreaking } } }.
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
repository's own hand-written `CHANGELOG.md` is never touched.

## Verification: `agenticReleaseCheck`

`agenticReleaseCheck` is part of `check`. It verifies every release directory against its
`release.json`: a released file edited or deleted, or a file added, fails the build and names the
file. The next `agenticRelease` fails on the same condition. With no release directory, it does
nothing.

Given a release version, it also fails unless `.atlas/releases/<version>/api.ir.json` is
byte-identical to the IR the build emits, so a build cannot claim a version whose contract it does
not publish:

```bash
./gradlew build agenticReleaseCheck --release-version=1.4.0
```

`agentic { release { checkVersion } }` sets the same version in the build script. The check is
offline, like the release.

## CI recipe

Release in the release pull request, and let CI verify:

1. On the release branch, run `atlasAccept` if the contract changed, then `agenticRelease`, and
   commit `.atlas/` with the change. The snapshot is reviewed like any other diff.
2. Every CI build runs `check`, so every release directory is verified.
3. The tag build checks that the tagged sources publish the released contract:

   ```yaml
   - name: Build
     run: ./gradlew build agenticReleaseCheck --release-version="${GITHUB_REF_NAME#v}"
   ```

`agenticRelease` itself is never run in CI: a tag checkout cannot commit the snapshot back, and
the next release needs the snapshot in the repository to compare with.

## Configuration

```kotlin
agentic {
    releaseVersion.set("1.4.0")                          // default: project.version
    releaseVersionTracksApiMajor.set(false)              // default: false
    release {
        directory.set(file(".atlas/releases"))           // default: <projectDir>/.atlas/releases
        changelog.set(file(".atlas/CHANGELOG.md"))       // default: <projectDir>/.atlas/CHANGELOG.md
        checkVersion.set(providers.gradleProperty("releaseTag"))  // default: unset
        deprecation {
            minReleases.set(1)                           // default: 1
            minMajors.set(1)                             // default: 1
            failOnBreaking.set(true)                     // default: true
        }
    }
}
```

Like `atlasAccept`, both tasks load the engine from the project's `annotationProcessor` classpath, so
the plugin and the processor must be the same ai-atlas version.
