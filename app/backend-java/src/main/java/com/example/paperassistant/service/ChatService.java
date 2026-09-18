package com.example.paperassistant.service;

import com.example.paperassistant.model.dto.ChatRequestDTO;
import com.example.paperassistant.model.dto.ChatScopeDTO;

public interface ChatService {
    ChatScopeDTO prepare(long libraryId, ChatRequestDTO request);
}
