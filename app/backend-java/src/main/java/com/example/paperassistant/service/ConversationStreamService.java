package com.example.paperassistant.service;

import com.example.paperassistant.client.RagChatClient;
import java.io.IOException;

public interface ConversationStreamService {
    void stream(ConversationService.PreparedTurn prepared, RagChatClient.EventSink sink) throws IOException;
}
