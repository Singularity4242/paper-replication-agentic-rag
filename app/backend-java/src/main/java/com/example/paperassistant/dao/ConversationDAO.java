package com.example.paperassistant.dao;

import com.example.paperassistant.model.dataobject.ConversationDO;
import com.example.paperassistant.model.dataobject.ConversationTurnDO;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationDAO {
    ConversationDO create(long libraryId, String title, String documents);
    Optional<ConversationDO> find(long libraryId, long id, boolean lock);
    List<ConversationDO> list(long libraryId, long beforeId, int limit);
    List<ConversationTurnDO> messages(long conversationId, long afterId, int limit);
    Optional<ConversationTurnDO> byRequest(long conversationId, UUID requestId);
    boolean running(long conversationId);
    ConversationTurnDO begin(long conversationId, UUID requestId, String question, long revision, long leaseMillis);
    int expire(long conversationId);
    void lock(long conversationId);
    List<Long> expiredConversations(int limit);
    int complete(ConversationTurnDO turn, String answer);
    int fail(ConversationTurnDO turn, String code, String message);
    int saveCheckpoint(long conversationId, long revision, String checkpoint);
}
