/**
 * The release workflow: immutable released contracts under a deprecation policy.
 *
 * <p>{@link com.egoge.ai.atlas.processor.release.ContractRelease} snapshots the accepted Contract IR
 * as a release, compares it with the previous release through
 * {@link com.egoge.ai.atlas.processor.contract.ContractGate#compareReleases}, checks the
 * {@link com.egoge.ai.atlas.processor.release.ReleasePolicy}, and renders the
 * {@link com.egoge.ai.atlas.processor.release.ReleaseChangelog}. It runs outside the compilation:
 * the Gradle plugin loads it from the {@code annotationProcessor} classpath.
 */
package com.egoge.ai.atlas.processor.release;
