package com.example.paperassistant.model.dataobject;

import java.time.OffsetDateTime;

public record ConversationDO(long id, long libraryId, String title, String documentScope,
                             String checkpoint, long revision, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
