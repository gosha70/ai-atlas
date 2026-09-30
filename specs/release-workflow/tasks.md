# Tasks: release workflow, expanded to the 3806e5d scope (PR #62)

Source: `specs/release-workflow/plan.md`, whose section numbers appear below as §n. Each task:
- runs 30-90 minutes;
- lands as one commit;
- leaves this command green:
  `./gradlew :modules:processor:test :modules:gradle-plugin:test :modules:gradle-plugin:functionalTest checkstyleMain checkstyleTest`.

The 500-line cap applies to every file, tests included. Each phase ends with a full
`./gradlew build`, javadoc included.

`proc/` is `modules/processor/src/main/java/com/egoge/ai/atlas/processor/` and `plug/` is
`modules/gradle-plugin/src/main/java/com/egoge/ai/atlas/plugin/`. Tests sit under the matching
`src/test`, or `src/functionalTest` for TestKit.

---

## Phase A: headroom refactors, no behaviour change

### A1. Extract the shared channel reachability (D4.8)
- **Owns:**
  - `proc/contract/ChannelReachability.java` (new);
  - `proc/contract/ContractComparison.java`;
  - `…/processor/contract/ChannelReachabilityTest.java` (new).
- **Do:**
  - Move `reachable(channel)` (`ContractComparison.java:251-274`) into
    `ChannelReachability.entities(ContractIr, int major, String channel)`.
  - Add `fieldVisible(...)` and `operationListed(...)` (§3.2).
  - `ContractComparison` delegates to it and keeps its cache.
- **Accept:**
  - The existing gate and processor tests pass unchanged.
  - The new tests cover:
    - a direct return;
    - a nested chain;
    - an intermediate field excluding C, which gives no visibility;
    - an operation not listing C;
    - elements inactive at the major.
- **Depends:** none.

### A2. `ReleaseComparison.compare` replaces `ContractGate.compareReleases` (D9.1, AC8)
- **Owns:**
  - `proc/contract/ReleaseComparison.java` (new, absorbing `ReleaseSurface.java`, which is
    deleted);
  - `proc/contract/ContractGate.java`, removing `:349-353`;
  - callers and Javadoc in `proc/release/{ContractRelease,ReleasePolicy,ReleaseChangelog,package-info}.java`;
  - `CompareReleasesTest.java`, renamed to `ReleaseComparisonTest.java`;
  - `ReleasePolicyTest.java` and `ReleaseChangelogTest.java`, call sites only.
- **Accept:**
  - `grep -r compareReleases modules/` finds nothing.
  - `ContractGate` public behaviour is unchanged.
  - The tests are green.
- **Depends:** none.

### A3. Extract the release task wiring from `AgenticPlugin`
- **Owns:**
  - `plug/ReleaseTasks.java` (new), holding `configureRelease` from `AgenticPlugin.java:220-256`;
  - `plug/AgenticPlugin.java`.
- **Accept:**
  - The functional tests are unchanged and green.
  - `AgenticPlugin.java` is 440 lines or fewer.
- **Depends:** none.

### A4. Split the snapshot reading out of `ContractRelease`
- **Owns:**
  - `proc/release/ReleaseHistory.java` (new), taking `history`, `verify`, `releaseDirs` and
    `aggregateChangelog`;
  - `proc/release/ContractRelease.java`;
  - `ContractReleaseTest.java`, moving the history cases into a new `ReleaseHistoryTest.java`;
  - `plug/ReleaseCheckAction.java`, call sites only.
- **Accept:** behaviour is identical, and both source files are 300 lines or fewer.
- **Depends:** none.

### A5. Extract `EffectiveOptions` from `AgenticProcessor` (§3.3)
- **Owns:**
  - `proc/contract/EffectiveOptions.java` (new);
  - `proc/AgenticProcessor.java`, where `resolveVersionConfig` (`:116-158`) moves out verbatim;
  - `EffectiveOptionsTest.java` (new).
- **Do:**
  - Add the record, with `resolve(options, messager)` giving the same messages.
  - Add `fromArguments(Map)` for plugin use.
