---
feature_id: contract-ir-gate
date: 2026-09-25
status: final
phase: build
mode: review
subject_provider: claude
peer_provider: deepseek
peer_profile: deepseek
runner_fingerprint: ae8dc147cf1264834bab35d116b6ab96b06618f438763cde3a90621d78036dba
verdict: PASS
blocking_findings_open: 0
target_ref: feature/contract-ir-gate
rounds_completed: 1
attempt_count: 1
bypass: false
---

# Peer Review: contract-ir-gate — Build Phase

**Reviewer**: deepseek
**Scope**: both
**Rounds**: 1
**Verdict**: PASS

## Summary

This round adds the demo's committed contract baseline, a byte-identity test, and the governance/annotation/changelog documentation for the contract IR gate. The changes are mostly documentation and a golden-file test, but the test's correctness depends on the emitted IR being on the test classpath and on the baseline being regenerated whenever the demo's contract changes — a coupling worth scrutinizing.

## Findings

- [warning] f-f147d035: The test reads `/META-INF/ai-atlas/api.ir.json` from the test classpath, but the IR is written to the *main* compilation's class output. Whether it is visible to the test runtime depends on the demo's `test` source set depending on `main` output (it does by default in Gradle, but the test does not assert the resource is the freshly emitted one vs. a stale copy from a previous build). If the processor is skipped (e.g., incremental compile with no changes) the resource may be stale, and the test would pass against a stale IR rather than the current sources. (demo/src/test/java/com/egoge/ai/atlas/demo/ContractBaselineTest.java)
- [warning] f-56fcb4c5: The baseline is declared as an input of `tasks.test` with `PathSensitivity.NONE`, but the test only reads it via a system property pointing at the absolute path. Declaring it as an input is correct for re-run triggering, but the test also depends on the *emitted* IR (main compile output), which is not declared as a test input. A change to demo sources that alters the IR without touching the baseline will re-run `compileJava` but may not re-run `test` if Gradle considers test inputs unchanged — the test would then not catch the divergence until something else forces it. (demo/build.gradle.kts)
- [note] f-a383a6c7: The doc states a configured major below the baseline's published major is a compile ERROR, but does not state what happens when the configured major is *equal* to M and the baseline's `apiMajor` is stale relative to a bumped `ai.atlas.api.major`. The "Accept after bumping `ai.atlas.api.major`" guidance in the version-control section implies the baseline must be re-accepted, but the error/behavior for the un-re-accepted case is not spelled out. (docs/contract-governance.md)
- [note] f-79b0852a: The changelog entry references "the fix below" and an "ADR-9 later-round OpenAPI/deprecation-manifest fix" in tasks.md, but the changelog text itself does not name the ADR or link to it. A reader of the changelog alone cannot trace the fix to its decision record. (CHANGELOG.md)
- [note] f-fecd2e19: The committed baseline records `apiMajor: 2`, and the demo's `findByPriority` operation has `apiSince: 3` (inactive at M=2). This is consistent with the documented projection semantics, but it means the demo baseline does not exercise the "operation added at a future major" path in the gate's comparison at M=2. If the intent is to demonstrate the gate protecting a published major while a future major is in development, this is fine; if the intent is to exercise the full rule set, a test fixture covering the future-major case would be stronger. (demo/.atlas/api.ir.json)
- [note] f-c0a35ae6: The test fails with a clear message if the system property is missing, which is good. However, it does not assert that the property points at the *committed* baseline under version control (e.g., that the path is inside the project directory). A misconfigured build could point the property at a generated file and the test would pass while the committed baseline drifts. (demo/src/test/java/com/egoge/ai/atlas/demo/ContractBaselineTest.java)
