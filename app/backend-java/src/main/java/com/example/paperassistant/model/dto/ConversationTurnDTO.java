package com.example.paperassistant.model.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 一轮包含用户问题和助手的最终结果，不暴露执行令牌与内部上下文。 */
public record ConversationTurnDTO(long id, UUID requestId, String question, String status, JsonNode result,
                                  String errorCode, String errorMessage, OffsetDateTime createdAt,
                                  OffsetDateTime finishedAt) { }
