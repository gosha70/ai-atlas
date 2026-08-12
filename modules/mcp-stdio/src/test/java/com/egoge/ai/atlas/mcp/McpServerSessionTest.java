/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@link McpServerSession} promises when the server stops answering — the case that made
 * {@link AtlasMcpServerTest} take half an hour to report "timeout" and nothing else on a CI runner.
 *
 * <p>Driven by a real spawned process that speaks no MCP at all, so the failure arrives the way a
 * wedged server's does: the request never gets a reply.
 */
class McpServerSessionTest {

    /** Long enough to prove a timeout happened, short enough that this test stays cheap. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    /** What {@link WedgedServer} writes before going quiet — the evidence a report must carry. */
    private static final String SERVER_COMPLAINT = "server-is-about-to-wedge";

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    @DisplayName("a server that stops answering is reported with its stderr, not a bare timeout")
    void timeoutCarriesTheServerStderr() {
        McpServerSession session = wedgedServer();
        try {
            assertThatThrownBy(session::initialize)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("initialize")
                    .hasMessageContaining("stopped answering")
                    // Without this the CI report says "TimeoutException" and nothing about why.
                    .hasMessageContaining(SERVER_COMPLAINT);
        } finally {
            session.close();
        }
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    @DisplayName("once the session is broken the next call fails at once, without waiting again")
    void aBrokenSessionShortCircuits() {
        McpServerSession session = wedgedServer();
        try {
            assertThatThrownBy(session::initialize).isInstanceOf(AssertionError.class);

            long startedAt = System.nanoTime();
            assertThatThrownBy(session::listTools)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("already broken")
                    .hasMessageContaining("initialize");
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

            // The point of the latch: every test after the first failure used to pay the timeout
            // again, which is what turned one wedged server into a half-hour build.
            assertThat(waited).as("a broken session must not wait out the timeout again")
                    .isLessThan(CALL_TIMEOUT);
        } finally {
            session.close();
        }
    }

    /** A live process that greets on stderr and then never speaks MCP again. */
    private static McpServerSession wedgedServer() {
        return McpServerSession.launch(McpServerSession.javaExecutable(),
                List.of("-cp", System.getProperty("java.class.path"), WedgedServer.class.getName()),
                CALL_TIMEOUT);
    }

    /**
     * Stands in for a server that has stopped responding: it writes one line to stderr, then reads
     * its stdin to the end without ever replying. Spawned as a real child process, so the client
     * hits the same request timeout a wedged {@code atlas-mcp.jar} produces.
     */
    static final class WedgedServer {

        private WedgedServer() {
        }

        public static void main(String[] args) throws Exception {
            System.err.println(SERVER_COMPLAINT);
            System.err.flush();
            // Consume stdin so the client's writes never block, but answer nothing.
            while (System.in.read() != -1) {
                continue;
            }
        }
    }
}
