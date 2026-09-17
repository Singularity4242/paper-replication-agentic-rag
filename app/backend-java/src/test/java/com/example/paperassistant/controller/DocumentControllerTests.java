package com.example.paperassistant.controller;

import com.example.paperassistant.common.exception.GlobalExceptionHandler;
import com.example.paperassistant.common.exception.ConflictException;
import com.example.paperassistant.model.dto.DocumentDTO;
import com.example.paperassistant.service.DocumentService;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentControllerTests {
    private DocumentService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(DocumentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void missingFileIsRejected() throws Exception {
        mvc.perform(multipart("/api/libraries/1/documents"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILE"));
        verifyNoInteractions(service);
    }

    @Test
    void invalidLibraryIdIsRejectedForBothRoutes() throws Exception {
        mvc.perform(get("/api/libraries/0/documents")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/libraries/-1/documents").file(file()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void createdDocumentDoesNotExposeItsStorageLocation() throws Exception {
        var time = OffsetDateTime.parse("2026-09-17T08:00:00Z");
        when(service.uploadDocument(eq(1L), any())).thenReturn(new DocumentDTO(2L, 1, "test.pdf", 5,
                "a".repeat(64), "UPLOADED", "QUEUED", null, null, time, time));
        mvc.perform(multipart("/api/libraries/1/documents").file(file()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andExpect(jsonPath("$.data.indexStatus").value("QUEUED"))
                .andExpect(jsonPath("$.data.id").value(2))
                .andExpect(jsonPath("$.data.storageKey").doesNotExist())
                .andExpect(jsonPath("$.data.storagePath").doesNotExist());
    }

    @Test
    void duplicateUploadReturnsExplicitConflictCode() throws Exception {
        when(service.uploadDocument(eq(1L), any())).thenThrow(new ConflictException("DUPLICATE_DOCUMENT", "重复资料"));
        mvc.perform(multipart("/api/libraries/1/documents").file(file()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_DOCUMENT"));
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF-".getBytes());
    }
}
