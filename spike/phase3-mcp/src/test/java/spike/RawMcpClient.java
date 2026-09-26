package spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Plain-HTTP JSON-RPC client over MCP SSE or Streamable HTTP; returns raw JSON-RPC responses. */
abstract class RawMcpClient implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final String base;
    private final AtomicInteger ids = new AtomicInteger();

    RawMcpClient(String base) {
        this.base = base;
    }

    abstract JsonNode send(String json, boolean expectResponse) throws Exception;

    JsonNode initialize(String protocolVersion) throws Exception {
        JsonNode init = request("initialize", "{\"protocolVersion\":\"" + protocolVersion
                + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"spike\",\"version\":\"1\"}}");
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", false);
        return init;
    }

    JsonNode request(String method, String params) throws Exception {
        return send("{\"jsonrpc\":\"2.0\",\"id\":" + ids.incrementAndGet() + ",\"method\":\"" + method
                + "\"" + (params == null ? "" : ",\"params\":" + params) + "}", true);
    }

    JsonNode call(String tool, String args) throws Exception {
        return request("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + args + "}");
    }

    @Override
    public void close() {
    }

    /** Streamable HTTP: POST /mcp, response is JSON or an SSE stream carrying it. */
    static final class Streamable extends RawMcpClient {
        private String session;

        Streamable(String base) {
            super(base);
        }

        @Override
        JsonNode send(String json, boolean expectResponse) throws Exception {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + "/mcp"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (session != null) {
                req.header("Mcp-Session-Id", session);
            }
            HttpResponse<Stream<String>> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofLines());
            resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session = s);
            if (!expectResponse) {
                resp.body().close();
                return null;
            }
            try (Stream<String> lines = resp.body()) {
                return lines.map(l -> l.startsWith("data:") ? l.substring(5).trim() : l.trim())
                        .filter(l -> l.startsWith("{"))
                        .map(RawMcpClient::parse)
                        .filter(n -> n.has("result") || n.has("error"))
                        .findFirst().orElseThrow();
            }
        }
    }

    /** Legacy SSE: GET /sse for the endpoint + responses, POST /mcp/message?sessionId=... */
    static final class Sse extends RawMcpClient {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final String endpoint;
        private final Thread reader;

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
                            event[0] = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            ("endpoint".equals(event[0]) ? endpoints : messages).add(data);
                        }
                    });
                } catch (Exception ignored) {
                    // stream closed at shutdown
                }
            }, "sse-reader");
            reader.setDaemon(true);
            reader.start();
            endpoint = endpoints.poll(10, TimeUnit.SECONDS);
        }

        @Override
        JsonNode send(String json, boolean expectResponse) throws Exception {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(base + endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                throw new IllegalStateException(resp.statusCode() + " " + resp.body());
            }
            if (!expectResponse) {
                return null;
            }
            String msg = messages.poll(10, TimeUnit.SECONDS);
            if (msg == null) {
                throw new IllegalStateException("no SSE response for " + json);
            }
            return parse(msg);
        }

        @Override
        public void close() {
            reader.interrupt();
        }
    }

    static JsonNode parse(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(s, e);
        }
    }
}
