package com.example.paperassistant.common.exception;

import com.example.paperassistant.model.dto.ChatScopeDTO.ExcludedDocument;
import java.util.List;

public class ChatNotReadyException extends RuntimeException {
    private final String code;
    private final List<ExcludedDocument> documents;

    public ChatNotReadyException(String code, String message, List<ExcludedDocument> documents) {
        super(message);
        this.code = code;
        this.documents = List.copyOf(documents);
    }
    public String getCode() { return code; }
    public List<ExcludedDocument> getDocuments() { return documents; }
}
