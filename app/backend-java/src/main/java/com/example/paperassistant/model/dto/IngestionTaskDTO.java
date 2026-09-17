package com.example.paperassistant.model.dto;

import java.time.OffsetDateTime;

public record IngestionTaskDTO(long id, String status, int attemptCount,
        OffsetDateTime nextAttemptAt, String errorCode, String errorMessage,
        OffsetDateTime startedAt, OffsetDateTime finishedAt) {
}
