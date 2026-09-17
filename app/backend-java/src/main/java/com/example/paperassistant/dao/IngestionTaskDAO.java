package com.example.paperassistant.dao;

import com.example.paperassistant.model.dataobject.IngestionTaskDO;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IngestionTaskDAO {
    void enqueue(long documentId);
    Optional<IngestionTaskDO> findByDocumentId(long documentId);
    Optional<IngestionTaskDO> claim(int maxAttempts, long leaseSeconds, UUID token);
    List<Long> failExpired(int maxAttempts);
    boolean finish(IngestionTaskDO task, String status, String code, String message, long delayMillis);
    boolean retry(long documentId);
}
