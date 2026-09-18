package com.example.paperassistant.model.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record ConversationDTO(long id, long libraryId, String title, List<Long> documentIds, long revision,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
