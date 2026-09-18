package com.example.paperassistant.service;

import com.example.paperassistant.common.exception.ChatNotReadyException;
import com.example.paperassistant.common.exception.ResourceNotFoundException;
import com.example.paperassistant.dao.DocumentDAO;
import com.example.paperassistant.dao.LibraryDAO;
import com.example.paperassistant.model.dataobject.DocumentDO;
import com.example.paperassistant.model.dto.ChatRequestDTO;
import com.example.paperassistant.service.impl.ChatServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatServiceTests {
    private final LibraryDAO libraries = mock(LibraryDAO.class);
    private final DocumentDAO documents = mock(DocumentDAO.class);
    private final ChatService service = new ChatServiceImpl(libraries, documents);

    private void fixture() {
        when(libraries.existsById(1)).thenReturn(true);
        when(documents.listDocuments(1)).thenReturn(List.of(doc(10, "INDEXED"), doc(11, "PROCESSING"), doc(12, "UNSUPPORTED")));
    }

    @Test
    void wholeLibraryUsesOnlyIndexedDocumentsAndReportsExclusions() {
        fixture();
        var result = service.prepare(1, new ChatRequestDTO(" question ", null));
        assertEquals("question", result.question());
        assertEquals(List.of(10L), result.documents().stream().map(d -> d.documentId()).toList());
        assertEquals(List.of("PROCESSING", "UNSUPPORTED"), result.excludedDocuments().stream().map(d -> d.indexStatus()).toList());
    }

    @Test
    void explicitSelectionDoesNotIncludeOtherDocumentsAndDeduplicatesIds() {
        fixture();
        var result = service.prepare(1, new ChatRequestDTO("q", List.of(10L, 10L)));
        assertEquals(1, result.documents().size());
        assertTrue(result.excludedDocuments().isEmpty());
    }

    @Test
    void selectedProcessingAndUnsupportedFilesBlockTheWholeQuestion() {
        fixture();
        for (long id : List.of(11L, 12L)) {
            var exception = assertThrows(ChatNotReadyException.class,
                    () -> service.prepare(1, new ChatRequestDTO("q", List.of(10L, id))));
            assertEquals("DOCUMENTS_NOT_READY", exception.getCode());
            assertEquals(id, exception.getDocuments().getFirst().documentId());
        }
    }

    @Test
    void foreignDocumentsAreRejectedBeforeCallingPython() {
        fixture();
        assertThrows(ResourceNotFoundException.class, () -> service.prepare(1, new ChatRequestDTO("q", List.of(999L))));
        assertThrows(ResourceNotFoundException.class, () -> service.prepare(2, new ChatRequestDTO("q", null)));
        verify(documents, never()).listDocuments(2);
    }

    @Test
    void emptyLibraryAndInconsistentIndexedMetadataFailClosed() {
        when(libraries.existsById(1)).thenReturn(true);
        when(documents.listDocuments(1)).thenReturn(List.of());
        assertEquals("NO_INDEXED_DOCUMENTS", assertThrows(ChatNotReadyException.class,
                () -> service.prepare(1, new ChatRequestDTO("q", null))).getCode());
        when(documents.listDocuments(1)).thenReturn(List.of(new DocumentDO(10L, 1, "test.md", "private", 1,
                "a".repeat(64), "UPLOADED", "INDEXED", null, null, null, null)));
        assertThrows(ChatNotReadyException.class, () -> service.prepare(1, new ChatRequestDTO("q", null)));
    }

    private DocumentDO doc(long id, String status) {
        return new DocumentDO(id, 1, "test.md", "private", 1, "a".repeat(64), "UPLOADED", status,
                "INDEXED".equals(status) ? "rag-" + id : null, null, null, null);
    }
}
