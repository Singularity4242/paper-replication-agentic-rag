package com.example.paperassistant.client;

import com.example.paperassistant.config.ChatProperties;
import com.example.paperassistant.config.IngestionProperties;
import com.example.paperassistant.model.dto.ChatScopeDTO;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class HttpRagChatClientTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> body = new AtomicReference<>("");
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private HttpRagChatClient client;
    private final ChatScopeDTO scope = new ChatScopeDTO(1, "学习率是多少？", List.of(
            new ChatScopeDTO.Document(10, "rag-10", "a".repeat(64), "实际文件名.md")), List.of());
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final RagChatClient.EventSink sink = (name, data) -> events.add(Map.of("event", name, "data", data));

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        client = client(Duration.ofSeconds(3));
    }

    private HttpRagChatClient client(Duration timeout) {
        var ingestion = new IngestionProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "tests", 1, 3, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(65), Duration.ofSeconds(1));
        return new HttpRagChatClient(ingestion, new ChatProperties(timeout, 1));
    }

    @AfterEach
    void stop() {
        client.close();
        server.stop(0);
        executor.shutdownNow();
    }

    private String frame(String event, Object data) {
        return "event: " + event + "\ndata: " + json.writeValueAsString(data) + "\n\n";
    }

    private String answer(String ragId) {
        return frame("answer", Map.of("answer", "学习率是 0.001。[1]", "outcome", "ANSWERED", "citations", List.of(
                Map.of("index", 1, "documentId", 10, "ragDocumentId", ragId, "filename", "untrusted.md",
                        "chunkId", "chunk-1", "pageNumbers", List.of(2), "content", "learning rate 0.001"))));
    }

    private void serve(String content) {
        server.createContext("/internal/chat/stream", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                // Fragment UTF-8 sequences and event frames across writes.
                for (byte b : content.getBytes(StandardCharsets.UTF_8)) { out.write(b); }
            }
        });
    }

    @Test
    void forwardsIncrementalEventsAndMapsCitationsFromTrustedBusinessMetadata() throws Exception {
        serve(frame("status", Map.of("phase", "SEARCHING")) + frame("delta", Map.of("text", "学习率"))
                + answer("rag-10") + frame("done", Map.of("status", "COMPLETED")));
        client.stream(scope, sink);
        assertEquals(List.of("status", "delta", "answer", "done"), events.stream().map(e -> e.get("event")).toList());
        var payload = json.readTree(body.get());
        assertEquals("tests", payload.path("namespace").asString());
        assertEquals("rag-10", payload.path("documents").get(0).path("ragDocumentId").asString());
        String result = json.writeValueAsString(events);
        assertTrue(result.contains("实际文件名.md"));
        assertFalse(result.contains("untrusted.md"));
        assertFalse(result.contains("rag-10"));
    }

    @Test
    void foreignCitationIsNeverForwarded() throws Exception {
        serve(answer("rag-other") + frame("done", Map.of("status", "COMPLETED")));
        client.stream(scope, sink);
        assertEquals(1, events.size());
        assertTrue(json.writeValueAsString(events).contains("INVALID_RAG_RESPONSE"));
        assertFalse(json.writeValueAsString(events).contains("learning rate"));
    }

    @Test
    void earlyEofAndPrematureDoneCannotBecomeSuccess() throws Exception {
        serve(frame("delta", Map.of("text", "partial")));
        client.stream(scope, sink);
        assertTrue(json.writeValueAsString(events).contains("RAG_STREAM_INTERRUPTED"));
        server.removeContext("/internal/chat/stream");
        events.clear();
        serve(frame("done", Map.of("status", "COMPLETED")));
        client.stream(scope, sink);
        assertTrue(json.writeValueAsString(events).contains("INVALID_RAG_RESPONSE"));
    }

    @Test
    void stalledBodyAfterHeadersIsBoundedByDeadline() throws Exception {
        client.close();
        client = client(Duration.ofMillis(200));
        var headers = new CountDownLatch(1);
        server.createContext("/internal/chat/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                headers.countDown();
                Thread.sleep(2000);
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> client.stream(scope, sink));
        assertTrue(headers.await(1, TimeUnit.SECONDS));
        assertTrue(json.writeValueAsString(events).contains("RAG_TIMEOUT"));
    }

    @Test
    void malformedAndOversizeFramesFailClosed() throws Exception {
        serve("event: answer\ndata: {not-json}\n\n");
        client.stream(scope, sink);
        assertTrue(json.writeValueAsString(events).contains("INVALID_RAG_RESPONSE"));
        server.removeContext("/internal/chat/stream");
        events.clear();
        serve("data: " + "x".repeat(262145));
        client.stream(scope, sink);
        assertTrue(json.writeValueAsString(events).contains("INVALID_RAG_RESPONSE"));
    }

    @Test
    void downstreamDisconnectPropagatesAndReleasesSlot() throws Exception {
        serve(frame("delta", Map.of("text", "first")) + answer("rag-10") + frame("done", Map.of("status", "COMPLETED")));
        assertThrows(IOException.class, () -> client.stream(scope, (name, data) -> { throw new IOException("disconnected"); }));
        client.stream(scope, sink);
        assertEquals("done", events.getLast().get("event"));
    }

    @Test
    void connectionFailureAndInternalErrorNeverExposeUpstreamDetails() throws Exception {
        serve(frame("error", Map.of("code", "RAG_CHAT_FAILED", "message", "secret-key-private-path")));
        client.stream(scope, sink);
        assertFalse(json.writeValueAsString(events).contains("secret-key"));
        events.clear();
        server.stop(0);
        client.stream(scope, sink);
        assertTrue(json.writeValueAsString(events).contains("RAG_UNAVAILABLE"));
    }
}
