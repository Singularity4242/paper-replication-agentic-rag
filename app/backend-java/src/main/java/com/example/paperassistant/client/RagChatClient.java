package com.example.paperassistant.client;

import com.example.paperassistant.model.dto.ChatScopeDTO;
import java.io.IOException;

public interface RagChatClient {
    void stream(ChatScopeDTO scope, EventSink sink) throws IOException;
    @FunctionalInterface
    interface EventSink {
        void send(String event, Object data) throws IOException;
    }
}
