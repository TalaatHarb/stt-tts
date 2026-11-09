package net.talaatharb.stttts.mcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.talaatharb.stttts.config.AppSettings;

/** Stateless MCP Streamable HTTP with JSON responses; never binds to a wildcard or public interface. */
@Singleton
public class LocalMcpServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LocalMcpServer.class);
    private static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
    private static final int MAX_BODY = 12 * 1024 * 1024;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final AppSettings settings;
    private final AudioMcpTools tools;
    private final Set<CompletableFuture<ObjectNode>> calls = ConcurrentHashMap.newKeySet();
    private HttpServer server;
    private ExecutorService executor;

    @Inject
    public LocalMcpServer(AppSettings settings, AudioMcpTools tools) {
        this.settings = settings;
        this.tools = tools;
    }

    public synchronized void start() throws IOException {
        start(settings.mcpPort(), settings.mcpToken());
    }

    synchronized void start(int port, String token) throws IOException {
        if (server != null) {
            return;
        }
        tools.validateDefaults();
        HttpServer created = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), work -> {
                    Thread thread = new Thread(work, "stt-tts-mcp");
                    thread.setDaemon(true);
                    return thread;
                });
        int actualPort = created.getAddress().getPort();
        created.createContext("/mcp", exchange -> handle(exchange, actualPort, token));
        created.setExecutor(workers);
        try {
            created.start();
        } catch (RuntimeException e) {
            created.stop(0);
            workers.shutdownNow();
            throw e;
        }
        server = created;
        executor = workers;
        LOG.info("MCP server listening at {}", endpoint());
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    synchronized InetSocketAddress boundAddress() {
        return server.getAddress();
    }

    public synchronized URI endpoint() {
        return URI.create("http://127.0.0.1:" + (server == null ? settings.mcpPort() : server.getAddress().getPort())
                + "/mcp");
    }

    private void handle(HttpExchange exchange, int port, String token) {
        try (exchange) {
            if (!safeHeaders(exchange, port)) {
                send(exchange, 403, error(null, -32600, "Forbidden Host or Origin"));
                return;
            }
            String authorization = singleHeader(exchange, "Authorization");
            if (authorization == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                    authorization.getBytes(StandardCharsets.UTF_8))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"STT-TTS MCP\"");
                send(exchange, 401, error(null, -32600, "Bearer token required; see settings.properties"));
                return;
            }
            if (!"/mcp".equals(exchange.getRequestURI().getPath()) || exchange.getRequestURI().getRawQuery() != null) {
                send(exchange, 404, error(null, -32600, "MCP endpoint is /mcp"));
                return;
            }
            String version = singleHeader(exchange, "MCP-Protocol-Version");
            if (exchange.getRequestHeaders().containsKey("MCP-Protocol-Version")
                    && (version == null || !VERSIONS.contains(version))) {
                send(exchange, 400, error(null, -32600, "Unsupported MCP-Protocol-Version"));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                send(exchange, 405, null);
                return;
            }
            String contentType = singleHeader(exchange, "Content-Type");
            if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";", 2)[0].strip())) {
                send(exchange, 415, error(null, -32600, "Content-Type must be application/json"));
                return;
            }
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (accept == null || !accepts(accept, "application/json") || !accepts(accept, "text/event-stream")) {
                send(exchange, 406, error(null, -32600, "Accept must include application/json and text/event-stream"));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                send(exchange, 413, error(null, -32600, "Request exceeds 12 MiB"));
                return;
            }
            JsonNode message;
            try {
                message = JSON.readTree(body);
            } catch (IOException e) {
                send(exchange, 400, error(null, -32700, "Invalid JSON"));
                return;
            }
            if (!validMessage(message)) {
                send(exchange, 400, error(null, -32600, "Expected a single JSON-RPC 2.0 message"));
                return;
            }
            if (!message.has("method") || !message.has("id")) {
                send(exchange, 202, null);
                return;
            }
            ObjectNode reply;
            try {
                reply = dispatch(message);
            } catch (RuntimeException e) {
                LOG.error("MCP request failed", e);
                reply = error(message.get("id"), -32603, "Internal server error; see application log");
            }
            send(exchange, 200, reply);
        } catch (IOException e) {
            LOG.warn("MCP HTTP connection failed", e);
        } catch (RuntimeException e) {
            LOG.error("MCP HTTP handler failed", e);
        }
    }

    private ObjectNode dispatch(JsonNode message) {
        JsonNode id = message.get("id");
        JsonNode params = message.path("params");
        switch (message.get("method").textValue()) {
            case "initialize": {
                if (!params.path("protocolVersion").isTextual() || !params.path("capabilities").isObject()
                        || !params.path("clientInfo").path("name").isTextual()
                        || !params.path("clientInfo").path("version").isTextual()) {
                    return error(id, -32602, "initialize requires protocolVersion, capabilities and clientInfo");
                }
                String requested = params.get("protocolVersion").textValue();
                ObjectNode result = JSON.createObjectNode().put("protocolVersion",
                        VERSIONS.contains(requested) ? requested : VERSIONS.get(0));
                result.putObject("capabilities").putObject("tools").put("listChanged", false);
                result.putObject("serverInfo").put("name", "stt-tts-studio").put("version", "1.0.0");
                result.put("instructions", "Local speech/music/SFX tools. Inspect list_models for voices and licenses. "
                        + "Downloads require allow_download=true; generation requires accept_license=true. "
                        + "Only one desktop or MCP model job can run at a time.");
                return response(id, result);
            }
            case "ping":
                return response(id, JSON.createObjectNode());
            case "tools/list":
                if (params.has("cursor")) {
                    return error(id, -32602, "This server returns all tools in one page; omit cursor");
                }
                ObjectNode list = JSON.createObjectNode();
                list.set("tools", tools.definitions());
                return response(id, list);
            case "tools/call":
                if (!params.path("name").isTextual() || !tools.hasTool(params.path("name").asText())) {
                    return error(id, -32602, "Unknown or missing tool name");
                }
                JsonNode args = params.path("arguments");
                if (args.isMissingNode() || args.isNull() || args.isTextual() && args.textValue().isBlank()) {
                    args = JSON.createObjectNode();
                } else if (args.isTextual()) {
                    // Some clients send the arguments object as a JSON string.
                    try {
                        args = JSON.readTree(args.textValue());
                    } catch (IOException e) {
                        return error(id, -32602, "arguments must be an object");
                    }
                }
                if (args == null || !args.isObject()) {
                    return error(id, -32602, "arguments must be an object");
                }
                CompletableFuture<ObjectNode> call = tools.call(params.get("name").textValue(), args);
                calls.add(call);
                try {
                    return response(id, call.get());
                } catch (ExecutionException e) {
                    return toolError(id, e.getCause());
                } catch (CancellationException e) {
                    return toolError(id, e);
                } catch (InterruptedException e) {
                    call.cancel(true);
                    Thread.currentThread().interrupt();
                    return toolError(id, e);
                } finally {
                    calls.remove(call);
                }
            default:
                return error(id, -32601, "Method not found");
        }
    }

    private static boolean validMessage(JsonNode message) {
        if (message == null || !message.isObject() || !"2.0".equals(message.path("jsonrpc").asText())) {
            return false;
        }
        JsonNode id = message.get("id");
        if (id != null && !id.isTextual() && !(id.isIntegralNumber() && id.canConvertToLong())) {
            return false;
        }
        if (message.has("method")) {
            return message.get("method").isTextual() && !message.has("result") && !message.has("error")
                    && (!message.has("params") || message.get("params").isObject());
        }
        return id != null && (message.has("result") != message.has("error"))
                && (!message.has("error") || message.get("error").isObject());
    }

    private static boolean accepts(String header, String mimeType) {
        for (String item : header.split(",")) {
            String[] parts = item.strip().split(";");
            if (mimeType.equalsIgnoreCase(parts[0].strip())) {
                for (int i = 1; i < parts.length; i++) {
                    if (parts[i].strip().matches("q=0(?:\\.0*)?")) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static boolean safeHeaders(HttpExchange exchange, int port) {
        String host = singleHeader(exchange, "Host");
        boolean defaultPort = port == 80 && ("127.0.0.1".equals(host) || "localhost".equals(host));
        if (!defaultPort && !List.of("127.0.0.1:" + port, "localhost:" + port).contains(host == null ? "" : host)) {
            return false;
        }
        if (exchange.getRequestHeaders().containsKey("Origin")) {
            String origin = singleHeader(exchange, "Origin");
            return port == 80 && ("http://127.0.0.1".equals(origin) || "http://localhost".equals(origin))
                    || List.of("http://127.0.0.1:" + port, "http://localhost:" + port)
                    .contains(origin == null ? "" : origin);
        }
        return true;
    }

    private static String singleHeader(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values != null && values.size() == 1 ? values.get(0) : null;
    }

    private static ObjectNode response(JsonNode id, JsonNode result) {
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    private static ObjectNode toolError(JsonNode id, Throwable failure) {
        LOG.warn("MCP tool failed", failure);
        ObjectNode result = JSON.createObjectNode().put("isError", true);
        result.putArray("content").addObject().put("type", "text").put("text",
                failure.getMessage() == null ? failure.toString() : failure.getMessage());
        return response(id, result);
    }

    private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            byte[] bytes = JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
            calls.forEach(call -> call.cancel(true));
            executor.shutdownNow();
            executor = null;
            LOG.info("MCP server stopped");
        }
    }
}
