package com.example.paperassistant.controller;

import com.example.paperassistant.client.RagChatClient;
import com.example.paperassistant.common.exception.ChatNotReadyException;
import com.example.paperassistant.common.exception.GlobalExceptionHandler;
import com.example.paperassistant.model.dto.ChatScopeDTO;
import com.example.paperassistant.service.ChatService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTests {
    private final ChatService service = mock(ChatService.class);
    private final RagChatClient client = mock(RagChatClient.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(service, client))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void validatesQuestionIdsAndEmptySelection() throws Exception {
        for (String body : List.of("{}", "{\"question\":\" \"}", "{\"question\":\"q\",\"documentIds\":[]}",
                "{\"question\":\"q\",\"documentIds\":[null]}", "{\"question\":\"q\",\"documentIds\":[0]}")) {
            mvc.perform(post("/api/libraries/1/chat").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/libraries/0/chat").contentType("application/json").content("{\"question\":\"q\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, client);
    }

    @Test
    void notReadyIsJsonConflictBeforeStreamStarts() throws Exception {
        when(service.prepare(eq(1L), any())).thenThrow(new ChatNotReadyException("DOCUMENTS_NOT_READY", "未完成",
                List.of(new ChatScopeDTO.ExcludedDocument(10, "PROCESSING"))));
        mvc.perform(post("/api/libraries/1/chat").contentType("application/json").content("{\"question\":\"q\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENTS_NOT_READY"))
                .andExpect(jsonPath("$.data.documents[0].indexStatus").value("PROCESSING"));
        verifyNoInteractions(client);
    }

    @Test
    void streamsScopeAndTerminalEventsWithoutWrappingSseInApiResponse() throws Exception {
        when(service.prepare(eq(1L), any())).thenReturn(new ChatScopeDTO(1, "q", List.of(
                new ChatScopeDTO.Document(10, "rag-10", "a".repeat(64), "test.md")), List.of()));
        doAnswer(call -> {
            RagChatClient.EventSink sink = call.getArgument(1);
            sink.send("error", Map.of("code", "RAG_UNAVAILABLE"));
            return null;
        }).when(client).stream(any(), any());
        var result = mvc.perform(post("/api/libraries/1/chat").contentType("application/json").content("{\"question\":\"q\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: scope")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: error")));
    }
}
