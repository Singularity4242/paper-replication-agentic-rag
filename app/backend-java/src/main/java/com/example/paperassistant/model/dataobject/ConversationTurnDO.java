package com.example.paperassistant.model.dataobject;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConversationTurnDO(long id, long conversationId, UUID requestId, String question, String status,
                                 String answer, String errorCode, String errorMessage, UUID executionToken,
                                 long baseRevision, OffsetDateTime leaseUntil, OffsetDateTime createdAt,
                                 OffsetDateTime finishedAt) { }
