package com.example.paperassistant.client;

import com.example.paperassistant.model.dto.ChatScopeDTO;
import java.io.IOException;

public interface RagChatClient {
    void stream(ChatScopeDTO scope, EventSink sink) throws IOException;
    default void streamConversation(ChatScopeDTO scope, long conversationId,
            tools.jackson.databind.JsonNode checkpoint, EventSink sink) throws IOException {
        throw new UnsupportedOperationException("Conversation streaming is not implemented");
    }
    @FunctionalInterface
    interface EventSink {
        void send(String event, Object data) throws IOException;
    }
}