- **Accept:**
  - The existing option and diagnostic tests are unchanged.
  - `AgenticProcessor.java` is 465 lines or fewer.
  - The new tests cover last-value-wins parsing and the defaults (`/api`, 1, `<major>.0.0`,
    false, false).
- **Depends:** none.

---

## Phase B: F3, channel-aware credit (processor only)

### B1. Published flag and channel-aware evidence (F3, D4.7, D4.9, AC10, AC11)
- **Owns:**
  - `proc/release/ReleasePolicy.java`;
  - `proc/release/ReleaseElements.java`;
  - `proc/release/ContractRelease.java`, where history releases are built with
    `published = true`: a transitional step that D4 replaces;
  - `ReleaseFixtures.java`: `release(...)` publishes; add `pending(...)` and a nested
    `Customer`/`Address` fixture;
  - `ReleasePolicyTest.java`;
  - `ChannelRemovalCreditTest.java` (new).
- **Do:** as in §3.2.
  - `Release(version, ir, published)`.
  - Evidence counts only published releases.
  - Field channel loss uses `ChannelReachability.fieldVisible` at each release's own major.
  - Operation channel loss is checked per lost channel with `operationListed`.
- **Accept:**
  - The reviewer's `/tmp/ReviewChannelCreditTest.java` case, added verbatim except for the
    `ReleaseComparison.compare` call, fails with the pre-B1 `ReleasePolicy` and passes after it.
  - Further tests:
    - credit from a visible deprecated release;
    - no credit when only an intermediate field excludes C;
    - no credit when the operation does not list C;
    - no credit from a pending release;
    - an operation losing two channels names the failing one.
- **Depends:** A1, A2.

### B2. Policy block and names (D4.5)
- **Owns:**
  - `ReleasePolicy.java`: the `Policy` fields `minDeprecatedReleases`, `minApiMajorAdvance` and
    `failOnBreaking`, plus `CONFIGURATION`;
  - `ReleaseManifest.java`, policy keys;
  - `plug/DeprecationSpec.java`, renamed to `plug/PolicySpec.java`;
  - `plug/ReleaseSpec.java`: `policy { }`;
  - `plug/ReleaseTasks.java`, `plug/AgenticRelease.java`, `plug/ReleaseAction.java`;
  - the affected tests: `ReleasePolicyTest`, `ReleaseManifestTest`, `ContractReleaseTest`,
    `AgenticReleaseFunctionalTest`.
- **Accept:**
  - `agentic { release { policy { minDeprecatedReleases.set(0) } } }` works in TestKit.
  - `release.json` shows the new keys.
  - `grep -rn "minMajors\|minReleases\|deprecation {" modules/` finds nothing.
- **Depends:** A3, B1.

### B3. Comparison coverage at each release's own major (D9.4, AC18)
- **Owns:**
  - `ReleaseComparisonTest.java`, split into a `ReleaseComparisonBoundaryTest.java` if it passes
    450 lines;
  - `ReleaseFixtures.java`, additions only;
  - `proc/contract/ReleaseComparison.java`, only if a case exposes a defect.
- **Accept:** tests pin the classification and path for:
  - a nested reference change;
  - renames: `dtoName`, `toolName`, `operationId`, REST path;
  - `sinceVersion` activation;
  - `removedInVersion` and `apiUntil` removal across a major;
  - a deprecation starting at exactly the release's major;
  - the empty contract as the previous and as the current release.
- **Depends:** A2.

---

## Phase C: F2, the contract resource manifest

### C1. `ContractResources`: reserved paths and manifest format (D2.7)
- **Owns:**
  - `proc/contract/ContractResources.java` (new);
  - `ContractResourcesTest.java` (new).
- **Do:** as in §3.3: `MANIFEST_PATH`, `isReserved`, `snapshotted`, `required`, and the
  `Manifest` record with canonical `write` and strict `read`.
- **Accept:**
  - The reserved matcher accepts `openapi-v1.json` and `openapi-v12.json`, and rejects
    `openapi-v0.json`, `openapi-vx.json` and `META-INF/other/x.json`.
  - A write-then-read round trip gives the same bytes.
  - Strict read rejects unknown `contract` values and a newer `manifestVersion`.
