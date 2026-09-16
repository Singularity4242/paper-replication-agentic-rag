package com.example.paperassistant.controller;

import com.example.paperassistant.common.exception.GlobalExceptionHandler;
import com.example.paperassistant.dao.LibraryDAO;
import com.example.paperassistant.model.dto.LibraryCreateDTO;
import com.example.paperassistant.model.dto.LibraryDTO;
import com.example.paperassistant.model.dataobject.LibraryDO;
import com.example.paperassistant.service.LibraryService;
import com.example.paperassistant.service.impl.LibraryServiceImpl;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证接口输入边界、业务名称处理及对外字段契约。 */
class LibraryControllerTests {

    private LibraryDAO libraryDAO;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        libraryDAO = mock(LibraryDAO.class);
        mockMvc = buildMvc(new LibraryServiceImpl(libraryDAO));
    }

    @Test
    void blankNameIsRejectedBeforePersistence() throws Exception {
        mockMvc.perform(post("/api/libraries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(libraryDAO);
    }

    @Test
    void oversizedNameIsRejectedBeforePersistence() throws Exception {
        mockMvc.perform(post("/api/libraries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "a".repeat(129) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(libraryDAO);
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        mockMvc.perform(post("/api/libraries").contentType(MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(libraryDAO);
    }

    @Test
    void nameIsStrippedBeforePersistenceAndGeneratedFieldsAreReturned() throws Exception {
        OffsetDateTime time = OffsetDateTime.parse("2026-09-16T08:00:00Z");
        when(libraryDAO.insertLibrary(new LibraryDO(null, "RAG papers", null, null, null)))
                .thenReturn(new LibraryDO(7L, "RAG papers", null, time, time));
        mockMvc.perform(post("/api/libraries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  RAG papers  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.name").value("RAG papers"));
    }

    @Test
    void emptyLibraryListUsesAnArray() throws Exception {
        when(libraryDAO.listLibraries()).thenReturn(java.util.List.of());
        mockMvc.perform(get("/api/libraries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void completedServiceResultWillUseThePublicViewContract() throws Exception {
        LibraryService service = mock(LibraryService.class);
        OffsetDateTime time = OffsetDateTime.parse("2026-09-16T08:00:00Z");
        when(service.createLibrary(new LibraryCreateDTO("RAG", null)))
                .thenReturn(new LibraryDTO(1L, "RAG", null, time, time));

        buildMvc(service).perform(post("/api/libraries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RAG\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.gmtCreate").doesNotExist());
    }

    @Test
    void invalidUpdatesAreRejectedBeforePersistence() throws Exception {
        for (String body : List.of("{}", "{\"name\":null}", "{\"name\":\"  \"}", "{broken",
                "{\"name\":\"" + "a".repeat(129) + "\"}",
                "{\"name\":\"RAG\",\"description\":\"" + "a".repeat(2001) + "\"}")) {
            mockMvc.perform(put("/api/libraries/1").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        verifyNoInteractions(libraryDAO);
    }

    @Test
    void invalidIdsAreRejectedBeforePersistence() throws Exception {
        for (String id : List.of("0", "-1", "abc", "9223372036854775808")) {
            mockMvc.perform(put("/api/libraries/" + id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"RAG\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
            mockMvc.perform(delete("/api/libraries/" + id))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        verifyNoInteractions(libraryDAO);
    }

    @Test
    void absentLibraryReturns404ForBothMutations() throws Exception {
        when(libraryDAO.updateLibrary(new LibraryDO(99L, "RAG", null, null, null)))
                .thenReturn(Optional.empty());
        when(libraryDAO.deleteLibrary(99L)).thenReturn(0);
        mockMvc.perform(put("/api/libraries/99").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RAG\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(delete("/api/libraries/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private MockMvc buildMvc(LibraryService service) {
        return MockMvcBuilders.standaloneSetup(new LibraryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
