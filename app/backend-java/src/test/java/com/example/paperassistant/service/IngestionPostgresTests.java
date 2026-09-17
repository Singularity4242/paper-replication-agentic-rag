package com.example.paperassistant.service;

import com.example.paperassistant.client.RagClient;
import com.example.paperassistant.client.RagClientException;
import com.example.paperassistant.common.exception.ConflictException;
import com.example.paperassistant.common.exception.ResourceNotFoundException;
import com.example.paperassistant.dao.DocumentDAO;
import com.example.paperassistant.dao.IngestionTaskDAO;
import com.example.paperassistant.model.dto.DocumentUploadDTO;
import com.example.paperassistant.model.dto.LibraryCreateDTO;
import com.example.paperassistant.task.DocumentIngestionWorker;
import com.example.paperassistant.config.IngestionProperties;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "paper.ingestion.enabled=false")
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class IngestionPostgresTests {
    @Autowired DocumentService documents;
    @Autowired LibraryService libraries;
    @Autowired IngestionTaskService service;
    @Autowired DocumentDAO documentDAO;
    @Autowired IngestionProperties properties;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean IngestionTaskDAO tasks;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DocumentPostgresTests.properties(registry);
    }

    @BeforeEach
    void clear() throws Exception {
        jdbc.sql("DELETE FROM documents").update();
        jdbc.sql("DELETE FROM paper_libraries").update();
        Files.createDirectories(root());
        try (var files = Files.list(root())) {
            for (Path file : files.toList()) { Files.delete(file); }
        }
    }

    @Test
    void uploadEnqueuesAtomicallyAndTaskFailureRollsBackDocumentAndFile() throws Exception {
        long library = library();
        doThrow(new IllegalStateException("task insert failed")).when(tasks).enqueue(anyLong());
        assertThrows(IllegalStateException.class, () -> upload(library));
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM documents").query(Long.class).single());
        try (var files = Files.list(root())) { assertEquals(0L, files.count()); }
    }

    @Test
    void workerCompletesAndKeepsMappingAndTaskConsistent() {
        long library = library();
        long id = upload(library);
        assertEquals("QUEUED", documents.getTask(library, id).status());
        RagClient client = mock(RagClient.class);
        when(client.ingest(any())).thenReturn(new RagClient.Result("INDEXED", "rag-123"));
        new DocumentIngestionWorker(service, documentDAO, client, properties).runOnce();
        var saved = documents.getDocument(library, id);
        assertEquals("INDEXED", saved.indexStatus());
        assertEquals("rag-123", saved.ragDocumentId());
        assertNotNull(saved.indexedAt());
        assertEquals("INDEXED", documents.getTask(library, id).status());
        assertTrue(service.claim().isEmpty());
        assertThrows(ConflictException.class, () -> documents.retry(library, id));
    }

    @Test
    void transientFailuresBackOffThenStopAtLimitAndCanBeRetried() {
        long library = library();
        long id = upload(library);
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            var claimed = service.claim().orElseThrow();
            assertEquals(attempt, claimed.attemptCount());
            assertTrue(service.fail(claimed, new RagClientException("RAG_UNAVAILABLE", "temporarily unavailable", true)));
            if (attempt < properties.maxAttempts()) {
                assertTrue(service.claim().isEmpty());
                jdbc.sql("UPDATE document_ingestion_tasks SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'").update();
            }
        }
        assertEquals("FAILED", documents.getDocument(library, id).indexStatus());
        assertEquals("RAG_UNAVAILABLE", documents.getTask(library, id).errorCode());
        assertTrue(service.claim().isEmpty());
        assertEquals(0, documents.retry(library, id).attemptCount());
        assertEquals("QUEUED", documents.getDocument(library, id).indexStatus());
        assertThrows(ConflictException.class, () -> documents.retry(library, id));
    }

    @Test
    void expiredLeaseIsRecoveredAndStaleResultCannotOverwriteNewAttempt() {
        long library = library();
        long id = upload(library);
        var first = service.claim().orElseThrow();
        assertTrue(service.claim().isEmpty());
        jdbc.sql("UPDATE document_ingestion_tasks SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'").update();
        assertFalse(service.complete(first, new RagClient.Result("INDEXED", "stale")));
        var recovered = service.claim().orElseThrow();
        assertNotEquals(first.executionToken(), recovered.executionToken());
        assertFalse(service.complete(first, new RagClient.Result("INDEXED", "stale")));
        assertTrue(service.complete(recovered, new RagClient.Result("INDEXED", "current")));
        assertEquals("current", documents.getDocument(library, id).ragDocumentId());
    }

    @Test
    void repeatedlyInterruptedTaskEventuallyFailsInsteadOfStayingProcessing() {
        long library = library();
        long id = upload(library);
        service.claim().orElseThrow();
        jdbc.sql("UPDATE document_ingestion_tasks SET attempt_count = :max, lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'")
                .param("max", properties.maxAttempts()).update();
        assertTrue(service.claim().isEmpty());
        assertEquals("FAILED", documents.getDocument(library, id).indexStatus());
        assertEquals("WORKER_INTERRUPTED", documents.getTask(library, id).errorCode());
    }

    @Test
    void unsupportedAndPermanentFailureKeepOriginalFiles() throws Exception {
        long library = library();
        long id = upload(library);
        assertTrue(service.complete(service.claim().orElseThrow(), new RagClient.Result("UNSUPPORTED", null)));
        assertEquals("UNSUPPORTED", documents.getDocument(library, id).indexStatus());
        assertEquals("UNSUPPORTED_TYPE", documents.getTask(library, id).errorCode());
        documents.retry(library, id);
        assertTrue(service.fail(service.claim().orElseThrow(), new RagClientException("PARSING_FAILED", "invalid file", false)));
        assertEquals("FAILED", documents.getDocument(library, id).indexStatus());
        try (var files = Files.list(root())) { assertEquals(1L, files.count()); }
    }

    @Test
    void wrongLibraryCannotReadOrRetryDocument() {
        long library = library();
        long other = library();
        long id = upload(library);
        assertThrows(ResourceNotFoundException.class, () -> documents.getDocument(other, id));
        assertThrows(ResourceNotFoundException.class, () -> documents.getTask(other, id));
        assertThrows(ResourceNotFoundException.class, () -> documents.retry(other, id));
        assertEquals("QUEUED", documents.getDocument(library, id).indexStatus());
    }

    @Test
    void concurrentWorkersOnlyClaimTaskOnce() throws Exception {
        upload(library());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> { start.await(); return service.claim().isPresent() ? 1 : 0; });
            var two = executor.submit(() -> { start.await(); return service.claim().isPresent() ? 1 : 0; });
            start.countDown();
            assertEquals(1, one.get(10, TimeUnit.SECONDS) + two.get(10, TimeUnit.SECONDS));
        }
    }

    private long library() { return libraries.createLibrary(new LibraryCreateDTO("ingestion test", null)).id(); }
    private long upload(long library) {
        byte[] bytes = "# training config\nlearning_rate: 0.001".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return documents.uploadDocument(library, new DocumentUploadDTO("config.md", bytes.length, new ByteArrayInputStream(bytes))).id();
    }
    private Path root() { return Path.of(System.getenv("TEST_STORAGE_ROOT")); }
}
