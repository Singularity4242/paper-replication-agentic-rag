package com.example.paperassistant.service;

import com.example.paperassistant.common.exception.ConflictException;
import com.example.paperassistant.common.exception.ResourceNotFoundException;
import com.example.paperassistant.dao.ConversationDAO;
import com.example.paperassistant.dao.DocumentDAO;
import com.example.paperassistant.model.dto.*;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "paper.ingestion.enabled=false")
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ConversationPostgresTests {
    @Autowired ConversationService service;
    @Autowired LibraryService libraries;
    @Autowired DocumentService documents;
    @Autowired DocumentDAO documentDAO;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean ConversationDAO dao;
    final JsonMapper json = JsonMapper.builder().build();
    long library;
    long document;
    long conversation;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { DocumentPostgresTests.properties(registry); }

    @BeforeEach
    void setup() {
        jdbc.sql("DELETE FROM conversations").update();
        jdbc.sql("DELETE FROM documents").update();
        jdbc.sql("DELETE FROM paper_libraries").update();
        library = libraries.createLibrary(new LibraryCreateDTO("Conversation test", null)).id();
        document = upload("first");
        conversation = service.create(library, new ConversationCreateDTO("Test conversation", null)).id();
    }

    long upload(String text) {
        var bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long id = documents.uploadDocument(library, new DocumentUploadDTO(text + ".md", bytes.length,
                new ByteArrayInputStream(bytes))).id();
        documentDAO.updateIndex(id, "INDEXED", "rag-" + id);
        return id;
    }
    ConversationService.PreparedTurn begin(UUID id, String question) {
        return service.begin(library, conversation, new ConversationMessageDTO(id, question));
    }
    JsonNode answer() { return json.readTree("{\"answer\":\"answer\",\"citations\":[],\"outcome\":\"INSUFFICIENT_EVIDENCE\"}"); }
    JsonNode checkpoint() { return json.readTree("{\"version\":1,\"conversationId\":" + conversation + ",\"messages\":[],\"state\":{}}"); }

    @Test
    void fixedScopeDoesNotGrowWithNewUploadsAndForeignConversationsAreHidden() {
        upload("later");
        var prepared = begin(UUID.randomUUID(), "q");
        assertEquals(List.of(document), prepared.scope().documents().stream().map(ChatScopeDTO.Document::documentId).toList());
        assertThrows(ResourceNotFoundException.class, () -> service.get(library + 1000, conversation));
        assertThrows(ResourceNotFoundException.class, () -> service.messages(library + 1000, conversation, 0, 50));
        assertEquals(List.of(document), service.get(library, conversation).documentIds());
        assertEquals(1, service.list(library, 0, 50).size());
    }

    @Test
    void completedRequestReplaysAndNewRequestReceivesPersistedCheckpoint() {
        UUID id = UUID.randomUUID();
        var first = begin(id, "q1");
        service.complete(first, answer(), checkpoint());
        assertEquals(1, service.get(library, conversation).revision());
        var replay = begin(id, "q1");
        assertTrue(replay.replay());
        assertEquals(first.turn().id(), replay.turn().id());
        assertEquals("COMPLETED", replay.turn().status());
        var second = begin(UUID.randomUUID(), "q2");
        assertEquals(checkpoint(), second.checkpoint());
        assertEquals(1, second.turn().baseRevision());
        var history = service.messages(library, conversation, 0, 50);
        assertEquals(2, history.size());
        assertEquals("q1", history.getFirst().question());
        assertEquals(answer(), history.getFirst().result());
        assertFalse(json.writeValueAsString(history).contains("checkpoint"));
        assertEquals(1, service.messages(library, conversation, first.turn().id(), 50).size());
    }

    @Test
    void reusedRequestAndConcurrentConversationRequestsAreRejected() {
        UUID id = UUID.randomUUID();
        begin(id, "q");
        assertEquals("REQUEST_IN_PROGRESS", assertThrows(ConflictException.class, () -> begin(id, "q")).getCode());
        assertEquals("REQUEST_ID_REUSED", assertThrows(ConflictException.class, () -> begin(id, "other")).getCode());
        assertEquals("CONVERSATION_BUSY", assertThrows(ConflictException.class, () -> begin(UUID.randomUUID(), "q")).getCode());
    }

    @Test
    void simultaneousClaimsHaveOnlyOneWinner() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(i -> executor.submit(() -> {
                start.await();
                try { begin(UUID.randomUUID(), "q" + i); return true; }
                catch (ConflictException expected) { return false; }
            })).toList();
            start.countDown();
            int count = 0;
            for (var task : tasks) { if (task.get()) { count++; } }
            assertEquals(1, count);
        }
    }

    @Test
    void failedTurnsPreserveCheckpointAndAreIdempotent() {
        var first = begin(UUID.randomUUID(), "first");
        service.complete(first, answer(), checkpoint());
        UUID failedId = UUID.randomUUID();
        var failed = begin(failedId, "failed");
        service.fail(failed, "RAG_TIMEOUT", "timeout");
        assertTrue(begin(failedId, "failed").replay());
        assertEquals("FAILED", begin(failedId, "failed").turn().status());
        assertEquals(checkpoint(), begin(UUID.randomUUID(), "next").checkpoint());
        assertEquals(1, service.get(library, conversation).revision());
    }

    @Test
    void expiredWorkCannotOverwriteRecoveredConversation() {
        var stale = begin(UUID.randomUUID(), "old");
        jdbc.sql("UPDATE conversation_turns SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=:id")
                .param("id", stale.turn().id()).update();
        service.recover(conversation);
        var next = begin(UUID.randomUUID(), "new");
        assertThrows(ConflictException.class, () -> service.complete(stale, answer(), checkpoint()));
        service.complete(next, answer(), checkpoint());
        var history = service.messages(library, conversation, 0, 50);
        assertEquals("WORKER_INTERRUPTED", history.getFirst().errorCode());
        assertEquals("COMPLETED", history.getLast().status());
        assertEquals(1, service.get(library, conversation).revision());
    }

    @Test
    void checkpointFailureRollsBackAnswerAndRevisionTogether() {
        var prepared = begin(UUID.randomUUID(), "q");
        doThrow(new IllegalStateException("database failure")).when(dao).saveCheckpoint(anyLong(), anyLong(), anyString());
        assertThrows(IllegalStateException.class, () -> service.complete(prepared, answer(), checkpoint()));
        assertEquals("RUNNING", service.messages(library, conversation, 0, 50).getFirst().status());
        assertEquals(0, service.get(library, conversation).revision());
        assertNull(dao.find(library, conversation, false).orElseThrow().checkpoint());
    }

    @Test
    void indexChangesInvalidateOldConversationScope() {
        documentDAO.updateIndex(document, "INDEXED", "new-rag-id");
        assertEquals("CONVERSATION_SCOPE_CHANGED", assertThrows(ConflictException.class,
                () -> begin(UUID.randomUUID(), "q")).getCode());
        assertTrue(service.messages(library, conversation, 0, 50).isEmpty());
    }
}
