/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import java.util.List;
import java.util.Optional;

/**
 * The read-only git operations {@link PublishedHistory} needs to prove a release's tag.
 * {@link GitRepository} is the real, offline implementation; a test uses a fake one, so
 * {@link PublishedHistory}'s tests need no real repository (unlike {@link GitRepositoryTest},
 * which exercises {@link GitRepository} itself against a real one).
 */
interface GitQuery {

    /** Whether a git repository was found at all. */
    boolean isInsideWorkTree();

    /** Whether the repository is a shallow clone. */
    boolean isShallow();

    /** Every tag in the repository. */
    List<String> tags();

    /** The commit a tag peels to, or empty when it does not exist or does not peel to a commit. */
    Optional<String> peelToCommit(String tag);

    /** Whether {@code commit} is an ancestor of {@code HEAD}, or is {@code HEAD} itself. */
    boolean isAncestorOfHead(String commit);

    /** The bytes of {@code path} as {@code commit}'s tree has it, or empty when it does not exist. */
    byte[] blobBytes(String commit, String path);
}
