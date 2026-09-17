package com.example.paperassistant.model.dataobject;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 内部任务记录，执行令牌不对外返回。 */
public record IngestionTaskDO(long id, long documentId, String status, int attemptCount,
        UUID executionToken, OffsetDateTime nextAttemptAt, OffsetDateTime leaseUntil,
        String errorCode, String errorMessage, OffsetDateTime startedAt, OffsetDateTime finishedAt) {
}