- **Depends:** A5.

### C2. The processor writes `contract-resources.json` (D2.4, D2.5, AC3)
- **Owns:**
  - `proc/contract/ResourceRecorder.java` (new);
  - `proc/AgenticProcessor.java`: wrap the environment in `init`, and write the manifest at the
    end of `processingOver`;
  - `ContractResourcesProcessorTest.java` (new, compile-testing).
- **Accept:**
  - Each listed digest equals the SHA-256 of the corresponding `generatedFile(CLASS_OUTPUT, …)`.
  - `-Aai.atlas.constraints=true` lists `mcp-tools.json`.
  - `-Aai.atlas.api.major=2` lists `openapi-v2.json` and `openapi.json`.
  - A configured baseline lists `contract-diff.json`.
  - Duplicate `-Aai.atlas.api.major=1 -Aai.atlas.api.major=2` records 2.
  - The manifest does not list itself.
  - Every existing golden and processor test is byte-identical.
  - `AgenticProcessor.java` and `ContractGate.java` stay under 500 lines.
- **Depends:** C1.

### C3a. Spike: where the empty manifest is written (time box 45 minutes)
- **Owns:** a scratch branch only. It produces a decision note appended to
  `specs/release-workflow/plan.md` §3.3.
- **Accept:** the chosen mechanism is demonstrated in a throwaway TestKit project:
  - **Preferred:** a `compileJava.doLast` that submits through `WorkerExecutor`.
  - **Fallback:** `atlasContractCheck` writing into the `compileJava` destination.
  - A second build shows `compileJava UP-TO-DATE`, or the fallback's documented cost.
  - `--configuration-cache` passes.
- **Depends:** C1.

### C3b. The plugin records the empty contract (D2.9, AC5)
- **Owns:**
  - `plug/EffectiveCompilerArguments.java` (new);
  - `plug/EmptyContractResourcesAction.java` (new);
  - `plug/ReleaseTasks.java` or `plug/AgenticPlugin.java`, per C3a, wiring only;
  - `EffectiveCompilerArgumentsTest.java` (new).
- **Accept:**
  - Unit test: last value wins over `compilerArgs` plus the providers' arguments.
  - TestKit (in C6): a module with no annotations gets a real `api.ir.json` holding
    `EmptyContract.json` and `contract-resources.json` with `"contract": "empty"`, the effective
    configuration including a manual `-A`, and only the `api.ir.json` artifact, whose digest is
    that of the bytes written (OQ-2).
  - TestKit (in C6): removing every annotation without `clean` leaves a fresh empty
    `api.ir.json`, never the stale non-empty one, whose digest is never recorded; the manifest
    digests the fresh bytes; the empty contract then releases with the accepted empty IR in its
    snapshot (compile/accept path); a leftover `openapi-v1.json` is kept and fails the release,
    naming it; the build still passes once the empty contract is accepted.
  - TestKit (in C6, owner correction): a stale or tampered reserved resource the compile path did
    not regenerate (`compileJava` UP-TO-DATE) fails `agenticRelease`, naming it, and the failed
    release leaves the class-output artifact bytes and `contract-resources.json` unchanged,
    creates no `.atlas/releases/<version>/` and leaves `.atlas/CHANGELOG.md` unchanged (release
    path).
- **Depends:** C3a.

### C4. The release consumes the manifest (F2, D2.1, D2.3, D2.6, D2.8, AC2, AC4)
- **Owns:**
  - `plug/ClassOutputResources.java` (new);
  - `ClassOutputResourcesTest.java` (new);
  - `plug/AgenticRelease.java`: remove `:98-102` and the extension major lookup;
  - `plug/ReleaseAction.java`;
  - `plug/ContractDeclarations.java`: delete `OPENAPI_PATH` and `MCP_TOOLS_PATH`;
  - `proc/release/ContractRelease.java`: in `Request`, an `artifacts` map and
    `contractResourcesJson` replace `openApi` and `mcpTools`;
  - `proc/release/ReleaseManifest.java`: embed `contractResources`;
  - `ContractReleaseTest.java` and `ReleaseManifestTest.java`.
