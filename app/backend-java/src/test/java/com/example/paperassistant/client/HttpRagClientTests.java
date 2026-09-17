package com.example.paperassistant.client;

import com.example.paperassistant.config.DocumentStorageProperties;
import com.example.paperassistant.config.IngestionProperties;
import com.example.paperassistant.model.dataobject.DocumentDO;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import static org.junit.jupiter.api.Assertions.*;

class HttpRagClientTests {
    @TempDir Path root;
    private HttpServer server;
    private HttpRagClient client;
    private DocumentDO document;
    private final AtomicReference<String> request = new AtomicReference<>();
    private int code = 200;
    private long bodyDelayMillis;
    private String response;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(root.resolve("test.md"), "# Notes\nNOVA731 is 73");
        document = new DocumentDO(1L, 2L, "notes.md", "test.md", 20, "a".repeat(64),
                "UPLOADED", "PROCESSING", null, null, null, null);
        response = body("INDEXED", "\"ragDocumentId\":\"rag-1\"");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/documents/ingest", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] result = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, result.length);
            if (bodyDelayMillis > 0) {
                try { Thread.sleep(bodyDelayMillis); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            try (var out = exchange.getResponseBody()) { out.write(result); }
        });
        server.start();
        var settings = new IngestionProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "tests", 1, 3, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(35), Duration.ofSeconds(1));
        client = new HttpRagClient(settings, new DocumentStorageProperties(root, DataSize.ofMegabytes(20)));
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void sendsActualFileWithBusinessIdentifiers() {
        assertEquals(new RagClient.Result("INDEXED", "rag-1"), client.ingest(document));
        assertTrue(request.get().contains("name=\"file\""));
        assertTrue(request.get().contains("NOVA731 is 73"));
        assertTrue(request.get().contains("name=\"libraryId\"\r\n\r\n2"));
        assertTrue(request.get().contains("name=\"originalFilename\"\r\n\r\nnotes.md"));
    }

    @Test
    void rejectsMalformedOrMismatchedResponses() {
        response = "{broken";
        assertEquals("INVALID_RAG_RESPONSE", assertThrows(RagClientException.class, () -> client.ingest(document)).code());
        response = body("INDEXED", "\"ragDocumentId\":\"rag-1\"").replace("\"documentId\":1", "\"documentId\":99");
        assertThrows(RagClientException.class, () -> client.ingest(document));
    }

    @Test
    void distinguishesTransientPermanentAndUnsupportedOutcomes() {
        code = 503;
        assertTrue(assertThrows(RagClientException.class, () -> client.ingest(document)).retryable());
        code = 400;
        assertFalse(assertThrows(RagClientException.class, () -> client.ingest(document)).retryable());
        code = 200;
        response = body("UNSUPPORTED", "\"errorCode\":\"UNSUPPORTED_TYPE\"");
        assertEquals("UNSUPPORTED", client.ingest(document).status());
        response = body("FAILED", "\"errorCode\":\"PARSING_FAILED\",\"retryable\":false,\"errorMessage\":\"secret\"");
        var exception = assertThrows(RagClientException.class, () -> client.ingest(document));
        assertFalse(exception.retryable());
        assertFalse(exception.getMessage().contains("secret"));
    }

    @Test
    void timeoutCoversBodyAfterHeadersHaveArrived() {
        bodyDelayMillis = 5000;
        assertEquals("RAG_UNAVAILABLE", assertThrows(RagClientException.class, () -> client.ingest(document)).code());
    }

    @Test
    void missingSourceFailsBeforeSendingHttpRequest() throws Exception {
        Files.delete(root.resolve("test.md"));
        assertEquals("SOURCE_MISSING", assertThrows(RagClientException.class, () -> client.ingest(document)).code());
        assertNull(request.get());
    }

    private String body(String status, String more) {
        return "{\"documentId\":1,\"libraryId\":2,\"sha256\":\"" + "a".repeat(64)
                + "\",\"status\":\"" + status + "\"," + more + "}";
    }
}
