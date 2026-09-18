package com.example.paperassistant.controller;

import com.example.paperassistant.common.exception.GlobalExceptionHandler;
import com.example.paperassistant.model.dto.ConversationDTO;
import com.example.paperassistant.service.ConversationService;
import com.example.paperassistant.service.ConversationStreamService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConversationControllerTests {
    private final ConversationService service = mock(ConversationService.class);
    private final ConversationStreamService streaming = mock(ConversationStreamService.class);
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ConversationController(service, streaming)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void invalidMessageAndPaginationNeverReachService() throws Exception {
        for (String body : List.of("{}", "{\"question\":\"q\"}", "{\"question\":\"q\",\"requestId\":\"bad\"}",
                "{\"question\":\" \",\"requestId\":\"00000000-0000-0000-0000-000000000001\"}")) {
            mvc.perform(post("/api/libraries/1/conversations/2/messages").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/libraries/1/conversations/2/messages?afterId=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/libraries/1/conversations?limit=101")).andExpect(status().isBadRequest());
        verifyNoInteractions(service, streaming);
    }

    @Test
    void creationReturnsPublicMetadataOnly() throws Exception {
        when(service.create(eq(1L), any())).thenReturn(new ConversationDTO(2, 1, "Study", List.of(10L), 0, null, null));
        mvc.perform(post("/api/libraries/1/conversations").contentType("application/json").content("{\"title\":\"Study\",\"documentIds\":[10]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.documentIds[0]").value(10))
                .andExpect(jsonPath("$.data.checkpoint").doesNotExist());
    }
}