- **Accept:** each of these fails, naming the path:
  - a listed artifact is missing;
  - a digest does not match;
  - a reserved file is not listed;
  - a required artifact is not listed.

  An unrelated `META-INF/other.json` passes. The snapshot holds exactly `snapshotted(manifest)`.
  `release.json` contains the manifest object verbatim.
- **Depends:** C2, C3b, A3, A4.

### C5. Canonical equality with the baseline (D3.2, AC6)
- **Owns:**
  - `proc/release/ContractRelease.java`, the `accepted` step;
  - `ContractReleaseTest.java`, or a new `ContractReleaseEqualityTest.java` if it would pass
    500 lines.
- **Accept:**
  - A baseline at an older `irVersion`, canonically equal to the build, releases.
  - Its snapshot `api.ir.json` is the baseline's bytes.
  - A semantic difference fails and names `atlasAccept`.
  - `ContractIr` equality is confirmed structural, with no array components.
- **Depends:** A4.

### C6. Resource functional tests (AC3, AC4, AC5)
- **Owns:** `ReleaseResourcesFunctionalTest.java` (new).
- **Accept (TestKit):**
  - Another processor's `META-INF/foo/bar.json` does not fail.
  - A stale `openapi-v1.json` fails after a bump to major 2. The file is planted by a
    build-script task ordered after `compileJava`.
  - A stale `mcp-tools.json` fails after constraints are turned off.
  - `constraints` set only through `options.compilerArgs` snapshots `mcp-tools.json`.
  - The major set by a user `CommandLineArgumentProvider` to 2, with the extension at 1,
    snapshots `openapi-v2.json`. This fails on `bb98010`.
  - An empty contract releases.
  - An empty contract with a leftover `api.ir.json` fails (OQ-2 default).
- **Depends:** C4, C5.

---

## Phase D: F1, tags, proof, pending and history

### D1. `tagName` configuration and recording (D10.2)
- **Owns:**
  - `plug/TagName.java` (new);
  - `TagNameTest.java` (new);
  - `plug/ReleaseSpec.java`: `getTagName()`, with the convention `v{version}` set in
    `ReleaseTasks`;
  - `plug/ReleaseTasks.java`, `plug/AgenticRelease.java`, `plug/ReleaseAction.java`;
  - `proc/release/ReleaseManifest.java`: the `tagName` key;
  - `proc/release/ContractRelease.java`: `Request.tagName`;
  - `ReleaseManifestTest.java`.
- **Accept:**
  - A template without `{version}`, or with two, is refused with a message.
  - `match("v1.0.0")` gives 1.0.0, while `match("1.0.0")` and `match("v1.0.0-rc.1")` give
    nothing.
  - `release.json` records `"tagName": "v1.0.0"`.
- **Depends:** B2, C4.

### D2. `GitRepository`: offline, read-only shell-out (§3.1)
- **Owns:**
  - `plug/GitRepository.java` (new);
  - `GitRepositoryTest.java` (new, real `git init` in `@TempDir`).
- **Accept:**
  - Detects a non-repository, a shallow clone (`git clone --depth 1 file://…`) and a missing
    executable (through an injectable command name).
  - Lists tags.
  - Peels an annotated tag to its commit.
  - Answers ancestry correctly for an ancestor, a non-ancestor and `HEAD` itself.
  - Reads blob bytes; a missing path gives empty.
  - A timeout fails.
  - No command writes to the repository: assert `git status --porcelain` and the reflog are
    unchanged.
- **Depends:** none (can run in parallel with Phases B and C).

### D3. `PublishedHistory`: the D10.5 table, proof and pending (§3.4)
- **Owns:**
  - `plug/PublishedHistory.java` (new);
  - `PublishedHistoryTest.java` (new, fake repository);
  - `modules/gradle-plugin/build.gradle.kts`: add `testImplementation(project(":modules:processor"))`.
