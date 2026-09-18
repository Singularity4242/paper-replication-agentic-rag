package com.example.paperassistant.service;

import com.example.paperassistant.model.dataobject.ConversationTurnDO;
import com.example.paperassistant.model.dto.*;
import java.util.List;
import tools.jackson.databind.JsonNode;

public interface ConversationService {
    ConversationDTO create(long libraryId, ConversationCreateDTO input);
    ConversationDTO get(long libraryId, long conversationId);
    List<ConversationDTO> list(long libraryId, long beforeId, int limit);
    List<ConversationTurnDTO> messages(long libraryId, long conversationId, long afterId, int limit);
    PreparedTurn begin(long libraryId, long conversationId, ConversationMessageDTO input);
    void complete(PreparedTurn turn, JsonNode answer, JsonNode checkpoint);
    void fail(PreparedTurn turn, String code, String message);
    void recover(long conversationId);
    record PreparedTurn(ChatScopeDTO scope, ConversationTurnDO turn, JsonNode checkpoint, boolean replay) { }
}
