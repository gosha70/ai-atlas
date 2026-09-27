/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A raw JSON-RPC MCP client over plain HTTP, against a server started on a real port: MCP SSE or
 * Streamable HTTP.
 */
abstract class RawMcpClient implements AutoCloseable {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final ObjectMapper JSON = new ObjectMapper();

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final String base;
    private int nextId = 1;

    RawMcpClient(String base) {
        this.base = base;
    }

    abstract String protocolVersion();

    /** Sends one message; returns the JSON-RPC response when one is expected. */
    abstract JsonNode send(String json, boolean expectResponse) throws Exception;

    void initialize() throws Exception {
        request("initialize", "{\"protocolVersion\":\"" + protocolVersion() + "\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"atlas-test\",\"version\":\"1\"}}");
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", false);
    }

    JsonNode call(String tool, String arguments) throws Exception {
        return request("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}");
    }

    /** Sends a request and returns its {@code result}. */
    JsonNode request(String method, String params) throws Exception {
        JsonNode response = send("{\"jsonrpc\":\"2.0\",\"id\":" + nextId++ + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}", true);
        if (response.has("error")) {
            throw new AssertionError(method + " failed: " + response.get("error"));
        }
        return response.get("result");
    }

    @Override
    public void close() {
    }

    static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Streamable HTTP: {@code POST /mcp}, answered as JSON or as an SSE stream carrying it. */
    static final class Streamable extends RawMcpClient {

        private String session;

        Streamable(String base) {
            super(base);
        }

        @Override
        String protocolVersion() {
            return "2025-03-26";
        }

        @Override
        JsonNode send(String json, boolean expectResponse) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/mcp"))
                    .timeout(RESPONSE_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (session != null) {
                request.header(SESSION_HEADER, session);
            }
            HttpResponse<Stream<String>> response = http.send(request.build(), HttpResponse.BodyHandlers.ofLines());
            response.headers().firstValue(SESSION_HEADER).ifPresent(id -> session = id);
            try (Stream<String> lines = response.body()) {
                if (!expectResponse) {
                    return null;
                }
                return lines.map(line -> line.startsWith(SSE_DATA_PREFIX)
                                ? line.substring(SSE_DATA_PREFIX.length()).trim() : line.trim())
                        .filter(line -> line.startsWith("{"))
                        .map(RawMcpClient::parse)
                        .filter(message -> message.has("result") || message.has("error"))
                        .findFirst().orElseThrow();
            }
        }
    }

    /** SSE: {@code GET /sse} for the message endpoint and the responses, {@code POST} to the endpoint. */
    static final class Sse extends RawMcpClient {

        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final Thread reader;
        private final String endpoint;

        Sse(String base) throws Exception {
            super(base);
            BlockingQueue<String> endpoints = new LinkedBlockingQueue<>();
            HttpResponse<Stream<String>> stream = http.send(HttpRequest.newBuilder(URI.create(base + "/sse"))
                    .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofLines());
            reader = new Thread(() -> {
                String[] event = {null};
                try {
                    stream.body().forEach(line -> {
                        if (line.startsWith("event:")) {
                            event[0] = line.substring("event:".length()).trim();
                        } else if (line.startsWith(SSE_DATA_PREFIX)) {
                            String data = line.substring(SSE_DATA_PREFIX.length()).trim();
                            ("endpoint".equals(event[0]) ? endpoints : messages).add(data);
                        }
                    });
                } catch (UncheckedIOException e) {
                    // the stream closes at shutdown
                }
            }, "mcp-sse-reader");
            reader.setDaemon(true);
            reader.start();
            endpoint = endpoints.poll(RESPONSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(endpoint).as("SSE endpoint event").isNotNull();
        }

        @Override
        String protocolVersion() {
            return "2024-11-05";
        }

        @Override
        JsonNode send(String json, boolean expectResponse) throws Exception {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isLessThan(300);
            if (!expectResponse) {
                return null;
            }
            String message = messages.poll(RESPONSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(message).as("SSE response to " + json).isNotNull();
            return parse(message);
        }

        @Override
        public void close() {
            reader.interrupt();
        }
    }
}