- **Accept:** one test per table row and per proof failure:
  - tree missing `release.json`;
  - tree missing `api.ir.json`;
  - digest mismatch inside the tag;
  - the tag differs from the working copy;
  - not an ancestor;
  - does not peel to a commit.

  Every message names the tag or version and the remedy. The newest snapshot without a tag is
  PENDING; an older one fails.
- **Depends:** D1, D2.

### D5. Migrate the existing functional tests to tagged history (runs **before** D4)
- **Owns:**
  - `GitFixture.java` (new, `src/functionalTest`);
  - `AgenticReleaseFunctionalTest.java`, trimmed to single-release and basic cases;
  - `ReleasePolicyFunctionalTest.java` (new), holding the multi-release cases moved from it.
- **Do:**
  - `git init`, then commit and tag each release in `setup` and helpers.
  - Repo-local identity and signing off, in the throwaway repository only.
- **Accept:**
  - Green against the current, git-ignorant plugin.
  - Both files under 500 lines.
- **Depends:** B2, C6.

### D4. Wire publication into `agenticRelease` (F1, D10.1, D10.4, D10.6, AC7, AC9)
- **Owns:**
  - `plug/ReleaseAction.java`: compute the verdict, pass `published`;
  - `proc/release/ContractRelease.java`: `Request.published`, the pending refusal, and
    `Release(…, published)`, replacing B1's transitional `true`;
  - `proc/release/ReleaseHistory.java`;
  - `ContractReleaseTest.java`.
- **Accept:**
  - The processor unit test refuses a release while a pending snapshot exists, and names it.
  - An unpublished deprecation earns no credit end to end.
  - The D5 functional tests stay green.
- **Depends:** D3, D5.

### D6. History functional tests (AC15, AC16)
- **Owns:** `ReleaseHistoryFunctionalTest.java` (new).
- **Accept (TestKit):**
  - A new project with no repository and no snapshots releases.
  - Each of these fails with the draft's remedy text:
    - an untagged older snapshot;
    - a tag without its snapshot;
    - a shallow clone;
    - snapshots without a repository (`GIT_CEILING_DIRECTORIES`);
    - a tag not an ancestor of `HEAD`;
    - a tag whose `release.json` differs from the working copy.
  - A pending release blocks the next `agenticRelease`.
  - An annotated tag works.
  - A `1.0.0` tag does not satisfy `v1.0.0`.
  - A custom `tagName` works.
- **Depends:** D4.

---

## Phase E: history check and verify split

### E1. `agenticReleaseHistoryCheck`, git-free, under `check` (D5.3, D6.1, AC13, AC14)
- **Owns:**
  - `plug/AgenticReleaseCheck.java`, renamed to `plug/AgenticReleaseHistoryCheck.java`;
  - `plug/ReleaseCheckAction.java`, renamed to `plug/ReleaseHistoryCheckAction.java`;
  - `plug/ReleaseSpec.java`: remove `checkVersion`;
  - `plug/ReleaseTasks.java`;
  - `proc/release/ReleaseHistory.java`: `checkConsistency`, covering ordering, the `previous`
    chain and aggregate changelog equality;
  - `ReleaseHistoryTest.java`;
  - the functional test that used `--release-version`, which is removed here and re-covered in E3.
- **Accept:**
  - A broken `previous` chain fails.
  - An edited `.atlas/CHANGELOG.md` fails.
  - The task runs with no git on the PATH and no repository.
  - `grep -rn "agenticReleaseCheck\|checkVersion" modules/` finds nothing.
- **Depends:** D4.

### E2. `agenticReleaseVerify` (D6.2, D8.2, §3.5)
- **Owns:**
  - `plug/AgenticReleaseVerify.java` (new);
  - `plug/ReleaseVerifyAction.java` (new);
  - `plug/ReleaseTasks.java`, registration outside `check`;
  - `proc/release/ReleaseHistory.java` or `ContractRelease.java`: `verifyBuild` compares the
    snapshot with the build (OQ-4: the IR canonically, generated artifacts byte for byte, the
    effective configuration structurally, and which artifacts are present or absent).
