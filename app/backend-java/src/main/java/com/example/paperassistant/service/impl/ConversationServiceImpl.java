package com.example.paperassistant.service.impl;

import com.example.paperassistant.common.exception.ConflictException;
import com.example.paperassistant.common.exception.ResourceNotFoundException;
import com.example.paperassistant.config.ChatProperties;
import com.example.paperassistant.dao.ConversationDAO;
import com.example.paperassistant.dao.LibraryDAO;
import com.example.paperassistant.model.dataobject.ConversationDO;
import com.example.paperassistant.model.dto.*;
import com.example.paperassistant.service.ChatService;
import com.example.paperassistant.service.ConversationService;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("postgres")
public class ConversationServiceImpl implements ConversationService {
    private final ConversationDAO dao;
    private final LibraryDAO libraries;
    private final ChatService chat;
    private final ChatProperties properties;
    private final JsonMapper json = JsonMapper.builder().build();

    public ConversationServiceImpl(ConversationDAO dao, LibraryDAO libraries, ChatService chat, ChatProperties properties) {
        this.dao = dao; this.libraries = libraries; this.chat = chat; this.properties = properties;
    }

    @Override
    @Transactional
    public ConversationDTO create(long libraryId, ConversationCreateDTO input) {
        if (!libraries.lockById(libraryId)) { throw new ResourceNotFoundException("资料库不存在"); }
        var scope = chat.prepare(libraryId, new ChatRequestDTO("创建会话", input.documentIds()));
        return view(dao.create(libraryId, input.title().strip(), json.writeValueAsString(scope.documents())));
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationDTO get(long libraryId, long conversationId) { return view(find(libraryId, conversationId, false)); }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationDTO> list(long libraryId, long beforeId, int limit) {
        if (!libraries.existsById(libraryId)) { throw new ResourceNotFoundException("资料库不存在"); }
        return dao.list(libraryId, beforeId, limit).stream().map(this::view).toList();
    }

    @Override
    @Transactional
    public List<ConversationTurnDTO> messages(long libraryId, long conversationId, long afterId, int limit) {
        find(libraryId, conversationId, true);
        dao.expire(conversationId);
        return dao.messages(conversationId, afterId, limit).stream().map(t -> new ConversationTurnDTO(t.id(), t.requestId(),
                t.question(), t.status(), t.answer() == null ? null : json.readTree(t.answer()), t.errorCode(), t.errorMessage(),
                t.createdAt(), t.finishedAt())).toList();
    }

    @Override
    @Transactional
    public PreparedTurn begin(long libraryId, long conversationId, ConversationMessageDTO input) {
        var conversation = find(libraryId, conversationId, true);
        dao.expire(conversationId);
        String question = input.question().strip();
        var previous = dao.byRequest(conversationId, input.requestId());
        if (previous.isPresent()) {
            var turn = previous.get();
            if (!turn.question().equals(question)) {
                throw new ConflictException("REQUEST_ID_REUSED", "同一 requestId 不能用于不同问题");
            }
            if ("RUNNING".equals(turn.status())) {
                throw new ConflictException("REQUEST_IN_PROGRESS", "该请求仍在执行，请稍后查询消息记录");
            }
            return new PreparedTurn(new ChatScopeDTO(libraryId, question, documents(conversation), List.of()), turn, null, true);
        }
        if (dao.running(conversationId)) { throw new ConflictException("CONVERSATION_BUSY", "该会话正在回答上一条问题"); }
        var frozen = documents(conversation);
        var scope = chat.prepare(libraryId, new ChatRequestDTO(question, frozen.stream().map(ChatScopeDTO.Document::documentId).toList()));
        if (!new java.util.HashSet<>(scope.documents()).equals(new java.util.HashSet<>(frozen))) {
            throw new ConflictException("CONVERSATION_SCOPE_CHANGED", "会话引用的文档索引已变化，请创建新会话");
        }
        var turn = dao.begin(conversationId, input.requestId(), question, conversation.revision(), properties.timeout().plusSeconds(30).toMillis());
        return new PreparedTurn(scope, turn, conversation.checkpoint() == null ? null : json.readTree(conversation.checkpoint()), false);
    }

    @Override
    @Transactional
    public void complete(PreparedTurn prepared, JsonNode answer, JsonNode checkpoint) {
        var turn = prepared.turn();
        var conversation = find(prepared.scope().libraryId(), turn.conversationId(), true);
        if (checkpoint == null || !checkpoint.isObject() || checkpoint.path("version").asInt() != 1
                || checkpoint.path("conversationId").asLong() != turn.conversationId()
                || conversation.revision() != turn.baseRevision()
                || dao.complete(turn, json.writeValueAsString(answer)) != 1
                || dao.saveCheckpoint(conversation.id(), turn.baseRevision(), json.writeValueAsString(checkpoint)) != 1) {
            throw new ConflictException("STALE_CHAT_EXECUTION", "该轮执行已失效，结果未写入会话");
        }
    }

    @Override
    @Transactional
    public void fail(PreparedTurn prepared, String code, String message) {
        dao.lock(prepared.turn().conversationId());
        dao.fail(prepared.turn(), code, message);
    }

    @Override
    @Transactional
    public void recover(long conversationId) { dao.lock(conversationId); dao.expire(conversationId); }

    private ConversationDO find(long libraryId, long id, boolean lock) {
        return dao.find(libraryId, id, lock).orElseThrow(() -> new ResourceNotFoundException("会话不存在或不属于当前资料库"));
    }
    private List<ChatScopeDTO.Document> documents(ConversationDO row) {
        return Arrays.asList(json.readValue(row.documentScope(), ChatScopeDTO.Document[].class));
    }
    private ConversationDTO view(ConversationDO row) {
        return new ConversationDTO(row.id(), row.libraryId(), row.title(), documents(row).stream().map(ChatScopeDTO.Document::documentId).toList(),
                row.revision(), row.createdAt(), row.updatedAt());
    }
}
