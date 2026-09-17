package com.example.paperassistant.service.impl;

import com.example.paperassistant.service.IngestionTaskService;

import com.example.paperassistant.client.RagClient;
import com.example.paperassistant.client.RagClientException;
import com.example.paperassistant.config.IngestionProperties;
import com.example.paperassistant.dao.DocumentDAO;
import com.example.paperassistant.dao.IngestionTaskDAO;
import com.example.paperassistant.model.dataobject.IngestionTaskDO;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("postgres")
public class IngestionTaskServiceImpl implements IngestionTaskService {
    private final IngestionTaskDAO tasks;
    private final DocumentDAO documents;
    private final IngestionProperties properties;

    public IngestionTaskServiceImpl(IngestionTaskDAO tasks, DocumentDAO documents, IngestionProperties properties) {
        this.tasks = tasks;
        this.documents = documents;
        this.properties = properties;
    }

    @Override
    @Transactional
    public Optional<IngestionTaskDO> claim() {
        tasks.failExpired(properties.maxAttempts()).forEach(id -> documents.updateIndex(id, "FAILED", null));
        var task = tasks.claim(properties.maxAttempts(), properties.leaseDuration().toSeconds(), UUID.randomUUID());
        task.ifPresent(value -> documents.updateIndex(value.documentId(), "PROCESSING", null));
        return task;
    }

    @Override
    @Transactional
    public boolean complete(IngestionTaskDO task, RagClient.Result result) {
        String code = "UNSUPPORTED".equals(result.status()) ? "UNSUPPORTED_TYPE" : null;
        String message = code == null ? null : "该类型文件暂不支持解析，已保留原文件";
        if (!tasks.finish(task, result.status(), code, message, 0)) {
            return false;
        }
        documents.updateIndex(task.documentId(), result.status(), result.ragDocumentId());
        return true;
    }

    @Override
    @Transactional
    public boolean fail(IngestionTaskDO task, RagClientException error) {
        boolean retry = error.retryable() && task.attemptCount() < properties.maxAttempts();
        String status = retry ? "QUEUED" : "FAILED";
        long delay = properties.retryDelay().toMillis() * (1L << Math.min(task.attemptCount() - 1, 8));
        if (!tasks.finish(task, status, error.code(), error.getMessage(), delay)) {
            return false;
        }
        documents.updateIndex(task.documentId(), status, null);
        return true;
    }
}
