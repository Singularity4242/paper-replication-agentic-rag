package com.example.paperassistant.model.dto;

import java.util.List;

/** 服务端生成的查询范围，不接收前端传入的 RAG ID 或检索过滤表达式。 */
public record ChatScopeDTO(long libraryId, String question, List<Document> documents,
                           List<ExcludedDocument> excludedDocuments) {
    public ChatScopeDTO {
        documents = List.copyOf(documents);
        excludedDocuments = List.copyOf(excludedDocuments);
    }
    public record Document(long documentId, String ragDocumentId, String sha256, String filename) { }
    public record ExcludedDocument(long documentId, String indexStatus) { }
}
