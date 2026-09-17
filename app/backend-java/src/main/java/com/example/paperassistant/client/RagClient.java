package com.example.paperassistant.client;

import com.example.paperassistant.model.dataobject.DocumentDO;

public interface RagClient {
    record Result(String status, String ragDocumentId) { }
    Result ingest(DocumentDO document);
}
