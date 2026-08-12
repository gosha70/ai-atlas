/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * One spawned {@code atlas-mcp.jar} process and the stdio client talking to it — the harness behind
 * {@link AtlasMcpServerTest}, which shares a single server across all its tests exactly as a
 * harness session would.
 *
 * <p>That sharing is why this class exists rather than a bare {@link McpSyncClient}. A server that
 * stops answering does not fail one test, it fails every test after it, and each one first waits
 * out the request timeout — a wedged server turns a two-minute build into a half-hour one whose
 * report says only "timeout" with no trace of why. So this class does two things a plain client
 * does not: it drains the child's stderr, which is where a crash, an OOM or a stack dump appears
 * and which is otherwise discarded, and it latches the first timeout so every later call fails
 * immediately, pointing at that first failure and carrying the server's output.
 */
final class McpServerSession {

    /** Cap on retained server stderr — enough for a stack dump, bounded if the child spews. */
    private static final int MAX_CAPTURED_STDERR_LINES = 500;

    private final Deque<String> stderr = new ArrayDeque<>();
    private final Duration callTimeout;

    /** Set once by {@link #start}: the stderr handler must be wired before the client is built. */
    private McpSyncClient client;

    /** Why the session stopped answering, or {@code null} while it is healthy. */
    private volatile String broken;

    private McpServerSession(Duration callTimeout) {
        this.callTimeout = callTimeout;
    }

    /**
     * Spawns the server and connects to it.
     *
     * @param jar         the packaged {@code atlas-mcp.jar} to run
     * @param childJvmArg an extra argument for the child's command line, or {@code null}
     * @param callTimeout per-request budget; a request that outlives it marks the session broken
     */
    static McpServerSession start(String jar, String childJvmArg, Duration callTimeout) {
        // The spec's .mcp.json launch, verbatim: {"command":"java","args":["-jar","atlas-mcp.jar"]}
        // — plus the build's JaCoCo agent argument when present, so the child's coverage counts.
        List<String> args = new ArrayList<>();
        if (childJvmArg != null && !childJvmArg.isBlank()) {
            args.add(childJvmArg);
        }
        args.add("-jar");
        args.add(jar);
        return launch(javaExecutable(), args, callTimeout);
    }

    /**
     * Spawns {@code command} as the server and connects to it.
     *
     * <p>{@link McpServerSessionTest} launches a deliberately unresponsive command through here to
     * pin what this class promises when a server stops answering.
     */
    static McpServerSession launch(String command, List<String> args, Duration callTimeout) {
        ServerParameters parameters = ServerParameters.builder(command)
                .args(args.toArray(String[]::new))
                .build();
        StdioClientTransport transport =
                new StdioClientTransport(parameters, new JacksonMcpJsonMapper(new ObjectMapper()));
        McpServerSession session = new McpServerSession(callTimeout);
        // Before the client is built, so nothing the server says on the way up is lost.
        transport.setStdErrorHandler(session::capture);
        session.client = McpClient.sync(transport).requestTimeout(callTimeout).build();
        return session;
    }

    /** The {@code java} of the JVM running the tests — a JDK, which generation requires. */
    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    McpSchema.InitializeResult initialize() {
        return call("initialize", client::initialize);
    }

    McpSchema.ListToolsResult listTools() {
        return call("listTools", client::listTools);
    }

    McpSchema.CallToolResult callTool(String tool, Map<String, Object> arguments) {
        return call("callTool(" + tool + ")",
                () -> client.callTool(new McpSchema.CallToolRequest(tool, arguments)));
    }

    void close() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    /**
     * Runs one round trip, converting the timeout a wedged server produces into a failure that
     * names the call and carries the server's stderr, and short-circuiting every call after it.
     */
    private <T> T call(String description, Supplier<T> roundTrip) {
        String cause = broken;
        if (cause != null) {
            throw new AssertionError("Skipped — the shared server session is already broken. "
                    + cause);
        }
        try {
            return roundTrip.get();
        } catch (RuntimeException e) {
            if (!timedOut(e)) {
                throw e;
            }
            broken = description + " timed out after " + callTimeout;
            throw new AssertionError(description + " timed out after " + callTimeout
                    + " — the server process stopped answering." + System.lineSeparator()
                    + "Server stderr:" + System.lineSeparator() + stderr(), e);
        }
    }

    /** Whether a request timeout — the shape a wedged server produces — is in the cause chain. */
    private static boolean timedOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    /** Drains one stderr line from the spawned server, keeping the most recent ones. */
    private void capture(String line) {
        if (line == null) {
            return;
        }
        synchronized (stderr) {
            stderr.addLast(line);
            if (stderr.size() > MAX_CAPTURED_STDERR_LINES) {
                stderr.removeFirst();
            }
        }
    }

    /** What the spawned server has written to stderr so far. */
    String stderr() {
        synchronized (stderr) {
            return stderr.isEmpty()
                    ? "<the server process wrote nothing to stderr>"
                    : String.join(System.lineSeparator(), stderr);
        }
    }
}
