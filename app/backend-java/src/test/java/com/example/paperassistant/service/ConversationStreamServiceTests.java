package com.example.paperassistant.service;

import com.example.paperassistant.client.RagChatClient;
import com.example.paperassistant.model.dataobject.ConversationTurnDO;
import com.example.paperassistant.model.dto.ChatScopeDTO;
import com.example.paperassistant.service.impl.ConversationStreamServiceImpl;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationStreamServiceTests {
    private final ConversationService service = mock(ConversationService.class);
    private final RagChatClient client = mock(RagChatClient.class);
    private final ConversationStreamService streaming = new ConversationStreamServiceImpl(service, client);
    private final List<String> events = new ArrayList<>();
    private final RagChatClient.EventSink sink = (event, value) -> events.add(event);

    private ConversationService.PreparedTurn prepared(boolean replay, String status) {
        return new ConversationService.PreparedTurn(new ChatScopeDTO(1, "q", List.of(), List.of()),
                new ConversationTurnDO(10, 5, UUID.randomUUID(), "q", status, "{\"answer\":\"saved\"}", "RAG_TIMEOUT", "timeout",
                        UUID.randomUUID(), 0, null, null, null), null, replay);
    }

    private void success() throws IOException {
        doAnswer(call -> {
            RagChatClient.EventSink out = call.getArgument(3);
            out.send("delta", Map.of("text", "partial"));
            out.send("answer", Map.of("answer", "final"));
            out.send("checkpoint", Map.of("version", 1, "conversationId", 5));
            out.send("done", Map.of("status", "COMPLETED"));
            return null;
        }).when(client).streamConversation(any(), anyLong(), any(), any());
    }

    @Test
    void finalAnswerIsSentOnlyAfterAtomicPersistenceAndCheckpointNeverLeaks() throws Exception {
        success();
        doAnswer(call -> { events.add("persist"); return null; }).when(service).complete(any(), any(), any());
        streaming.stream(prepared(false, "RUNNING"), sink);
        assertEquals(List.of("turn", "delta", "persist", "answer", "done"), events);
        verify(service, never()).fail(any(), anyString(), anyString());
    }

    @Test
    void saveFailureDoesNotPublishFinalAnswerOrDone() throws Exception {
        success();
        doThrow(new IllegalStateException("db")).when(service).complete(any(), any(), any());
        streaming.stream(prepared(false, "RUNNING"), sink);
        assertEquals(List.of("turn", "delta", "error"), events);
        verify(service).fail(any(), eq("TURN_SAVE_FAILED"), anyString());
    }

    @Test
    void replayDoesNotCallModelForCompletedOrFailedRequest() throws Exception {
        streaming.stream(prepared(true, "COMPLETED"), sink);
        assertEquals(List.of("turn", "answer", "done"), events);
        events.clear();
        streaming.stream(prepared(true, "FAILED"), sink);
        assertEquals(List.of("turn", "error"), events);
        verifyNoInteractions(client, service);
    }

    @Test
    void disconnectBeforeCompletionMarksFailureButDisconnectAfterCommitDoesNotUndoSuccess() throws Exception {
        success();
        assertThrows(IOException.class, () -> streaming.stream(prepared(false, "RUNNING"), (event, value) -> {
            if (event.equals("delta")) { throw new IOException("disconnect"); }
        }));
        verify(service).fail(any(), eq("STREAM_INTERRUPTED"), anyString());
        clearInvocations(service);
        assertThrows(IOException.class, () -> streaming.stream(prepared(false, "RUNNING"), (event, value) -> {
            if (event.equals("done")) { throw new IOException("disconnect"); }
        }));
        verify(service).complete(any(), any(), any());
        verify(service, never()).fail(any(), anyString(), anyString());
    }

    @Test
    void upstreamErrorPersistsFailureAndNoIncompleteCheckpoint() throws Exception {
        doAnswer(call -> {
            RagChatClient.EventSink out = call.getArgument(3);
            out.send("answer", Map.of("answer", "not committed"));
            out.send("error", Map.of("code", "RAG_TIMEOUT", "message", "timeout"));
            return null;
        }).when(client).streamConversation(any(), anyLong(), any(), any());
        streaming.stream(prepared(false, "RUNNING"), sink);
        assertEquals(List.of("turn", "error"), events);
        verify(service).fail(any(), eq("RAG_TIMEOUT"), eq("timeout"));
        verify(service, never()).complete(any(), any(), any());
    }
}