- **Accept:**
  - Covered by the E3 tests.
  - It writes no file.
- **Depends:** E1, C4.

### E3. Verify functional tests (AC14)
- **Owns:** `ReleaseVerifyFunctionalTest.java` (new).
- **Accept:**
  - Verify passes on the tagged commit with `-Pversion`.
  - It fails when the tag is missing, and when `HEAD` is not the tag (OQ-3 default).
  - It fails when the build's IR or OpenAPI differs.
  - A consistent rewrite of a published snapshot, with digests recomputed, passes
    `agenticReleaseHistoryCheck` and fails both `agenticReleaseVerify` and `agenticRelease`,
    naming the tag.
- **Depends:** E2.

---

## Phase F: relocate the filesystem work to the plugin (D9.3; required, OQ-1 decided)

### F1. Move snapshot writing, digests and staging into the plugin
- **Owns:**
  - `proc/release/ContractRelease.java` and `ReleaseHistory.java`, moved to
    `plug/ReleaseSnapshots.java` and `plug/ReleaseSnapshotHistory.java`;
  - their tests, moved to `modules/gradle-plugin/src/test`;
  - the `proc/release/package-info.java` Javadoc;
  - `plug/ReleaseAction.java`, `ReleaseHistoryCheckAction.java`, `ReleaseVerifyAction.java`.
- **Accept:**
  - The processor's `release` package keeps only the comparison, policy, changelog, manifest
    and version.
  - Every test from Phases A-E is green unmodified, except for package and imports.
- **Depends:** E3, and the owner's answer to OQ-1.

---

## Phase G: docs and final verification

### G1. Documentation and changelog (AC19)
- **Owns:**
  - `docs/contract-releases.md`: rewrite the task, layout, `contract-resources.json`, version,
    policy block, publication/tags/pending/history table, validation boundary, accept-then-release
    and CI recipe sections. The recipe uses `fetch-depth: 0`, `fetch-tags: true`, `agenticRelease`
    in the release PR, the tag, and `agenticReleaseVerify` in the release job.
  - Also document `.gitattributes` `-text`, and for multi-module repositories a unique tag pattern
    per module (`tagName`) with an independent release history each (OQ-7).
  - `docs/contract-governance.md`: its cross-references.
  - `docs/processor-internals.md`: the reserved paths and the manifest.
  - `CHANGELOG.md`: the Unreleased entries at lines 9-17.
  - `scripts/check-contract-docs.sh`: update the required terms at `:34`.
- **Accept:**
  - `bash scripts/check-contract-docs.sh` passes.
  - No doc mentions `agenticReleaseCheck`, `compareReleases`, `checkVersion`, `minReleases` or
    `minMajors`.
- **Depends:** E3, and F1 when it runs.

### G2. Final verification
- **Owns:** no source.
- **Accept:**
  - `./gradlew build` passes, javadoc and `functionalTest` included.
  - `./gradlew :demo:compileJava` passes, and the demo's class output holds
    `contract-resources.json`.
  - A final grep for stale names finds nothing.
  - Run `scripts/check-origin-alignment.sh release-workflow` if the script exists in the
    repository. It is absent from this worktree: report that rather than skipping silently.
  - Re-review, with `/team-review`, against the three findings and every gap-table row.
- **Depends:** G1.

---

## Dependency order (summary)

```
A1 ─┬─ B1 ── B2 ──────────────┐
A2 ─┼─ B3                     │
    └──────────┐              │
A5 ── C1 ─┬─ C2 ─┐            │
          └─ C3a ─ C3b ─┐     │
A3, A4 ─────────────────┴─ C4 ─┬─ C6 ── D5 ─┐
A4 ── C5 ───────────────────────┘           │
B2, C4 ── D1 ── D3 ── (D5) ── D4 ── D6 ── E1 ── E2 ── E3 ── [F1] ── G1 ── G2
D2 ───────────┘
```

Phases A, B and C can each be merged or pushed independently. D5 lands before D4, so the pending
rule never breaks an existing test.
