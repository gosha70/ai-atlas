/**
 * The release workflow's transport-independent core: comparing two releases, checking the
 * deprecation policy, rendering the changelog, and the release manifest's format.
 *
 * <p>{@link com.egoge.ai.atlas.processor.contract.ReleaseComparison#compare} compares a release
 * with the previous one; {@link com.egoge.ai.atlas.processor.release.ReleasePolicy} checks the
 * deprecation policy against that comparison; {@link com.egoge.ai.atlas.processor.release.ReleaseChangelog}
 * renders a release's changelog section; {@link com.egoge.ai.atlas.processor.release.ReleaseManifest}
 * is {@code release.json}'s pure format; {@link com.egoge.ai.atlas.processor.release.ReleaseVersion}
 * parses and orders {@code MAJOR.MINOR.PATCH} versions. None of these classes touch the filesystem,
 * git or Gradle.
 *
 * <p>The filesystem work this package's classes once did — writing a release snapshot, computing
 * digests, staging and the atomic move into {@code <releases>/<version>/}, reading and verifying
 * the committed history, and rewriting the aggregate changelog — moved to the Gradle plugin (F1):
 * the plugin's {@code ReleaseSnapshots} and {@code ReleaseSnapshotHistory} call these classes from
 * the {@code annotationProcessor} classpath, in the isolated class loader the release and verify
 * worker actions already run in.
 */
package com.egoge.ai.atlas.processor.release;
